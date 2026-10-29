package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch

/**
 * 搜索页：默认用设置里选的书源，也可以在这里临时切一个源只影响本次搜索。
 * 换源后会清空结果，按新源重新搜一次，避免两个源的结果混在一起。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBook: (Book) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var sourceKey by remember { mutableStateOf(app.settings.value.sourceKey) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var trigger by remember { mutableIntStateOf(0) }
    // 0 = 关键词搜索，1 = 按类型浏览
    var mode by remember { mutableIntStateOf(0) }
    // 聚合搜索时"部分站点失败"的原因：有结果也要显示，否则用户以为没接入
    var siteNote by remember { mutableStateOf<String?>(null) }
    var shelfId by remember { mutableStateOf<String?>(null) }

    val source = Source.of(sourceKey)

    LaunchedEffect(trigger, sourceKey) {
        if (trigger == 0) return@LaunchedEffect
        val byShelf = mode == 1
        if (byShelf && shelfId == null) return@LaunchedEffect
        if (!byShelf && keyword.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        page = 0
        runCatching {
            if (byShelf) app.sources.of(source).browse(shelfId!!, 0)
            else app.sources.of(source).search(keyword, 0)
        }
            .onSuccess {
                books = it
                hasMore = it.isNotEmpty()
                searched = true
                siteNote = if (source == Source.AGG) {
                    app.sources.aggregateErrors().entries
                        .joinToString("；") { e -> "${e.key}：${e.value}" }
                        .takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
            .onFailure { error = it.message ?: "搜索失败" }
        loading = false
    }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            runCatching { app.sources.of(source).search(keyword, page + 1) }
                .onSuccess {
                    books = books + it.filter { b -> books.none { old -> old.id == b.id } }
                    hasMore = it.isNotEmpty()
                    page += 1
                }
                .onFailure { error = it.message }
            loading = false
        }
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
            item { TopBar(title = "搜索", onBack = onBack) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(text = "关键词", selected = mode == 0, compact = true, onClick = {
                        if (mode != 0) {
                            mode = 0
                            books = emptyList()
                            searched = false
                        }
                    })
                    Pill(text = "类型", selected = mode == 1, compact = true, onClick = {
                        if (mode != 1) {
                            mode = 1
                            books = emptyList()
                            searched = false
                        }
                    })
                }
            }

            if (mode == 0) item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalPalette.current.card)
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (keyword.isEmpty()) {
                        Text(
                            text = "输入书名或作者",
                            fontSize = dims.body,
                            color = LocalPalette.current.muted,
                        )
                    }
                    BasicTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = dims.body, color = LocalPalette.current.text),
                        cursorBrush = SolidColor(FanqieRed),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { trigger++ }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 临时书源：只影响本页，不改设置里的默认源
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    app.sources.all.forEach { s ->
                        Pill(
                            text = s.source.label,
                            selected = s.source == source,
                            compact = true,
                            onClick = {
                                if (s.source != source) {
                                    sourceKey = s.source.key
                                    books = emptyList()
                                    searched = false
                                    if (keyword.isNotBlank()) trigger++
                                }
                            },
                        )
                    }
                }
            }

            if (mode == 0) {
                item { Pill("开始搜索", selected = true, onClick = { trigger++ }) }
            } else {
                val shelves = app.sources.of(source).shelves
                if (shelves.isEmpty()) {
                    item {
                        HintBox("${source.label}不支持按类型浏览") { }
                    }
                } else {
                    shelves.chunked(2).forEach { pair ->
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { sh ->
                                    Pill(
                                        text = sh.name,
                                        selected = shelfId == sh.id,
                                        compact = true,
                                        onClick = {
                                            shelfId = sh.id
                                            trigger++
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (loading) item { LoadingBox("正在搜索") }
            if (error != null) item { HintBox(error!!, "重试") { trigger++ } }
            if (!loading && searched && books.isEmpty() && error == null) {
                item {
                    HintBox(
                        if (mode == 1) "${source.label}这个分类下没有内容" else "${source.label}没有找到相关作品",
                    ) { trigger++ }
                }
            }

            items(books.size) { i ->
                BookCard(book = books[i], onClick = { onBook(books[i]) })
            }

            if (books.isNotEmpty() && hasMore) {
                item {
                    Pill(text = "加载更多", compact = true, onClick = { loadMore() })
                }
            }

            if (books.isNotEmpty()) {
                item {
                    Text(
                        text = "结果来自 ${source.label} · 共 ${books.size} 本",
                        fontSize = dims.label,
                        color = LocalPalette.current.muted,
                    )
                }
                // 部分站点没搜到（超时 / 站内搜索被占 / 证书问题）也要说出来
                siteNote?.let { note ->
                    item {
                        Text(
                            text = "未返回：$note",
                            fontSize = dims.label,
                            color = LocalPalette.current.muted,
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
