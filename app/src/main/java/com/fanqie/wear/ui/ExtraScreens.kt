package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil.compose.AsyncImage
import com.fanqie.wear.app
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 阅读历史：按最后阅读时间倒序 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String, String, String) -> Unit) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var list by remember { mutableStateOf(app.shelf.allProgress()) }

    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = "阅读历史", onBack = onBack, action = "清空", onAction = {
                app.shelf.clearHistory()
                list = emptyList()
            }) }

            if (list.isEmpty()) {
                item { HintBox("还没有阅读记录") }
            }

            items(list.size) { i ->
                val p = list[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalPalette.current.card)
                        .clickable { onOpen(p.bookId, p.itemId, p.chapterTitle) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = p.chapterTitle.ifBlank { p.bookId },
                        fontSize = dims.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "读到第 ${p.chapterIndex + 1} 章 · ${timeText(p.updatedAt)}",
                        fontSize = dims.label,
                        color = LocalPalette.current.muted,
                        maxLines = 1,
                    )
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

private fun timeText(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))

/** 我的：书架入口 + 统计 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onShelf: () -> Unit,
    onSettings: () -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val shelf = remember { app.shelf.all() }
    val history = remember { app.shelf.allProgress() }

    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = "我的", onBack = onBack) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF17323A))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("本地书库", fontSize = dims.label, color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Stat("${shelf.size}", "本藏书")
                        Stat("${history.size}", "本在读")
                    }
                }
            }

            item {
                SettingsRow("我的书架", "${shelf.size} 本书", com.fanqie.wear.R.drawable.ic_book, Color(0xFF5B4B63), onShelf)
            }
            item {
                SettingsRow("设置", "阅读、听书、存储", com.fanqie.wear.R.drawable.ic_settings, Color(0xFF2C2F36), onSettings)
            }
            item {
                Text(
                    text = "数据保存在本机，不会上传。",
                    fontSize = dims.label,
                    color = LocalPalette.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val dims = rememberDeviceDims()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = dims.titleLarge, fontWeight = FontWeight.Bold, color = FanqieRed)
        Text(label, fontSize = dims.label, color = Color.White.copy(alpha = 0.7f))
    }
}

/** 下载与缓存：目前只做缓存统计/清理，正文离线缓存未实现 */
@Composable
fun DownloadScreen(onBack: () -> Unit) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var info by remember { mutableStateOf("") }

    fun scan(): String {
        var size = 0L
        app.cacheDir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return "缓存占用 ${size / 1024} KB"
    }

    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = "下载与缓存", onBack = onBack) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalPalette.current.card)
                        .padding(12.dp),
                ) {
                    Text("正文缓存", fontSize = dims.titleSmall, fontWeight = FontWeight.Medium)
                    Text(
                        text = "手表端暂不提供整本离线下载：正文按章实时拉取，已读章节会复用内存缓存。",
                        fontSize = dims.label,
                        color = LocalPalette.current.muted,
                        maxLines = 4,
                    )
                }
            }
            item { Pill(text = "统计缓存", selected = true, onClick = { info = scan() }) }
            if (info.isNotBlank()) {
                item {
                    Text(info, fontSize = dims.label, color = LocalPalette.current.muted)
                }
            }
            item {
                Pill(text = "清理缓存", onClick = {
                    app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    info = "已清理"
                })
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
