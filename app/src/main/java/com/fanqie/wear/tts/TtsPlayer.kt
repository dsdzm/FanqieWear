package com.fanqie.wear.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 听书引擎：直接用系统 TTS 朗读正文，不依赖番茄的音频接口。
 * 优点是完全离线可用、不额外耗流量；代价是音色取决于手表自带语音包。
 */
class TtsPlayer(private val context: Context) {

    data class State(
        val ready: Boolean = false,
        val playing: Boolean = false,
        val sentences: List<String> = emptyList(),
        val index: Int = 0,
        val title: String = "",
        val bookId: String = "",
        val chapterId: String = "",
        val speed: Float = 1.0f,
        val error: String? = null,
    ) {
        val current: String get() = sentences.getOrElse(index) { "" }
        val progress: Float
            get() = if (sentences.isEmpty()) 0f else (index + 1f) / sentences.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var initDone = false
    private var zhAvailable = true

    init {
        initEngine()
    }

    val engineReady: Boolean get() = initDone && zhAvailable

    /** 用户刚去商店装了引擎 / 下载了语音包后调用，重新初始化 */
    fun refresh() {
        runCatching { tts?.shutdown() }
        initDone = false
        zhAvailable = true
        _state.value = _state.value.copy(ready = false, error = null, playing = false)
        initEngine()
    }

    private fun initEngine() {
        tts = TextToSpeech(context.applicationContext) { status ->
            initDone = status == TextToSpeech.SUCCESS
            if (!initDone) {
                _state.value = _state.value.copy(ready = false, error = "语音引擎初始化失败")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val res = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            zhAvailable = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            engine.setSpeechRate(_state.value.speed)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val i = utteranceId?.substringAfterLast('-')?.toIntOrNull() ?: return
                    _state.value = _state.value.copy(index = i, playing = true)
                }

                override fun onDone(utteranceId: String?) {
                    val s = _state.value
                    if (!s.playing) return
                    val next = s.index + 1
                    if (next < s.sentences.size) {
                        speak(next)
                    } else {
                        _state.value = s.copy(playing = false, index = (s.sentences.size - 1).coerceAtLeast(0))
                        TtsService.stop(context)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _state.value = _state.value.copy(playing = false, error = "朗读出错")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _state.value = _state.value.copy(playing = false, error = "朗读出错（$errorCode）")
                }
            })
            _state.value = _state.value.copy(ready = true, error = if (zhAvailable) null else "设备缺少中文语音包")
        }
    }

    /** 载入一章：切句 + 重置进度。 */
    fun load(bookId: String, chapterId: String, title: String, text: String) {
        val sentences = splitSentences(text)
        _state.value = State(
            ready = _state.value.ready,
            playing = false,
            sentences = sentences,
            index = 0,
            title = title,
            bookId = bookId,
            chapterId = chapterId,
            speed = _state.value.speed,
            error = _state.value.error,
        )
    }

    fun play() {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        if (!initDone || !zhAvailable) {
            _state.value = s.copy(error = if (!initDone) "语音引擎未就绪" else "设备缺少中文语音包，请在系统设置中安装")
            return
        }
        _state.value = s.copy(playing = true, error = null)
        TtsService.start(context, s.title)
        speak(s.index)
    }

    fun pause() {
        _state.value = _state.value.copy(playing = false)
        tts?.stop()
        TtsService.stop(context)
    }

    fun next() {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val i = (s.index + 1).coerceAtMost(s.sentences.size - 1)
        _state.value = s.copy(index = i)
        if (s.playing) speak(i)
    }

    fun prev() {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val i = (s.index - 1).coerceAtLeast(0)
        _state.value = s.copy(index = i)
        if (s.playing) speak(i)
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val i = index.coerceIn(0, s.sentences.size - 1)
        _state.value = s.copy(index = i)
        if (s.playing) speak(i)
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        tts?.setSpeechRate(speed)
        if (_state.value.playing) speak(_state.value.index)
    }

    fun stop() {
        _state.value = _state.value.copy(playing = false, index = 0)
        tts?.stop()
        TtsService.stop(context)
    }

    private fun speak(index: Int) {
        val s = _state.value
        val text = s.sentences.getOrNull(index) ?: return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-$index")
    }

    private fun splitSentences(raw: String): List<String> {
        val cleaned = raw.replace(Regex("[ \\t\\u3000]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (c in cleaned) {
            sb.append(c)
            if (c in "。！？!?…；;\n" || (c == '.' && sb.length > 2)) {
                val t = sb.toString().trim()
                if (t.isNotEmpty()) parts.add(t)
                sb.clear()
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString().trim())
        // 合并过短的片段，避免朗读时频繁断句
        val merged = ArrayList<String>()
        for (p in parts) {
            if (p.length < 8 && merged.isNotEmpty()) {
                merged[merged.size - 1] = merged.last() + p
            } else {
                merged.add(p)
            }
        }
        return merged.ifEmpty { listOf(cleaned.take(400)) }
    }
}
