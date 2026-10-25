package com.fanqie.wear.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 听书引擎：用内置的 [EspeakEngine] 合成 PCM，再用 AudioTrack 播放。
 *
 * 之前的实现依赖系统 TextToSpeech，手表 ROM 一旦删掉语音引擎就哑了，只能引导用户去装 APK。
 * 现在引擎和数据都在 APK 里，安装即用、完全离线，代价只是音色是合成腔（eSpeak 的一贯水平）。
 *
 * 逐句合成播放：一句合成完再播下一句，句子边界正好用来做进度和暂停点。
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
        val pitch: Float = 1.0f,
        val error: String? = null,
    ) {
        val current: String get() = sentences.getOrElse(index) { "" }
        val progress: Float
            get() = if (sentences.isEmpty()) 0f else (index + 1f) / sentences.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var track: AudioTrack? = null

    init {
        // 引擎初始化要解压数据，放后台跑，不卡首屏
        scope.launch {
            val ok = EspeakEngine.ensureReady(context)
            _state.value = _state.value.copy(
                ready = ok,
                error = if (ok) null else "内置语音引擎初始化失败",
            )
        }
    }

    val engineReady: Boolean get() = _state.value.ready

    /** 引擎异常后手动重来一次；参数保留兼容旧调用 */
    fun refresh(enginePackage: String? = null) {
        scope.launch {
            _state.value = _state.value.copy(ready = false, error = null, playing = false)
            EspeakEngine.release()
            val ok = EspeakEngine.ensureReady(context)
            _state.value = _state.value.copy(
                ready = ok,
                error = if (ok) null else "内置语音引擎初始化失败",
            )
        }
    }

    /** 载入一章：切句 + 重置进度 */
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
            pitch = _state.value.pitch,
            error = _state.value.error,
        )
    }

    fun play() {
        val s = _state.value
        if (s.sentences.isEmpty()) return

        // 不少 Wear OS 表根本没扬声器，这种情况先说清楚，免得以为是引擎坏了
        if (!hasAnyOutput()) {
            _state.value = s.copy(
                playing = false,
                error = "没检测到音频输出设备：这台手表可能没有扬声器，连上蓝牙耳机再试",
            )
            return
        }

        TtsService.start(context, s.title)
        _state.value = s.copy(playing = true, error = null)
        job?.cancel()
        stopTrack()
        job = scope.launch {
            if (!EspeakEngine.ensureReady(context)) {
                _state.value = _state.value.copy(ready = false, playing = false, error = "内置语音引擎初始化失败")
                TtsService.stop(context)
                return@launch
            }
            _state.value = _state.value.copy(ready = true)
            playFrom(_state.value.index)
        }
    }

    /** 有没有可用的音频输出：内置扬声器或已连接蓝牙/A2DP */
    private fun hasAnyOutput(): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return true
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) return true
        outputs.any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
    }.getOrDefault(true)

    fun pause() {
        job?.cancel()
        job = null
        stopTrack()
        _state.value = _state.value.copy(playing = false)
        TtsService.stop(context)
    }

    fun next() {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        jumpTo((s.index + 1).coerceAtMost(s.sentences.size - 1))
    }

    fun prev() {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        jumpTo((s.index - 1).coerceAtLeast(0))
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        jumpTo(index.coerceIn(0, s.sentences.size - 1))
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        if (_state.value.playing) jumpTo(_state.value.index)
    }

    fun setPitch(pitch: Float) {
        _state.value = _state.value.copy(pitch = pitch)
        if (_state.value.playing) jumpTo(_state.value.index)
    }

    fun stop() {
        job?.cancel()
        job = null
        stopTrack()
        _state.value = _state.value.copy(playing = false, index = 0)
        TtsService.stop(context)
    }

    // ---------------- 播放 ----------------

    private fun jumpTo(index: Int) {
        val s = _state.value
        _state.value = s.copy(index = index)
        if (!s.playing) return
        job?.cancel()
        stopTrack()
        job = scope.launch {
            if (!EspeakEngine.ensureReady(context)) return@launch
            playFrom(index)
        }
    }

    /** 从第 index 句开始逐句合成播放；被取消时直接返回 */
    private suspend fun playFrom(start: Int) {
        var i = start
        while (currentCoroutineContext().isActive) {
            val s = _state.value
            val text = s.sentences.getOrNull(i) ?: break
            _state.value = s.copy(index = i, playing = true)
            val pcm = EspeakEngine.synth(text, wpmOf(s.speed), pitchOf(s.pitch))
            if (pcm == null || pcm.isEmpty()) {
                i++
                continue
            }
            val finished = playPcm(pcm)
            if (!finished) return
            i++
        }
        if (currentCoroutineContext().isActive) {
            _state.value = _state.value.copy(playing = false)
            TtsService.stop(context)
        }
    }

    /**
     * 写一帧 PCM 到 AudioTrack。返回 false 表示被打断（暂停/切句），调用方应退出循环。
     * write 是阻塞的，外部 stopTrack() 会让它返回负值。
     */
    private suspend fun playPcm(pcm: ShortArray): Boolean {
        val rate = EspeakEngine.sampleRate
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val bufferSize = maxOf(minBuffer, pcm.size * 2)

        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e("TtsPlayer", "创建 AudioTrack 失败", it)
            _state.value = _state.value.copy(playing = false, error = "音频输出初始化失败")
            return false
        }

        track = t
        var offset = 0
        var ok = true
        try {
            t.play()
            while (offset < pcm.size) {
                if (!currentCoroutineContext().isActive) {
                    ok = false
                    break
                }
                val written = t.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    // 被打断（pause/stop 会让 write 返回负值）或底层出错，两种情况都退出这一句
                    ok = false
                    break
                }
                offset += written
            }
        } catch (t2: Throwable) {
            Log.e("TtsPlayer", "播放失败", t2)
            ok = false
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            if (track === t) track = null
        }
        return ok
    }

    private fun stopTrack() {
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
    }

    /** 语速倍率 → eSpeak 的每分钟词数（默认 175） */
    private fun wpmOf(speed: Float): Int = (175f * speed).toInt().coerceIn(80, 450)

    /** 音调倍率 → eSpeak 的 0~99（50 为默认） */
    private fun pitchOf(pitch: Float): Int = (50f * pitch).toInt().coerceIn(0, 99)

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
