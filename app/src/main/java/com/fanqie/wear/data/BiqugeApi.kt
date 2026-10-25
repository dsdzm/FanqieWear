package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 笔趣阁（biquge365.net）数据源：**全程免登录**。
 *
 * 番茄源第 11 章起要登录 Cookie，登录链路又依赖字节 Passport 的签名，
 * 手表端跑不通，所以默认改用这一类站点。选它是因为链路完整、结构稳定：
 *
 * | 能力 | 路径 | 说明 |
 * |---|---|---|
 * | 搜索 | `POST /s.php`（`type=articlename&s=关键词`） | 服务端渲染，直接给 HTML |
 * | 详情 | `GET /book/{id}/` | h1 + `.xinxi` 里有作者/类型/状态/最新章节 |
 * | 目录 | `GET /newbook/{id}/` | `<ul class="info">` 里是全量正序章节 |
 * | 正文 | `GET /chapter/{bid}/{cid}.html` | `<div id="txt">`，`<br>` 分段 |
 * | 分类 | `GET /sort/{分类}_{页码}/` | 页码从 1 开始 |
 *
 * 解析全部用正则，不引 jsoup：站点模板固定，少一个依赖就少一份体积和风险。
 */
class BiqugeApi(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gate = Mutex()
    private var lastRequestAt = 0L

    /** 详情/目录这些变动慢的页面缓存 2 分钟，翻来翻去不重复打站点 */
    private val cache = object : LinkedHashMap<String, Pair<Long, String>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>): Boolean =
            size > 40
    }

    // ---------------- 对外能力 ----------------

    suspend fun search(keyword: String, page: Int = 0): List<Book> {
        if (keyword.isBlank() || page > 0) return emptyList()
        val html = fetch(
            "$BASE/s.php",
            form = mapOf("type" to "articlename", "s" to keyword.trim()),
        )
        val list = section(html, "class=\"search\"", "</ul>") ?: return emptyList()
        return liBlocks(list).mapNotNull { li ->
            val id = Regex("href=\"/book/(\\d+)/\"").find(li)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = spanText(li, "name").trim('《', '》', ' ')
            if (title.isBlank()) return@mapNotNull null
            Book(
                id = id,
                title = title,
                author = spanText(li, "zuo"),
                category = spanText(li, "lei").trim('[', ']'),
                lastChapter = spanText(li, "jie"),
                source = Source.BIQUGE,
            )
        }.distinctBy { it.id }
    }

    suspend fun browse(shelfId: String, page: Int = 0): List<Book> {
        val id = shelfId.toIntOrNull() ?: return emptyList()
        val html = fetch("$BASE/sort/${id}_${page + 1}/")
        val list = section(html, "class=\"gengxin\"", "</ul>") ?: return emptyList()
        return liBlocks(list).mapNotNull { li ->
            val id2 = Regex("href=\"/book/(\\d+)/\"").find(li)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = spanText(li, "name").trim('《', '》', ' ')
            if (title.isBlank()) return@mapNotNull null
            Book(
                id = id2,
                title = title,
                author = spanText(li, "zuo"),
                lastChapter = spanText(li, "jie"),
                source = Source.BIQUGE,
            )
        }.distinctBy { it.id }
    }

    suspend fun detail(bookId: String): BookDetail {
        val html = fetch("$BASE/book/$bookId/", cacheKey = "detail:$bookId")
        val title = Regex("<h1>([\\s\\S]*?)</h1>").find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        if (title.isBlank()) throw ApiException("书籍不存在或已下架")

        val coverPath = Regex("<div class=\"zhutu\">[\\s\\S]*?<img[^>]*src=\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)
        val introRaw = Regex("<div class=\"x3\">([\\s\\S]*?)</div>").find(html)?.groupValues?.get(1).orEmpty()
        val intro = clean(introRaw).removePrefix("简介：").trim()

        val book = Book(
            id = bookId,
            title = title,
            author = labeled(html, "作者"),
            cover = coverPath?.let(::absolute),
            intro = intro,
            category = labeled(html, "类型"),
            finished = labeled(html, "状态").contains("完结"),
            lastChapter = labeled(html, "最新章节"),
            source = Source.BIQUGE,
        )

        val chapters = runCatching { catalog(bookId) }.getOrDefault(emptyList())
        return BookDetail(book, chapters.size, chapters.firstOrNull()?.itemId)
    }

    suspend fun catalog(bookId: String): List<Chapter> {
        val html = fetch("$BASE/newbook/$bookId/", cacheKey = "catalog:$bookId")
        val list = section(html, "class=\"info\"", "</ul>") ?: return emptyList()
        val out = ArrayList<Chapter>(1024)
        Regex("<a href=\"/chapter/(\\d+)/(\\d+)\\.html\"[^>]*>([^<]*)</a>")
            .findAll(list)
            .forEach { m ->
                val chapterId = m.groupValues[2]
                val name = clean(m.groupValues[3])
                if (chapterId.isNotBlank() && name.isNotBlank()) {
                    out.add(Chapter(itemId = chapterId, title = name))
                }
            }
        return out.distinctBy { it.itemId }
    }

    suspend fun chapter(bookId: String, itemId: String): ChapterContent {
        val chapterId = itemId.removeSuffix(".html")
        val html = fetch("$BASE/chapter/$bookId/$chapterId.html")
        val anchor = html.indexOf("id=\"txt\"")
        if (anchor < 0) throw ApiException("正文解析失败，可能该章已下架")
        val open = html.indexOf(">", anchor)
        val close = html.indexOf("</div>", open)
        if (open < 0 || close <= open) throw ApiException("正文解析失败")

        val body = htmlToParagraphs(html.substring(open + 1, close))
        if (body.isBlank()) throw ApiException("本章正文为空")

        val title = Regex("<h1>([\\s\\S]*?)</h1>").find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()

        return ChapterContent(
            itemId = chapterId,
            bookId = bookId,
            title = title,
            content = body,
            prevId = neighbor(html, "上一章"),
            nextId = neighbor(html, "下一章"),
        )
    }

    suspend fun ping(): String {
        val html = fetch("$BASE/", cacheKey = null)
        return if (html.contains("笔趣阁") || html.contains("小说")) "连接正常" else "返回内容异常"
    }

    // ---------------- 网络 ----------------

    private suspend fun fetch(
        url: String,
        form: Map<String, String>? = null,
        cacheKey: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (cacheKey != null) {
            cache[cacheKey]?.let { (at, body) ->
                if (System.currentTimeMillis() - at < 120_000) return@withContext body
            }
        }
        gate.withLock {
            val gap = System.currentTimeMillis() - lastRequestAt
            if (gap < 200) delay(200 - gap)
            lastRequestAt = System.currentTimeMillis()

            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "$BASE/")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")

            if (form != null) {
                val body = FormBody.Builder()
                form.forEach { (k, v) -> body.add(k, v) }
                builder.post(body.build())
            }
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(
                        when (resp.code) {
                            404 -> "内容不存在或已下架"
                            in 500..599 -> "书源服务器异常（${resp.code}）"
                            else -> "网络异常（${resp.code}）"
                        },
                    )
                }
                if (text.isBlank()) throw ApiException("书源返回空数据")
                if (cacheKey != null) cache[cacheKey] = System.currentTimeMillis() to text
                text
            }
        }
    }

    // ---------------- 解析 ----------------

    /** 从 [from] 标记往后截到 [endTag]，找不到返回 null */
    private fun section(html: String, from: String, endTag: String): String? {
        val start = html.indexOf(from)
        if (start < 0) return null
        val end = html.indexOf(endTag, start)
        if (end < 0) return null
        return html.substring(start, end)
    }

    private fun liBlocks(list: String): List<String> =
        Regex("<li[^>]*>([\\s\\S]*?)</li>").findAll(list)
            .map { it.groupValues[1] }
            .filterNot { it.contains("class=\"fen\"") }
            .toList()

    /** 取 `<span class="cls">…</span>` 里的纯文本（含里面的 a 标签文本） */
    private fun spanText(block: String, cls: String): String {
        val m = Regex("<span[^>]*class=\"$cls\"[^>]*>([\\s\\S]*?)</span>").find(block) ?: return ""
        return clean(m.groupValues[1])
    }

    /** 取「作者：xx」这类行内字段，兼容被 a 包一层的情况 */
    private fun labeled(html: String, label: String): String {
        val m = Regex("$label：\\s*(?:<a[^>]*>[\\s\\S]*?</a>|[^<\\n]*)").find(html) ?: return ""
        return clean(m.value).removePrefix("$label：").trim()
    }

    private fun neighbor(html: String, label: String): String? {
        val idx = html.indexOf(">$label</a>")
        if (idx < 0) return null
        val start = html.lastIndexOf("href=\"", idx)
        if (start < 0) return null
        val end = html.indexOf('"', start + 6)
        if (end < 0) return null
        val href = html.substring(start + 6, end)
        val m = Regex("/chapter/\\d+/(\\d+)\\.html").find(href) ?: return null
        val id = m.groupValues[1]
        return if (id.isBlank() || id == "0") null else id
    }

    private fun htmlToParagraphs(raw: String): String {
        val text = raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("<[^>]*>"), "")
        return text.split("\n")
            .map { clean(it) }
            .filter { it.isNotBlank() && !isAd(it) }
            .joinToString("\n\n")
    }

    /** 站点在正文里插的广告与推书行，逐行丢掉 */
    private fun isAd(line: String): Boolean = AD_MARKS.any { line.contains(it) }

    private fun clean(raw: String): String = unescape(raw)
        .replace(Regex("<[^>]*>"), "")
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t\\u3000]+"), " ")
        .trim()

    private fun unescape(raw: String): String = raw
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun absolute(path: String): String =
        if (path.startsWith("http")) path else BASE + path.removePrefix(".")

    companion object {
        const val BASE = "https://www.biquge365.net"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

        private val AD_MARKS = listOf(
            "一秒记住",
            "笔趣阁",
            "biquge365",
            "更新快，无弹窗",
            "无弹窗",
            "最新网址",
            "手机版阅读网址",
            "请收藏本站",
            "首发地址",
            "章节报错",
        )
    }
}
