package com.fanqie.wear.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fanqie.wear.MainActivity
import com.fanqie.wear.R

/**
 * 让听书在息屏/退出界面后继续：前台服务 + 一条可控通知。TTS 实例本身在 Application 中。
 *
 * 注意 startForeground 的写法：targetSdk 34 之后，服务在清单里声明了
 * foregroundServiceType 时，两参数的 startForeground 会直接抛
 * MissingForegroundServiceTypeException 把 App 干掉 —— v1.6.0 点听书闪退就是这个。
 * 所以统一走 ServiceCompat.startForeground 带上 type，并且整体兜住异常：
 * 通知发不出来顶多少一条通知，不能让播放连带崩掉。
 */
class TtsService : Service() {

    override fun onCreate() {
        super.onCreate()
        runCatching { createChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(title),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
            START_STICKY
        } catch (t: Throwable) {
            // 没有通知权限、前台服务被系统拒绝、渠道被用户关掉……都走这里。
            // 服务退出即可，播放器还在 Application 里跑，声音不受影响。
            stopSelf()
            START_NOT_STICKY
        }
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
            .setContentText(title.ifBlank { "书阁" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
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
