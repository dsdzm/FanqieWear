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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.ShelfItem
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

@Composable
fun DetailScreen(
    bookId: String,
    source: Source,
    onBack: () -> Unit,
    onChapters: () -> Unit,
    onRead: (String) -> Unit,
    onSwitchBook: (String, String) -> Unit = { _, _ -> },
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()

    var book by remember { mutableStateOf<Book?>(null) }
    var altSources by remember { mutableStateOf<List<Book>>(emptyList()) }
    var chapterCount by remember { mutableIntStateOf(0) }
    var firstChapter by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inShelf by remember { mutableStateOf(app.shelf.contains(bookId)) }
    var reload by remember { mutableIntStateOf(0) }
    val tasks by app.downloader.tasks.collectAsStateWithLifecycle()

    LaunchedEffect(bookId, source, reload) {
        loading = true
        error = null
        runCatching { app.sources.of(source).detail(bookId) }
            .onSuccess {
                book = it.book
                chapterCount = it.chapterCount
                firstChapter = it.firstChapterId
            }
            .onFailure {
                error = it.message ?: "加载失败"
            }
        loading = false
    }

    // 聚合源：顺便查一下这本书在其他站点有没有，用于「换源」
    LaunchedEffect(book?.title, source) {
        if (source != Source.AGG) {
            altSources = emptyList()
            return@LaunchedEffect
        }
        val b = book ?: return@LaunchedEffect
        val agg = app.sources.of(Source.AGG) as? com.fanqie.wear.data.web.AggregateSource
        altSources = agg?.alternatives(b.title, b.author)?.take(8).orEmpty()
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
            item {
                TopBar(
                    title = book?.title ?: "书籍详情",
                    onBack = onBack,
                    action = if (inShelf) "已藏" else "收藏",
                    onAction = {
                        book?.let { b ->
                            if (inShelf) {
                                app.shelf.remove(bookId)
                            } else {
                                app.shelf.add(
                                    ShelfItem(
                                        id = b.id,
                                        title = b.title,
                                        author = b.author,
                                        cover = b.cover,
                                        source = source.key,
                                    ),
                                )
                            }
                            inShelf = !inShelf
                        }
                    },
                )
            }

            when {
                loading -> item { LoadingBox("正在加载书籍") }
                error != null -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HintBox(error!!, "重试") { reload++ }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "这本书来自「${source.label}」。如果这个源打不开，" +
                                "可以到「设置 → 网络与书源」换源后，用书名重新搜一次。",
                            fontSize = dims.label,
                            color = LocalPalette.current.muted,
                        )
                    }
                }
                book != null -> {
                    val b = book!!
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(LocalPalette.current.card)
                                .padding(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = if (dims.narrow) 52.dp else 60.dp, height = if (dims.narrow) 70.dp else 80.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(LocalPalette.current.cardHigh),
                            ) {
                                b.cover?.let {
                                    AsyncImage(
                                        model = it,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = b.title,
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = b.author,
                                    fontSize = dims.label,
                                    color = LocalPalette.current.muted,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    MiniTag(source.label, sourceTint(source))
                                    if (b.category.isNotBlank()) MiniTag(b.category.take(5), Color(0xFF2F5A7A))
                                    if (b.finished) MiniTag("完结", Color(0xFF3C4A3E))
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${chapterCount} 章" + if (b.wordCount.isNotBlank()) " · ${b.wordCount}" else "",
                                    fontSize = dims.label,
                                    color = LocalPalette.current.muted,
                                )
                            }
                        }
                    }

                    item {
                        Pill(
                            text = if (app.shelf.progress(bookId) != null) "继续阅读" else "开始阅读",
                            selected = true,
                            onClick = {
                                val target = app.shelf.progress(bookId)?.itemId ?: firstChapter
                                if (target != null) {
                                    app.shelf.add(
                                        ShelfItem(
                                            id = b.id,
                                            title = b.title,
                                            author = b.author,
                                            cover = b.cover,
                                            source = source.key,
                                        ),
                                    )
                                    inShelf = true
                                    onRead(target)
                                } else {
                                    error = "没有可读章节"
                                }
                            },
                        )
                    }

                    item {
                        Pill(text = "目录 · $chapterCount 章", onClick = onChapters)
                    }

                    if (app.sources.of(source).capability.chapter) {
                        item {
                            val task = tasks[bookId]
                            val localCount = remember(tasks, reload) {
                                app.downloads.list().firstOrNull { it.bookId == bookId }?.done ?: 0
                            }
                            GlassCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    when {
                                        task?.running == true -> {
                                            Text(
                                                text = "下载中 ${task.done}/${task.total.coerceAtLeast(1)} 章",
                                                fontSize = dims.titleSmall,
                                                color = LocalPalette.current.text,
                                            )
                                            Spacer(Modifier.height(5.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(3.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(LocalPalette.current.divider),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(task.progress)
                                                        .height(3.dp)
                                                        .background(LocalPalette.current.accent),
                                                )
                                            }
                                            Spacer(Modifier.height(7.dp))
                                            Pill(text = "取消下载", onClick = { app.downloader.cancel(bookId) }, compact = true)
                                        }
                                        localCount > 0 -> {
                                            Text(
                                                text = "已缓存 $localCount 章 · 可离线阅读",
                                                fontSize = dims.titleSmall,
                                                color = LocalPalette.current.text,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Pill(
                                                    text = "继续下载",
                                                    selected = true,
                                                    compact = true,
                                                    onClick = { b.let { app.downloader.start(bookId, it.title, it.cover, source) } },
                                                )
                                                Pill(
                                                    text = "删除缓存",
                                                    compact = true,
                                                    onClick = { app.downloads.delete(bookId); reload++ },
                                                )
                                            }
                                        }
                                        else -> {
                                            Text(
                                                text = "离线下载",
                                                fontSize = dims.titleSmall,
                                                color = LocalPalette.current.text,
                                            )
                                            Text(
                                                text = "整本缓存到手表，没网也能看",
                                                fontSize = dims.label,
                                                color = LocalPalette.current.muted,
                                                maxLines = 2,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Pill(
                                                    text = "下载全本",
                                                    selected = true,
                                                    compact = true,
                                                    onClick = { b.let { app.downloader.start(bookId, it.title, it.cover, source) } },
                                                )
                                                Pill(
                                                    text = "前 50 章",
                                                    compact = true,
                                                    onClick = { b.let { app.downloader.start(bookId, it.title, it.cover, source, limit = 50) } },
                                                )
                                            }
                                            if (task?.error != null) {
                                                Text(
                                                    text = task.error!!,
                                                    fontSize = dims.label,
                                                    color = LocalPalette.current.accent,
                                                    maxLines = 2,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (b.intro.isNotBlank()) {
                        item {
                            Column {
                                SectionLabel("简介")
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = b.intro.replace(Regex("\\s+"), " ").trim(),
                                    fontSize = dims.body,
                                    color = LocalPalette.current.muted,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    val src = app.sources.of(source)
                    if (!src.capability.chapter) {
                        item {
                            Text(
                                text = "${src.displayName}这个源暂不支持取正文，只能看详情和目录。想看正文请到「设置 → 网络与书源」换成笔趣阁。",
                                fontSize = dims.label,
                                color = Color(0xFFC98A5A),
                                maxLines = 4,
                            )
                        }
                    }

                    if (altSources.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                                Text(
                                    text = "其他来源 ${altSources.size} 个",
                                    fontSize = dims.label,
                                    color = LocalPalette.current.muted,
                                )
                                Spacer(Modifier.height(4.dp))
                                altSources.take(6).forEach { alt ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(LocalPalette.current.card)
                                            .clickable { onSwitchBook(alt.id, alt.title) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = alt.author.ifBlank { "未知来源" },
                                            fontSize = dims.label,
                                            color = LocalPalette.current.text,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("换到这里", fontSize = dims.label, color = LocalPalette.current.accent)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

/** 书源配色：标签上区分来源，一眼知道这本书是从哪个源来的 */
internal fun sourceTint(source: Source): Color = when (source) {
    Source.BIQUGE -> Color(0xFF2F6B5A)
    Source.QIMAO -> Color(0xFF3A6EA5)
    Source.FANQIE -> Color(0xFF8A3A34)
    Source.AGG -> Color(0xFF4A5A8A)
    Source.LOCAL -> Color(0xFF6B5A2F)
}
