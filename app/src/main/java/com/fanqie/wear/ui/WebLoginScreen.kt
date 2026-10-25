package com.fanqie.wear.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import com.fanqie.wear.app
import com.fanqie.wear.data.FanqieApi
import com.fanqie.wear.data.LoginProbe
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

/**
 * 网页登录（需要设备有 WebView）：
 * 直接加载番茄官方登录页，登录完成后把 CookieManager 里的 Cookie 抄进设置。
 *
 * 不逆向登录接口、不碰验证码：走官方页面，用户自己完成登录，我们只取 Cookie。
 * 有些手表没装 Android System WebView，构造 WebView 会直接抛异常，这里会降级成「粘贴 Cookie」。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(onBack: () -> Unit, onUsePaste: () -> Unit) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<LoginProbe?>(null) }
    val scope = rememberCoroutineScope()

    val webView: WebView? = remember {
        runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webViewClient = WebViewClient()
                val wv = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }
            }
        }.onFailure {
            // 没有 WebView：交给调用方引导去粘贴
        }.getOrNull()
    }

    fun saveCookie(): Boolean {
        val manager = CookieManager.getInstance()
        manager.flush()
        val cookie = manager.getCookie("https://fanqienovel.com")
            ?: manager.getCookie("https://www.fanqienovel.com")
        if (cookie.isNullOrBlank()) {
            message = "还没拿到 Cookie，请先在页面里完成登录"
            return false
        }
        // 只认真正的登录票据；passport_csrf_token 匿名访问首页就会下发，当成登录态会误判
        if (!FanqieApi.looksLoggedIn(cookie)) {
            message = "没有登录票据（缺 sessionid），页面里可能还没登录成功"
            return false
        }
        app.settings.update { it.copy(cookie = cookie) }
        message = "已保存，正在用第 11 章验证…"
        verifying = true
        scope.launch {
            probe = runCatching { app.sources.probeFanqie(cookie) }.getOrNull()
            verifying = false
            message = when {
                probe?.effective == true -> "登录已生效：第 11 章读到 ${probe?.chars} 字"
                probe != null -> "已保存，但服务端仍按未登录处理（第 11 章 ${probe?.chars} 字）"
                else -> "已保存，但验证失败，请稍后到「番茄账号」页重试"
            }
        }
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bgTop),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(text = "‹", onClick = onBack, minWidth = dims.buttonHeight * 0.7f, compact = true)
            Text(
                text = "网页登录",
                fontSize = dims.titleSmall,
                color = p.text,
                modifier = Modifier.weight(1f),
            )
            Pill(
                text = if (verifying) "验证中…" else "保存登录",
                selected = true,
                compact = true,
                onClick = { saveCookie() },
            )
        }

        if (webView == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = dims.sidePadding),
                ) {
                    Text(
                        text = "这台手表没有 WebView，打不开网页登录。",
                        fontSize = dims.body,
                        color = p.text,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "请改用「粘贴 Cookie」：手机浏览器登录后复制 Cookie 发到手表。",
                        fontSize = dims.label,
                        color = p.muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Pill(text = "去粘贴 Cookie", selected = true, onClick = onUsePaste)
                }
            }
        } else {
            AndroidView(
                factory = {
                    webView.loadUrl("https://fanqienovel.com/")
                    webView
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp)),
                update = { },
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    fontSize = dims.label,
                    color = if (message.startsWith("已保存")) p.jade else p.accent,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = dims.sidePadding, vertical = 3.dp),
                )
            }
            Text(
                text = "登录完成后点右上角「保存登录」，再返回阅读。",
                fontSize = dims.label,
                color = p.muted,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = dims.sidePadding, vertical = 2.dp),
            )
        }
    }
}
