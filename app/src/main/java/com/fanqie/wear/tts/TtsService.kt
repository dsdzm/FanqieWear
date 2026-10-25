package com.fanqie.wear.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fanqie.wear.MainActivity
import com.fanqie.wear.R

/** 让听书在息屏/退出界面后继续：前台服务 + 一条可控通知。TTS 实例本身在 Application 中。 */
class TtsService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        startForeground(NOTIF_ID, buildNotification(title))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("正在听书")
            .setContentText(title.ifBlank { "番茄小说" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL = "fanqie_tts"
        private const val NOTIF_ID = 41
        private const val ACTION_STOP = "com.fanqie.wear.STOP_TTS"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, TtsService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TtsService::class.java)) }
        }
    }
}
