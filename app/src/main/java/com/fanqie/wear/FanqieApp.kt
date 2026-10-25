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

class FanqieApp : Application() {

    /** 书源注册表：页面统一从这里取当前书源，不再直接依赖某个站点 */
    lateinit var sources: SourceRegistry
        private set
    lateinit var shelf: ShelfStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var tts: TtsPlayer
        private set
    lateinit var downloads: DownloadStore
        private set
    lateinit var downloader: DownloadManager
        private set

    /** 应用级作用域：下载等长任务不随页面销毁 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        FontDecoder.load(this)
        settings = SettingsStore(this)
        sources = SourceRegistry(this, settings)
        shelf = ShelfStore(this)
        tts = TtsPlayer(this)
        downloads = DownloadStore(this)
        downloader = DownloadManager(sources, downloads, scope)
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
