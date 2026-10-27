package com.fanqie.wear.tts

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 在线语音：走百度翻译的朗读接口。
 *
 * 为什么不用 Edge「大声朗读」了：微软后来给它加了 Sec-MS-GEC 的 DRM 签名
 * （自定义 CS64 + 改过常量的 SHA-256），不发对应令牌一律 403。
 * 复刻那套签名既容易写错，也挡不住它再改；百度这个接口是普通 GET，
 * 免 key、免签名，中文音色也够用，适合手表这种场景。
 *
 * 特点：
 * - 一次最多 300 字左右，正好对上播放器的分块；
 * - 返回 16kHz 单声道 mp3（约 64kbps，150 字 ≈ 300KB）；
 * - 结果按文本哈希落盘，重听不联网。
 */
class OnlineTtsEngine(private val context: Context, private val cache: TtsCache) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastError: String? = null

    val errorText: String? get() = lastError

    /**
     * 合成一段，返回本地 mp3。
     * 命中缓存直接返回；失败返回 null，并把原因记在 [errorText]。
     */
    suspend fun synthesize(text: String, ratePercent: Int): File? {
        cache.find(text, VOICE_KEY, ratePercent)?.let { return it }

        val tmp = File(context.cacheDir, "tts-${UUID.randomUUID()}.part")
        val ok = runCatching { download(text, ratePercent, tmp) }.getOrElse {
            lastError = it.message ?: "网络异常"
            TtsLog.e("online", it)
            false
        }
        if (!ok || !tmp.isFile || tmp.length() < 1024) {
            tmp.delete()
            if (lastError == null) lastError = "返回内容为空"
            return null
        }
        lastError = null
        return cache.put(text, VOICE_KEY, ratePercent, tmp)
    }

    fun cached(text: String, ratePercent: Int): File? = cache.find(text, VOICE_KEY, ratePercent)

    private fun download(text: String, ratePercent: Int, out: File): Boolean {
        // 语速：spd 1~9，5 是正常
        val spd = (5 + ratePercent / 20).coerceIn(1, 9)
        val url = "https://fanyi.baidu.com/gettts?lan=zh&spd=$spd&source=web&text=" +
            URLEncoder.encode(text, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://fanyi.baidu.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastError = "服务返回 ${resp.code}"
                return false
            }
            val body = resp.body ?: run { lastError = "空响应"; return false }
            if (body.contentLength() > 8L * 1024 * 1024) {
                lastError = "内容过大"
                return false
            }
            out.outputStream().use { os -> body.byteStream().copyTo(os) }
            return true
        }
    }

    companion object {
        /** 线上接口只有一个中文音色，缓存 key 里固定用它 */
        const val VOICE_KEY = "baidu-zh"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
