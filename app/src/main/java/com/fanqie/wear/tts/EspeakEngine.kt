package com.fanqie.wear.tts

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内置 eSpeak NG 语音引擎。
 *
 * 和 v1.5 之前的做法不同：不再让用户去装 eSpeak NG 的 APK 或系统语音包，
 * 引擎（libespeak-ng.so + JNI）和中文语音数据（assets/espeak-ng-data，约 2.6MB）都随 APK 一起发，
 * 装上就能读，全程离线。
 *
 * 数据放在 assets 里是因为 espeak 的字典要按目录结构读（phondata / *_dict / lang / voices），
 * 首次使用时整目录解到私有目录，再用一个版本号文件避免每次启动都解压。
 */
object EspeakEngine {

    private const val TAG = "EspeakEngine"

    /** 数据有变化时改这个值，App 会自动重新解压 */
    private const val DATA_VERSION = "espeak-ng-1.52-cmn-2"

    private var libLoaded = false
    private var engineReady = false
    private var sampleRateHz = 22050

    external fun nativeInit(dataParent: String): Int
    external fun nativeSetVoice(name: String): Int
    external fun nativeSynth(text: String, rate: Int, pitch: Int): ShortArray?
    external fun nativeRelease()
    external fun nativeVersion(): String

    val isReady: Boolean get() = engineReady

    /** 合成采样率，由 espeak_Initialize 返回 */
    val sampleRate: Int get() = sampleRateHz

    fun version(): String =
        if (libLoaded || loadLibrary()) runCatching { nativeVersion() }.getOrDefault("unknown") else "未加载"

    private fun loadLibrary(): Boolean {
        if (libLoaded) return true
        libLoaded = runCatching {
            System.loadLibrary("espeakng-jni")
            true
        }.getOrElse {
            Log.e(TAG, "加载 native 库失败", it)
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
        if (!loadLibrary()) return false
        val parent = runCatching { prepareData(context) }.getOrElse {
            Log.e(TAG, "解压语音数据失败", it)
            return false
        }
        val rate = runCatching { nativeInit(parent.absolutePath) }.getOrDefault(-1)
        if (rate <= 0) {
            Log.e(TAG, "espeak 初始化失败，返回 $rate")
            return false
        }
        sampleRateHz = rate
        // 1.52 的中文音色名是 cmn，个别构建里叫 zh，两个都试一下
        val voiceOk = nativeSetVoice("cmn") == 0 || nativeSetVoice("zh") == 0
        if (!voiceOk) Log.w(TAG, "中文音色设置失败，将使用默认音色")
        engineReady = true
        return true
    }

    /** 合成一句，返回 16bit 单声道 PCM；失败返回 null */
    fun synth(text: String, wpm: Int, pitch: Int): ShortArray? =
        if (engineReady) runCatching { nativeSynth(text, wpm, pitch) }.getOrNull() else null

    fun release() {
        if (engineReady) runCatching { nativeRelease() }
        engineReady = false
    }

    // ---------------- 数据解压 ----------------

    private fun prepareData(context: Context): File {
        val root = File(context.filesDir, "tts")
        val dataDir = File(root, "espeak-ng-data")
        val stamp = File(root, ".data-version")
        val upToDate = stamp.isFile && stamp.readText().trim() == DATA_VERSION && dataDir.isDirectory
        if (upToDate) return root

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
