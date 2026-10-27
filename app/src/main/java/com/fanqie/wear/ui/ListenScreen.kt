package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

/**
 * 听书页：只管播放，不显示正文。
 *
 * 暂停/继续、上一章、下一章、倍速都在这一屏；章与章之间由播放器自己取，
 * 所以从这一页切章不需要阅读页在旁边。
 */
@Composable
fun ListenScreen(onBack: () -> Unit) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val s by app.tts.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dl by remember { mutableStateOf<String?>(null) }
    var packed by remember { mutableStateOf(app.tts.currentChapterPacked()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dims.sidePadding, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(text = "返回", compact = true, onClick = onBack)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (s.playing) "正在朗读" else "已暂停",
                    fontSize = dims.label,
                    color = if (s.playing) p.accent else p.muted,
                    maxLines = 1,
                )
            }

            Text(
                text = s.title.ifBlank { "听书" },
                fontSize = dims.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 当前这一块的开头，给个上下文
            Text(
                text = s.current.take(60).ifBlank { "还没有开始朗读" },
                fontSize = dims.label,
                color = p.muted,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(2.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtn("⏮", enabled = true) { app.tts.prevChapter() }
                CircleBtn(if (s.playing) "⏸" else "▶", big = true) {
                    if (s.playing) app.tts.pause() else app.tts.play()
                }
                CircleBtn("⏭", enabled = true) { app.tts.nextChapter() }
            }

            Spacer(Modifier.height(2.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.8f, 1.0f, 1.5f, 2f, 3f).forEach { sp ->
                    Pill(
                        text = "${sp}x",
                        compact = true,
                        selected = kotlin.math.abs(s.speed - sp) < 0.01f,
                        onClick = { app.tts.setSpeed(sp) },
                    )
                }
            }

            Pill(
                text = when {
                    dl != null -> "下载中 $dl"
                    packed -> "本章语音已下载"
                    else -> "下载本章语音"
                },
                compact = true,
                selected = packed,
                onClick = {
                    if (dl == null) {
                        if (packed) {
                            app.tts.deleteCurrentChapterPack()
                            packed = false
                            Toast.makeText(ctx, "已删除本章语音", Toast.LENGTH_SHORT).show()
                        } else {
                            dl = "0/0"
                            scope.launch {
                                val ok = app.tts.downloadCurrentChapter { i, n -> dl = "$i/$n" }
                                dl = null
                                packed = ok
                                Toast.makeText(
                                    ctx,
                                    if (ok) "本章语音已保存，断网也能听" else "下载失败，稍后再试",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
            )

            Pill(
                text = if (dl != null) "批量下载中 $dl" else "下载后续 5 章",
                compact = true,
                onClick = {
                    if (dl == null) {
                        dl = "准备中"
                        scope.launch {
                            val n = app.tts.downloadFollowing(5) { msg -> dl = msg }
                            dl = null
                            packed = app.tts.currentChapterPacked()
                            Toast.makeText(ctx, "已下载 $n 章语音", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )

            Text(
                text = "第 ${s.index + 1}/${s.sentences.size.coerceAtLeast(1)} 段 · 引擎 ${
                    when (s.engine) {
                        "online" -> "在线"
                        "system" -> "系统语音"
                        "espeak" -> "内置"
                        else -> "自动"
                    }
                }",
                fontSize = dims.label,
                color = p.muted,
                maxLines = 1,
            )

            s.error?.let {
                Text(it, fontSize = dims.label, color = p.accent, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CircleBtn(text: String, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val size = if (big) 52.dp else 40.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (big) p.accent else p.cardHigh)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = dims.titleSmall,
            color = if (big) p.onAccent else p.text,
        )
    }
}
