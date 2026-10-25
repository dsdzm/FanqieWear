package com.fanqie.wear.data

/** 书源具备的能力。UI 按能力开关入口，避免"点了才知道不支持"。 */
data class SourceCapability(
    /** 支持关键词搜索 */
    val search: Boolean = true,
    /** 支持按分类/榜单浏览 */
    val browse: Boolean = true,
    /** 支持取章节正文 */
    val chapter: Boolean = true,
    /** 需要登录才能看完整内容 */
    val login: Boolean = false,
)

/** 书源内部的一个"书架"：分类、榜单、热词都归到这里，UI 只认 id + 名字。 */
data class SourceShelf(val id: String, val name: String)

/**
 * 统一书源接口。
 *
 * 之前各页面直接调 [FanqieApi] / [QimaoApi]，书源散落在 UI 里，
 * 想加一个源要改七八处。现在每个源实现这个接口，页面只跟 [SourceRegistry] 打交道。
 *
 * 约定：
 * - 分页统一从 0 开始，返回空列表表示没有更多；
 * - [Book.id] 只在本源内唯一，跨源靠 [Book.source] 区分；
 * - [Chapter.itemId] 是"本章在本源内的定位串"，不含书名，可安全当文件名。
 */
interface BookSource {

    val source: Source

    /** 设置页展示名 */
    val displayName: String

    /** 一句话说明，例如「免登录 · 搜索/正文可用」 */
    val desc: String

    val capability: SourceCapability

    /** 浏览页可选的分类/榜单，空列表表示该源不支持浏览 */
    val shelves: List<SourceShelf>

    suspend fun search(keyword: String, page: Int = 0): List<Book>

    suspend fun browse(shelfId: String, page: Int = 0): List<Book>

    suspend fun detail(bookId: String): BookDetail

    suspend fun catalog(bookId: String): List<Chapter>

    /** 取正文；源不支持时抛 [ApiException]，文案要能直接给用户看 */
    suspend fun chapter(bookId: String, itemId: String): ChapterContent

    /** 连通性自检：成功返回一句结果，失败抛异常 */
    suspend fun ping(): String
}
