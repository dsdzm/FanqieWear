package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fanqie.wear.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

/**
 * 番茄网页登录：把浏览器里的 Cookie 粘进来。
 *
 * 为什么需要：番茄网页端未登录只给前 10 章左右的试读，第 11 章起正文会被截断；
 * 带上登录态的 Cookie 后，网页会返回完整正文。手表上没有浏览器/商店，
 * 只能从手机复制 Cookie 再粘贴（支持系统剪贴板同步）。
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onWebLogin: () -> Unit = {}) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val state = rememberScalingLazyListState()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(settings.cookie) }
    var saved by remember { mutableStateOf(false) }

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
                            text = if (settings.cookie.isBlank()) "未登录 · 正文只有试读" else "已保存 Cookie（${settings.cookie.length} 字符）",
                            fontSize = dims.titleSmall,
                            color = if (settings.cookie.isBlank()) p.accent else p.jade,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "登录后可读完整章节；免费书也是登录后才放开全文。",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 3,
                        )
                    }
                }
            }

            item {
                NavCard(
                    title = "用网页登录（推荐）",
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
                            text = "1. 手机上打开 fanqienovel.com 并登录\n" +
                                "2. 用支持「查看 Cookie」的浏览器复制整段 Cookie\n" +
                                "3. 发到手表（或手表剪贴板同步）后粘到下面",
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
                                text = "在此粘贴 Cookie，例如 sid_tt=...; sessionid=...",
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
                            text = if (saved) "已保存" else "保存",
                            selected = true,
                            compact = true,
                            onClick = {
                                app.settings.update { it.copy(cookie = text.trim()) }
                                saved = true
                            },
                        )
                        if (settings.cookie.isNotBlank()) {
                            Pill(
                                text = "退出登录",
                                compact = true,
                                onClick = {
                                    text = ""
                                    app.settings.update { it.copy(cookie = "") }
                                    saved = false
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "保存后回到阅读页会自动重新拉取本章。Cookie 只存在本机，不会上传。",
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 3,
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
