package com.fanqie.wear.ui

import androidx.compose.foundation.background
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
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()

    var book by remember { mutableStateOf<Book?>(null) }
    var chapterCount by remember { mutableIntStateOf(0) }
    var firstChapter by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inShelf by remember { mutableStateOf(app.shelf.contains(bookId)) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(bookId, source, reload) {
        loading = true
        error = null
        runCatching {
            when (source) {
                Source.FANQIE -> {
                    val d = app.api.detail(bookId)
                    val chapters = runCatching { app.api.directory(bookId) }.getOrDefault(emptyList())
                    Triple(d.book, d.chapterCount, chapters.firstOrNull()?.itemId)
                }
                Source.QIMAO -> {
                    val b = app.qimao.detail(bookId)
                    val chapters = runCatching { app.qimao.catalog(bookId) }.getOrDefault(emptyList())
                    Triple(b, chapters.size, chapters.firstOrNull()?.itemId)
                }
            }
        }.onSuccess { (b, count, first) ->
            book = b
            chapterCount = count
            firstChapter = first
        }.onFailure {
            error = it.message ?: "加载失败"
        }
        loading = false
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
                                    ShelfItem(id = b.id, title = b.title, author = b.author, cover = b.cover)
                                )
                            }
                            inShelf = !inShelf
                        }
                    },
                )
            }

            when {
                loading -> item { LoadingBox("正在加载书籍") }
                error != null -> item { HintBox(error!!, "重试") { reload++ } }
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
                                    MiniTag(
                                        if (source == Source.QIMAO) "七猫" else "番茄",
                                        if (source == Source.QIMAO) Color(0xFF3A6EA5) else Color(0xFF8A3A34),
                                    )
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
                                        ShelfItem(id = b.id, title = b.title, author = b.author, cover = b.cover)
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

                    if (source == Source.QIMAO) {
                        item {
                            Text(
                                text = "七猫源暂只支持浏览详情与目录，正文请切换番茄源阅读。",
                                fontSize = dims.label,
                                color = Color(0xFFC98A5A),
                                maxLines = 3,
                            )
                        }
                    }

                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}
