package com.fanqie.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch

/**
 * 分类书库：原来写死番茄的排行榜，现在跟着书源走。
 * 每个源自己声明有哪些书架（分类 / 榜单 / 热词），这里只负责列表与分页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RankScreen(
    initialMale: Boolean,
    onBack: () -> Unit,
    onBook: (Book) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var sourceKey by remember { mutableStateOf(app.settings.value.sourceKey) }
    val api = app.sources.of(Source.of(sourceKey))

    var shelfIndex by remember(sourceKey) { mutableIntStateOf(0) }
    var books by remember(sourceKey) { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }

    val shelf = api.shelves.getOrNull(shelfIndex)

    LaunchedEffect(sourceKey, shelf?.id, reload) {
        val shelfId = shelf?.id ?: run {
            books = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        books = emptyList()
        page = 0
        hasMore = true
        runCatching { api.browse(shelfId, 0) }
            .onSuccess {
                books = it
                hasMore = it.isNotEmpty()
            }
            .onFailure { error = it.message ?: "加载失败" }
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
            item { TopBar(title = "分类书库", onBack = onBack) }

            // 书源：就地切换，先不动设置里的默认值
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    app.sources.all.forEach { s ->
                        Pill(
                            text = s.source.label,
                            selected = s.source.key == sourceKey,
                            compact = true,
                            onClick = {
                                if (s.source.key != sourceKey) {
                                    sourceKey = s.source.key
                                    shelfIndex = 0
                                }
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    text = api.displayName + " · " + api.desc,
                    fontSize = dims.label,
                    color = LocalPalette.current.muted,
                    maxLines = 2,
                )
            }

            if (api.shelves.isNotEmpty()) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        api.shelves.forEachIndexed { i, s ->
                            Pill(
                                text = s.name,
                                selected = i == shelfIndex,
                                compact = true,
                                onClick = { shelfIndex = i },
                            )
                        }
                    }
                }
            }

            if (loading && books.isEmpty()) {
                item { LoadingBox("正在取书单") }
            }
            if (error != null && books.isEmpty()) {
                item { HintBox(error!!, "重试") { reload++ } }
            }
            if (!loading && books.isEmpty() && error == null) {
                item { HintBox("这个分类暂时没有书") }
            }

            items(books.size) { i ->
                BookCard(book = books[i], onClick = { onBook(books[i]) })
            }

            if (books.isNotEmpty() && hasMore) {
                item {
                    Pill(text = if (loading) "加载中…" else "加载更多", onClick = {
                        val shelfId = shelf?.id ?: return@Pill
                        if (loading) return@Pill
                        loading = true
                        scope.launch {
                            runCatching { api.browse(shelfId, page + 1) }
                                .onSuccess {
                                    books = books + it.filter { b -> books.none { old -> old.id == b.id } }
                                    hasMore = it.isNotEmpty()
                                    page += 1
                                }
                                .onFailure { error = it.message }
                            loading = false
                        }
                    })
                }
            }

            if (books.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
