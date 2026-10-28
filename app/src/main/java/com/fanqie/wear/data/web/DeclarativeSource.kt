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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
)

@Serializable
data class DetailRule(
    val path: String,
    val title: String = "h1",
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
)

@Serializable
data class ChapterRule(
    val path: String = "{itemId}",
    val content: String,
    val remove: List<String> = emptyList(),
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
) : BookSource {

    override val source: Source = Source.AGG
    override val displayName: String = rule.name
    override val desc: String = rule.note.ifBlank { "声明式书源" }
    override val capability: SourceCapability = SourceCapability(
        search = rule.search != null,
        browse = false,
        chapter = true,
        login = false,
    )
    override val shelves: List<SourceShelf> = emptyList()

    /** key 供聚合层路由 */
    val siteKey: String get() = rule.key

    override suspend fun search(keyword: String, page: Int): List<Book> {
        val r = rule.search ?: throw ApiException("${rule.name} 不支持搜索")
        if (page > 0) return emptyList()
        val doc = fetch(absolute(r.path.replace("{q}", enc(keyword))), r.method, r.body.replace("{q}", enc(keyword)))
        val out = ArrayList<Book>()
        for (el in doc.select(r.list)) {
            val a = pick(el, r.link) ?: continue
            val title = pick(el, r.title)?.trim().orEmpty()
            if (title.isBlank()) continue
            out.add(
                Book(
                    id = normalizeId(a),
                    title = title,
                    author = pick(el, r.author)?.trim().orEmpty(),
                    source = Source.AGG,
                ),
            )
            if (out.size >= 30) break
        }
        onLog("${rule.name} 搜索「$keyword」得到 ${out.size} 条")
        return out
    }

    override suspend fun browse(shelfId: String, page: Int): List<Book> = emptyList()

    override suspend fun detail(bookId: String): BookDetail {
        val doc = fetch(absolute(fill(rule.detail.path, bookId)))
        val title = pick(doc, rule.detail.title)?.trim().orEmpty().ifBlank { bookId }
        val book = Book(
            id = bookId,
            title = title,
            author = pick(doc, rule.detail.author)?.trim().orEmpty(),
            intro = pick(doc, rule.detail.intro)?.trim().orEmpty(),
            cover = pick(doc, rule.detail.cover)?.let { absolute(it) },
            source = Source.AGG,
        )
        val chapters = catalog(bookId)
        return BookDetail(book = book, chapterCount = chapters.size, firstChapterId = chapters.firstOrNull()?.itemId)
    }

    override suspend fun catalog(bookId: String): List<Chapter> {
        val doc = fetch(absolute(fill(rule.catalog.path, bookId)))
        val list = ArrayList<Chapter>()
        for (el in doc.select(rule.catalog.list)) {
            val href = pick(el, rule.catalog.link) ?: continue
            val title = if (rule.catalog.title.isBlank()) el.text().trim() else pick(el, rule.catalog.title)?.trim().orEmpty()
            if (title.isBlank()) continue
            list.add(Chapter(itemId = normalizeId(href), title = title))
        }
        catalogCache[bookId] = list
        return list
    }

    override suspend fun chapter(bookId: String, itemId: String): ChapterContent {
        val url = if (itemId.startsWith("http")) itemId else absolute(itemId)
        val doc = fetch(url)
        rule.chapter.remove.forEach { sel -> runCatching { doc.select(sel).remove() } }
        val el = doc.selectFirst(rule.chapter.content)
            ?: throw ApiException("${rule.name} 正文没解析出来，站点可能改版了")
        val text = extractText(el)

        val list = catalogCache[bookId] ?: runCatching { catalog(bookId) }.getOrDefault(emptyList())
        val idx = list.indexOfFirst { it.itemId == itemId }
        return ChapterContent(
            itemId = itemId,
            bookId = bookId,
            title = list.getOrNull(idx)?.title ?: "",
            content = text,
            prevId = if (idx > 0) list[idx - 1].itemId else null,
            nextId = if (idx >= 0 && idx < list.lastIndex) list[idx + 1].itemId else null,
        )
    }

    override suspend fun ping(): String {
        val doc = fetch(rule.base)
        val ok = doc.title().isNotBlank()
        return if (ok) "${rule.name} 可访问：${doc.title().take(20)}" else "${rule.name} 页面异常"
    }

    // ---------------- 内部 ----------------

    private val catalogCache = HashMap<String, List<Chapter>>()

    /** 会话 Cookie：有些站搜索要先访问首页拿到 cookie 才给结果 */
    private val cookieJar = LinkedHashMap<String, String>()
    private var warmedUp = false

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

    private fun pick(scope: Element, selector: String): String? {
        if (selector.isBlank()) return null
        for (one in selector.split(",")) {
            val s = one.trim()
            if (s.isEmpty()) continue
            val at = s.substringAfterLast('@', "")
            val css = if (at.isEmpty()) s else s.substringBeforeLast('@')
            val el = runCatching { scope.selectFirst(css) }.getOrNull() ?: continue
            val v = if (at.isEmpty()) el.text() else el.attr(at)
            if (v.isNotBlank()) return v.trim()
        }
        return null
    }

    private fun fetch(url: String, method: String = "GET", body: String = ""): Document {
        if (!warmedUp && rule.warmup.isNotBlank()) {
            warmedUp = true
            runCatching { fetch(absolute(rule.warmup)) }
        }
        val conn = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .timeout(15000)
            .ignoreHttpErrors(true)
            .followRedirects(true)
        if (method.equals("POST", true)) {
            conn.method(org.jsoup.Connection.Method.POST)
            if (body.isNotBlank()) {
                body.split("&").forEach { kv ->
                    val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
                    conn.data(k, java.net.URLDecoder.decode(v, "UTF-8"))
                }
            }
        }
        if (cookieJar.isNotEmpty()) {
            conn.header("Cookie", cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        val doc = runCatching { conn.execute().also { resp -> resp.cookies().forEach { (k, v) -> cookieJar[k] = v } }.parse() }
            .getOrElse { throw ApiException("${rule.name} 连不上：${it.message?.take(40)}") }
        if (doc.title().isBlank() && doc.body() == null) throw ApiException("${rule.name} 返回了空页面")
        return doc
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
        const val UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
