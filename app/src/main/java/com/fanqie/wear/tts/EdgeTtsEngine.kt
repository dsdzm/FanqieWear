package com.fanqie.wear.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 在线语音：微软 Edge「大声朗读」的公开接口。
 *
 * 为什么选它：免费、不要 key、中文音色接近真人（晓晓/云希/晓伊…），
 * 而且是 WebSocket 流式返回 MP3，几百字一句话一两秒就回来。
 *
 * 接口非官方，随时可能变，所以：
 * - 失败就回退本地引擎，不让听书整个挂掉；
 * - 合成结果按内容哈希落盘（[TtsCache]），同一章重听不再请求，断网也能播。
 */
class EdgeTtsEngine(private val context: Context, private val cache: TtsCache) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastError: String? = null

    val errorText: String? get() = lastError

    /** 可选音色（中文） */
    val voices: List<Pair<String, String>> get() = VOICE_LIST

    companion object {
        val VOICE_LIST: List<Pair<String, String>> = listOf(
            "晓晓 · 女声" to "zh-CN-XiaoxiaoNeural",
            "云希 · 男声" to "zh-CN-YunxiNeural",
            "晓伊 · 女声" to "zh-CN-XiaoyiNeural",
            "云健 · 男声" to "zh-CN-YunjianNeural",
            "晓北 · 女声" to "zh-CN-liaoning-XiaobeiNeural",
            "晓妮 · 女声" to "zh-CN-shaanxi-XiaoniNeural",
        )
    }

    /**
     * 合成一段文本，返回本地 mp3 文件。
     * 命中缓存直接返回，不联网。
     */
    suspend fun synthesize(text: String, voice: String, ratePercent: Int): File? {
        cache.find(text, voice, ratePercent)?.let { return it }
        val tmp = File(context.cacheDir, "tts-${UUID.randomUUID()}.part")
        val ok = streamTo(text, voice, ratePercent, tmp)
        if (!ok || !tmp.isFile || tmp.length() < 512) {
            tmp.delete()
            return null
        }
        return cache.put(text, voice, ratePercent, tmp)
    }

    /** 缓存里有没有这段（离线判断用） */
    fun cached(text: String, voice: String, ratePercent: Int): File? = cache.find(text, voice, ratePercent)

    private suspend fun streamTo(text: String, voice: String, ratePercent: Int, out: File): Boolean {
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val request = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")
            .build()

        return withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                var finished = false
                val ws = client.newWebSocket(request, object : WebSocketListener() {

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val id = UUID.randomUUID().toString().replace("-", "")
                        val cfg = "X-Timestamp:${System.currentTimeMillis() / 1000}.0\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                        webSocket.send(cfg)

                        val rate = (if (ratePercent >= 0) "+" else "") + ratePercent + "%"
                        val ssml = "X-RequestId:$id\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${System.currentTimeMillis() / 1000}.0Z\r\n" +
                            "Path:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                            "<voice name='$voice'>" +
                            "<prosody rate='$rate' pitch='+0Hz'>${escape(text)}</prosody>" +
                            "</voice></speak>"
                        webSocket.send(ssml)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        // 二进制帧：前 2 字节是头长度，头里带 Path:audio 才是音频
                        runCatching {
                            if (bytes.size < 2) return
                            val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                            val header = bytes.substring(2, 2 + headerLen).utf8()
                            if (header.contains("Path:audio")) {
                                val audio = bytes.substring(2 + headerLen)
                                out.appendBytes(audio.toByteArray())
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            finished = true
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(true)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        lastError = t.message ?: "网络错误"
                        Log.w("EdgeTts", "合成失败", t)
                        if (cont.isActive) cont.resume(finished)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(finished)
                    }
                })
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        } ?: false
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/**
 * 听书音频缓存：按「文本+音色+语速」做 key，落盘为 mp3。
 * 超过上限按最久未用淘汰，手表存储小，别让它无限长。
 */
class TtsCache(context: Context) {

    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val maxBytes = 120L * 1024 * 1024 // 120MB 上限

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
