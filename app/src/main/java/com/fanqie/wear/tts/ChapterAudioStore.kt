package com.fanqie.wear.tts

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 章节语音包：把一章的在线语音全部合成本地文件，之后离线也能整章听。
 *
 * 目录结构：filesDir/tts-audio/<bookKey>/<chapterKey>/000.mp3 + index.json
 * index.json 里存的是**分块文本**——播放时按包里的分块走，这样即使以后改了分块长度，
 * 已经下载好的章节也能原样播。
 */
class ChapterAudioStore(private val context: Context) {

    @Serializable
    private data class Index(val chunks: List<String>, val createdAt: Long)

    private val root = File(context.filesDir, "tts-audio")
    private val json = Json { ignoreUnknownKeys = true }

    fun has(bookId: String, chapterId: String): Boolean {
        val d = dir(bookId, chapterId)
        val hasIndex = File(d, "index.json").isFile
        val mp3 = d.listFiles()?.count { it.name.endsWith(".mp3") } ?: 0
        return hasIndex && mp3 > 0
    }

    fun chunks(bookId: String, chapterId: String): List<String>? = runCatching {
        val f = File(dir(bookId, chapterId), "index.json")
        if (!f.isFile) null else json.decodeFromString<Index>(f.readText()).chunks
    }.getOrNull()

    fun file(bookId: String, chapterId: String, index: Int): File? {
        val f = File(dir(bookId, chapterId), "%03d.mp3".format(index))
        return if (f.isFile && f.length() > 512) f else null
    }

    /**
     * 下载整章：逐块合成写盘，最后写 index.json。
     * 中途失败会清掉半成品，避免"看起来下好了其实是残章"。
     */
    suspend fun save(
        bookId: String,
        chapterId: String,
        chunks: List<String>,
        ratePercent: Int,
        synth: suspend (String, Int) -> File?,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        val d = dir(bookId, chapterId)
        d.mkdirs()
        val made = ArrayList<File>()
        for ((i, text) in chunks.withIndex()) {
            onProgress(i + 1, chunks.size)
            val src = synth(text, ratePercent) ?: run {
                made.forEach { it.delete() }
                d.deleteRecursively()
                return false
            }
            val dst = File(d, "%03d.mp3".format(i))
            runCatching {
                src.copyTo(dst, overwrite = true)
                made.add(dst)
            }.onFailure {
                made.forEach { f -> f.delete() }
                d.deleteRecursively()
                return false
            }
        }
        File(d, "index.json").writeText(json.encodeToString(Index(chunks, System.currentTimeMillis())))
        return true
    }

    fun remove(bookId: String, chapterId: String) {
        runCatching { dir(bookId, chapterId).deleteRecursively() }
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun chapterCount(): Int = root.listFiles()?.sumOf { b ->
        b.listFiles()?.count { File(it, "index.json").isFile } ?: 0
    } ?: 0

    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    private fun dir(bookId: String, chapterId: String): File =
        File(File(root, safe(bookId)), safe(chapterId))

    private fun safe(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "x" }
}
