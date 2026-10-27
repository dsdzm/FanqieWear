package com.fanqie.wear.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 一本导入的本地书 */
@Serializable
data class LocalBook(
    val id: String,
    val title: String,
    val author: String = "",
    val charCount: Int = 0,
    val chapterCount: Int = 0,
    val importedAt: Long = 0L,
    /** 原始文件名，方便用户认 */
    val fileName: String = "",
)

/** 章节目录项：正文按字节偏移随机读，不整本进内存 */
@Serializable
data class LocalChapter(
    val title: String,
    val start: Long,
    val length: Int,
)

@Serializable
private data class LocalIndex(
    val book: LocalBook,
    val chapters: List<LocalChapter>,
)

/**
 * 本地书库。
 *
 * 导入流程：选文件 → 猜编码 → 正则切章 → 正文按 UTF-8 重新写一份 + 记字节偏移。
 * 之后阅读器按偏移随机读，几十 MB 的书也不会一次读进内存。
 *
 * 为什么重写一份而不是原地读：源文件可能是 GBK/UTF-16，重写成 UTF-8 后
 * 编码问题一次性解决，偏移也稳定。
 */
class LocalLibrary(private val context: Context) {

    private val root = File(context.filesDir, "books")
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<LocalBook> = runCatching {
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readIndex(dir)?.book }
            ?.sortedByDescending { it.importedAt }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun find(bookId: String): LocalBook? = readIndex(File(root, bookId))?.book

    fun chapters(bookId: String): List<LocalChapter> = readIndex(File(root, bookId))?.chapters.orEmpty()

    /** 读一章正文；偏移越界或文件损坏时返回提示串，不抛异常 */
    fun readChapter(bookId: String, index: Int): String = runCatching {
        val idx = readIndex(File(root, bookId)) ?: return "本地书索引损坏，请重新导入"
        val ch = idx.chapters.getOrNull(index) ?: return "章节不存在"
        val file = File(root, "$bookId/source.txt")
        if (!file.isFile) return "正文文件丢失，请重新导入"
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(ch.length)
            raf.seek(ch.start)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrElse { "读取失败：${it.message}" }

    fun delete(bookId: String) {
        runCatching { File(root, bookId).deleteRecursively() }
    }

    /**
     * 导入一个 txt。会回调进度（0~1，主要在切章和写入阶段）。
     * 失败抛异常，文案可以直接给用户看。
     */
    fun import(uri: Uri, fileName: String, onProgress: (Float, String) -> Unit = { _, _ -> }): LocalBook {
        val resolver = context.contentResolver
        onProgress(0.05f, "读取文件")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("打不开这个文件")
        if (bytes.isEmpty()) throw IllegalStateException("文件是空的")
        if (bytes.size > 60 * 1024 * 1024) throw IllegalStateException("文件超过 60MB，手表带不动")

        onProgress(0.15f, "识别编码")
        val text = decode(bytes)
        if (text.isBlank()) throw IllegalStateException("没读出文字，可能不是纯文本")

        val title = guessTitle(fileName, text)
        return writeBook(text, title, fileName, bytes.size.toLong(), onProgress)
    }

    /** 直接把一段完整文本存成一本本地书（第三方站点导出的 TXT 走这里） */
    fun importText(text: String, title: String, fileName: String = "$title.txt"): LocalBook =
        writeBook(text, title, fileName, text.length.toLong()) { _, _ -> }

    private fun writeBook(
        text: String,
        title: String,
        fileName: String,
        sizeHint: Long,
        onProgress: (Float, String) -> Unit,
    ): LocalBook {
        onProgress(0.3f, "切分章节")
        val chunks = splitChapters(text)
        if (chunks.isEmpty()) throw IllegalStateException("切分不出章节")

        val id = bookIdOf(title, sizeHint.toInt(), text.firstOrNull()?.code ?: 0)
        val dir = File(root, id)
        // 重复导入直接复用，避免占两份空间
        readIndex(dir)?.let { existing ->
            onProgress(1f, "已存在，直接复用")
            return existing.book
        }
        dir.mkdirs()

        onProgress(0.4f, "写入正文")
        val index = ArrayList<LocalChapter>(chunks.size)
        var offset = 0L
        File(dir, "source.txt").outputStream().buffered().use { out ->
            chunks.forEachIndexed { i, c ->
                val body = if (i == 0) c.text else "${c.title}\n\n${c.text}"
                val b = body.toByteArray(Charsets.UTF_8)
                out.write(b)
                index.add(LocalChapter(c.title, offset, b.size))
                offset += b.size
                if (i % 50 == 0) onProgress(0.4f + 0.5f * i / chunks.size, "写入第 ${i + 1} 章")
            }
        }

        val book = LocalBook(
            id = id,
            title = title,
            author = guessAuthor(text),
            charCount = text.length,
            chapterCount = chunks.size,
            importedAt = System.currentTimeMillis(),
            fileName = fileName,
        )
        File(dir, "index.json").writeText(json.encodeToString(LocalIndex(book, index)))
        return book
    }


    // ---------------- 解析细节 ----------------

    private fun readIndex(dir: File): LocalIndex? = runCatching {
        val f = File(dir, "index.json")
        if (!f.isFile) null else json.decodeFromString<LocalIndex>(f.readText())
    }.getOrNull()

    /** BOM → UTF-8 严格解码 → GB18030，三级回退 */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8：遇到非法序列直接抛，交给下面的回退
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null && utf8.isNotEmpty()) return utf8

        // 中文老书基本都是 GBK/GB2312，GB18030 是超集
        return runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private data class Chunk(val title: String, val text: String)

    private val chapterPatterns = listOf(
        // 第一章 xxx / 第 12 节 / 第三卷（"第 2 章" 这种带空格的也认）
        Regex("^\\s*第\\s*[0-9０-９零一二三四五六七八九十百千万两]{1,10}\\s*[章节回卷][^\\n]{0,30}$"),
        // Chapter 12 / CHAPTER XII
        Regex("^\\s*[Cc]hapter\\s+[0-9IVXLC]{1,6}[^\\n]{0,30}$"),
        // 序章 / 楔子 / 前言 / 后记 / 尾声 / 番外
        Regex("^\\s*(序章|序言|楔子|引子|前言|后记|尾声|终章|番外)[^\\n]{0,20}$"),
        // 纯数字标题：1 xxx（限定短行，避免误伤正文）
        Regex("^\\s*[0-9]{1,4}[、.．\\s][^\\n]{1,30}$"),
    )

    private fun isChapterTitle(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 40) return false
        return chapterPatterns.any { it.matches(t) }
    }

    private fun splitChapters(text: String): List<Chunk> {
        val lines = text.split("\n")
        val chunks = ArrayList<Chunk>()
        var title = "正文"
        val sb = StringBuilder()

        fun flush() {
            val body = sb.toString().trim()
            if (body.isNotEmpty()) chunks.add(Chunk(title, body))
            sb.setLength(0)
        }

        for (line in lines) {
            if (isChapterTitle(line)) {
                flush()
                title = line.trim()
            } else {
                sb.append(line).append('\n')
            }
        }
        flush()

        // 完全没识别到章节：按 3000 字切
        if (chunks.size <= 1) {
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()
            val out = ArrayList<Chunk>()
            var i = 0
            var n = 1
            while (i < clean.length) {
                val end = (i + 3000).coerceAtMost(clean.length)
                out.add(Chunk("第 $n 节", clean.substring(i, end).trim()))
                i = end
                n++
            }
            return out
        }
        return chunks
    }

    private fun guessTitle(fileName: String, text: String): String {
        val fromName = fileName.substringBeforeLast('.').trim()
        if (fromName.length in 2..40) return fromName
        // 文件名不行就取第一行非空文本（常见于"_book.txt"这类）
        return text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.length <= 40 }
            ?: "本地书"
    }

    private fun guessAuthor(text: String): String {
        val head = text.take(600)
        val m = Regex("作\\s*者[：:]\\s*([^\\n]{1,20})").find(head) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun bookIdOf(title: String, size: Int, salt: Int): String {
        var h = 1125899906842597L
        for (c in title) h = 31 * h + c.code
        h = h * 31 + size
        h = h * 31 + salt
        return "local-" + java.lang.Long.toHexString(h)
    }
}
