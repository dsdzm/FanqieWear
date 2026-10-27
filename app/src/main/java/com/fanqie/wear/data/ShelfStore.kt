package com.fanqie.wear.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ShelfItem(
    val id: String,
    val title: String,
    val author: String = "",
    val cover: String? = null,
    val lastChapterId: String? = null,
    val lastChapterTitle: String = "",
    val chapterIndex: Int = 0,
    val updatedAt: Long = 0L,
    /** 收藏时用的书源，回到详情/阅读页要按它打开。旧数据没有这一项，默认番茄。 */
    val source: String = Source.FANQIE.key,
)

@Serializable
data class Progress(
    val bookId: String,
    /** 书名，历史列表要显示；旧数据为空时回退到书架里的名字 */
    val bookTitle: String = "",
    val itemId: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val updatedAt: Long = 0L,
    val source: String = Source.FANQIE.key,
)

/** 书架与阅读进度：数据量小（手表上几十本），用文件持久化即可，不引入数据库。 */
class ShelfStore(context: Context) {

    private val file = context.filesDir.resolve("shelf.json")
    private val progressFile = context.filesDir.resolve("progress.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private var items: MutableList<ShelfItem> = load(file) ?: mutableListOf()
    private var progresses: MutableMap<String, Progress> = run {
        val list: List<Progress>? = load(progressFile)
        list?.associateBy { it.bookId }?.toMutableMap() ?: mutableMapOf()
    }

    fun all(): List<ShelfItem> = items.sortedByDescending { it.updatedAt }

    fun contains(bookId: String): Boolean = items.any { it.id == bookId }

    fun add(item: ShelfItem) {
        items.removeAll { it.id == item.id }
        items.add(item.copy(updatedAt = System.currentTimeMillis()))
        save()
    }

    fun remove(bookId: String) {
        items.removeAll { it.id == bookId }
        save()
    }

    fun touch(item: ShelfItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items[idx] = item.copy(updatedAt = System.currentTimeMillis())
            save()
        }
    }

    fun progress(bookId: String): Progress? = progresses[bookId]

    fun allProgress(): List<Progress> = progresses.values.sortedByDescending { it.updatedAt }

    fun clearHistory() {
        progresses.clear()
        runCatching { progressFile.delete() }
    }

    fun saveProgress(p: Progress) {
        progresses[p.bookId] = p.copy(updatedAt = System.currentTimeMillis())
        runCatching { progressFile.writeText(json.encodeToString(progresses.values.toList())) }
        items.firstOrNull { it.id == p.bookId }?.let {
            touch(it.copy(lastChapterId = p.itemId, lastChapterTitle = p.chapterTitle, chapterIndex = p.chapterIndex))
        }
    }

    private inline fun <reified T> load(f: java.io.File): T? = runCatching {
        if (!f.exists()) null else json.decodeFromString<T>(f.readText())
    }.getOrNull()

    private fun save() {
        runCatching { file.writeText(json.encodeToString(items.toList())) }
    }
}
