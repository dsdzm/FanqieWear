package com.fanqie.wear

import android.app.Application
import com.fanqie.wear.data.FanqieApi
import com.fanqie.wear.data.FontDecoder
import com.fanqie.wear.data.QimaoApi
import com.fanqie.wear.data.SettingsStore
import com.fanqie.wear.data.ShelfStore
import com.fanqie.wear.tts.TtsPlayer

class FanqieApp : Application() {

    lateinit var api: FanqieApi
        private set
    lateinit var qimao: QimaoApi
        private set
    lateinit var shelf: ShelfStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var tts: TtsPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        FontDecoder.load(this)
        api = FanqieApi(this)
        qimao = QimaoApi(this)
        shelf = ShelfStore(this)
        settings = SettingsStore(this)
        tts = TtsPlayer(this)
    }

    companion object {
        lateinit var instance: FanqieApp
            private set
    }
}

val app: FanqieApp get() = FanqieApp.instance
