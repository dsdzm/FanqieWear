package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.app
import com.fanqie.wear.data.Chapter
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

private const val PAGE = 60

@Composable
fun ChapterListScreen(
    bookId: String,
    source: Source,
    onBack: () -> Unit,
    onChapter: (String) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableIntStateOf(PAGE) }
    var reload by remember { mutableIntStateOf(0) }
    val progressId = remember { app.shelf.progress(bookId)?.itemId }

    LaunchedEffect(bookId, source, reload) {
        loading = true
        error = null
        runCatching {
            when (source) {
                Source.FANQIE -> app.api.directory(bookId)
                Source.QIMAO -> app.qimao.catalog(bookId)
            }
        }.onSuccess { chapters = it }
            .onFailure { error = it.message ?: "目录加载失败" }
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { TopBar(title = "目录 ${chapters.size}", onBack = onBack) }

            when {
                loading -> item { LoadingBox("正在取目录") }
                error != null -> item { HintBox(error!!, "重试") { reload++ } }
                chapters.isEmpty() -> item { HintBox("目录为空") }
                else -> {
                    items(chapters.take(shown).size) { i ->
                        val ch = chapters[i]
                        val current = ch.itemId == progressId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (current) FanqieRed.copy(alpha = 0.22f)
                                    else LocalPalette.current.card.copy(alpha = 0.6f)
                                )
                                .clickable { onChapter(ch.itemId) }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = ch.title.ifBlank { "第 ${i + 1} 章" },
                                fontSize = dims.titleSmall,
                                color = if (current) FanqieRed else LocalPalette.current.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (shown < chapters.size) {
                        item {
                            Pill(
                                text = "加载更多（$shown/${chapters.size}）",
                                onClick = { shown = (shown + PAGE).coerceAtMost(chapters.size) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}
