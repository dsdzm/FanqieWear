package com.fanqie.wear.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.data.FanqieApi
import com.fanqie.wear.data.Progress
import com.fanqie.wear.data.Source
import com.fanqie.wear.tts.TtsPlayer
import com.fanqie.wear.ui.theme.LocalDisplayConfig
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val READER_BG = listOf(
    Color(0xFF000000), Color(0xFFEDE5D3), Color(0xFFC9D6C0),
    Color(0xFF1A1A1C), Color(0xFF15242B), Color(0xFFFDFBF6),
)

private val CHAR_COLORS = listOf(
    Color.Unspecified,          // 跟随背景
    Color(0xFFEDE6D8),          // 米白
    Color(0xFFB9BEC2),          // 浅灰
    Color(0xFFE8C98A),          // 暖黄
    Color(0xFF9FD6C8),          // 淡青
    Color(0xFF1B1B1B),          // 墨黑
)

@Composable
fun ReaderScreen(
    bookId: String,
    itemId: String,
    source: Source,
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
    onLogin: () -> Unit = {},
    onOpenTtsSettings: () -> Unit = {},
    onOpenListen: () -> Unit = {},
) {
    val dims = rememberDeviceDims()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val uiScale = LocalDisplayConfig.current.uiScale.factor
    val haptics = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var currentId by remember { mutableStateOf(itemId) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var prevId by remember { mutableStateOf<String?>(null) }
    var nextId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var fromLocal by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    var page by remember(currentId) { mutableIntStateOf(0) }
    /** 沉浸模式：默认收起底部操作条，点屏幕中央展开/收起 */
    var chrome by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf(listOf<String>()) }

    val ttsState by app.tts.state.collectAsStateWithLifecycle()
    val onThisChapter = ttsState.chapterId == currentId

    // 听书：把"下一章怎么取"交给播放器，读完一章自动接下一章
    DisposableEffect(source, bookId) {
        val api = app.sources.of(source)
        app.tts.chapterSource = object : TtsPlayer.ChapterSource {
            override suspend fun fetch(chapterId: String): TtsPlayer.Fetched? = runCatching {
                val r = api.chapter(bookId, chapterId)
                TtsPlayer.Fetched(chapterId, r.title, r.content, r.nextId)
            }.getOrNull()
        }
        onDispose { app.tts.chapterSource = null }
    }

    // 播放器自动续章后，正文跟着翻；只在播放中跟随，避免覆盖用户手动翻章
    LaunchedEffect(ttsState.chapterId, ttsState.playing) {
        val cid = ttsState.chapterId
        if (ttsState.playing && cid.isNotBlank() && cid != currentId) {
            currentId = cid
        }
    }

    val bg = READER_BG.getOrElse(settings.readerBg) { Color.Black }
    val darkBg = settings.readerBg in listOf(0, 3, 4)
    val baseFg = if (darkBg) Color(0xFFE6E6E6) else Color(0xFF1B1B1B)
    val fg = CHAR_COLORS.getOrElse(settings.charColor) { Color.Unspecified }.orFallback(baseFg)
    val subFg = if (darkBg) Color(0xFF9A9AA0) else Color(0xFF5A5A5A)
    val fontSize = listOf(14f, 16f, 19f, 22f).getOrElse(settings.fontStep) { 16f }.times(uiScale).sp
    val lineMul = listOf(1.4f, 1.6f, 1.85f, 2.1f).getOrElse(settings.lineStep) { 1.6f }
    val paraGap = listOf(3.dp, 6.dp, 10.dp).getOrElse(settings.paraStep) { 6.dp }

    // 载入正文：先看本地离线，再走网络
    LaunchedEffect(currentId, retry, source, settings.cookie) {
        loading = true
        error = null
        content = ""
        val api = app.sources.of(source)
        val local = app.downloads.readChapter(bookId, currentId)
        if (local != null) {
            title = local.title
            content = local.content
            prevId = local.prevId
            nextId = local.nextId
            fromLocal = true
            locked = false
            loading = false
            app.shelf.saveProgress(Progress(bookId, currentId, local.title, 0, source = source.key))
            return@LaunchedEffect
        }
        fromLocal = false
        if (!api.capability.chapter) {
            error = "${api.displayName}暂不支持取正文，请到「设置 → 网络与书源」换成笔趣阁"
            loading = false
            return@LaunchedEffect
        }
        runCatching { api.chapter(bookId, currentId) }
            .onSuccess {
                title = it.title
                content = it.content
                prevId = it.prevId
                nextId = it.nextId
                locked = it.locked
                app.shelf.saveProgress(
                    Progress(
                        bookId = if (it.bookId.isNotBlank()) it.bookId else bookId,
                        itemId = it.itemId,
                        chapterTitle = it.title,
                        chapterIndex = 0,
                        source = source.key,
                    ),
                )
            }
            .onFailure { error = it.message ?: "正文加载失败" }
        loading = false
        if (settings.preloadNext) {
            nextId?.let { runCatching { api.chapter(bookId, it) } }
        }
    }

    // 分页：按字号/行距/屏幕高度估算每页字数
    LaunchedEffect(content, settings.pageMode, settings.fontStep, settings.lineStep, dims.widthDp, dims.heightDp) {
        if (content.isBlank()) {
            pages = emptyList()
            return@LaunchedEffect
        }
        val textW = (dims.widthDp - dims.sidePadding.value * 2).coerceAtLeast(80f)
        val textH = (dims.heightDp - 76f).coerceAtLeast(80f)
        val charsPerLine = (textW / (fontSize.value * 1.02f)).toInt().coerceAtLeast(8)
        val linesPerPage = (textH / (fontSize.value * lineMul)).toInt().coerceAtLeast(4)
        val budget = (charsPerLine * linesPerPage).coerceAtLeast(60)
        val paras = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in paras) {
            if (sb.isNotEmpty() && sb.length + p.length > budget) {
                out += sb.toString()
                sb.clear()
            }
            if (p.length > budget) {
                // 超长段落按字数硬切
                var i = 0
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                while (i < p.length) {
                    out += p.substring(i, minOf(i + budget, p.length))
                    i += budget
                }
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(p)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        pages = out
        page = 0
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    DisposableEffect(settings.keepAwake) {
        view.keepScreenOn = settings.keepAwake
        onDispose { view.keepScreenOn = false }
    }
    val rotaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rotaryFocus.requestFocus() } }

    fun goNext() {
        if (settings.pageMode == 1 && page < pages.lastIndex) {
            page++
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else {
            nextId?.let { currentId = it }
        }
    }

    fun goPrev() {
        if (settings.pageMode == 1 && page > 0) {
            page--
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else {
            prevId?.let { currentId = it }
        }
    }

    val listState = rememberLazyListState()

    // 切章后回到顶部：滚动模式下自动下一章、点按/表冠翻章都走这里
    LaunchedEffect(currentId) {
        runCatching { listState.scrollToItem(0) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .onRotaryScrollEvent { event ->
                val down = event.verticalScrollPixels > 0f
                when {
                    settings.pageMode == 1 -> {
                        repeat(settings.crownSpeed + 1) { if (down) goNext() else goPrev() }
                        true
                    }
                    settings.crownTurn -> {
                        if (down) nextId?.let { currentId = it } else prevId?.let { currentId = it }
                        true
                    }
                    else -> {
                        scope.launch {
                            listState.scrollBy((event.verticalScrollPixels * (settings.crownSpeed + 1)))
                        }
                        true
                    }
                }
            }
            .focusRequester(rotaryFocus)
            .focusable(),
    ) {
        // 顶部工具条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = "‹", onClick = onBack, minWidth = dims.buttonHeight * 0.7f, compact = true)
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
            if (fromLocal) {
                Text("离线", fontSize = dims.label, color = LocalPalette.current.jade, maxLines = 1)
                Spacer(Modifier.size(4.dp))
            }
            Pill(
                text = "字",
                onClick = { app.settings.update { it.copy(fontStep = (it.fontStep + 1) % 4) } },
                minWidth = dims.buttonHeight * 0.7f,
                compact = true,
            )
        }

        if (ttsState.error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dims.sidePadding, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalPalette.current.accentSoft)
                    .clickable { app.tts.refresh() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "语音引擎初始化失败 · 点此重试",
                    fontSize = dims.label,
                    color = LocalPalette.current.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onThisChapter && ttsState.sentences.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dims.sidePadding)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (darkBg) Color(0xFF232326) else Color(0xFFDDD6C4))
                    .clickable { if (ttsState.playing) app.tts.pause() else app.tts.play() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ttsState.current,
                    fontSize = dims.label,
                    color = if (ttsState.playing) LocalPalette.current.accent else subFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (ttsState.playing) "暂停" else "续听",
                    fontSize = dims.label,
                    color = LocalPalette.current.accent,
                )
            }
        }

        if (locked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dims.sidePadding, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalPalette.current.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        settings.cookie.isBlank() -> "未登录，本章只有试读"
                        !FanqieApi.looksLoggedIn(settings.cookie) -> "Cookie 里没有登录票据，本章仍是试读"
                        else -> "登录未生效或本章需付费，本章仍是试读"
                    },
                    fontSize = dims.label,
                    color = LocalPalette.current.accent,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Pill(text = "去登录", selected = true, compact = true, onClick = onLogin)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingBox("正在取正文") }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HintBox(error!!, "重试") { retry++ }
                }
                settings.pageMode == 1 -> {
                    val text = pages.getOrElse(page) { "" }
                    AnimatedContent(
                        targetState = page to text,
                        transitionSpec = {
                            when (settings.pageAnim) {
                                0 -> fadeIn(androidx.compose.animation.core.tween(0)) togetherWith
                                    fadeOut(androidx.compose.animation.core.tween(0))
                                1 -> fadeIn(androidx.compose.animation.core.tween(180)) togetherWith
                                    fadeOut(androidx.compose.animation.core.tween(120))
                                else -> (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                            }
                        },
                        label = "page",
                        modifier = Modifier.fillMaxSize(),
                    ) { (_, body) ->
                        Text(
                            text = body,
                            fontSize = fontSize,
                            lineHeight = fontSize * lineMul,
                            color = fg,
                            textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 左 / 中 / 右 三个点按区
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(0.3f)
                                .fillMaxHeight()
                                .clickable { goPrev() },
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.4f)
                                .fillMaxHeight()
                                .pointerInput(currentId) {
                                    detectTapGestures(
                                        onTap = { chrome = !chrome },
                                        onDoubleTap = { onOpenChapters() },
                                    )
                                },
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.3f)
                                .fillMaxHeight()
                                .clickable { goNext() },
                        )
                    }
                }
                else -> {
                    val paragraphs = remember(content) {
                        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    }
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
                                modifier = Modifier
                                    .padding(vertical = paraGap / 2)
                                    .clickable { chrome = !chrome },
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

            // 悬浮听书键：点了直接在正文里开始读，不跳页面；齿轮进听书页
            TtsFab(
                playing = onThisChapter && ttsState.playing,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 6.dp),
                onToggle = {
                    if (onThisChapter) {
                        if (ttsState.playing) app.tts.pause() else app.tts.play()
                    } else if (content.isNotBlank()) {
                        app.tts.load(bookId, currentId, title, content, nextId, prevId, source.key)
                        app.tts.setSpeed(settings.ttsSpeed)
                        app.tts.setPitch(settings.ttsPitch)
                        app.tts.play()
                    }
                },
                onOpen = onOpenListen,
            )
        }

        if (settings.bottomProgress) {
            val pct = if (settings.pageMode == 1) {
                if (pages.isEmpty()) 0f else (page + 1).toFloat() / pages.size
            } else {
                val total = listState.layoutInfo.totalItemsCount
                if (total == 0) 0f else (listState.firstVisibleItemIndex + 1).toFloat() / total
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = dims.sidePadding)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (darkBg) Color(0xFF2C2C30) else Color(0xFFD8D2C4)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(LocalPalette.current.accent),
                    )
                }
            }

        // 底部操作条：沉浸模式下收起，点屏幕中央再展开
        if (!chrome) return@Column
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.sidePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = "上章", onClick = { prevId?.let { currentId = it } }, minWidth = 46.dp, compact = true)
            if (settings.pageMode == 1 && pages.isNotEmpty()) {
                Text("${page + 1}/${pages.size}", fontSize = dims.label, color = subFg, maxLines = 1)
            } else {
                Pill(text = "下章", onClick = { nextId?.let { currentId = it } }, minWidth = 46.dp, compact = true)
            }
        }
    }
}
}

private fun Color.orFallback(fallback: Color): Color =
    if (this == Color.Unspecified) fallback else this

/** 悬浮听书键：主按钮播放/暂停，右上角小齿轮进听书页 */
@Composable
private fun TtsFab(
    playing: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(p.cardHigh)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", fontSize = 10.sp, color = p.muted)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(p.accent)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (playing) "⏸" else "▶",
                fontSize = 15.sp,
                color = p.onAccent,
            )
        }
    }
}
