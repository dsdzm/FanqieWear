package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** 下载时用的书源，离线打开时按它走 */
    val source: String = Source.BIQUGE.key,
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

    fun writeMeta(
        bookId: String,
        title: String,
        cover: String?,
        total: Int,
        firstChapterId: String?,
        source: Source,
        done: Int = -1,
    ) {
        val d = dir(bookId).apply { mkdirs() }
        val meta = JSONObject()
            .put("bookId", bookId)
            .put("title", title)
            .put("cover", cover ?: "")
            .put("total", total)
            .put("first", firstChapterId ?: "")
            .put("source", source.key)
            .put("done", if (done >= 0) done else countFiles(d))
            .put("updatedAt", System.currentTimeMillis())
        File(d, "meta.json").writeText(meta.toString())
    }

    private fun countFiles(d: File): Int = d.listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    /**
     * 这本书已经缓存了多少章。
     *
     * 下载过程中每章都会问一次，所以先看 meta 里记的 done，实在没有再数文件 ——
     * 之前这里每次都走 [list]，等于每下一章就把整个下载目录深度遍历一遍，
     * 又慢又把 UI 线程拖住（进度条一动就掉帧）。
     */
    fun countOf(bookId: String): Int {
        val d = dir(bookId)
        if (!d.isDirectory) return 0
        val meta = runCatching { JSONObject(File(d, "meta.json").readText()) }.getOrNull()
        val done = meta?.optInt("done", -1) ?: -1
        return if (done >= 0) done else countFiles(d)
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
                source = meta?.optString("source").orEmpty().ifBlank { Source.BIQUGE.key },
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
    /** 已经试过但没下下来的章节数（站点超时/504 之类），可以再点「继续下载」补 */
    val failed: Int = 0,
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 下载队列：串行跑（接口有频率限制），进度走 StateFlow，
 * 页面切走也继续下，返回来还能看到进度。
 *
 * 按书源下载：目录与正文都从 [SourceRegistry] 里取，
 * 所以七猫那种没有正文的源会在开始时就报错，而不是下到一半全空。
 *
 * ## 单章失败不等于整本失败
 *
 * 七小说的章节页是逐章 SSR，源站一忙就回 Cloudflare 504（16 字节的 `error code: 504`），
 * 抓 535 章必然有几章撞上。旧版把整个循环包在一个 runCatching 里 —— 第 300 章超时，
 * 前面下好的 299 章还在，但任务直接变「失败」，界面上看起来就是"下不了"。
 *
 * 现在每章独立重试（[CHAPTER_TRIES] 次，退避递增），失败只跳过并计数，
 * 整本照跑；结束后把「已下 X 章 / Y 章失败」一起报出来，再点一次就从缺的那章续传。
 */
class DownloadManager(
    private val sources: SourceRegistry,
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val context: Context? = null,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun isDownloaded(bookId: String): Boolean = store.countOf(bookId) > 0

    fun start(bookId: String, title: String, cover: String?, source: Source, limit: Int = 0) {
        if (jobs[bookId]?.isActive == true) return
        _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title))
        jobs[bookId] = scope.launch {
            var wake: android.os.PowerManager.WakeLock? = null
            try {
                val api = sources.of(source)
                if (!api.capability.chapter) throw ApiException("${api.displayName} 暂不支持下载正文")
                val chapters = api.catalog(bookId)
                if (chapters.isEmpty()) throw ApiException("目录为空，无法下载")
                val targets = if (limit > 0) chapters.take(limit) else chapters
                val total = targets.size
                // 断点续传：已经落盘的章节直接跳过，重跑「下载全本」只补缺的那几章
                val todo = targets.filterNot { store.has(bookId, it.itemId) }
                var done = total - todo.size
                var failed = 0
                var lastError = ""

                publish(bookId, title, total, done, failed)
                // 屏幕一灭系统就会挂起协程，长下载必须在灭屏后继续跑
                wake = acquireWakeLock()

                // 一批几章并发：源站慢的时候能明显缩短总时长，又不会把站点打死
                for (group in todo.chunked(CONCURRENCY)) {
                    val results = coroutineScope {
                        group.map { ch -> async(Dispatchers.IO) { ch to fetchOne(api, bookId, ch) } }
                            .awaitAll()
                    }
                    for ((_, r) in results) {
                        if (r != null) {
                            store.saveChapter(bookId, r)
                            done++
                        } else {
                            failed++
                            if (lastError.isBlank()) lastError = "章节页超时（站点返回 504）"
                        }
                    }
                    publish(bookId, title, total, done, failed)
                }

                store.writeMeta(bookId, title, cover, total, targets.firstOrNull()?.itemId, source, done)
                val msg = when {
                    done == 0 -> "一章都没下下来：$lastError"
                    failed > 0 -> "已下载 $done 章，$failed 章失败（$lastError）· 可点「继续下载」补齐"
                    else -> null
                }
                _tasks.value = _tasks.value + (
                    bookId to DownloadTask(bookId, title, total, done, false, msg, failed)
                    )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val got = store.countOf(bookId)
                val msg = if (got > 0) {
                    "已下载 $got 章，中断：${e.message ?: e.javaClass.simpleName}"
                } else {
                    e.message ?: "下载失败"
                }
                _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title, 0, got, false, msg))
            } finally {
                runCatching { if (wake?.isHeld == true) wake?.release() }
                jobs.remove(bookId)
            }
        }
    }

    /**
     * 取一章，失败重试。
     *
     * 除了异常，**正文太短也算失败**：七小说的正文容器里夹了一行没渲染的模板占位
     * （`<img src="{{image_domain}}...">`），偶尔整页只回这一行，存下来就是空章。
     */
    private suspend fun fetchOne(
        api: BookSource,
        bookId: String,
        ch: Chapter,
    ): ChapterContent? {
        for (i in 0 until CHAPTER_TRIES) {
            val r = runCatching { api.chapter(bookId, ch.itemId) }.getOrNull()
            if (r != null && r.content.trim().length >= 24) return r
            if (i < CHAPTER_TRIES - 1) delay(500L * (i + 1))
        }
        return null
    }

    private fun publish(bookId: String, title: String, total: Int, done: Int, failed: Int) {
        _tasks.value = _tasks.value + (bookId to DownloadTask(bookId, title, total, done, true, null, failed))
    }

    private fun acquireWakeLock(): android.os.PowerManager.WakeLock? = runCatching {
        val pm = context?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "fanqie:download")?.apply {
            setReferenceCounted(false)
            acquire(60L * 60L * 1000L)
        }
    }.getOrNull()

    fun cancel(bookId: String) {
        jobs[bookId]?.cancel()
        jobs.remove(bookId)
        _tasks.value = _tasks.value + (bookId to (_tasks.value[bookId]?.copy(running = false, error = "已取消")
            ?: DownloadTask(bookId, "", running = false, error = "已取消")))
    }

    fun dismiss(bookId: String) {
        _tasks.value = _tasks.value - bookId
    }

    private companion object {
        /** 同时抓几章。七小说无登录、无频率限制，实测 3 路稳定 */
        const val CONCURRENCY = 3

        /** 单章最多试几次 */
        const val CHAPTER_TRIES = 3
    }
}
