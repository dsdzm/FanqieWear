package com.fanqie.wear.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.fanqie.wear.FanqieApp
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
import kotlin.math.min

/**
 * 听书引擎：用内置的 [EspeakEngine] 合成 PCM，再用 AudioTrack 播放。
 *
 * 三条硬规则：
 * 1. **所有 espeak 调用和 AudioTrack 操作都发生在同一根线程上**（tts-audio）。
 *    espeak 不是线程安全的，AudioTrack 被另一根线程 stop/release 更是经典的 use-after-free 闪退源。
 *    暂停/切块只改一个 volatile 标志位，由音频线程自己收尾。
 * 2. **任何异常都不许冒到协程外面**，否则会走 Thread 默认崩溃处理，表现就是"闪退"。
 * 3. **按段落块合成**（约 300 字一块），不是逐句。逐句合成每句都从零起调，
 *    听感是每个分句都重新开口、顿挫很重；整块合成让 espeak 自己在句子间处理停顿和语调。
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

    /** 下一章的取法由阅读页注入：播放器只负责"读完了，给我下一章" */
    interface ChapterSource {
        suspend fun fetch(chapterId: String): Fetched?
    }

    data class Fetched(
        val chapterId: String,
        val title: String,
        val text: String,
        val nextId: String?,
    )

    @Volatile var chapterSource: ChapterSource? = null

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

    /** 暂停/切块信号：音频线程在每个小片段之间检查 */
    @Volatile private var stopRequested = false

    /** 当前章读完后的下一章 id，load 时由阅读页传入 */
    @Volatile private var nextChapterId: String? = null

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

    /**
     * 载入一章。nextId 用于读完自动接下一章。
     * 文本按段落合并成约 300 字一块，而不是逐句合成——逐句会让每句话都重新起调。
     */
    fun load(
        bookId: String,
        chapterId: String,
        title: String,
        text: String,
        nextId: String? = null,
    ) {
        val chunks = splitChunks(text)
        TtsLog.i("load", "《$title》切成 ${chunks.size} 块")
        nextChapterId = nextId
        _state.value = State(
            ready = _state.value.ready,
            playing = false,
            sentences = chunks,
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
            TtsLog.w("play", "文本为空，拒绝播放")
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
        TtsLog.i("play", "从第 ${s.index} 块开始，共 ${s.sentences.size} 块")
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
                val spoke = playAll()
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
        TtsLog.i("pause", "第 ${_state.value.index} 块")
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
                playAll()
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
        playAll()
    }.getOrElse { TtsLog.e("playBlocking", it); false }

    /**
     * 播放当前章；读完视设置决定是否自动接下一章。
     * 返回是否至少播出过一块音频。
     */
    private suspend fun playAll(): Boolean {
        var spokeAny = false
        while (currentCoroutineContext().isActive && !stopRequested) {
            val spoke = playChunks(_state.value.index)
            spokeAny = spokeAny || spoke
            if (stopRequested || !currentCoroutineContext().isActive) break

            if (!autoNextEnabled()) {
                TtsLog.i("play", "本章读完（未开自动续播）")
                break
            }
            val src = chapterSource
            val nid = nextChapterId
            if (src == null || nid.isNullOrBlank()) {
                TtsLog.i("play", "本章读完（没有下一章）")
                break
            }
            TtsLog.i("autoNext", "取下一章 $nid")
            val next = runCatching { src.fetch(nid) }.getOrNull()
            if (stopRequested) break
            if (next == null || next.text.isBlank()) {
                TtsLog.w("autoNext", "下一章取不到正文，停在这里")
                break
            }
            load(_state.value.bookId, next.chapterId, next.title, next.text, next.nextId)
            _state.value = _state.value.copy(playing = true)
            TtsLog.i("autoNext", "已切到《${next.title}》")
        }

        if (!stopRequested) {
            _state.value = _state.value.copy(playing = false)
            TtsService.stop(context)
        }
        return spokeAny
    }

    /** 逐块合成 + 播放；返回是否至少播完一块 */
    private suspend fun playChunks(start: Int): Boolean {
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
                TtsLog.w("synth", "第 $i 块为空（连续 $failedRun）")
                if (failedRun >= 3) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "连续 3 块合成失败，语音数据可能没解压完整，点「自检」看看",
                    )
                    break
                }
                i++
                continue
            }
            failedRun = 0
            if (!playPcm(pcm)) {
                TtsLog.i("play", "第 $i 块被打断")
                return spoke
            }
            spoke = true
            i++
        }
        return spoke
    }

    /**
     * 写一块 PCM 到 AudioTrack，返回 false 表示被打断。
     *
     * 只在音频线程上调用；分小块写，块与块之间检查停止标志，
     * 这样暂停响应在 100ms 以内，也不会出现"另一根线程去 stop 正在写的 track"。
     *
     * 结尾的排空只补"还没播完的那部分"：阻塞写本身按实时速度推进，
     * 写完再等一整块时长，会让每块之间空出等长的静音——听起来就是"读完一段就没声了"。
     */
    private suspend fun playPcm(pcm: ShortArray): Boolean {
        val rate = EspeakEngine.sampleRate.coerceAtLeast(8000)
        val minBuffer = runCatching {
            AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
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

        val started = SystemClock.elapsedRealtime()
        try {
            fadeEdges(pcm)
            track.play()
            var offset = 0
            val chunk = 2048
            while (offset < pcm.size && !stopRequested && currentCoroutineContext().isActive) {
                val n = min(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) {
                    TtsLog.w("write", "写入返回 $written")
                    return false
                }
                offset += written
            }
            if (stopRequested || !currentCoroutineContext().isActive) return false

            // 总时长 - 已经过去的时间 = 还需要等多久
            val totalMs = pcm.size * 1000L / rate
            while (!stopRequested && currentCoroutineContext().isActive) {
                if (SystemClock.elapsedRealtime() - started >= totalMs) break
                delay(30)
            }
            return !stopRequested
        } catch (t: Throwable) {
            Log.e("TtsPlayer", "播放失败", t)
            TtsLog.e("playPcm", t)
            _state.value = _state.value.copy(playing = false, error = "播放失败：${t.message}")
            return false
        } finally {
            // 始终在本线程收尾，绝不跨线程 stop/release
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** 块首块尾各做一个 6ms 淡入淡出，去掉切块处的"啪"声 */
    private fun fadeEdges(pcm: ShortArray) {
        val ramp = 128
        if (pcm.size < ramp * 4) return
        for (i in 0 until ramp) {
            val g = i.toFloat() / ramp
            pcm[i] = (pcm[i] * g).toInt().toShort()
            val j = pcm.size - 1 - i
            pcm[j] = (pcm[j] * g).toInt().toShort()
        }
    }

    private fun autoNextEnabled(): Boolean = runCatching {
        (context.applicationContext as FanqieApp).settings.value.ttsAutoNext
    }.getOrDefault(true)

    /** 语速倍率 → eSpeak 的每分钟词数（默认 170，比 175 稍慢，中文更清楚） */
    private fun wpmOf(speed: Float): Int = (170f * speed).toInt().coerceIn(80, 450)

    /** 音调倍率 → eSpeak 的 0~99（50 为默认） */
    private fun pitchOf(pitch: Float): Int = (50f * pitch).toInt().coerceIn(0, 99)

    /**
     * 按段落合并成块，单块目标 300 字左右。
     * 逐句合成的毛病是每句都重新起调、句间还夹一次 AudioTrack 重建，听感一顿一顿；
     * 按段落成块后，顿挫交给 espeak 自己处理。
     */
    private fun splitChunks(raw: String): List<String> {
        val cleaned = raw.replace("\r\n", "\n")
            .replace(Regex("[ \\t\\u3000]+"), " ")
            .trim()
        if (cleaned.isEmpty()) return emptyList()

        val target = 300
        val paras = cleaned.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotBlank()) {
                chunks.add(sb.toString().trim())
                sb.clear()
            }
        }

        for (p in paras) {
            if (p.length >= target) {
                // 超长段落按句号切开，尽量别把一句话劈两半
                flush()
                var rest = p
                while (rest.length > target) {
                    var cut = rest.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '.', '!', '?'), target)
                    if (cut < target / 2) cut = target - 1
                    chunks.add(rest.substring(0, cut + 1).trim())
                    rest = rest.substring(cut + 1)
                }
                if (rest.isNotBlank()) sb.append(rest)
            } else {
                if (sb.length + p.length > target) flush()
                sb.append(p).append('\n')
            }
        }
        flush()
        return chunks
    }
}
