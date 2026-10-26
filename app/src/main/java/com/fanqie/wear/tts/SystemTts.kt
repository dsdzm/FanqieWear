package com.fanqie.wear.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 系统语音（android.speech.tts）。
 *
 * 内置 eSpeak 的问题是音色机械、中文按词一顿一顿，这是共振峰合成的固有限制，
 * 调参调不出来。很多国产手表 ROM 自带中文语音引擎（讯飞/出门问问等），
 * 质量比 eSpeak 好得多，而且不用往 APK 里塞模型——有就用，没有就算了。
 *
 * 这里只做薄封装：初始化、判断中文是否可用、说一段并等它说完。
 */
class SystemTts(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var chineseOk = false
    @Volatile private var speaking = false

    val isChineseAvailable: Boolean get() = ready && chineseOk

    /** 已经初始化过（不论是否支持中文） */
    val isInitialized: Boolean get() = ready

    /**
     * 初始化并检查中文是否可用。TextToSpeech 必须在主线程创建，
     * 这里统一切到主线程，调用方可以在任意线程 await。
     */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.Main) {
        if (ready) return@withContext chineseOk
        val gate = CompletableDeferred<Boolean>()
        val created = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = true
                chineseOk = false
                TtsLog.w("sysTts", "初始化失败 status=$status")
                if (!gate.isCompleted) gate.complete(false)
                return@TextToSpeech
            }
            val engine = tts
            if (engine == null) {
                ready = true
                chineseOk = false
                if (!gate.isCompleted) gate.complete(false)
                return@TextToSpeech
            }
            val r = runCatching { engine.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(-1)
            chineseOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            ready = true
            TtsLog.i("sysTts", "引擎=${engine.defaultEngine} 中文可用=$chineseOk result=$r")
            if (!gate.isCompleted) gate.complete(chineseOk)
        }
        tts = created
        withTimeoutOrNull(7000) { gate.await() } ?: false
    }

    /**
     * 朗读一段并等它播完。返回 false 表示没播成（引擎缺失/出错/超时）。
     * 调用方取消协程时会走到 [stop]。
     */
    suspend fun speak(text: String, rate: Float, pitch: Float): Boolean = withContext(Dispatchers.Main) {
        val engine = tts ?: return@withContext false
        if (!isChineseAvailable) return@withContext false

        runCatching {
            engine.setSpeechRate(rate.coerceIn(0.4f, 2.0f))
            engine.setPitch(pitch.coerceIn(0.4f, 2.0f))
        }

        val id = "tts-" + System.nanoTime()
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                if (!done.isCompleted) done.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                if (!done.isCompleted) done.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking = false
                TtsLog.w("sysTts", "朗读出错 code=$errorCode")
                if (!done.isCompleted) done.complete(false)
            }
        })

        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            TtsLog.w("sysTts", "speak 返回 $r")
            return@withContext false
        }

        // 中文按每秒 4~5 字估算，留足余量；超时说明引擎卡住了，当失败处理
        val estimate = (text.length / 4f * 1000L / rate.coerceAtLeast(0.4f)).toLong() + 8000
        withTimeoutOrNull(estimate) { done.await() } ?: false
    }

    fun stop() {
        runCatching { tts?.stop() }
        speaking = false
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        chineseOk = false
    }
}
