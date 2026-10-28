package com.fanqie.wear.ui

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.tts.TtsPlayer
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.8f, 1.0f, 1.5f, 2.0f, 3.0f)

/**
 * 听书页：只管播放，不显示正文。
 *
 * 这一屏可以上下滚动（小表盘放不下所有控件）；倍速和引擎都是点开弹窗选，
 * 避免一排小按钮挤在一起点不准。
 */
@Composable
fun ListenScreen(onBack: () -> Unit) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val s by app.tts.state.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var dl by remember { mutableStateOf<String?>(null) }
    var packed by remember { mutableStateOf(app.tts.currentChapterPacked()) }
    var showSpeed by remember { mutableStateOf(false) }
    var showEngine by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dims.sidePadding, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = s.current.take(60).ifBlank { "还没有开始朗读" },
                fontSize = dims.label,
                color = p.muted,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtn("⏮") { app.tts.prevChapter() }
                CircleBtn(if (s.playing) "⏸" else "▶", big = true) {
                    if (s.playing) app.tts.pause() else app.tts.play()
                }
                CircleBtn("⏭") { app.tts.nextChapter() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(text = "${trimSpeed(s.speed)}x", compact = true, onClick = { showSpeed = true })
                Pill(
                    text = when (settings.ttsEngine) {
                        "system" -> "系统"
                        "online" -> "在线"
                        "espeak" -> "内置"
                        else -> "自动"
                    },
                    compact = true,
                    onClick = { showEngine = true },
                )
            }

            Pill(
                text = when {
                    dl != null -> "下载中 $dl"
                    packed -> "本章已下载 · 点击删除"
                    else -> "下载本章语音"
                },
                compact = true,
                selected = packed,
                onClick = {
                    if (dl != null) return@Pill
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
                },
            )

            Pill(
                text = if (dl != null) "批量下载中 $dl" else "下载后续 5 章",
                compact = true,
                onClick = {
                    if (dl != null) return@Pill
                    dl = "准备中"
                    scope.launch {
                        val n = app.tts.downloadFollowing(5) { msg -> dl = msg }
                        dl = null
                        packed = app.tts.currentChapterPacked()
                        Toast.makeText(ctx, "已下载 $n 章语音", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            Text(
                text = "第 ${s.index + 1}/${s.sentences.size.coerceAtLeast(1)} 段 · " + when (s.engine) {
                    "online" -> "在线语音"
                    "system" -> "系统语音"
                    "espeak" -> "内置 eSpeak"
                    else -> "未选择引擎"
                },
                fontSize = dims.label,
                color = p.muted,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )

            s.error?.let {
                Text(it, fontSize = dims.label, color = p.accent, maxLines = 3, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSpeed) {
        ChoiceDialog(
            title = "播放倍速",
            options = SPEEDS.map { "${trimSpeed(it)}x" },
            selected = SPEEDS.indexOfFirst { kotlin.math.abs(it - s.speed) < 0.01f }.coerceAtLeast(0),
            onDismiss = { showSpeed = false },
            onPick = { i ->
                app.tts.setSpeed(SPEEDS[i])
                showSpeed = false
            },
        )
    }

    if (showEngine) {
        val engines = listOf(
            "自动" to TtsPlayer.ENGINE_AUTO,
            "系统语音" to TtsPlayer.ENGINE_SYSTEM,
            "在线语音" to TtsPlayer.ENGINE_ONLINE,
            "内置 eSpeak" to TtsPlayer.ENGINE_ESPEAK,
        )
        ChoiceDialog(
            title = "朗读引擎",
            options = engines.map { it.first },
            selected = engines.indexOfFirst { it.second == settings.ttsEngine }.coerceAtLeast(0),
            onDismiss = { showEngine = false },
            onPick = { i ->
                app.settings.update { it.copy(ttsEngine = engines[i].second) }
                showEngine = false
                Toast.makeText(ctx, "已切换，下一段生效", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

private fun trimSpeed(v: Float): String =
    if (kotlin.math.abs(v - v.toInt()) < 0.01f) v.toInt().toString() else v.toString()

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(p.card)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontSize = dims.titleSmall, color = p.text, maxLines = 1)
            options.forEachIndexed { i, label ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == selected) p.accent else p.cardHigh)
                        .clickable { onPick(i) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = dims.label,
                        color = if (i == selected) p.onAccent else p.text,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleBtn(text: String, big: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val size = if (big) 54.dp else 42.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (big) p.accent else p.cardHigh)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = dims.titleSmall,
            color = if (big) p.onAccent else p.text,
        )
    }
}
