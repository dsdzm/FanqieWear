package com.fanqie.wear

import android.app.Application
import com.fanqie.wear.data.DownloadManager
import com.fanqie.wear.data.DownloadStore
import com.fanqie.wear.data.FontDecoder
import com.fanqie.wear.data.SettingsStore
import com.fanqie.wear.data.ShelfStore
import com.fanqie.wear.data.SourceRegistry
import com.fanqie.wear.tts.FanqieTtsBridge
import com.fanqie.wear.tts.TtsInstaller
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
    lateinit var ttsInstaller: TtsInstaller
        private set

    /** 应用级作用域：下载等长任务不随页面销毁 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        FontDecoder.load(this)
        settings = SettingsStore(this)
        sources = SourceRegistry(this, settings)
        shelf = ShelfStore(this)
        tts = TtsPlayer(this)
        downloads = DownloadStore(this)
        downloader = DownloadManager(sources, downloads, scope)
        ttsInstaller = TtsInstaller(this, scope) { pkg -> tts.refresh(pkg) }
        FanqieTtsBridge.installer = ttsInstaller
    }

    companion object {
        lateinit var instance: FanqieApp
            private set
    }
}

val app: FanqieApp get() = FanqieApp.instance
