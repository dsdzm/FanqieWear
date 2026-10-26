package com.fanqie.wear.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 听书引擎：用内置的 [EspeakEngine] 合成 PCM，再用 AudioTrack 播放。
 *
 * 两条硬规则（v1.6.3 起）：
 * 1. **所有 espeak 调用和 AudioTrack 操作都发生在同一根线程上**（tts-audio）。
 *    espeak 不是线程安全的，AudioTrack 被另一根线程 stop/release 更是经典的 use-after-free 闪退源。
 *    暂停/切句只改一个 volatile 标志位，由音频线程自己收尾。
 * 2. **任何异常都不许冒到协程外面**。以前 playFrom 里抛出的异常没人接，
 *    会直接走 Thread 默认的崩溃处理，表现就是"点了没声音紧接着闪退"。
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

    /** 单线程：espeak 与 AudioTrack 都只在这根线程上碰 */
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-audio").apply { priority = Thread.NORM_PRIORITY }
    }

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        TtsLog.e("player", e)
        _state.value = _state.value.copy(
            playing = false,
            error = "播放出错：" + (e.message ?: e.javaClass.simpleName),
        )
        runCatching { TtsService.stop(context) }
    }

    private val scope = CoroutineScope(SupervisorJob() + audioExecutor.asCoroutineDispatcher() + crashGuard)

    private var job: Job? = null

    /** 暂停/切句信号：音频线程在每个小片段之间检查 */
    @Volatile private var stopRequested = false

    init {
        TtsLog.init(context)
        scope.launch {
            runCatching {
                val ok = EspeakEngine.ensureReady(context)
                _state.value = _state.value.copy(
                    ready = ok,
                    error = if (ok) null else "内置语音引擎初始化失败",
                )
                TtsLog.i("player", "启动预热完成 ready=$ok")
            }.onFailure { TtsLog.e("player-init", it) }
        }
    }

    val engineReady: Boolean get() = _state.value.ready

    /** 引擎异常后手动重来一次；参数保留兼容旧调用 */
    fun refresh(enginePackage: String? = null) {
        stopRequested = true
        job?.cancel()
        job = null
        scope.launch {
            runCatching {
                _state.value = _state.value.copy(ready = false, error = null, playing = false)
                EspeakEngine.release()
                val ok = EspeakEngine.ensureReady(context)
                _state.value = _state.value.copy(
                    ready = ok,
                    error = if (ok) null else "内置语音引擎初始化失败",
                )
            }.onFailure { TtsLog.e("player-refresh", it) }
        }
    }

    /** 载入一章：切句 + 重置进度 */
    fun load(bookId: String, chapterId: String, title: String, text: String) {
        val sentences = splitSentences(text)
        TtsLog.i("load", "《$title》切出 ${sentences.size} 句")
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
        if (s.sentences.isEmpty()) {
            _state.value = s.copy(
                playing = false,
                error = "这一章没有可朗读的文字，可能是章节还没加载出来",
            )
            TtsLog.w("play", "句子为空，拒绝播放")
            return
        }

        // 不少 Wear OS 表根本没扬声器，这种情况先说清楚，免得以为是引擎坏了
        if (!hasAnyOutput()) {
            _state.value = s.copy(
                playing = false,
                error = "没检测到音频输出设备：这台手表可能没有扬声器，连上蓝牙耳机再试",
            )
            return
        }

        stopRequested = false
        _state.value = s.copy(playing = true, error = null)
        TtsLog.i("play", "从第 ${s.index} 句开始，共 ${s.sentences.size} 句")
        job?.cancel()
        job = scope.launch {
            try {
                if (!EspeakEngine.ensureReady(context)) {
                    TtsLog.w("play", "引擎未就绪")
                    _state.value = _state.value.copy(
                        ready = false,
                        playing = false,
                        error = "内置语音引擎初始化失败，去「设置 → 关于」看看自检结果",
                    )
                    TtsService.stop(context)
                    return@launch
                }
                _state.value = _state.value.copy(ready = true)
                TtsService.start(context, _state.value.title)
                val spoke = playFrom(_state.value.index)
                if (!spoke && !stopRequested) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "合成失败：拿到的是空音频，点「自检」看具体卡在哪一步",
                    )
                }
            } catch (t: Throwable) {
                TtsLog.e("play", t)
                _state.value = _state.value.copy(
                    playing = false,
                    error = "播放异常：" + (t.message ?: t.javaClass.simpleName),
                )
                TtsService.stop(context)
            }
        }
    }

    /** 有没有可用的音频输出：内置扬声器或已连接蓝牙/USB/有线耳机 */
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
        stopRequested = true
        job?.cancel()
        job = null
        _state.value = _state.value.copy(playing = false)
        TtsLog.i("pause", "第 ${_state.value.index} 句")
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
        stopRequested = true
        job?.cancel()
        job = null
        _state.value = _state.value.copy(playing = false, index = 0)
        TtsService.stop(context)
    }

    /** 自检用完就回收，别留一根空转线程 */
    fun close() {
        stopRequested = true
        job?.cancel()
        job = null
        runCatching { audioExecutor.shutdown() }
    }

    // ---------------- 播放 ----------------

    private fun jumpTo(index: Int) {
        val s = _state.value
        _state.value = s.copy(index = index)
        if (!s.playing) return
        stopRequested = true
        job?.cancel()
        stopRequested = false
        job = scope.launch {
            try {
                if (!EspeakEngine.ensureReady(context)) return@launch
                playFrom(index)
            } catch (t: Throwable) {
                TtsLog.e("jumpTo", t)
                _state.value = _state.value.copy(playing = false, error = "播放异常：" + t.message)
            }
        }
    }

    /** 自检用：同步把当前内容播一遍，返回是否真的送出了音频 */
    internal suspend fun playBlocking(): Boolean = runCatching {
        if (!EspeakEngine.ensureReady(context)) return false
        stopRequested = false
        playFrom(0)
    }.getOrElse { TtsLog.e("playBlocking", it); false }

    /** 从第 start 句开始逐句合成播放；返回是否至少播完了一句 */
    private suspend fun playFrom(start: Int): Boolean {
        var i = start
        var spoke = false
        var failedRun = 0
        while (currentCoroutineContext().isActive && !stopRequested) {
            val s = _state.value
            val text = s.sentences.getOrNull(i) ?: break
            if (text.isBlank()) { i++; continue }
            _state.value = s.copy(index = i, playing = true)

            val pcm = EspeakEngine.synth(text, wpmOf(s.speed), pitchOf(s.pitch))
            if (pcm == null || pcm.isEmpty()) {
                failedRun++
                TtsLog.w("synth", "第 $i 句为空（连续 $failedRun）")
                if (failedRun >= 3) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "连续 3 句合成失败，语音数据可能没解压完整，点「自检」看看",
                    )
                    break
                }
                i++
                continue
            }
            failedRun = 0
            if (!playPcm(pcm)) {
                // 被打断（暂停/切句/取消），直接退出
                TtsLog.i("play", "第 $i 句被打断")
                return spoke
            }
            spoke = true
            i++
        }
        if (!stopRequested) {
            _state.value = _state.value.copy(playing = false)
            TtsLog.i("play", "本章读完")
            TtsService.stop(context)
        }
        return spoke
    }

    /**
     * 写一帧 PCM 到 AudioTrack，返回 false 表示被打断。
     *
     * 只在音频线程上调用；分小块写，块与块之间检查停止标志，
     * 这样暂停响应在 100ms 以内，也不会出现"另一根线程去 stop 正在写的 track"。
     */
    private suspend fun playPcm(pcm: ShortArray): Boolean {
        val rate = EspeakEngine.sampleRate
        val minBuffer = runCatching {
            AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(8192)
        }.getOrDefault(8192)
        val bufferSize = maxOf(minBuffer, 16384)

        val track = runCatching {
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
            TtsLog.e("audiotrack", it)
            _state.value = _state.value.copy(playing = false, error = "音频输出初始化失败：${it.message}")
            return false
        }

        var ok = false
        try {
            track.play()
            var offset = 0
            val chunk = 2048
            while (offset < pcm.size && !stopRequested && currentCoroutineContext().isActive) {
                val n = minOf(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) {
                    TtsLog.w("write", "写入返回 $written")
                    return false
                }
                offset += written
            }
            if (stopRequested || !currentCoroutineContext().isActive) return false

            // 等缓冲区把最后一段放完，否则 release 会把尾巴掐掉
            val totalMs = pcm.size * 1000L / rate
            val begin = SystemClock.elapsedRealtime()
            while (!stopRequested && currentCoroutineContext().isActive) {
                val used = SystemClock.elapsedRealtime() - begin
                if (used >= totalMs) break
                delay(40)
            }
            ok = !stopRequested
        } catch (t: Throwable) {
            Log.e("TtsPlayer", "播放失败", t)
            TtsLog.e("playPcm", t)
            _state.value = _state.value.copy(playing = false, error = "播放失败：${t.message}")
            ok = false
        } finally {
            // 始终在本线程收尾，绝不跨线程 stop/release
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        return ok
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
