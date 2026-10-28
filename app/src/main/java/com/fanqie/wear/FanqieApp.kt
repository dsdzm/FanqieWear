package com.fanqie.wear

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fanqie.wear.data.DownloadManager
import com.fanqie.wear.data.DownloadStore
import com.fanqie.wear.data.FontDecoder
import com.fanqie.wear.data.SettingsStore
import com.fanqie.wear.data.ShelfStore
import com.fanqie.wear.data.SourceRegistry
import com.fanqie.wear.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FanqieApp : Application() {

    /**
     * 这些都用 by lazy：冷启动时只做必须做的事，
     * 重对象（各自的 OkHttp 客户端、文件解析）等第一次真正用到再建。
     */
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val sources: SourceRegistry by lazy { SourceRegistry(this, settings) }
    val shelf: ShelfStore by lazy { ShelfStore(this) }
    val tts: TtsPlayer by lazy { TtsPlayer(this) }
    val downloads: DownloadStore by lazy { DownloadStore(this) }
    val downloader: DownloadManager by lazy { DownloadManager(sources, downloads, scope) }
    val exporter: com.fanqie.wear.data.TxtExporter by lazy {
        com.fanqie.wear.data.TxtExporter(this, sources, sources.localLibrary)
    }

    /** 应用级作用域：下载等长任务不随页面销毁 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        // 字体映射和图片加载器都不影响首帧，挪到后台线程，别堵冷启动
        scope.launch {
            runCatching { FontDecoder.load(this@FanqieApp) }
            runCatching {
                coil.Coil.setImageLoader(
                    coil.ImageLoader.Builder(this@FanqieApp)
                        .memoryCache { coil.memory.MemoryCache.Builder(this@FanqieApp).maxSizePercent(0.12).build() }
                        .diskCache {
                            coil.disk.DiskCache.Builder()
                                .directory(cacheDir.resolve("img"))
                                .maxSizeBytes(64L * 1024 * 1024)
                                .build()
                        }
                        .build(),
                )
            }
        }
    }

    /**
     * 崩一次就留一份堆栈到私有目录。
     * 手表上不方便连 adb 抓 logcat，出了问题至少能从「设置 → 关于」里看到原因。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                        append("\n\n")
                        append(Log.getStackTraceString(error))
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 上次崩溃堆栈，没有则返回 null */
    fun lastCrash(): String? = runCatching {
        File(filesDir, CRASH_FILE).takeIf { it.isFile }?.readText()
    }.getOrNull()

    fun clearCrash() {
        runCatching { File(filesDir, CRASH_FILE).delete() }
    }

    companion object {
        private const val CRASH_FILE = "last-crash.txt"

        lateinit var instance: FanqieApp
            private set
    }
}

val app: FanqieApp get() = FanqieApp.instance
