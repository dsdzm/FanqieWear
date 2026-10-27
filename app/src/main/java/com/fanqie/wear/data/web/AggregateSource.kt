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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聚合书源：一次搜索查所有站点，合并去重。
 *
 * 书 id 编码成 `站点key|原id`，详情/目录/正文按前缀路由回对应站点，
 * 这样书架里存一本书就等于存了"哪站哪本"，换源时也不用改存储格式。
 */
class AggregateSource(private val sites: () -> List<DeclarativeSource>) : BookSource {

    override val source = Source.AGG
    override val displayName = "聚合搜索"
    override val desc = "一次搜遍所有站点"
    override val capability = SourceCapability(search = true, browse = false, chapter = true, login = false)
    override val shelves: List<SourceShelf> = emptyList()

    override suspend fun search(keyword: String, page: Int): List<Book> = coroutineScope {
        val list = sites().filter { it.capability.search }
        if (list.isEmpty()) throw ApiException("没有启用的站点，去「设置 → 网络与书源」开几个")
        val jobs = list.map { s ->
            async {
                val r = runCatching { withTimeoutOrNull(9000) { s.search(keyword, page) } }.getOrNull()
                r.orEmpty().map { b -> b.copy(id = "${s.siteKey}|${b.id}", source = Source.AGG) }
            }
        }
        val all = jobs.awaitAll().flatten()
        // 同名同作者算一本，保留第一个（站点顺序即优先级）
        val seen = HashSet<String>()
        all.filter { seen.add(it.title.replace(Regex("\\s+"), "") + "|" + it.author.substringBefore('·').trim()) }
    }

    override suspend fun browse(shelfId: String, page: Int): List<Book> = emptyList()

    override suspend fun detail(bookId: String): BookDetail {
        val (site, id) = route(bookId)
        val d = site.detail(id)
        return d.copy(book = d.book.copy(id = bookId, source = Source.AGG, author = decorate(d.book.author, site.displayName)))
    }

    override suspend fun catalog(bookId: String): List<Chapter> {
        val (site, id) = route(bookId)
        return site.catalog(id)
    }

    override suspend fun chapter(bookId: String, itemId: String): ChapterContent {
        val (site, id) = route(bookId)
        val c = site.chapter(id, itemId)
        return c.copy(bookId = bookId)
    }

    override suspend fun ping(): String {
        val list = sites()
        return if (list.isEmpty()) "没有启用的站点" else "已启用 ${list.size} 个站点"
    }

    /** 同一本书在其他站点里的结果，用于「换源」 */
    suspend fun alternatives(title: String, author: String): List<Book> = coroutineScope {
        val cleanAuthor = author.substringBefore('·').trim()
        sites().filter { it.capability.search }.map { s ->
            async {
                val r = runCatching { withTimeoutOrNull(9000) { s.search(title, 0) } }.getOrNull().orEmpty()
                r.filter { it.title.replace(Regex("\\s+"), "") == title.replace(Regex("\\s+"), "") }
                    .filter { cleanAuthor.isBlank() || it.author.isBlank() || it.author.contains(cleanAuthor) }
                    .map { b -> b.copy(id = "${s.siteKey}|${b.id}", author = decorate(b.author, s.displayName)) }
            }
        }.awaitAll().flatten()
    }

    private fun decorate(author: String, site: String): String =
        if (author.isBlank()) site else "$author · $site".replace(" · $site · $site", " · $site")

    private fun route(bookId: String): Pair<DeclarativeSource, String> {
        val key = bookId.substringBefore('|')
        val id = bookId.substringAfter('|', "")
        val site = sites().firstOrNull { it.siteKey == key }
            ?: throw ApiException("这本书来自已关闭的站点（$key），换源或重新搜索")
        if (id.isBlank()) throw ApiException("书号缺失")
        return site to id
    }
}
