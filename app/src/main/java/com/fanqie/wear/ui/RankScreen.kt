package com.fanqie.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Category
import com.fanqie.wear.data.Gender
import com.fanqie.wear.data.RankMold
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch

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

    var gender by remember { mutableStateOf(if (initialMale) Gender.MALE else Gender.FEMALE) }
    var mold by remember { mutableStateOf(RankMold.READ) }
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var catIndex by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    val catId = cats.getOrNull(catIndex)?.id ?: -1

    LaunchedEffect(gender) {
        picking = false
        cats = runCatching { app.api.rankCategories(gender) }.getOrDefault(emptyList())
        catIndex = 0
    }

    LaunchedEffect(gender, mold, catId, reload) {
        if (cats.isEmpty()) return@LaunchedEffect
        loading = true
        error = null
        books = emptyList()
        offset = 0
        hasMore = true
        runCatching { app.api.rank(gender, mold, cats[catIndex].id, 0) }
            .onSuccess {
                books = it
                offset = it.size
                hasMore = it.size >= 15
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
            item {
                TopBar(
                    title = "排行榜",
                    onBack = onBack,
                    action = if (picking) "收起" else "分类",
                    onAction = { picking = !picking },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Gender.entries.forEach { g ->
                        Pill(g.label, selected = g == gender, onClick = { gender = g })
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    RankMold.entries.take(2).forEach { m ->
                        Pill(m.label, selected = m == mold, onClick = { mold = m })
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = cats.getOrNull(catIndex)?.name ?: "加载分类中",
                        fontSize = dims.titleSmall,
                        color = LocalPalette.current.muted,
                    )
                    if (!picking) {
                        Text(
                            text = "共 ${books.size} 本",
                            fontSize = dims.label,
                            color = LocalPalette.current.muted,
                        )
                    }
                }
            }

            if (picking) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cats.forEachIndexed { i, c ->
                            Pill(
                                text = c.name,
                                selected = i == catIndex,
                                compact = true,
                                onClick = {
                                    catIndex = i
                                    picking = false
                                },
                            )
                        }
                    }
                }
            } else {
                if (loading && books.isEmpty()) {
                    item { LoadingBox("正在取榜单") }
                }
                if (error != null && books.isEmpty()) {
                    item { HintBox(error!!, "重试") { reload++ } }
                }
                items(books.size) { i ->
                    BookCard(
                        book = books[i],
                        index = i + 1,
                        showCover = false,
                        onClick = { onBook(books[i]) },
                    )
                }
                if (books.isNotEmpty() && hasMore) {
                    item {
                        Pill(text = "加载更多", onClick = {
                            if (!loading) {
                                loading = true
                                scope.launch {
                                    runCatching { app.api.rank(gender, mold, cats[catIndex].id, offset) }
                                        .onSuccess {
                                            books = books + it
                                            offset += it.size
                                            hasMore = it.isNotEmpty()
                                        }
                                        .onFailure { error = it.message }
                                    loading = false
                                }
                            }
                        })
                    }
                }
                if (books.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
