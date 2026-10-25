package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/**
 * 番茄小说网页端公开数据的访问层。
 *
 * 说明：全部走 fanqienovel.com / novel.snssdk.com 的网页端接口，
 * 不依赖任何第三方中转服务。榜单与书库接口返回的书名/作者是字体加密的，
 * 统一交给 [FontDecoder] 还原；搜索、目录、正文、推荐流本身是明文。
 */
class FanqieApi(
    private val context: Context,
    private val cookieProvider: () -> String = { "" },
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()
    private var lastRequestAt = 0L
    private val cache = object : LinkedHashMap<String, Pair<Long, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 48
    }

    init {
        FontDecoder.load(context)
    }

    // ---------------- 网络 ----------------

    /**
     * @param cookieOverride 传了就用它，不传走设置里的 Cookie。
     *        登录自检需要"拿一段还没保存的 cookie 去试"，所以留了这个口子。
     */
    private suspend fun raw(
        url: String,
        cacheable: Boolean = true,
        cookieOverride: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val cookie = cookieOverride ?: cookieProvider()
        val key = if (cookie.isBlank()) url else "$url#${cookie.hashCode()}"
        cache[key]?.let { (at, body) -> if (System.currentTimeMillis() - at < 120_000) return@withContext body }
        gate.withLock {
            val gap = System.currentTimeMillis() - lastRequestAt
            if (gap < 220) kotlinx.coroutines.delay(220 - gap)
            lastRequestAt = System.currentTimeMillis()
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            val req = builder
                .header("Referer", "https://fanqienovel.com/")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw ApiException(
                        when (resp.code) {
                            404 -> "内容不存在或已下架"
                            403 -> "该内容需要官方授权"
                            else -> "网络异常（${resp.code}）"
                        }
                    )
                }
                if (body.isEmpty()) throw ApiException("服务返回空数据")
                // 站点可能下发验证页
                if (body.startsWith("<") && !body.contains("__INITIAL_STATE__") && url.contains("/api/")) {
                    throw ApiException("接口返回了验证页，请稍后再试")
                }
                if (cacheable) cache[key] = System.currentTimeMillis() to body
                body
            }
        }
    }

    private suspend fun getJson(url: String, cookieOverride: String? = null): JsonElement {
        val text = raw(url, cookieOverride = cookieOverride)
        return runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw ApiException("数据格式异常") }
    }

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonPrimitive) {
                if (v.isString) return v.content
                return v.content
            }
        }
        return ""
    }

    // ---------------- 榜单 ----------------

    suspend fun rank(
        gender: Gender,
        mold: RankMold,
        categoryId: Int,
        offset: Int,
        limit: Int = 15,
    ): List<Book> {
        val url = "https://fanqienovel.com/api/rank/category/list?app_id=2503&rank_list_type=3" +
            "&offset=$offset&limit=$limit&category_id=$categoryId&rank_version=" +
            "&gender=${gender.api}&rankMold=${mold.api}"
        val root = getJson(url).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val list = data["book_list"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { it.asBook(rankStyle = true) }
    }

    /** 榜单页顶部的分类（男/女频各自的题材列表）。 */
    suspend fun rankCategories(gender: Gender): List<Category> = withContext(Dispatchers.IO) {
        val url = "https://fanqienovel.com/api/author/book/category_list/v0/?gender=${gender.api}"
        val root = getJson(url)
        val arr = (root as? JsonArray)
            ?: (root as? JsonObject)?.get("data") as? JsonArray
            ?: return@withContext defaultCategories(gender)
        val out = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id").toIntOrNull() ?: return@mapNotNull null
            val name = FontDecoder.decode(o.str("name"))
            if (name.isBlank()) null else Category(id, name)
        }.distinctBy { it.id }
        out.ifEmpty { defaultCategories(gender) }
    }

    private fun defaultCategories(gender: Gender): List<Category> =
        if (gender == Gender.MALE) listOf(
            Category(258, "传统玄幻"), Category(1140, "东方仙侠"), Category(1141, "西方奇幻"),
            Category(1014, "都市高武"), Category(751, "悬疑灵异"), Category(273, "历史古代"),
            Category(262, "都市脑洞"), Category(124, "都市修真"), Category(504, "抗战谍战"),
        ) else listOf(
            Category(248, "玄幻言情"), Category(246, "宫斗宅斗"), Category(749, "青春甜宠"),
            Category(748, "豪门总裁"), Category(267, "现言脑洞"), Category(253, "古言脑洞"),
            Category(747, "女频悬疑"), Category(1139, "古风世情"), Category(750, "职场婚恋"),
        )

    /** 推荐流：等同「书荒广场」的推书信息流。 */
    suspend fun recommend(gender: Gender, offset: Int, limit: Int = 12): List<Book> {
        val url = "https://fanqienovel.com/api/rank/recommend/list?type=${if (gender == Gender.MALE) 2 else 3}" +
            "&limit=$limit&offset=$offset"
        val root = getJson(url).jsonObject
        val list = root["data"]?.jsonObject?.get("list")?.jsonArray ?: return emptyList()
        return list.mapNotNull { it.asBook() }
    }

    suspend fun recent(offset: Int, limit: Int = 12): List<Book> {
        val url = "https://fanqienovel.com/api/rank/recent/update/list?limit=$limit&offset=$offset"
        val root = getJson(url).jsonObject
        val arr = root["data"]?.jsonObject?.get("data")?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("bookId")
            if (id.isBlank()) null else Book(
                id = id,
                title = FontDecoder.decode(o.str("bookName")),
                author = FontDecoder.decode(o.str("author")),
                category = o.str("category"),
                lastChapter = o.str("title"),
                source = Source.FANQIE,
            )
        }
    }

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String, offset: Int = 0): List<Book> {
        if (keyword.isBlank()) return emptyList()
        val q = java.net.URLEncoder.encode(keyword.trim(), "UTF-8")
        val url = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?aid=1967&q=$q&offset=$offset"
        val root = getJson(url).jsonObject
        val ret = root["data"]?.jsonObject?.get("ret_data") as? JsonArray ?: return emptyList()
        return ret.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("book_id")
            if (id.isBlank()) null else Book(
                id = id,
                title = FontDecoder.decode(o.str("title")),
                author = FontDecoder.decode(o.str("author")),
                cover = o.str("thumb_url").ifBlank { null },
                intro = FontDecoder.decode(o.str("abstract")),
                category = o.str("category"),
                score = o.str("score"),
                finished = o.str("creation_status") == "0",
                source = Source.FANQIE,
            )
        }
    }

    // ---------------- 详情 / 目录 / 正文 ----------------

    suspend fun detail(bookId: String): BookDetail {
        val html = raw("https://fanqienovel.com/page/$bookId")
        val state = initialJson(html) ?: throw ApiException("页面解析失败")
        val page = state["page"]?.jsonObject ?: throw ApiException("书籍不存在或不可见")
        val id = page.str("bookId").ifBlank { bookId }
        val book = Book(
            id = id,
            title = FontDecoder.decode(page.str("bookName")),
            author = FontDecoder.decode(page.str("authorName", "author")),
            cover = page.str("thumbUrl", "thumbUri").ifBlank { null },
            intro = FontDecoder.decode(page.str("abstract")),
            category = FontDecoder.decode(page.str("category")),
            wordCount = normalizeWordCount(page.str("wordNumber")),
            finished = page.str("creationStatus") == "0",
            lastChapter = FontDecoder.decode(page.str("lastChapterTitle")),
            source = Source.FANQIE,
        )
        val count = page.str("chapterTotal").toIntOrNull() ?: 0
        // 详情页早先给 firstChapterItemId，现在这个字段没了；
        // itemIds 是倒序（最新章节在最前），最后一个才是第 1 章
        val firstFromList = (page["itemIds"] as? JsonArray)?.lastOrNull()
            ?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() }
        val first = page.str("firstChapterItemId").ifBlank { firstFromList }
        return BookDetail(book, count, first)
    }

    suspend fun directory(bookId: String): List<Chapter> {
        val url = "https://fanqienovel.com/api/reader/directory/detail?bookId=$bookId"
        val root = getJson(url).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val vols = data["chapterListWithVolume"] as? JsonArray ?: return emptyList()
        val names = (data["volumeNameList"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content?.let(FontDecoder::decode)
        } ?: emptyList()
        val out = ArrayList<Chapter>(512)
        vols.forEachIndexed { index, volEl ->
            val volume = names.getOrNull(index).orEmpty()
            val chapters = volEl as? JsonArray ?: return@forEachIndexed
            chapters.forEach { ch ->
                val o = ch as? JsonObject ?: return@forEach
                val itemId = o.str("itemId")
                if (itemId.isNotBlank()) {
                    out.add(Chapter(itemId, FontDecoder.decode(o.str("title")), volume))
                }
            }
        }
        return out
    }

    suspend fun chapter(itemId: String): ChapterContent {
        val html = raw("https://fanqienovel.com/reader/$itemId")
        val state = initialJson(html) ?: throw ApiException("正文解析失败")
        val ch = state["reader"]?.jsonObject?.get("chapterData")?.jsonObject
            ?: throw ApiException("章节不存在或不可见")
        // isChapterLock / needPay 在页面里是 JSON 布尔（true/false），早期版本才是 1/0，
        // 两种都得认，否则锁章会被当成"正常章节"，界面上什么都不提示
        val locked = ch.str("isChapterLock").isTruthy() || ch.str("needPay").isTruthy()
        val rawContent = ch.str("content")
        val text = FontDecoder.decode(htmlToText(rawContent)) // 正文同样是字体加密，需要还原
        return ChapterContent(
            itemId = ch.str("itemId").ifBlank { itemId },
            bookId = ch.str("bookId"),
            title = FontDecoder.decode(ch.str("title")),
            content = text.trim().replace(Regex("\n{3,}"), "\n\n"),
            prevId = ch.str("preItemId").takeIf { it.isNotBlank() && it != "0" },
            nextId = ch.str("nextItemId").takeIf { it.isNotBlank() && it != "0" },
            locked = locked,
        )
    }

    // ---------------- 登录自检 ----------------

    /**
     * 判断一段 Cookie 到底有没有被番茄认可。
     *
     * 番茄不会回"登录无效"：Cookie 不对就直接当匿名处理，正文照旧只给前 10 章。
     * 所以只能拿一本固定书的第 11 章当探针 —— 登录生效时这一章有上千字，
     * 没生效就恒定在 200 字左右。这样至少能区分"cookie 没生效"和"这章本来就要付费"。
     */
    suspend fun probe(cookie: String): LoginProbe {
        if (cookie.isBlank()) return LoginProbe(false, false, 0, "没有填写 Cookie")
        // 探针书可能下架，失败了就从推荐流临时取一本，保证自检不会因为一本书失效
        val chapterId = runCatching { eleventhChapterId(cookie, PROBE_BOOK_ID) }.getOrNull()
            ?: runCatching { eleventhChapterId(cookie, recommendBookId(cookie)) }.getOrNull()
            ?: return LoginProbe(true, false, 0, "取不到探针章节，可能网络异常")
        val html = runCatching {
            raw("https://fanqienovel.com/reader/$chapterId", cacheable = false, cookieOverride = cookie)
        }.getOrElse {
            return LoginProbe(true, false, 0, it.message ?: "探针请求失败")
        }
        val ch = initialJson(html)?.get("reader")?.jsonObject?.get("chapterData")?.jsonObject
            ?: return LoginProbe(true, false, 0, "探针章节解析失败")
        val chars = ch.str("content").length
        val locked = ch.str("isChapterLock").isTruthy() || ch.str("needPay").isTruthy()
        return LoginProbe(
            hasCookie = true,
            effective = chars > 800 && !locked,
            chars = chars,
            message = "",
        )
    }

    /** 探针书的第 11 章：itemIds 倒序，倒数第 11 个才是第 11 章 */
    private suspend fun eleventhChapterId(cookie: String, bookId: String): String? {
        val html = raw("https://fanqienovel.com/page/$bookId", cacheable = false, cookieOverride = cookie)
        val page = initialJson(html)?.get("page")?.jsonObject ?: return null
        val ids = page["itemIds"] as? JsonArray ?: return null
        if (ids.size < 11) return null
        return (ids[ids.size - 11] as? JsonPrimitive)?.content
    }

    /** 推荐流第一本的书号，仅用于探针兜底 */
    private suspend fun recommendBookId(cookie: String): String {
        val root = getJson(
            "https://fanqienovel.com/api/rank/recommend/list?type=2&limit=3&offset=0",
            cookie,
        ).jsonObject
        val list = root["data"]?.jsonObject?.get("list")?.jsonArray ?: throw ApiException("推荐流为空")
        val id = (list.firstOrNull() as? JsonObject)?.str("bookId").orEmpty()
        if (id.isBlank()) throw ApiException("推荐流没有书号")
        return id
    }

    // ---------------- 杂项 ----------------

    private fun String.isTruthy(): Boolean = this == "1" || equals("true", ignoreCase = true)

    private fun JsonElement.asBook(rankStyle: Boolean = false): Book? {
        val o = this as? JsonObject ?: return null
        val id = o.str("book_id", "bookId")
        if (id.isBlank()) return null
        val title = FontDecoder.decode(o.str("book_name", "bookName", "title"))
        val cover = o.str("thumb_url", "thumbUri", "thumbUri_").ifBlank { null }
        val words = normalizeWordCount(o.str("word_count", "wordNumber"))
        val reads = o.str("read_count", "readCount").let { r ->
            val n = r.toLongOrNull()
            if (n != null && n > 0) n.toWan() else FontDecoder.decode(r)
        }
        return Book(
            id = id,
            title = title,
            author = FontDecoder.decode(o.str("author", "authorName")),
            cover = cover,
            intro = FontDecoder.decode(o.str("abstract")),
            category = FontDecoder.decode(o.str("category", "categoryV2")),
            wordCount = words,
            readCount = reads,
            score = o.str("score"),
            finished = o.str("creation_status", "creationStatus") == "0",
            lastChapter = FontDecoder.decode(o.str("lastChapterTitle")),
            source = Source.FANQIE,
        )
    }

    /** 从页面里截取 window.__INITIAL_STATE__ 的 JSON 对象（只解析，不执行脚本）。 */
    private fun initialJson(html: String): JsonObject? {
        val marker = "window.__INITIAL_STATE__="
        val start = html.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length
        while (i < html.length && html[i].isWhitespace()) i++
        var depth = 0
        var inStr = false
        var esc = false
        val begin = i
        while (i < html.length) {
            val c = html[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            return runCatching {
                                json.parseToJsonElement(html.substring(begin, i + 1)).jsonObject
                            }.getOrNull()
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<script[\\s\\S]*?</script>"), "")
            .replace(Regex("<style[\\s\\S]*?</style>"), "")
            .replace(Regex("</(p|div)>|<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

        /** 自检用的探针书，章节多、长期在架 */
        private const val PROBE_BOOK_ID = "7069948840148732967"

        /**
         * Cookie 里有没有真正的登录票据。
         *
         * 注意不能拿 passport_csrf_token 当判据 —— 匿名访问首页也会下发，
         * 之前就是因为把它算作"已登录"，用户以为登上了，实际还是试读。
         */
        fun looksLoggedIn(cookie: String): Boolean =
            Regex("(^|;)\\s*(sessionid|sessionid_ss|sid_guard|sid_tt)\\s*=").containsMatchIn(cookie)
    }
}

/**
 * 登录自检结果。
 * [effective] 才是"服务端认不认这个登录"的唯一可靠信号，光看 cookie 非空没用。
 */
data class LoginProbe(
    val hasCookie: Boolean,
    /** 服务端是否真的按登录态返回正文 */
    val effective: Boolean,
    /** 探针章节的字数：生效时上千，没生效恒定 200 左右 */
    val chars: Int,
    /** 失败原因，成功时为空 */
    val message: String,
)
