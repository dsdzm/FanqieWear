package com.fanqie.wear.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.login.PhoneLoginServer
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机登录器：手表显示二维码，手机扫码后把番茄 Cookie 粘进来。
 * 全程局域网，不出公网。
 */
@Composable
fun PhoneLoginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val scope = rememberCoroutineScope()

    val server = remember { PhoneLoginServer(ctx) }
    var addr by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("点「开始」生成二维码") }
    var probe by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    fun start() {
        val ok = server.start(
            onCookie = { cookie ->
                app.settings.update { it.copy(cookie = cookie) }
                status = "收到 Cookie（${cookie.length} 字符），正在验证…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { app.sources.probeFanqie(cookie) }.getOrNull() }
                    probe = when {
                        r == null -> "验证失败：网络异常"
                        r.effective -> "登录已生效 · 正文 ${r.chars} 字"
                        else -> r.message.ifBlank { "Cookie 未生效，第 11 章起仍是试读" }
                    }
                    status = "已保存到手表"
                }
            },
            onLog = { status = it },
        )
        running = ok
        if (ok) {
            addr = server.address
            status = "用手机扫下面的二维码"
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dims.sidePadding, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row3(onBack = onBack, running = running, onToggle = {
                if (running) {
                    server.stop()
                    running = false
                    status = "已停止"
                } else {
                    start()
                }
            })

            if (addr.isNotBlank() && running) {
                val bmp = remember(addr) { qrBitmap(addr) }
                Text(addr, fontSize = dims.label, color = p.muted, maxLines = 1)
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "配对二维码",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(4.dp),
                )
            }

            Text(
                text = status,
                fontSize = dims.label,
                color = p.text,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            probe?.let {
                Text(it, fontSize = dims.label, color = p.accent, textAlign = TextAlign.Center, maxLines = 3)
            }
            Text(
                text = "手机与手表连同一个 WiFi；手机上打开番茄网页版登录后，" +
                    "把整条 Cookie 粘到页面里发送。",
                fontSize = dims.label,
                color = p.muted,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun Row3(onBack: () -> Unit, running: Boolean, onToggle: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(text = "返回", compact = true, onClick = onBack)
        Spacer(Modifier.size(6.dp))
        Pill(text = if (running) "停止" else "开始", compact = true, selected = !running, onClick = onToggle)
    }
}

/** 生成二维码位图：白底黑点，手表屏幕小，别用透明底 */
private fun qrBitmap(text: String, size: Int = 480): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
