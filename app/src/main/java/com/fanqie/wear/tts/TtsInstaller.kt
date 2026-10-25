package com.fanqie.wear.tts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内安装语音引擎：不依赖 Play 商店、不依赖 WebView。
 *
 * 流程：HTTPS 下载开源引擎 eSpeak NG 的 APK → PackageInstaller 静默写包 → 系统弹一次确认 → 装完自动识别。
 * 手表没有「安装未知应用」权限时，会直接跳到本应用的授权页（PackageInstaller 也需要这个权限）。
 */
class TtsInstaller(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onInstalled: (String?) -> Unit,
) {

    enum class Phase { IDLE, DOWNLOADING, INSTALLING, INSTALLED, NEED_PERMISSION, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val done: Long = 0,
        val total: Long = 0,
        val message: String? = null,
        val pkg: String? = null,
    ) {
        val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val apkFile: File get() = File(context.cacheDir, "espeak-engine.apk")

    fun start() {
        if (_state.value.phase == Phase.DOWNLOADING || _state.value.phase == Phase.INSTALLING) return
        scope.launch {
            runCatching {
                _state.value = State(Phase.DOWNLOADING, 0, ENGINE_SIZE)
                download()
                _state.value = _state.value.copy(phase = Phase.INSTALLING)
                install()
            }.onFailure { e ->
                _state.value = _state.value.copy(phase = Phase.ERROR, message = e.message ?: "安装失败")
            }
        }
    }

    private suspend fun download() = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "espeak-engine.tmp")
        val req = Request.Builder()
            .url(ENGINE_URL)
            .header("User-Agent", "FanqieWear/1.4")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败（${resp.code}）")
            val body = resp.body ?: throw IllegalStateException("下载失败（空响应）")
            val total = body.contentLength().takeIf { it > 0 } ?: ENGINE_SIZE
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            _state.value = _state.value.copy(done = done, total = total)
                        }
                    }
                    _state.value = _state.value.copy(done = done, total = total)
                }
            }
        }
        if (tmp.length() < 1_000_000) {
            tmp.delete()
            throw IllegalStateException("下载内容不完整，请重试")
        }
        tmp.renameTo(apkFile)
    }

    private suspend fun install() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IllegalStateException("安装包损坏，请重试")
        val pkg = archive.packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(
                phase = Phase.NEED_PERMISSION,
                pkg = pkg,
                message = "需要允许本应用安装应用",
            )
            openInstallPermission()
            return@withContext
        }

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            context.contentResolver.openInputStream(Uri.fromFile(apkFile))?.use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            } ?: throw IllegalStateException("读取安装包失败")
            val intent = Intent(context, TtsInstallReceiver::class.java)
                .setAction(ACTION_RESULT)
                .putExtra(EXTRA_PKG, pkg)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        _state.value = _state.value.copy(phase = Phase.INSTALLING, pkg = pkg, message = "等待系统确认安装")
    }

    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .recoverCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }

    fun onPackageInstalled(pkg: String?) {
        _state.value = State(Phase.INSTALLED, message = "安装完成", pkg = pkg)
        onInstalled(pkg)
    }

    fun onInstallFailed(message: String?) {
        _state.value = _state.value.copy(phase = Phase.ERROR, message = message ?: "安装被取消")
    }

    companion object {
        private const val ENGINE_URL =
            "https://github.com/espeak-ng/espeak-ng/releases/download/1.52.0/espeak-1.52.0-signed.apk"
        private const val ENGINE_SIZE = 10_446_659L
        const val ACTION_RESULT = "com.fanqie.wear.TTS_INSTALL_RESULT"
        const val EXTRA_PKG = "pkg"
        const val ENGINE_NAME = "eSpeak NG"
        const val ENGINE_NOTE = "开源引擎（GPLv3），约 10MB，支持中文；音色偏机械，胜在离线、不依赖商店"
    }
}

/** PackageInstaller 的回调 */
class TtsInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(TtsInstaller.EXTRA_PKG)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> FanqieTtsBridge.installed(pkg)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            else -> FanqieTtsBridge.failed(msg)
        }
    }
}

/** 接收器与安装器之间的桥（避免把 context 塞进静态字段） */
object FanqieTtsBridge {
    var installer: TtsInstaller? = null
    fun installed(pkg: String?) {
        installer?.onPackageInstalled(pkg)
    }

    fun failed(message: String?) {
        installer?.onInstallFailed(message)
    }
}
