package com.fanqie.wear.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 听书音频缓存：按「文本 + 音色 + 语速」做 key，落盘为 mp3。
 * 超过上限按最久未用淘汰，手表存储小，别让它无限长。
 */
class TtsCache(context: Context) {

    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val maxBytes = 120L * 1024 * 1024

    fun find(text: String, voice: String, rate: Int): File? {
        val f = File(dir, key(text, voice, rate) + ".mp3")
        return if (f.isFile && f.length() > 512) {
            f.setLastModified(System.currentTimeMillis())
            f
        } else null
    }

    fun put(text: String, voice: String, rate: Int, tmp: File): File? = runCatching {
        val dst = File(dir, key(text, voice, rate) + ".mp3")
        if (dst.isFile) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        trim()
        dst
    }.getOrNull()

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun key(text: String, voice: String, rate: Int): String {
        val md = MessageDigest.getInstance("SHA-1")
        val raw = "$voice|$rate|$text".toByteArray()
        return md.digest(raw).joinToString("") { "%02x".format(it) }
    }
}
