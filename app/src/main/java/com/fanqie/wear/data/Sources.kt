package com.fanqie.wear.data

import android.content.Context

/**
 * 书源注册表：全 App 只有这一处知道"有哪些源、当前用哪个"。
 *
 * 页面拿 [current] 或 [of] 就行，不再直接摸 [FanqieApi] / [QimaoApi]，
 * 以后加源只需要在这里注册一行。
 */
class SourceRegistry(context: Context, private val settings: SettingsStore) {

    private val biqugeApi = BiqugeApi(context)
    private val qimaoApi = QimaoApi(context)
    private val fanqieApi = FanqieApi(context) { settings.value.cookie }

    private val biquge = BiqugeSource(biqugeApi)
    private val qimao = QimaoSource(qimaoApi)
    private val fanqie = FanqieSource(fanqieApi)

    /** 设置页展示顺序：能直接用正文的排前面 */
    val all: List<BookSource> = listOf(biquge, qimao, fanqie)

    fun of(source: Source): BookSource = when (source) {
        Source.BIQUGE -> biquge
        Source.QIMAO -> qimao
        Source.FANQIE -> fanqie
    }

    /** 用户在设置里选的默认源 */
    fun current(): BookSource = of(Source.of(settings.value.sourceKey))
}

// ---------------- 笔趣阁：默认源，免登录、正文完整 ----------------

private class BiqugeSource(private val api: BiqugeApi) : BookSource {

    override val source = Source.BIQUGE
    override val displayName = "笔趣阁"
    override val desc = "免登录 · 搜索 / 分类 / 正文全通"
    override val capability = SourceCapability()
    override val shelves = listOf(
        SourceShelf("1", "玄幻"),
        SourceShelf("2", "仙侠"),
        SourceShelf("3", "都市"),
        SourceShelf("4", "网游"),
        SourceShelf("5", "科幻"),
        SourceShelf("6", "灵异"),
        SourceShelf("7", "历史"),
        SourceShelf("8", "其他"),
    )

    override suspend fun search(keyword: String, page: Int) = api.search(keyword, page)
    override suspend fun browse(shelfId: String, page: Int) = api.browse(shelfId, page)
    override suspend fun detail(bookId: String) = api.detail(bookId)
    override suspend fun catalog(bookId: String) = api.catalog(bookId)
    override suspend fun chapter(bookId: String, itemId: String) = api.chapter(bookId, itemId)
    override suspend fun ping() = api.ping()
}

// ---------------- 七猫：能搜能看目录，正文接口没打通 ----------------

private class QimaoSource(private val api: QimaoApi) : BookSource {

    override val source = Source.QIMAO
    override val displayName = "七猫免费小说"
    override val desc = "免登录 · 正文接口需 App 签名，暂不支持"
    override val capability = SourceCapability(chapter = false)
    override val shelves = listOf(
        SourceShelf("玄幻", "玄幻"),
        SourceShelf("都市", "都市"),
        SourceShelf("总裁", "总裁"),
        SourceShelf("甜宠", "甜宠"),
        SourceShelf("悬疑", "悬疑"),
        SourceShelf("战神", "战神"),
        SourceShelf("重生", "重生"),
        SourceShelf("穿越", "穿越"),
    )

    override suspend fun search(keyword: String, page: Int) = api.search(keyword, page + 1)
    override suspend fun browse(shelfId: String, page: Int) = api.search(shelfId, page + 1)

    override suspend fun detail(bookId: String): BookDetail {
        val book = api.detail(bookId)
        val chapters = runCatching { api.catalog(bookId) }.getOrDefault(emptyList())
        return BookDetail(book, chapters.size, chapters.firstOrNull()?.itemId)
    }

    override suspend fun catalog(bookId: String) = api.catalog(bookId)

    override suspend fun chapter(bookId: String, itemId: String): ChapterContent =
        throw ApiException("七猫正文接口需要 App 端专有签名，暂不支持阅读；请到设置里换一个书源")

    override suspend fun ping(): String {
        val list = api.search("玄幻")
        return "连接正常 · 命中 ${list.size} 本"
    }
}

// ---------------- 番茄：登录链路依赖 Passport 签名，先降级为备用源 ----------------

private class FanqieSource(private val api: FanqieApi) : BookSource {

    override val source = Source.FANQIE
    override val displayName = "番茄小说"
    override val desc = "需登录 · 未登录第 11 章起只有试读"
    override val capability = SourceCapability(login = true)
    override val shelves = listOf(
        SourceShelf("m", "男频推荐"),
        SourceShelf("f", "女频推荐"),
    )

    override suspend fun search(keyword: String, page: Int) = api.search(keyword, offset = page * 10)

    override suspend fun browse(shelfId: String, page: Int): List<Book> =
        api.recommend(
            gender = if (shelfId == "f") Gender.FEMALE else Gender.MALE,
            offset = page * 12,
            limit = 12,
        )

    override suspend fun detail(bookId: String) = api.detail(bookId)
    override suspend fun catalog(bookId: String) = api.directory(bookId)
    override suspend fun chapter(bookId: String, itemId: String) = api.chapter(itemId)

    override suspend fun ping(): String {
        val list = api.search("斗破苍穹")
        return "连接正常 · 命中 ${list.size} 本"
    }
}
