package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.data.Progress
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalDisplayConfig
import com.fanqie.wear.ui.theme.rememberDeviceDims

private val READER_BG = listOf(
    Color(0xFF000000), Color(0xFFEDE5D3), Color(0xFFC9D6C0),
    Color(0xFF1A1A1C), Color(0xFF15242B), Color(0xFFFDFBF6),
)

@Composable
fun ReaderScreen(
    bookId: String,
    itemId: String,
    source: Source,
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
) {
    val dims = rememberDeviceDims()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val uiScale = LocalDisplayConfig.current.uiScale.factor
    val listState = rememberLazyListState()

    var currentId by remember { mutableStateOf(itemId) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var prevId by remember { mutableStateOf<String?>(null) }
    var nextId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    val ttsState by app.tts.state.collectAsStateWithLifecycle()
    val onThisChapter = ttsState.chapterId == currentId

    val bg = READER_BG.getOrElse(settings.readerBg) { Color.Black }
    val darkBg = settings.readerBg in listOf(0, 3, 4)
    val fg = if (darkBg) Color(0xFFE6E6E6) else Color(0xFF1B1B1B)
    val subFg = if (darkBg) Color(0xFF9A9AA0) else Color(0xFF5A5A5A)
    val fontSize = listOf(13f, 15f, 17f).getOrElse(settings.fontStep) { 15f }.times(uiScale).sp
    val lineMul = listOf(1.45f, 1.65f, 1.9f).getOrElse(settings.lineStep) { 1.65f }
    val paraGap = listOf(2.dp, 4.dp, 7.dp).getOrElse(settings.paraStep) { 4.dp }

    LaunchedEffect(currentId, retry, source) {
        loading = true
        error = null
        content = ""
        if (source == Source.QIMAO) {
            error = "七猫源正文暂未支持，请切换到番茄源阅读"
            loading = false
            return@LaunchedEffect
        }
        runCatching { app.api.chapter(currentId) }
            .onSuccess {
                title = it.title
                content = it.content
                prevId = it.prevId
                nextId = it.nextId
                app.shelf.saveProgress(
                    Progress(
                        bookId = if (it.bookId.isNotBlank()) it.bookId else bookId,
                        itemId = it.itemId,
                        chapterTitle = it.title,
                        chapterIndex = 0,
                    )
                )
            }
            .onFailure { error = it.message ?: "正文加载失败" }
        loading = false
        // 预加载下一章：只是把正文塞进 API 的 LRU 缓存，翻章时不用再等
        if (settings.preloadNext) {
            nextId?.let { runCatching { app.api.chapter(it) } }
        }
        runCatching { listState.scrollToItem(0) }
    }

    val view = LocalView.current
    DisposableEffect(settings.keepAwake) {
        view.keepScreenOn = settings.keepAwake
        onDispose { view.keepScreenOn = false }
    }
    val rotaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rotaryFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .onRotaryScrollEvent { event ->
                if (!settings.crownTurn) return@onRotaryScrollEvent false
                if (event.verticalScrollPixels > 0f) nextId?.let { currentId = it }
                else prevId?.let { currentId = it }
                true
            }
            .focusRequester(rotaryFocus)
            .focusable(),
    ) {
        // 顶部工具条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = "‹", onClick = onBack, minWidth = 34.dp)
            Text(
                text = title.ifBlank { "阅读中" },
                fontSize = dims.label,
                color = subFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .clickable { onOpenChapters() },
            )
            Pill(text = "字", onClick = {
                app.settings.update { it.copy(fontStep = (it.fontStep + 1) % 3) }
            }, minWidth = 34.dp)
        }

        if (onThisChapter && ttsState.sentences.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dims.sidePadding)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (darkBg) Color(0xFF232326) else Color(0xFFDDD6C4))
                    .clickable {
                        if (ttsState.playing) app.tts.pause() else app.tts.play()
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ttsState.current,
                    fontSize = dims.label,
                    color = if (ttsState.playing) FanqieRed else subFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (ttsState.playing) "暂停" else "续听",
                    fontSize = dims.label,
                    color = FanqieRed,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding)
                .pointerInput(currentId) {
                    detectTapGestures(onDoubleTap = { onOpenChapters() })
                }
                .pointerInput(currentId) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onHorizontalDrag = { change, drag ->
                            total += drag
                            change.consume()
                        },
                        onDragEnd = {
                            if (total < -70f) nextId?.let { currentId = it }
                            else if (total > 70f) prevId?.let { currentId = it }
                        },
                    )
                },
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingBox("正在取正文")
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HintBox(error!!, "重试") { retry++ }
                }
                else -> {
                    val paragraphs = remember(content) {
                        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    // 读到页尾自动接下一章（可关），要求用户确实滚动过，避免正文不足一屏时连续跳章
                    var prevLast by remember(currentId) { mutableIntStateOf(0) }
                    LaunchedEffect(currentId, paragraphs.size, settings.autoNext, nextId) {
                        if (!settings.autoNext || nextId == null) return@LaunchedEffect
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .distinctUntilChanged()
                            .collect { idx ->
                                if (idx >= paragraphs.size && prevLast in 1 until paragraphs.size) {
                                    nextId?.let { currentId = it }
                                }
                                prevLast = idx
                            }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(paragraphs.size) { i ->
                            Text(
                                text = paragraphs[i],
                                fontSize = fontSize,
                                lineHeight = fontSize * lineMul,
                                color = fg,
                                textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
                                modifier = Modifier.padding(vertical = paraGap / 2),
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (nextId == null) {
                                    Text("已是最后一章", fontSize = dims.label, color = subFg)
                                } else {
                                    Pill(text = "下一章 ›", selected = true, onClick = { nextId?.let { currentId = it } })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部操作条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = "上章", onClick = { prevId?.let { currentId = it } }, minWidth = 44.dp)
            Box(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(FanqieRed)
                    .clickable {
                        if (onThisChapter) {
                            if (ttsState.playing) app.tts.pause() else app.tts.play()
                        } else if (content.isNotBlank()) {
                            app.tts.load(bookId, currentId, title, content)
                            app.tts.setSpeed(settings.ttsSpeed)
                            app.tts.play()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        onThisChapter && ttsState.playing -> "⏸ 暂停"
                        onThisChapter -> "▶ 续听"
                        else -> "▶ 听书"
                    },
                    fontSize = dims.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                )
            }
            Pill(text = "下章", onClick = { nextId?.let { currentId = it } }, minWidth = 44.dp)
        }
    }
}
