package com.fanqie.wear.data

import java.util.Locale

/**
 * 书源。默认值给笔趣阁：它免登录、正文完整，是现在的主用源。
 * 番茄与七猫保留为可切换的备用源，不再硬编码在页面里。
 */
enum class Source(val key: String, val label: String) {
    BIQUGE("biquge", "笔趣阁"),
    QIMAO("qimao", "七猫"),
    FANQIE("fanqie", "番茄"),
    AGG("agg", "聚合"),
    LOCAL("local", "本地"),
    ;

    companion object {
        fun of(key: String?): Source = entries.firstOrNull { it.key == key } ?: BIQUGE
    }
}

data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val cover: String? = null,
    val intro: String = "",
    val category: String = "",
    val wordCount: String = "",
    val readCount: String = "",
    val score: String = "",
    val finished: Boolean = false,
    val lastChapter: String = "",
    val tags: List<String> = emptyList(),
    val source: Source = Source.BIQUGE,
)

data class Chapter(
    val itemId: String,
    val title: String,
    val volume: String = "",
)

data class ChapterContent(
    val itemId: String,
    val bookId: String,
    val title: String,
    val content: String,
    val prevId: String? = null,
    val nextId: String? = null,
    val locked: Boolean = false,
)

data class BookDetail(
    val book: Book,
    val chapterCount: Int,
    val firstChapterId: String?,
)

data class Category(val id: Int, val name: String)

/** 榜单维度：男频/女频 × 阅读榜/新书榜。
 *  注意：番茄接口里 gender=1 才是男频，gender=0 是女频。 */
enum class Gender(val api: Int, val label: String) { MALE(1, "男频"), FEMALE(0, "女频") }

enum class RankMold(val api: Int, val label: String) {
    READ(1, "阅读榜"), NEW(2, "新书榜"), FINISH(3, "完结榜")
}

fun Long.toWan(): String {
    val v = this
    return when {
        v >= 100_000_000 -> String.format(Locale.CHINA, "%.1f亿", v / 100_000_000.0)
        v >= 10_000 -> String.format(Locale.CHINA, "%.1f万", v / 10_000.0)
        else -> v.toString()
    }
}

/** 字数文案：接口里既有 "297502" 这种纯数字，也有 "29.7万字"。 */
fun normalizeWordCount(raw: String): String {
    val n = raw.toLongOrNull() ?: return raw
    return n.toWan() + "字"
}
