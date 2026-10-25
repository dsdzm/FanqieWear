package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.fanqie.wear.R
import com.fanqie.wear.app
import com.fanqie.wear.data.FanqieApi
import com.fanqie.wear.data.LoginProbe
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch

/**
 * 番茄网页登录：把浏览器里的 Cookie 粘进来。
 *
 * 这里有个坑要说明白：番茄对无效 Cookie 是**静默降级**的 —— 不报错、不跳登录，
 * 直接把请求当匿名处理，正文继续只给前 10 章。所以"cookie 非空"完全不代表登录成功，
 * 保存之后必须实测一次（拿第 11 章试读），才知道服务端认不认。
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onWebLogin: () -> Unit = {}) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val state = rememberScalingLazyListState()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(settings.cookie) }
    var checking by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<LoginProbe?>(null) }

    fun verify(cookie: String) {
        if (cookie.isBlank()) {
            probe = null
            return
        }
        checking = true
        scope.launch {
            probe = runCatching { app.sources.probeFanqie(cookie) }.getOrNull()
            checking = false
        }
    }

    ScreenScaffold(
        scrollState = state,
        timeText = { WearTimeText() },
        scrollIndicator = { ScrollIndicator(state) },
    ) {
        WearList(
            state = state,
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = "番茄账号", onBack = onBack) }

            item {
                GlassCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            text = when {
                                checking -> "正在验证登录态…"
                                probe?.effective == true -> "登录已生效"
                                probe != null -> "登录未生效"
                                settings.cookie.isBlank() -> "未登录"
                                else -> "已保存 Cookie"
                            },
                            fontSize = dims.titleSmall,
                            color = when {
                                probe?.effective == true -> p.jade
                                probe != null -> p.accent
                                settings.cookie.isBlank() -> p.accent
                                else -> p.text
                            },
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = probeHint(probe, settings.cookie, checking),
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 5,
                        )
                    }
                }
            }

            item {
                NavCard(
                    title = "用网页登录",
                    subtitle = "官方页面登录，自动取 Cookie",
                    icon = R.drawable.ic_compass,
                    tint = p.jade,
                    onClick = onWebLogin,
                )
            }

            item {
                GlassCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("手动粘贴步骤", fontSize = dims.label, color = p.muted)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "1. 电脑 Chrome 打开 fanqienovel.com 并登录\n" +
                                "2. F12 → Network → 任一请求 → 复制整条 Cookie\n" +
                                "3. 粘到下面，点「保存并验证」",
                            fontSize = dims.label,
                            color = p.text,
                            maxLines = 6,
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp, max = 120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.card)
                            .border(1.dp, p.cardBorder, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                    ) {
                        if (text.isBlank()) {
                            Text(
                                text = "在此粘贴 Cookie，必须有 sessionid=…",
                                fontSize = dims.label,
                                color = p.muted,
                                maxLines = 3,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = TextStyle(color = p.text, fontSize = dims.label),
                            cursorBrush = SolidColor(p.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(
                            text = "粘贴剪贴板",
                            compact = true,
                            onClick = {
                                clipboard.getText()?.text?.let { text = it }
                            },
                        )
                        Pill(
                            text = if (checking) "验证中…" else "保存并验证",
                            selected = true,
                            compact = true,
                            onClick = {
                                val v = text.trim()
                                app.settings.update { it.copy(cookie = v) }
                                verify(v)
                            },
                        )
                        if (settings.cookie.isNotBlank()) {
                            Pill(
                                text = "退出登录",
                                compact = true,
                                onClick = {
                                    text = ""
                                    probe = null
                                    app.settings.update { it.copy(cookie = "") }
                                },
                            )
                        }
                    }
                    if (probe == null && settings.cookie.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Pill(
                            text = "重新检测当前登录",
                            compact = true,
                            onClick = { verify(settings.cookie) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Cookie 只存在本机，不会上传。番茄对无效 Cookie 不报错，" +
                            "所以一定要看到「登录已生效」才算成功。",
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 4,
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** 把自检结果翻译成用户能照做的提示 */
private fun probeHint(probe: LoginProbe?, cookie: String, checking: Boolean): String = when {
    checking -> "正在用第 11 章试读，判断服务端认不认这份 Cookie…"
    probe == null && cookie.isBlank() -> "未登录时每本书只有前 10 章试读。"
    probe == null -> "已保存，但还没验证。点「重新检测」看看是否真的生效。"
    probe.effective -> "第 11 章读到 ${probe.chars} 字，服务端已按登录态返回全文。"
    !FanqieApi.looksLoggedIn(cookie) ->
        "这份 Cookie 里没有 sessionid / sid_guard，说明复制时没带上登录票据。" +
            "注意 passport_csrf_token 不算登录态，匿名访问也会下发。"
    probe.chars in 1..400 ->
        "Cookie 有登录票据，但服务端仍按未登录处理（第 11 章只有 ${probe.chars} 字）。" +
            "多半是 Cookie 已过期或被顶号，重新登录一次再复制。"
    probe.message.isNotBlank() -> probe.message
    else -> "验证结果异常，稍后重试。"
}
