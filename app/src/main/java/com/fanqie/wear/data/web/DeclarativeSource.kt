package com.fanqie.wear.data.web

import com.fanqie.wear.data.ApiException
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.BookDetail
import com.fanqie.wear.data.BookSource
import com.fanqie.wear.data.Chapter
import com.fanqie.wear.data.ChapterContent
import com.fanqie.wear.data.Source
import com.fanqie.wear.data.SourceCapability
import com.fanqie.wear.data.SourceShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 一个站点 = 一份 JSON 规则。
 *
 * 页面结构变了只改 JSON，不用发版；书源越加越多也不会把代码撑爆。
 * 选择器语法：`css` 取文本，`css@attr` 取属性，多个选择器用逗号分隔（取第一个非空）。
 */
@Serializable
data class SiteRule(
    val key: String,
    val name: String,
    val base: String,
    /** 站点备注：能搜/只能读/需要登录 */
    val note: String = "",
    /** 搜索规则，没有就表示不支持搜索 */
    val search: SearchRule? = null,
    /** 没有关键词搜索、但可以贴书号/链接直接读（7novel 就是这样） */
    val directSearch: Boolean = false,
    /** 先访问这个路径拿 Cookie（部分站点搜索必须先有会话） */
    val warmup: String = "",
    val detail: DetailRule,
    val catalog: CatalogRule,
    val chapter: ChapterRule,
    /** 启用状态，用户可在设置里关掉某个源 */
    val enabled: Boolean = true,
)

@Serializable
data class SearchRule(
    val path: String,
    val method: String = "GET",
    /** POST 时的表单体，支持 {q} 占位 */
    val body: String = "",
    val list: String,
    val title: String,
    val link: String,
    val author: String = "",
    /**
     * 站点要求两次搜索之间的最小间隔（毫秒）。
     *
     * 娜娜这类站把"搜索间隔 10 秒"记在 `ss_search_delay` cookie 里，
     * 连续搜索时直接返回一行 `alert("搜索间隔: 10 秒")`，页面里一条结果都没有。
     * 填了这里的值，客户端就会自己排好队，不再白搜一次。
     */
    val intervalMs: Long = 0,
    /**
     * 结果页会回显本次搜索词的选择器（如 7novel 的 `关键字: xxx`）。
     *
     * 用来识别"站点把搜索结果按 URL 路径缓存了"：缓存命中时页面回显的是**别人**的关键词，
     * 结果跟你要的毫无关系。有这个选择器就能发现并换路径重试，而不是把错结果当成"搜不到"。
     */
    val echo: String = "",
    /**
     * 从链接里算出的 id 需要去掉的前缀。
     *
     * 默认 id 是"详情页路径去掉首尾斜杠"（如 `book/12345`）；
     * 7novel 的详情页是 `/cn/books/{id}`，id 只想要那串数字，所以填 `cn/books/`。
     */
    val linkStrip: String = "",
    /** 备用搜索路径：站点缓存按路径分桶时，换一条路径就能拿到真实结果 */
    val mirrors: List<String> = emptyList(),
)

@Serializable
data class DetailRule(
    val path: String,
    val title: String = "h1",
    /** 站点标题带尾巴（如 `书名 - 7novel.com 七小说`）时，按这个后缀裁掉 */
    val titleTrim: String = "",
    val author: String = "",
    val intro: String = "",
    val cover: String = "",
)

@Serializable
data class CatalogRule(
    val path: String,
    val list: String,
    val title: String = "",
    val link: String = "@href",
    /**
     * 目录不是 HTML 列表、而是页面里内嵌的 JSON 数组时置 true。
     * 此时 [list] 是数组前的标记（如 `chapters:[`），[link] 是 id 模板（如 `cn/chapters/{id}`）。
     * 7novel 这类 Alpine/Livewire 站把整本目录塞在 x-data 里，CSS 选不到。
     */
    val json: Boolean = false,
    /** 站点目录是"最新章节在前"，置 true 反转为正序 */
    val reverse: Boolean = false,
)

@Serializable
data class ChapterRule(
    val path: String = "{itemId}",
    val content: String,
    val remove: List<String> = emptyList(),
    /**
     * 正文页自带的上一章 / 下一章链接。
     *
     * 有这两条就优先用页面里的，不再依赖目录里的顺序 —— 有些站点的目录只列最新 100 章、
     * 或者一章被拆成 `xxx_2.html` 多页，靠目录推 next 会走丢。
     */
    val prev: String = "",
    val next: String = "",
)

/** 站点配置集合，放在 assets/sources/sites.json，也可以被云端书源覆盖 */
@Serializable
data class SiteConfig(val version: Int = 1, val sites: List<SiteRule> = emptyList())

object SiteRules {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<SiteRule> = runCatching {
        json.decodeFromString<SiteConfig>(text).sites
    }.getOrElse {
        Log.w("SiteRules", "书源配置解析失败：${it.message}")
        emptyList()
    }
}

/**
 * 声明式书源：按 JSON 规则抓页面。
 *
 * id 约定：
 * - 书 id 用详情页路径去掉首尾斜杠，如 `book/12345`；
 * - 章 id 用章节页路径，如 `book/12345/678.html`。
 */
class DeclarativeSource(
    private val rule: SiteRule,
    private val onLog: (String) -> Unit = {},
    /** 本地书名库目录（filesDir）：搜过的书存这里，下次搜同一个词直接命中，不再打站点 */
    private val memoDir: File? = null,
) : BookSource {

    override val source: Source = Source.AGG
    override val displayName: String = rule.name
    override val desc: String = rule.note.ifBlank { "声明式书源" }
    override val capability: SourceCapability = SourceCapability(
        search = rule.search != null || rule.directSearch,
        browse = false,
        chapter = true,
        login = false,
    )
    override val shelves: List<SourceShelf> = emptyList()

    /** key 供聚合层路由 */
    val siteKey: String get() = rule.key

    override suspend fun search(keyword: String, page: Int): List<Book> = withContext(Dispatchers.IO) {
        searchIo(keyword, page)
    }

    private suspend fun searchIo(keyword: String, page: Int): List<Book> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()

        val r = rule.search

        // 「书号」或「书籍链接」永远优先：不经过搜索，直接开
        if (r == null || rule.directSearch) {
            directId(q)?.let { hit ->
                return listOf(runCatching { detail(hit) }.getOrThrow().book)
            }
        }
        if (r == null) throw ApiException("${rule.name} 不支持关键词搜索")
        if (page > 0) return emptyList()

        // ① 本地书名库：搜过的书秒出，断网也能用
        val memo = memoTake(q)
        if (memo.isNotEmpty()) {
            onLog("${rule.name} 本地书库命中「$q」${memo.size} 条")
            return memo
        }

        // 站点限流：窗口内重复搜同一个词直接给上次的结果
        val interval = r.intervalMs
        if (interval > 0) {
            val since = System.currentTimeMillis() - lastSearchAt
            if (lastSearchAt > 0 && since < interval) {
                searchCache[q]?.let {
                    onLog("${rule.name} 距上次搜索 ${since / 1000}s，直接复用「$q」的结果")
                    return it
                }
                val wait = interval - since
                if (wait > MAX_BLOCK_MS) {
                    throw ApiException("${rule.name} 要求搜索间隔 ${interval / 1000} 秒，请稍后再试")
                }
                delay(wait)
            }
        }

        val form = r.body.replace("{q}", enc(q))
        val urls = LinkedHashSet<String>()
        urls.add(absolute(r.path.replace("{q}", enc(q))))
        for (m in r.mirrors) urls.add(absolute(m.replace("{q}", enc(q))))

        // ② 站点把搜索结果按「路径」缓存，同一路径同一时间只装得下一个关键词。
        //    先挑「缓存里正好是这个词」的路径，再挑「空的」路径，最后才碰已知装了别的词的
        val now = System.currentTimeMillis()
        val fresh = { u: String -> bucket[u]?.let { now - it.second >= BUCKET_TTL_MS } ?: true }
        val same = { u: String -> bucket[u]?.let { it.first == q && now - it.second < BUCKET_TTL_MS } ?: false }
        val ordered = urls.filter(same) + urls.filter { fresh(it) } + urls.filter { !fresh(it) && !same(it) }

        var echoOf: String? = null
        for (url in ordered) {
            // 每条路径单独兜底：备用路径里带了「域名尾点」，个别系统上证书校验会失败，
            // 那种情况下必须继续试下一条，而不是让整个搜索挂掉
            val doc = try {
                var d = fetch(url, r.method, form)
                if (isThrottled(d)) {
                    val wait = (interval.takeIf { it > 0 } ?: 10_000L).coerceAtMost(MAX_BLOCK_MS)
                    onLog("${rule.name} 触发搜索间隔限制，等待 ${wait}ms 后重试")
                    delay(wait)
                    d = fetch(url, r.method, form)
                    if (isThrottled(d)) throw ApiException("${rule.name} 被限流了，请过 10 秒再试")
                }
                d
            } catch (e: ApiException) {
                throw e
            } catch (t: Throwable) {
                onLog("${rule.name} 这条路径打不开（${t.message?.take(24)}），换下一条")
                continue
            }
            lastSearchAt = System.currentTimeMillis()

            // ③ 回显校验：页面写的是别人的关键词，说明这条路径的缓存被占了，换下一条
            val echo = pick(doc, r.echo)
            if (echo != null) {
                val shown = echo.replace("关键字", "").replace("關鍵字", "")
                    .trim().trimStart(':', '：').trim()
                if (shown.isNotBlank() && shown != q) {
                    bucket[url] = shown to now
                    echoOf = shown
                    onLog("${rule.name} 该路径缓存的是「$shown」，换下一条路径")
                    continue
                }
            }

            var out = parseSearchList(doc, r)
            // 只有"靠会话"的站点才值得重建重试；限流型站点再打一次只会重置它的等待窗口
            if (out.isEmpty() && rule.warmup.isNotBlank() && interval == 0L) {
                ensureSession(force = true)
                val retryDoc = fetchRaw(url, r.method, form)
                if (!isThrottled(retryDoc)) {
                    out = parseSearchList(retryDoc, r)
                    if (out.isNotEmpty()) onLog("${rule.name} 重建会话后搜到 ${out.size} 条")
                }
            }
            if (out.isNotEmpty()) {
                bucket[url] = q to now
                return finishSearch(q, out)
            }
        }

        echoOf?.let {
            throw ApiException("${rule.name} 站内搜索被站点缓存占用（当前缓存的是「$it」），换个书名或稍后再试；也可以直接贴书号")
        }
        onLog("${rule.name} 搜索「$q」得到 0 条")
        return emptyList()
    }

    private fun finishSearch(q: String, out: List<Book>): List<Book> {
        if (searchCache.size > 12) searchCache.clear()
        searchCache[q] = out
        memoPut(out)
        onLog("${rule.name} 搜索「$q」得到 ${out.size} 条")
        return out
    }

    // ---------------- 本地书名库（按站点分文件） ----------------

    private fun memoFile(): File? = memoDir?.let { File(it, "books-${rule.key}.tsv") }

    /** 搜过的书按书名/作者模糊匹配，够用了就直接返回，不再打站点 */
    private fun memoTake(q: String): List<Book> {
        val f = memoFile()?.takeIf { it.isFile } ?: return emptyList()
        val out = ArrayList<Book>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 2 || p[0].isBlank() || p[1].isBlank()) continue
                    val title = p[1]
                    val author = p.getOrElse(2) { "" }
                    if (!title.contains(q, true) && !author.contains(q, true)) continue
                    out.add(Book(id = p[0], title = title, author = author, source = Source.AGG))
                    if (out.size >= 30) break
                }
            }
        }
        return out
    }

    private fun memoPut(books: List<Book>) {
        val f = memoFile() ?: return
        if (books.isEmpty()) return
        runCatching {
            val known = HashSet<String>()
            if (f.isFile) f.bufferedReader().useLines { lines -> lines.forEach { known.add(it.substringBefore('\t')) } }
            val add = books.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.id !in known }
            if (add.isEmpty()) return@runCatching
            f.appendText(buildString {
                for (b in add) {
                    append(b.id).append('\t')
                        .append(b.title.replace('\t', ' ')).append('\t')
                        .append(b.author.replace('\t', ' ')).append('\n')
                }
            })
        }
    }

    private fun parseSearchList(doc: Document, r: SearchRule): List<Book> {
        val out = ArrayList<Book>()
        for (el in doc.select(r.list)) {
            val a = pick(el, r.link) ?: continue
            val title = pick(el, r.title)?.trim().orEmpty()
            if (title.isBlank()) continue
            // linkStrip：7novel 的链接是 `/cn/books/{id}`，而详情页规则是 `/cn/books/{id}`，
            // 去掉路径前缀后才会拼成同一个地址
            val id = normalizeId(a).removePrefix(r.linkStrip).trim('/')
            if (id.isBlank()) continue
            out.add(
                Book(
                    id = id,
                    title = title,
                    author = pick(el, r.author)?.let(::cleanAuthor).orEmpty(),
                    source = Source.AGG,
                ),
            )
            if (out.size >= 30) break
        }
        return out
    }

    /** 限流页没有正文，只有一行 `alert("搜索间隔: 10 秒")` */
    private fun isThrottled(doc: Document): Boolean {
        val html = doc.outerHtml()
        if (html.length > 2000) return false
        return html.contains("搜索间隔") || html.contains("window.history.go(-1)")
    }

    /** 从「纯数字书号」或「站点书籍链接」里取出 id；不是这两种就返回 null */
    private fun directId(raw: String): String? {
        val q = raw.trim()
        if (q.matches(Regex("\\d{6,}"))) return q
        if (!q.startsWith("http")) return null
        val m = Regex("/(\\d{6,})").find(q) ?: return null
        return m.groupValues[1]
    }

    override suspend fun browse(shelfId: String, page: Int): List<Book> = emptyList()

    override suspend fun detail(bookId: String): BookDetail = withContext(Dispatchers.IO) {
        detailIo(bookId)
    }

    private suspend fun detailIo(bookId: String): BookDetail {
        val doc = fetch(absolute(fill(rule.detail.path, bookId)))
        val rawTitle = pick(doc, rule.detail.title)?.trim().orEmpty()
        val title = (if (rule.detail.titleTrim.isNotBlank()) rawTitle.substringBefore(rule.detail.titleTrim) else rawTitle)
            .trim().ifBlank { bookId }
        val book = Book(
            id = bookId,
            title = title,
            author = pick(doc, rule.detail.author)?.let(::cleanAuthor).orEmpty(),
            intro = pick(doc, rule.detail.intro)?.trim().orEmpty(),
            cover = pick(doc, rule.detail.cover)?.let { absolute(it) },
            source = Source.AGG,
        )
        val chapters = catalog(bookId)
        return BookDetail(book = book, chapterCount = chapters.size, firstChapterId = chapters.firstOrNull()?.itemId)
    }

    override suspend fun catalog(bookId: String): List<Chapter> = withContext(Dispatchers.IO) {
        catalogIo(bookId)
    }

    private suspend fun catalogIo(bookId: String): List<Chapter> {
        val doc = fetch(absolute(fill(rule.catalog.path, bookId)))
        val list = if (rule.catalog.json) parseJsonCatalog(doc) else parseCssCatalog(doc)
        if (rule.catalog.reverse) list.reverse()
        catalogCache[bookId] = list
        return list
    }

    private fun parseCssCatalog(doc: Document): ArrayList<Chapter> {
        val list = ArrayList<Chapter>()
        for (el in doc.select(rule.catalog.list)) {
            val href = pick(el, rule.catalog.link) ?: continue
            val title = if (rule.catalog.title.isBlank()) el.text().trim() else pick(el, rule.catalog.title)?.trim().orEmpty()
            if (title.isBlank()) continue
            list.add(Chapter(itemId = normalizeId(href), title = title))
        }
        return list
    }

    /** 从整页 HTML 里抠出内嵌的 `[{id,title},...]` 数组 */
    private fun parseJsonCatalog(doc: Document): ArrayList<Chapter> {
        val marker = rule.catalog.list
        val blobs = catalogBlobs(doc, marker)
        if (blobs.isEmpty()) throw ApiException("${rule.name} 没找到目录数据（站点可能改版了）")

        for (blob in blobs) {
            val arr = parseLenientArray(blob) ?: continue
            val out = ArrayList<Chapter>(arr.size)
            for (e in arr) {
                val obj = runCatching { e.jsonObject }.getOrNull() ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) continue
                val item = rule.catalog.link.replace("{id}", id).replace("{num}", id)
                out.add(Chapter(itemId = item.trim('/'), title = title))
            }
            if (out.isNotEmpty()) return out
        }

        // 结构实在不合法（少逗号、单引号、裸 id）时退回按字段抠，比整页报错强
        val salvaged = salvageIdTitle(blobs.first())
        if (salvaged.isNotEmpty()) return salvaged
        throw ApiException("${rule.name} 目录解析失败（站点格式变了）")
    }

    /**
     * 目录 JSON 大概率躺在 `x-data` / `data-*` 这类属性里。
     *
     * 注意不能只看 [Document.outerHtml]：jsoup 序列化属性时会把里面的 `"` 转成 `&quot;`，
     * 抠出来就不是 JSON 了（表现是 `unexpected json token at offset 0`）。
     * 属性值的原始形态（`Attribute.value`）是已解转义的，先读它；再退到 `<script>` 与整页。
     */
    private fun catalogBlobs(doc: Document, marker: String): List<String> {
        if (marker.isBlank()) return emptyList()
        val out = ArrayList<String>(4)
        fun take(s: String?) {
            if (s != null && out.none { it == s }) out.add(s)
        }
        for (el in doc.getAllElements()) {
            for (attr in el.attributes()) {
                val v = attr.value
                if (v.contains(marker)) take(slice(marker, v))
            }
        }
        for (el in doc.select("script")) {
            val v = el.data()
            if (v.contains(marker)) take(slice(marker, v))
        }
        val html = doc.outerHtml()
        if (html.contains(marker)) take(slice(marker, Entities.unescape(html)))
        return out
    }

    /** marker 之后第一个 `[` 到与它配对的 `]` */
    private fun slice(marker: String, s: String): String? {
        val at = s.indexOf(marker)
        if (at < 0) return null
        val from = s.indexOf('[', at + marker.length)
        if (from < 0) return null
        val to = matchBracket(s, from)
        if (to < 0) return null
        return s.substring(from, to + 1)
    }

    /** 先按标准 JSON 解；不行再把 `&quot;` 之类当引号解一次 */
    private fun parseLenientArray(blob: String): JsonArray? {
        val candidates = listOf(blob, Entities.unescape(blob), blob.replace('\'', '"'))
        for (c in candidates) {
            val arr = runCatching { Json.parseToJsonElement(c).jsonArray }.getOrNull()
            if (arr != null) return arr
        }
        return null
    }

    /** 最后兜底：直接按 "id"/"title" 字段抓，容忍尾逗号、单引号、未加引号的 id */
    private fun salvageIdTitle(blob: String): ArrayList<Chapter> {
        val out = ArrayList<Chapter>()
        val re = Regex("\"id\"\\s*:\\s*\"?([0-9A-Za-z_-]+)\"?\\s*,\\s*\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        for (m in re.findAll(blob)) {
            val id = m.groupValues[1]
            val title = jsonUnescape(m.groupValues[2]).trim()
            if (id.isBlank() || title.isBlank()) continue
            val item = rule.catalog.link.replace("{id}", id).replace("{num}", id)
            out.add(Chapter(itemId = item.trim('/'), title = title))
        }
        return out
    }

    /** 处理 `\u7b2c`、`\"`、`\\`、`\/`、`\n` 这些 JSON 转义 */
    private fun jsonUnescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = runCatching { hex.toInt(16) }.getOrDefault(-1)
                    if (code > 0) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    /** 从 '[' 开始找配对的 ']'，跳过字符串里的括号 */
    private fun matchBracket(s: String, start: Int): Int {
        if (start < 0 || start >= s.length || s[start] != '[') return -1
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    override suspend fun chapter(bookId: String, itemId: String): ChapterContent = withContext(Dispatchers.IO) {
        chapterIo(bookId, itemId)
    }

    private suspend fun chapterIo(bookId: String, itemId: String): ChapterContent {
        val url = if (itemId.startsWith("http")) itemId else absolute(itemId)
        val doc = fetch(url)
        rule.chapter.remove.forEach { sel -> runCatching { doc.select(sel).remove() } }
        val el = doc.selectFirst(rule.chapter.content)
            ?: throw ApiException("${rule.name} 正文没解析出来，站点可能改版了")
        val text = extractText(el)

        val list = catalogCache[bookId] ?: runCatching { catalog(bookId) }.getOrDefault(emptyList())
        val idx = list.indexOfFirst { it.itemId == itemId }

        // 优先用正文页自带的上一章/下一章；目录只列最新 N 章、或一章拆多页时更靠得住
        val pagePrev = linkId(doc, rule.chapter.prev)
        val pageNext = linkId(doc, rule.chapter.next)

        return ChapterContent(
            itemId = itemId,
            bookId = bookId,
            title = list.getOrNull(idx)?.title ?: "",
            content = text,
            prevId = pagePrev ?: if (idx > 0) list[idx - 1].itemId else null,
            nextId = pageNext ?: if (idx >= 0 && idx < list.lastIndex) list[idx + 1].itemId else null,
        )
    }

    override suspend fun ping(): String = withContext(Dispatchers.IO) {
        pingIo()
    }

    private suspend fun pingIo(): String {
        ensureSession()
        val doc = runCatching { fetchRaw(rule.base) }.getOrElse {
            throw ApiException("${rule.name} 连不上：${it.message?.take(30)}")
        }
        if (doc.title().isBlank()) throw ApiException("${rule.name} 页面异常")
        val session = if (cookieJar.isEmpty()) "无会话" else "会话 ${cookieJar.size} 个 Cookie"
        return "${rule.name} 可访问 · $session"
    }

    // ---------------- 内部 ----------------

    private val catalogCache = HashMap<String, List<Chapter>>()

    /** 共用一个客户端：连接复用、TLS 配置统一 */
    private val http = okhttp3.OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 会话 Cookie：站点把限流、会话状态都存在这里，必须跨请求带着 */
    private val cookieJar = LinkedHashMap<String, String>()

    /** 上次真正发起搜索的时间，用来遵守站点的最小搜索间隔 */
    @Volatile private var lastSearchAt = 0L

    /** 同一关键词的最近结果：节流窗口内重复搜同一个词直接复用，不浪费一次配额 */
    private val searchCache = HashMap<String, List<Book>>()

    /**
     * 每条搜索路径当前缓存的是哪个关键词。
     *
     * 7novel 这类站前面挂了"忽略 query 的全站页面缓存"：同一路径在 2 小时内对任何关键词
     * 都返回**同一个**结果页。记住"路径 → 里面装着的词"，就能避开撞车、复用命中的缓存。
     */
    private val bucket = HashMap<String, Pair<String, Long>>()

    /** 会话建立时间；0 表示还没建 */
    @Volatile private var sessionAt = 0L

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> rule.base.trimEnd('/') + path
        else -> rule.base.trimEnd('/') + "/" + path
    }

    /** {num} 只取路径最后一段（书号），{id} 保留完整路径 */
    private fun fill(tpl: String, id: String): String =
        tpl.replace("{num}", id.substringAfterLast('/')).replace("{id}", id)

    /** 把 href 归一成 `book/123` 这种 id：去掉协议与域名、首尾斜杠、查询串 */
    private fun normalizeId(raw: String): String = raw
        .substringAfter("://", raw)
        .substringAfter('/', "")
        .substringBefore('?')
        .trim('/')

    /** 取页面里的上一章/下一章链接；指向书页（以 / 结尾）的当成"没有" */
    private fun linkId(doc: Document, selector: String): String? {
        if (selector.isBlank()) return null
        val href = pick(doc, selector) ?: return null
        if (href.endsWith("/") || href.isBlank()) return null
        val id = normalizeId(href)
        return id.ifBlank { null }
    }

    /** 「作者：xxx」「作 者: xxx」「作者 xxx」都归一成名字 */
    private fun cleanAuthor(raw: String): String =
        raw.replace(Regex("^作\\s*者\\s*[：:]?\\s*"), "").trim()

    private fun pick(scope: Element, selector: String): String? {
        if (selector.isBlank()) return null
        for (one in selector.split(",")) {
            val s = one.trim()
            if (s.isEmpty()) continue
            val at = s.substringAfterLast('@', "")
            val css = if (at.isEmpty()) s else s.substringBeforeLast('@')
            // "@href" 这种是取元素自己的属性，css 为空时要落在 scope 本身
            val el = if (css.isBlank()) scope else (runCatching { scope.selectFirst(css) }.getOrNull() ?: continue)
            val v = if (at.isEmpty()) el.text() else el.attr(at)
            if (v.isNotBlank()) return v.trim()
        }
        return null
    }

    /**
     * 建会话：先访问首页把 Cookie 收进罐子（限流、会话状态都在里面）。
     *
     * 之前的写法是"先把 warmedUp 置 true 再请求"，一旦这次请求失败就再也不会重试，
     * 后面所有搜索都带着空罐子跑。现在改成成功才记时间，失败可以直接重建。
     */
    private fun ensureSession(force: Boolean = false): Boolean {
        if (rule.warmup.isBlank()) return true
        if (!force && sessionAt > 0 && System.currentTimeMillis() - sessionAt < SESSION_TTL_MS) return true
        return runCatching {
            fetchRaw(absolute(rule.warmup))
            sessionAt = System.currentTimeMillis()
            onLog("${rule.name} 会话就绪：${cookieJar.size} 个 Cookie")
            true
        }.getOrElse {
            onLog("${rule.name} 会话获取失败：${it.message?.take(30)}")
            false
        }
    }

    private fun fetch(url: String, method: String = "GET", body: String = ""): Document {
        ensureSession()
        return fetchRaw(url, method, body)
    }

    /**
     * 真正的网络请求。
     *
     * 用 OkHttp 而不是 Jsoup.connect：
     *  - jsoup 底层是 HttpsURLConnection，TLS 协商完全交给系统栈；
     *    老手表 ROM 上遇到新证书链（如 Sectigo R46）会直接抛 SSLHandshakeException，
     *    而界面只能显示一串 `java.xxx` —— 这就是"娜娜显示 JAVA 开头错误"的来源。
     *  - OkHttp 自带的 TLS 配置更完整，且错误类型明确，能翻译成人话。
     */
    private fun fetchRaw(url: String, method: String = "GET", body: String = ""): Document {
        val builder = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (cookieJar.isNotEmpty()) {
            builder.header("Cookie", cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (method.equals("POST", true)) {
            val form = body.split("&").filter { it.isNotBlank() }.joinToString("&") { kv ->
                val k = kv.substringBefore('=')
                val v = if (kv.contains('=')) kv.substringAfter('=') else ""
                "$k=${enc(java.net.URLDecoder.decode(v, "UTF-8"))}"
            }
            builder.post(form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        } else {
            builder.get()
        }

        val resp = try {
            http.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            throw ApiException("${rule.name} ${friendly(t)}")
        }

        resp.use { r ->
            // 收 Cookie：限流、会话状态都靠它
            r.headers("Set-Cookie").forEach { raw ->
                val pair = raw.substringBefore(';')
                val k = pair.substringBefore('=').trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isNotEmpty()) cookieJar[k] = v
            }
            val bytes = runCatching { r.body?.bytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                throw ApiException("${rule.name} 返回空内容（HTTP ${r.code}）")
            }
            if (r.code >= 400) {
                throw ApiException("${rule.name} 拒绝访问（HTTP ${r.code}），可能被反爬或需要登录")
            }
            val charset = r.body?.contentType()?.charset()?.name()
            val doc = Jsoup.parse(java.io.ByteArrayInputStream(bytes), charset, r.request.url.toString())
            if (doc.title().isBlank() && doc.body() == null) throw ApiException("${rule.name} 返回了空页面")
            return doc
        }
    }

    /** 把异常翻译成用户能看懂的一句话，别再往界面上丢 `java.xxx` */
    private fun friendly(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            t is java.net.UnknownHostException -> "域名解析失败（可能是没网或被 DNS 拦截）"
            t is java.net.SocketTimeoutException -> "连接超时"
            t is javax.net.ssl.SSLException -> "HTTPS 证书校验失败（手表系统证书库太旧）"
            msg.contains("SSL", true) || msg.contains("Cert", true) -> "HTTPS 证书校验失败"
            t is java.net.ConnectException -> "连不上服务器"
            msg.contains("timeout", true) -> "连接超时"
            else -> msg.take(60).ifBlank { t.javaClass.simpleName }
        }
    }

    /** 把 HTML 转成"段落用换行分隔"的纯文本，<br> 与块级标签都算换行 */
    private fun extractText(el: Element): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "br") { sb.append('\n'); return }
                    val block = tag in setOf("p", "div", "h1", "h2", "h3", "li", "tr")
                    if (block && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    node.childNodes().forEach { walk(it) }
                    if (block && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                }
            }
        }
        walk(el)
        return sb.toString()
            .replace("\u00a0", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    companion object {
        /** 会话有效期：过期内重新访问首页 */
        private const val SESSION_TTL_MS = 30 * 60 * 1000L

        /** 最多愿意为一个站点等多久（聚合搜索单站超时 9 秒，别把整体拖死） */
        private const val MAX_BLOCK_MS = 3000L

        /** 站点页面缓存的存活时间：2 小时内同一路径只装一个关键词（与响应头 max-age 一致） */
        private const val BUCKET_TTL_MS = 2 * 60 * 60 * 1000L

        const val UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
