package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil.compose.AsyncImage
import com.fanqie.wear.app
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 阅读历史：按最后阅读时间倒序 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String, String, String, Source) -> Unit) {
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
                        .clickable { onOpen(p.bookId, p.itemId, p.chapterTitle, Source.of(p.source)) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = p.bookTitle.ifBlank {
                            app.shelf.all().firstOrNull { it.id == p.bookId }?.title ?: p.bookId
                        },
                        fontSize = dims.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = LocalPalette.current.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(
                            p.chapterTitle.ifBlank { "读到第 ${p.chapterIndex + 1} 章" },
                            timeText(p.updatedAt),
                        ).filter { it.isNotBlank() }.joinToString(" · "),
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

/** 下载与缓存：离线书籍列表 + 正在下载的任务 + 空间清理 */
@Composable
fun DownloadScreen(onBack: () -> Unit) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val state = rememberScalingLazyListState()
    val tasks by app.downloader.tasks.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    // 只在「有没有任务在跑」变化时重读磁盘：任务每完成一章都会发一次新状态，
    // 直接 remember(tasks) 等于每章都把下载目录深度遍历一遍，进度条一动就掉帧
    val runningCount = tasks.count { it.value.running }
    val books = remember(tick, runningCount) { app.downloads.list() }
    var info by remember { mutableStateOf("") }

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
            item { TopBar(title = "离线下载", onBack = onBack) }

            item {
                Text(
                    text = "已下载 ${books.size} 本 · 占用 ${formatSize(app.downloads.totalBytes())}",
                    fontSize = dims.label,
                    color = p.muted,
                )
            }

            val running = tasks.values.filter { it.running }
            if (running.isNotEmpty()) {
                item { SectionLabel("正在下载") }
                items(running.size) { i ->
                    val t = running[i]
                    GlassCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = t.title,
                                    fontSize = dims.titleSmall,
                                    color = p.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${t.done}/${t.total.coerceAtLeast(1)}",
                                    fontSize = dims.label,
                                    color = p.muted,
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(p.divider),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(t.progress)
                                        .height(3.dp)
                                        .background(p.accent),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Pill(text = "取消", compact = true, onClick = { app.downloader.cancel(t.bookId) })
                        }
                    }
                }
            }

            if (books.isEmpty()) {
                item {
                    HintBox("还没有离线书籍。\n在书籍详情页点「下载全本」即可缓存到手表。")
                }
            } else {
                item { SectionLabel("已下载") }
                items(books.size) { i ->
                    val b = books[i]
                    GlassCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverBox(b.cover, dims)
                            Spacer(Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = b.title,
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${b.done}/${b.total} 章 · ${b.sizeText}",
                                    fontSize = dims.label,
                                    color = p.muted,
                                    maxLines = 1,
                                )
                            }
                            Pill(
                                text = "删除",
                                compact = true,
                                onClick = {
                                    app.downloads.delete(b.bookId)
                                    tick++
                                },
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(text = "刷新", compact = true, onClick = { tick++ })
                    if (books.isNotEmpty()) {
                        Pill(
                            text = "清空全部",
                            compact = true,
                            onClick = {
                                app.downloads.deleteAll()
                                tick++
                            },
                        )
                    }
                }
            }
            if (info.isNotBlank()) {
                item {
                    Text(info, fontSize = dims.label, color = p.muted, maxLines = 2)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes > 1024 * 1024) String.format("%.1f MB", bytes / 1024f / 1024f) else "${bytes / 1024} KB"
