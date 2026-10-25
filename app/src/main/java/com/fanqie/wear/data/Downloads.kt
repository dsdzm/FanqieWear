package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 离线下载：一章一个文件，目录结构
 *
 * ```
 * files/downloads/<bookId>/meta.json
 * files/downloads/<bookId>/<itemId>.txt
 * ```
 *
 * 正文文件格式：第一行章节名、第二行 prevId、第三行 nextId，其余是正文。
 * 不用 JSON 存正文，省掉转义开销，1000 章的书也只有几 MB。
 */
data class DownloadedBook(
    val bookId: String,
    val title: String,
    val cover: String?,
    val total: Int,
    val done: Int,
    val firstChapterId: String?,
    val updatedAt: Long,
    val bytes: Long,
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val sizeText: String
        get() = if (bytes > 1024 * 1024) String.format("%.1f MB", bytes / 1024f / 1024f)
        else "${bytes / 1024} KB"
}

class DownloadStore(context: Context) {

    private val root = File(context.filesDir, "downloads").apply { mkdirs() }

    private fun dir(bookId: String) = File(root, bookId.replace(Regex("[^A-Za-z0-9_]"), "_"))

    fun has(bookId: String, itemId: String): Boolean = File(dir(bookId), "$itemId.txt").exists()

    fun saveChapter(bookId: String, c: ChapterContent) {
        val d = dir(bookId).apply { mkdirs() }
        File(d, "${c.itemId}.txt").writeText(
            buildString {
                append(c.title.replace("\n", " ")).append('\n')
                append(c.prevId ?: "").append('\n')
                append(c.nextId ?: "").append('\n')
                append(c.content)
            },
            Charsets.UTF_8,
        )
    }

    fun readChapter(bookId: String, itemId: String): ChapterContent? {
        val f = File(dir(bookId), "$itemId.txt")
        if (!f.exists()) return null
        return runCatching {
            val lines = f.readText(Charsets.UTF_8).split("\n", limit = 4)
            ChapterContent(
                itemId = itemId,
                bookId = bookId,
                title = lines.getOrElse(0) { "" },
                content = lines.getOrElse(3) { "" },
                prevId = lines.getOrElse(1) { "" }.ifBlank { null },
                nextId = lines.getOrElse(2) { "" }.ifBlank { null },
            )
        }.getOrNull()
    }

    fun writeMeta(bookId: String, title: String, cover: String?, total: Int, firstChapterId: String?) {
        val d = dir(bookId).apply { mkdirs() }
        val meta = JSONObject()
            .put("bookId", bookId)
            .put("title", title)
            .put("cover", cover ?: "")
            .put("total", total)
            .put("first", firstChapterId ?: "")
            .put("updatedAt", System.currentTimeMillis())
        File(d, "meta.json").writeText(meta.toString())
    }

    fun list(): List<DownloadedBook> {
        return root.listFiles()?.mapNotNull { d ->
            if (!d.isDirectory) return@mapNotNull null
            val metaFile = File(d, "meta.json")
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            val chapters = d.listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0
            if (chapters == 0) return@mapNotNull null
            DownloadedBook(
                bookId = meta?.optString("bookId").orEmpty().ifBlank { d.name },
                title = meta?.optString("title").orEmpty().ifBlank { d.name },
                cover = meta?.optString("cover").orEmpty().ifBlank { null },
                total = meta?.optInt("total") ?: chapters,
                done = chapters,
                firstChapterId = meta?.optString("first").orEmpty().ifBlank { null },
                updatedAt = meta?.optLong("updatedAt") ?: d.lastModified(),
                bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        }?.sortedByDescending { it.updatedAt }.orEmpty()
    }

    fun delete(bookId: String) {
        dir(bookId).deleteRecursively()
    }

    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

data class DownloadTask(
    val bookId: String,
    val title: String,
    val total: Int = 0,
    val done: Int = 0,
    val running: Boolean = true,
    val error: String? = null,
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 下载队列：串行跑（接口有频率限制），进度走 StateFlow，
 * 页面切走也继续下，返回来还能看到进度。
 */
class DownloadManager(
    private val api: FanqieApi,
    private val store: DownloadStore,
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun isDownloaded(bookId: String): Boolean = store.list().any { it.bookId == bookId }

    fun start(bookId: String, title: String, cover: String?, limit: Int = 0) {
        if (jobs[bookId]?.isActive == true) return
        _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title))
        jobs[bookId] = scope.launch {
            runCatching {
                val chapters = api.directory(bookId)
                val targets = if (limit > 0) chapters.take(limit) else chapters
                var done = 0
                for (ch in targets) {
                    if (!store.has(bookId, ch.itemId)) {
                        val c = api.chapter(ch.itemId)
                        store.saveChapter(bookId, c)
                        delay(150)
                    }
                    done++
                    _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title, targets.size, done, true))
                }
                store.writeMeta(bookId, title, cover, targets.size, targets.firstOrNull()?.itemId)
                _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title, targets.size, done, false))
            }.onFailure { e ->
                _tasks.value = _tasks.value + (
                    bookId to DownloadTask(bookId, title, 0, 0, false, e.message ?: "下载失败")
                    )
            }
        }
    }

    fun cancel(bookId: String) {
        jobs[bookId]?.cancel()
        jobs.remove(bookId)
        _tasks.value = _tasks.value + (bookId to (_tasks.value[bookId]?.copy(running = false, error = "已取消")
            ?: DownloadTask(bookId, "", running = false, error = "已取消")))
    }

    fun dismiss(bookId: String) {
        _tasks.value = _tasks.value - bookId
    }
}
