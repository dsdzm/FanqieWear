package com.fanqie.wear.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.random.Random

/**
 * 书荒广场：从当前书源的分类里随机抽一批书，点「换一批」换分类或换页。
 * 以前这里写死番茄推荐流，番茄不可用后整页就空了，现在跟着书源走。
 */
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onBook: (Book) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()

    var sourceKey by remember { mutableStateOf(app.settings.value.sourceKey) }
    val api = app.sources.of(Source.of(sourceKey))

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var shelfName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var nonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(sourceKey, nonce) {
        loading = true
        error = null
        books = emptyList()
        runCatching {
            val shelf = api.shelves.randomOrNull()
                ?: throw com.fanqie.wear.data.ApiException("${api.displayName}没有可浏览的分类")
            shelfName = shelf.name
            api.browse(shelf.id, Random.nextInt(0, 4))
        }
            .onSuccess { books = it }
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
            item { TopBar(title = "书荒广场", onBack = onBack) }

            // 书源：只影响本页浏览
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(dims.gridGap)) {
                    app.sources.all.forEach { s ->
                        Pill(
                            text = s.source.label,
                            selected = s.source.key == sourceKey,
                            compact = true,
                            onClick = {
                                if (s.source.key != sourceKey) {
                                    sourceKey = s.source.key
                                    nonce++
                                }
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    text = if (loading) "正在找书…"
                    else "「${shelfName}」挑了 ${books.size} 本",
                    fontSize = dims.label,
                    color = LocalPalette.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (loading && books.isEmpty()) {
                item { LoadingBox("正在找书") }
            }
            if (error != null && books.isEmpty()) {
                item { HintBox(error!!, "重试") { nonce++ } }
            }

            items(books.size) { i ->
                BookCard(book = books[i], onClick = { onBook(books[i]) })
            }

            if (books.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Pill(
                            text = "换一批",
                            selected = true,
                            onClick = { if (!loading) nonce++ },
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
