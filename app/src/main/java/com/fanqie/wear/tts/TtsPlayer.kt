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
import kotlinx.coroutines.CancellationException
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
 * 听书引擎。两条音源：
 *
 * - **系统语音**（[SystemTts]）：手表 ROM 自带中文 TTS 时优先用它，音色接近真人，零体积。
 * - **内置 eSpeak**（[EspeakEngine]）：ROM 没有语音引擎时兜底，完全离线，音色是共振峰合成腔。
 *
 * 三条硬规则：
 * 1. 内置引擎的 espeak 与 AudioTrack 全部跑在 `tts-audio` 单线程上（espeak 非线程安全，
 *    AudioTrack 跨线程 stop/release 会 use-after-free）。
 * 2. 任何异常都不许冒到协程外面，否则走 Thread 默认崩溃处理。
 * 3. 暂停要记住**句内位置**：eSpeak 路径按采样偏移续播，续听接着刚才那句往下读，
 *    不会整块从头再来。
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
        /** 实际在用的引擎：system / espeak，空串表示还没决定 */
        val engine: String = "",
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

    private val systemTts = SystemTts(context)
    private val ttsCache = TtsCache(context)
    private val onlineTts = OnlineTtsEngine(context, ttsCache)
    private val audioFile = AudioFilePlayer(context)

    /** 章节语音包（下载后离线整章可播） */
    val chapterStore = ChapterAudioStore(context)

    /** 预取用：网络请求不能占着音频线程 */
    private val netScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var stopRequested = false
    @Volatile private var nextChapterId: String? = null
    @Volatile private var prevChapterId: String? = null

    /** 当前书来自哪个源，用于自动续章/切换章节（播放器自己取，不依赖阅读页是否在前台） */
    @Volatile private var bookSourceKey: String = ""

    /** 续播位置：暂停时记住"第几块 + 块内第几个采样" */
    @Volatile private var resumeChunk = -1
    @Volatile private var resumeSample = 0
    /** 在线语音按毫秒记续播点 */
    @Volatile private var resumeMs = 0

    init {
        TtsLog.init(context)
        // 启动只记下音色，不预热 eSpeak：它要解压 2.8MB 数据，放到第一次听书时再做，
        // 免得每次冷启动都卡一下
        runCatching { EspeakEngine.setVoice(settings()?.ttsVoice ?: "cmn") }
        // 系统语音有就顺手探一下，供设置页显示
        scope.launch {
            runCatching { systemTts.ensureReady() }.onSuccess {
                TtsLog.i("player", "系统中文语音可用=$it")
            }
        }
    }

    val engineReady: Boolean get() = _state.value.ready

    /** 系统中文语音是否可用（未初始化完时返回 false） */
    val systemVoiceAvailable: Boolean get() = systemTts.isChineseAvailable

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
     * eSpeak 路径的续播是采样级的，块可以大一些（顿挫更少）；系统语音每块短一点，暂停粒度更细。
     */
    fun load(
        bookId: String,
        chapterId: String,
        title: String,
        text: String,
        nextId: String? = null,
        prevId: String? = null,
        sourceKey: String = "",
    ) {
        val stored = chapterStore.chunks(bookId, chapterId)
        val chunks = stored ?: splitChunks(
            text,
            when {
                useSystemVoice() -> 90
                (settings()?.ttsEngine ?: ENGINE_AUTO) == ENGINE_ONLINE -> 180
                else -> 220
            },
        )
        TtsLog.i("load", "《$title》${chunks.size} 块${if (stored != null) "（用已下载语音包）" else ""}")
        nextChapterId = nextId
        prevChapterId = prevId
        if (sourceKey.isNotBlank()) bookSourceKey = sourceKey
        resumeChunk = -1
        resumeSample = 0
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
            engine = _state.value.engine,
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

        if (!hasAnyOutput()) {
            _state.value = s.copy(
                playing = false,
                error = "没检测到音频输出设备：这台手表可能没有扬声器，连上蓝牙耳机再试",
            )
            return
        }

        stopRequested = false
        _state.value = s.copy(playing = true, error = null)
        TtsLog.i("play", "从第 ${s.index} 块、块内 ${resumeSample} 采样继续，共 ${s.sentences.size} 块")
        job?.cancel()
        job = scope.launch {
            try {
                val engine = decideEngine()
                _state.value = _state.value.copy(engine = engine)

                if (engine == ENGINE_SYSTEM) {
                    // 系统语音不动 eSpeak，直接开跑
                    TtsService.start(context, _state.value.title)
                    var spoke = playAll(ENGINE_SYSTEM)
                    if (!spoke && !stopRequested) {
                        // 系统引擎在有些 ROM 上装了但不出声，静默回退到内置引擎，别让用户听空气
                        TtsLog.w("play", "系统语音没出声，回退内置引擎")
                        runCatching { systemTts.stop() }
                        if (EspeakEngine.ensureReady(context)) {
                            _state.value = _state.value.copy(engine = ENGINE_ESPEAK, ready = true)
                            resumeChunk = _state.value.index
                            resumeSample = 0
                            spoke = playAll(ENGINE_ESPEAK)
                        }
                    }
                    if (!spoke && !stopRequested) {
                        _state.value = _state.value.copy(
                            playing = false,
                            error = "两个引擎都没出声，去「关于与支持 → 听书自检」看看",
                        )
                    }
                    return@launch
                }

                if (engine == ENGINE_ONLINE) {
                    TtsService.start(context, _state.value.title)
                    var spoke = playAll(ENGINE_ONLINE)
                    if (!spoke && !stopRequested) {
                        // 在线接口不通（无网/被限流）时静默回退，别让用户干等
                        TtsLog.w("play", "在线合成失败，回退内置引擎")
                        if (EspeakEngine.ensureReady(context)) {
                            _state.value = _state.value.copy(engine = ENGINE_ESPEAK, ready = true)
                            resumeChunk = _state.value.index
                            resumeSample = 0
                            resumeMs = 0
                            spoke = playAll(ENGINE_ESPEAK)
                        }
                    }
                    if (!spoke && !stopRequested) {
                        _state.value = _state.value.copy(
                            playing = false,
                            error = "在线语音和内置引擎都没出声，看「听书日志」",
                        )
                    }
                    return@launch
                }

                if (!EspeakEngine.ensureReady(context)) {
                    TtsLog.w("play", "内置引擎未就绪")
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
                val spoke = playAll(ENGINE_ESPEAK)
                if (!spoke && !stopRequested) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "合成失败：拿到的是空音频，点「自检」看具体卡在哪一步",
                    )
                }
            } catch (c: CancellationException) {
                TtsLog.i("play", "播放协程被取消")
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
        // 记下当前位置：暂停后接着读，而不是从这一块的开头重来
        resumeChunk = _state.value.index
        stopRequested = true
        job?.cancel()
        job = null
        runCatching { systemTts.stop() }
        _state.value = _state.value.copy(playing = false)
        TtsLog.i("pause", "第 ${_state.value.index} 块，块内 ${resumeSample} 采样")
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
        resumeChunk = -1
        resumeSample = 0
        stopRequested = true
        job?.cancel()
        job = null
        runCatching { systemTts.stop() }
        _state.value = _state.value.copy(playing = false, index = 0)
        TtsService.stop(context)
    }

    /** 取某一章的正文（播放器自己走书源，阅读页不在前台也能续章） */
    private suspend fun fetchChapter(chapterId: String): Fetched? = runCatching {
        val app = context.applicationContext as FanqieApp
        val api = app.sources.of(com.fanqie.wear.data.Source.of(bookSourceKey))
        val r = api.chapter(_state.value.bookId, chapterId)
        Fetched(chapterId, r.title, r.content, r.nextId).also { prevChapterId = r.prevId }
    }.getOrNull()

    /** 听书页：下一章 */
    fun nextChapter() {
        val id = nextChapterId ?: return
        switchChapter(id)
    }

    /** 听书页：上一章 */
    fun prevChapter() {
        val id = prevChapterId ?: return
        switchChapter(id)
    }

    private fun switchChapter(chapterId: String) {
        val wasPlaying = _state.value.playing
        stopRequested = true
        job?.cancel()
        job = null
        runCatching { systemTts.stop() }
        stopRequested = false
        _state.value = _state.value.copy(playing = false)
        job = scope.launch {
            try {
                val f = fetchChapter(chapterId) ?: run {
                    _state.value = _state.value.copy(error = "这一章取不到正文")
                    return@launch
                }
                load(_state.value.bookId, f.chapterId, f.title, f.text, f.nextId, prevChapterId, bookSourceKey)
                if (wasPlaying || true) play()
            } catch (c: CancellationException) {
            } catch (t: Throwable) {
                TtsLog.e("switch", t)
            }
        }
    }

    /** 自检用完就回收，别留一根空转线程 */
    fun close() {
        stopRequested = true
        job?.cancel()
        job = null
        runCatching { systemTts.shutdown() }
        runCatching { audioExecutor.shutdown() }
    }

    // ---------------- 播放 ----------------

    private fun jumpTo(index: Int) {
        val s = _state.value
        resumeChunk = -1
        resumeSample = 0
        _state.value = s.copy(index = index)
        if (!s.playing) return
        stopRequested = true
        job?.cancel()
        stopRequested = false
        job = scope.launch {
            try {
                val engine = decideEngine()
                _state.value = _state.value.copy(engine = engine)
                if (engine == ENGINE_ESPEAK && !EspeakEngine.ensureReady(context)) return@launch
                playAll(engine)
            } catch (c: CancellationException) {
                // 正常打断
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
        playAll(ENGINE_ESPEAK)
    }.getOrElse { TtsLog.e("playBlocking", it); false }

    /**
     * 播放当前章；读完视设置决定是否自动接下一章。
     * 返回是否至少播出过一块。
     */
    private suspend fun playAll(engine: String): Boolean {
        var spokeAny = false
        while (currentCoroutineContext().isActive && !stopRequested) {
            val spoke = playChunks(_state.value.index, engine)
            spokeAny = spokeAny || spoke
            if (stopRequested || !currentCoroutineContext().isActive) break

            if (!autoNextEnabled()) {
                TtsLog.i("play", "本章读完（未开自动续播）")
                break
            }
            val nid = nextChapterId
            if (nid.isNullOrBlank()) {
                TtsLog.i("play", "本章读完（没有下一章）")
                break
            }
            TtsLog.i("autoNext", "取下一章 $nid")
            val src = chapterSource
            val next = if (src != null) runCatching { src.fetch(nid) }.getOrNull() else fetchChapter(nid)
            if (stopRequested) break
            if (next == null || next.text.isBlank()) {
                TtsLog.w("autoNext", "下一章取不到正文，停在这里")
                break
            }
            load(_state.value.bookId, next.chapterId, next.title, next.text, next.nextId)
            _state.value = _state.value.copy(playing = true, engine = engine)
            TtsLog.i("autoNext", "已切到《${next.title}》")
        }

        if (!stopRequested) {
            resumeChunk = -1
            resumeSample = 0
            _state.value = _state.value.copy(playing = false)
            TtsService.stop(context)
        }
        return spokeAny
    }

    /** 逐块朗读；返回是否至少读完一块 */
    private suspend fun playChunks(start: Int, engine: String): Boolean {
        var i = start
        var spoke = false
        var failedRun = 0
        while (currentCoroutineContext().isActive && !stopRequested) {
            val s = _state.value
            val text = s.sentences.getOrNull(i) ?: break
            if (text.isBlank()) { i++; continue }

            // 续播：同一块且记住了块内位置，就从那里接着读
            val offset = if (i == resumeChunk && resumeSample > 0) resumeSample else 0
            _state.value = s.copy(index = i, playing = true)

            val ok = when (engine) {
                ENGINE_SYSTEM -> speakWithSystem(text, s.speed, s.pitch)
                ENGINE_ONLINE -> speakWithOnline(
                    text, s.speed,
                    if (i == resumeChunk) resumeMs else 0,
                    i,
                )
                else -> speakWithEspeak(text, s.speed, s.pitch, offset)
            }

            if (!ok) {
                if (stopRequested || !currentCoroutineContext().isActive) return spoke
                failedRun++
                TtsLog.w(engine, "第 $i 块没出声（连续 $failedRun）")
                if (failedRun >= 3) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "连续 3 块没出声，点「自检」看看引擎状态",
                    )
                    break
                }
                i++
                continue
            }
            failedRun = 0
            resumeChunk = -1
            resumeSample = 0
            spoke = true
            i++
        }
        return spoke
    }

    private suspend fun speakWithSystem(text: String, speed: Float, pitch: Float): Boolean =
        runCatching { systemTts.speak(text, speed, pitch) }
            .onFailure { if (it !is CancellationException) TtsLog.e("sysTts", it) }
            .getOrDefault(false)

    /**
     * 在线语音：语音包 → 缓存 → 合成，逐级回退。
     * 倍速交给播放器（MediaPlayer.setPlaybackParams），服务端始终按正常语速合成，
     * 这样 2x/3x 不会失真。
     */
    private suspend fun speakWithOnline(
        text: String,
        speed: Float,
        startMs: Int,
        chunkIndex: Int,
    ): Boolean {
        val s = _state.value
        val packed = chapterStore.file(s.bookId, s.chapterId, chunkIndex)
        val file = packed
            ?: onlineTts.cached(text, 0)
            ?: onlineTts.synthesize(text, 0)
            ?: run {
                TtsLog.w("online", "合成失败：${onlineTts.errorText ?: "无网络"}")
                return false
            }
        if (packed == null) prefetchFollowing(chunkIndex)
        return audioFile.play(file, startMs, speed) { resumeMs = it } >= 0
    }

    /** 播放当前块的同时，把后面几块先合成好，避免每块之间卡一下 */
    private fun prefetchFollowing(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        netScope.launch {
            for (j in index + 1..minOf(index + 3, s.sentences.lastIndex)) {
                val text = s.sentences[j]
                if (chapterStore.file(s.bookId, s.chapterId, j) != null) continue
                if (onlineTts.cached(text, 0) != null) continue
                onlineTts.synthesize(text, 0)
            }
        }
    }

    /** 下载当前章的语音包（听书页按钮） */
    suspend fun downloadCurrentChapter(onProgress: (Int, Int) -> Unit): Boolean {
        val s = _state.value
        if (s.chapterId.isBlank() || s.sentences.isEmpty()) return false
        return chapterStore.save(
            bookId = s.bookId,
            chapterId = s.chapterId,
            chunks = s.sentences,
            ratePercent = 0,
            synth = { text, rate -> onlineTts.synthesize(text, rate) },
            onProgress = onProgress,
        )
    }

    /**
     * 往后连续下载 N 章的语音包（听书页「下载后续章节」）。
     * 章节由播放器自己顺着 nextId 取，不依赖阅读页。
     */
    suspend fun downloadFollowing(count: Int, onProgress: (String) -> Unit): Int {
        var done = 0
        val s0 = _state.value
        if (s0.chapterId.isBlank()) return 0

        // 当前章先下
        onProgress("本章")
        if (chapterStore.has(s0.bookId, s0.chapterId) ||
            downloadCurrentChapter { i, n -> onProgress("本章 $i/$n") }
        ) done++

        var next = nextChapterId
        var guard = 0
        while (next != null && done < count && guard < count + 2) {
            guard++
            val f = fetchChapter(next) ?: break
            onProgress("《${f.title}》")
            val chunks = splitChunks(f.text, 180)
            val ok = chapterStore.save(
                bookId = s0.bookId,
                chapterId = f.chapterId,
                chunks = chunks,
                ratePercent = 0,
                synth = { text, rate -> onlineTts.synthesize(text, rate) },
                onProgress = { i, n -> onProgress("${done + 1}/$count · $i/$n") },
            )
            if (ok) done++
            next = f.nextId
        }
        return done
    }

    fun currentChapterPacked(): Boolean {
        val s = _state.value
        return s.chapterId.isNotBlank() && chapterStore.has(s.bookId, s.chapterId)
    }

    fun deleteCurrentChapterPack() {
        val s = _state.value
        if (s.chapterId.isNotBlank()) chapterStore.remove(s.bookId, s.chapterId)
    }

    private suspend fun speakWithEspeak(text: String, speed: Float, pitch: Float, offset: Int): Boolean {
        // 音色在设置里可切，切了要立刻生效；setVoice 内部会判重，不会每次都重载
        runCatching { EspeakEngine.setVoice(settings()?.ttsVoice ?: "cmn") }
        val pcm = EspeakEngine.synth(text, wpmOf(speed), pitchOf(pitch))
        if (pcm == null || pcm.isEmpty()) return false
        val from = offset.coerceIn(0, maxOf(pcm.size - 1, 0))
        if (from >= pcm.size - 800) return true // 已经播到尾巴，直接换下一块
        return playPcm(pcm, from)
    }

    /**
     * 写一块 PCM 到 AudioTrack；返回 false 表示被打断或失败。
     * 被打断时把"已经播到第几个采样"记到 [resumeSample]，下次从这里续。
     */
    private suspend fun playPcm(pcm: ShortArray, startSample: Int): Boolean {
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
        var offset = startSample
        try {
            track.play()
            val chunk = 2048
            while (offset < pcm.size && !stopRequested && currentCoroutineContext().isActive) {
                val n = min(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) {
                    TtsLog.w("write", "写入返回 $written")
                    saveResume(offset, rate, started)
                    return false
                }
                offset += written
            }
            if (stopRequested || !currentCoroutineContext().isActive) {
                saveResume(offset, rate, started)
                return false
            }

            // 只补"还没播完的那部分"：总时长 - 已经过去的时间
            val totalMs = (pcm.size - startSample) * 1000L / rate
            while (!stopRequested && currentCoroutineContext().isActive) {
                if (SystemClock.elapsedRealtime() - started >= totalMs) break
                delay(30)
            }
            if (stopRequested) saveResume(pcm.size, rate, started)
            return !stopRequested
        } catch (c: CancellationException) {
            saveResume(offset, rate, started)
            throw c
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

    /** 估算已经播到第几个采样：写入量受缓冲影响，用"已过去时间"换算更贴近实际听到的位置 */
    private fun saveResume(writtenSamples: Int, rate: Int, startedAt: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val played = (elapsed * rate / 1000L).toInt()
        resumeSample = played.coerceIn(0, writtenSamples)
        if (resumeSample < rate / 4) resumeSample = 0 // 不到 0.25 秒就当还没开始
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

    // ---------------- 引擎选择 ----------------

    private fun settings(): com.fanqie.wear.data.AppSettings? = runCatching {
        (context.applicationContext as FanqieApp).settings.value
    }.getOrNull()

    private fun autoNextEnabled(): Boolean = settings()?.ttsAutoNext ?: true

    /** 当前设置是否倾向系统语音（不代表它一定可用） */
    private fun useSystemVoice(): Boolean {
        val pref = settings()?.ttsEngine ?: ENGINE_AUTO
        return when (pref) {
            ENGINE_SYSTEM -> true
            ENGINE_ESPEAK -> false
            else -> systemTts.isChineseAvailable
        }
    }

    /** 真正决定这次用哪个引擎；auto 优先系统语音，不可用就回落到 eSpeak */
    private suspend fun decideEngine(): String {
        val pref = settings()?.ttsEngine ?: ENGINE_AUTO
        return when (pref) {
            ENGINE_ESPEAK -> ENGINE_ESPEAK
            ENGINE_SYSTEM -> ENGINE_SYSTEM
            ENGINE_ONLINE -> ENGINE_ONLINE
            else -> {
                val sysOk = if (systemTts.isInitialized) systemTts.isChineseAvailable else systemTts.ensureReady()
                when {
                    sysOk -> ENGINE_SYSTEM
                    isOnline() -> ENGINE_ONLINE
                    else -> ENGINE_ESPEAK
                }
            }
        }
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** 语速倍率 → eSpeak 的每分钟词数（默认 170，比 175 稍慢，中文更清楚） */
    private fun wpmOf(speed: Float): Int = (170f * speed).toInt().coerceIn(80, 450)

    /** 音调倍率 → eSpeak 的 0~99（50 为默认） */
    private fun pitchOf(pitch: Float): Int = (50f * pitch).toInt().coerceIn(0, 99)

    /**
     * 按段落合并成块。块大一点顿挫少，块小一点暂停粒度细。
     * 逐句合成的毛病是每句都重新起调、句间还夹一次建轨开销，听感一顿一顿。
     */
    private fun splitChunks(raw: String, target: Int): List<String> {
        val cleaned = raw.replace("\r\n", "\n")
            .replace(Regex("[ \t\u3000]+"), " ")
            .trim()
        if (cleaned.isEmpty()) return emptyList()

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

    companion object {
        const val ENGINE_AUTO = "auto"
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_ESPEAK = "espeak"
        const val ENGINE_ONLINE = "online"
    }
}
