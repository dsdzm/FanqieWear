package com.fanqie.wear.data

/**
 * 本地导入的书，做成一个书源，书架/阅读器/听书全部复用现有链路。
 *
 * itemId 就是章节序号（字符串），不依赖任何文件命名规则。
 */
class LocalSource(private val library: LocalLibrary) : BookSource {

    override val source = Source.LOCAL
    override val displayName = "本地导入"
    override val desc = "自己导入的 txt · 离线可读"
    override val capability = SourceCapability(search = true, browse = true, chapter = true, login = false)
    override val shelves = listOf(SourceShelf("all", "全部本地书"))

    override suspend fun search(keyword: String, page: Int): List<Book> {
        if (page > 0) return emptyList()
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        return library.all()
            .filter { it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true) }
            .map { it.toBook() }
    }

    override suspend fun browse(shelfId: String, page: Int): List<Book> {
        if (page > 0) return emptyList()
        return library.all().map { it.toBook() }
    }

    override suspend fun detail(bookId: String): BookDetail {
        val b = library.find(bookId) ?: throw ApiException("本地书不存在，可能已被删除")
        return BookDetail(
            book = b.toBook(),
            chapterCount = b.chapterCount,
            firstChapterId = if (b.chapterCount > 0) "0" else null,
        )
    }

    override suspend fun catalog(bookId: String): List<Chapter> =
        library.chapters(bookId).mapIndexed { i, c -> Chapter(i.toString(), c.title) }

    override suspend fun chapter(bookId: String, itemId: String): ChapterContent {
        val index = itemId.toIntOrNull() ?: throw ApiException("章节号不合法")
        val chapters = library.chapters(bookId)
        val ch = chapters.getOrNull(index) ?: throw ApiException("章节不存在")
        return ChapterContent(
            itemId = itemId,
            bookId = bookId,
            title = ch.title,
            content = library.readChapter(bookId, index),
            prevId = if (index > 0) (index - 1).toString() else null,
            nextId = if (index < chapters.lastIndex) (index + 1).toString() else null,
        )
    }

    override suspend fun ping(): String {
        val n = library.all().size
        return if (n == 0) "本地书库为空，书架页可以导入 txt" else "本地书 $n 本，可用"
    }

    private fun LocalBook.toBook(): Book = Book(
        id = id,
        title = title,
        author = author.ifBlank { "本地导入" },
        intro = "本地导入 · ${chapterCount} 章 · ${charCount} 字",
        category = "本地",
        wordCount = charCount.toLong().toWan() + "字",
        source = Source.LOCAL,
    )
}
