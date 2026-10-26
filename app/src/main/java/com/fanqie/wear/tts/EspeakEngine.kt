package com.fanqie.wear.tts

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内置 eSpeak NG 语音引擎。
 *
 * 和 v1.5 之前的做法不同：不再让用户去装 eSpeak NG 的 APK 或系统语音包，
 * 引擎（libespeak-ng.so + JNI）和语音数据（assets/espeak-ng-data，约 2.8MB）都随 APK 一起发，
 * 装上就能读，全程离线。
 *
 * 数据放在 assets 里是因为 espeak 的字典要按目录结构读（phondata / *_dict / lang / voices），
 * 首次使用时整目录解到私有目录，再用一个版本号文件避免每次启动都解压。
 */
object EspeakEngine {

    private const val TAG = "EspeakEngine"

    /** 数据有变化时改这个值，App 会自动重新解压 */
    private const val DATA_VERSION = "espeak-ng-1.52-cmn3-en"

    /** 合成必需的文件，缺一个都说明解压不完整 */
    private val REQUIRED = listOf(
        "phondata", "phonindex", "phontab", "intonations", "cmn_dict",
    )

    @Volatile private var libLoaded = false
    @Volatile private var engineReady = false
    @Volatile private var sampleRateHz = 22050
    @Volatile private var versionText = "eSpeak NG 1.52"

    external fun nativeInit(dataParent: String): Int
    external fun nativeSetVoice(name: String): Int
    external fun nativeSynth(text: String, rate: Int, pitch: Int): ShortArray?
    external fun nativeRelease()
    external fun nativeVersion(): String

    val isReady: Boolean get() = engineReady

    /** 合成采样率，由 espeak_Initialize 返回 */
    val sampleRate: Int get() = sampleRateHz

    /** 只读缓存值，不在 UI 线程碰原生层 */
    fun version(): String = versionText

    private fun loadLibrary(): Boolean {
        if (libLoaded) return true
        libLoaded = runCatching {
            System.loadLibrary("espeakng-jni")
            true
        }.getOrElse {
            Log.e(TAG, "加载 native 库失败", it)
            TtsLog.e("lib", it)
            false
        }
        return libLoaded
    }

    /**
     * 解压语音数据并初始化引擎。可重复调用，已就绪时直接返回。
     * 失败时返回 false，调用方据此给出提示，不抛异常。
     */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (engineReady) return true
        TtsLog.init(context)
        TtsLog.i("engine", "开始初始化")

        if (!loadLibrary()) {
            TtsLog.w("engine", "so 未加载")
            return false
        }

        val parent = runCatching { prepareData(context) }.getOrElse {
            Log.e(TAG, "解压语音数据失败", it)
            TtsLog.e("data", it)
            return false
        }
        TtsLog.i("data", "数据目录 ${parent.absolutePath}")

        val rate = runCatching { nativeInit(parent.absolutePath) }.getOrElse {
            TtsLog.e("init", it)
            -1
        }
        if (rate <= 0) {
            Log.e(TAG, "espeak 初始化失败，返回 $rate")
            TtsLog.w("init", "espeak_Initialize 返回 $rate")
            return false
        }
        sampleRateHz = rate
        TtsLog.i("init", "采样率 $rate")

        // 1.52 的中文音色名是 cmn，个别构建里叫 zh
        var voiceCode = nativeSetVoice("cmn")
        if (voiceCode != 0) voiceCode = nativeSetVoice("zh")
        if (voiceCode != 0) {
            // 音色都没设上，合成必然失败（还可能让引擎在默认音色上撞缺失词典），直接判失败
            Log.e(TAG, "中文音色设置失败")
            TtsLog.w("voice", "cmn/zh 均设置失败")
            runCatching { nativeRelease() }
            return false
        }
        TtsLog.i("voice", "中文音色就绪")

        versionText = runCatching { "eSpeak NG " + nativeVersion() }.getOrDefault("eSpeak NG")
        engineReady = true
        TtsLog.i("engine", "初始化完成")
        return true
    }

    /** 合成一句，返回 16bit 单声道 PCM；失败返回 null */
    fun synth(text: String, wpm: Int, pitch: Int): ShortArray? =
        if (engineReady) runCatching { nativeSynth(text, wpm, pitch) }.getOrElse {
            TtsLog.e("synth", it); null
        } else null

    fun release() {
        if (engineReady) runCatching { nativeRelease() }
        engineReady = false
    }

    /**
     * 自检：把「解压 → 初始化 → 设音色 → 合成 → AudioTrack」整条链路跑一遍，
     * 返回一段人眼可读的结论，同时写进日志。手表上排查问题主要靠它。
     */
    fun selfTest(context: Context): String {
        val sb = StringBuilder()
        TtsLog.i("selftest", "开始")
        sb.appendLine("① 加载 native 库")
        if (!loadLibrary()) {
            sb.appendLine("   ✗ 失败：so 没加载上")
            return sb.toString()
        }
        sb.appendLine("   ✓ 成功")

        sb.appendLine("② 解压语音数据")
        val parent = runCatching { prepareData(context) }.getOrElse {
            sb.appendLine("   ✗ 失败：${it.message}")
            TtsLog.e("selftest-data", it)
            return sb.toString()
        }
        val dataDir = File(parent, "espeak-ng-data")
        val missing = REQUIRED.filterNot { File(dataDir, it).length() > 0 }
        if (missing.isNotEmpty()) {
            sb.appendLine("   ✗ 缺文件：${missing.joinToString()}")
            return sb.toString()
        }
        sb.appendLine("   ✓ ${dataDir.absolutePath}")

        sb.appendLine("③ 初始化引擎")
        val rate = runCatching { nativeInit(parent.absolutePath) }.getOrDefault(-1)
        if (rate <= 0) {
            sb.appendLine("   ✗ 返回 $rate")
            return sb.toString()
        }
        sb.appendLine("   ✓ 采样率 $rate")

        sb.appendLine("④ 设置中文音色")
        var v = nativeSetVoice("cmn")
        if (v != 0) v = nativeSetVoice("zh")
        if (v != 0) {
            sb.appendLine("   ✗ cmn / zh 都失败")
            return sb.toString()
        }
        sb.appendLine("   ✓ 成功")

        sb.appendLine("⑤ 合成测试句")
        val probe = "他今天去学校了，我们一起走吧。"
        val pcm = runCatching { nativeSynth(probe, 175, 50) }.getOrNull()
        if (pcm == null || pcm.isEmpty()) {
            sb.appendLine("   ✗ 没有拿到音频数据")
            return sb.toString()
        }
        val ms = pcm.size * 1000L / rate
        sb.appendLine("   ✓ ${pcm.size} 个采样 ≈ ${ms}ms")

        // 词典是否生效：按词念会把「今天/学校/我们」连成一段，逐字念则一个字一段
        val segs = countVoicedSegments(pcm, rate)
        val chars = probe.count { it in '\u4e00'..'\u9fff' }
        sb.appendLine(
            when {
                segs <= 0 -> "   发声段：检测不到"
                segs >= chars -> "   发声段 $segs / $chars 字 → 词典可能没生效（逐字念）"
                else -> "   发声段 $segs / $chars 字 → 按词合成，正常"
            },
        )

        sb.appendLine("⑥ 播放")
        val played = runCatching {
            kotlinx.coroutines.runBlocking {
                val player = TtsPlayer(context)
                try {
                    player.load("", "", "自检", "你好，这是语音自检。")
                    player.playBlocking()
                } finally {
                    player.close()
                }
            }
        }.getOrElse { TtsLog.e("selftest-play", it); false }
        sb.appendLine(if (played) "   ✓ 已送进扬声器" else "   ✗ 播放失败，看下面日志")

        TtsLog.i("selftest", "结束")
        return sb.toString()
    }

    // ---------------- 数据解压 ----------------

    /** 数一下 PCM 里有多少段"有声"，用来自检中文是按词合成还是逐字念 */
    private fun countVoicedSegments(pcm: ShortArray, rate: Int): Int {
        val win = (rate * 0.02).toInt().coerceAtLeast(64)
        if (pcm.size < win * 4) return 0
        val rms = ArrayList<Double>(pcm.size / win + 1)
        var i = 0
        while (i + win <= pcm.size) {
            var sum = 0.0
            for (j in i until i + win) {
                val v = pcm[j].toDouble()
                sum += v * v
            }
            rms.add(kotlin.math.sqrt(sum / win))
            i += win
        }
        val peak = rms.maxOrNull() ?: return 0
        if (peak <= 0.0) return 0
        val threshold = peak * 0.05
        var segments = 0
        var prevVoiced = false
        for (v in rms) {
            val voiced = v > threshold
            if (voiced && !prevVoiced) segments++
            prevVoiced = voiced
        }
        return segments
    }

    private fun prepareData(context: Context): File {
        val root = File(context.filesDir, "tts")
        val dataDir = File(root, "espeak-ng-data")
        val stamp = File(root, ".data-version")
        val upToDate = stamp.isFile && stamp.readText().trim() == DATA_VERSION &&
            dataDir.isDirectory && REQUIRED.all { File(dataDir, it).length() > 0 }
        if (upToDate) return root

        TtsLog.i("data", "重新解压（版本 $DATA_VERSION）")
        dataDir.deleteRecursively()
        copyAssetDir(context, "espeak-ng-data", dataDir)
        stamp.writeText(DATA_VERSION)
        return root
    }

    private fun copyAssetDir(context: Context, src: String, dest: File) {
        val assets = context.assets
        val children = assets.list(src).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(src).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetDir(context, "$src/$child", File(dest, child))
            }
        }
    }
}
