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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Gender
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.launch
import kotlin.random.Random

private val QIMAO_HOT_WORDS = listOf("总裁", "玄幻", "穿越", "甜宠", "悬疑", "战神", "重生", "无敌")

/**
 * 书荒广场：番茄 / 七猫 双源。
 * 番茄有官方推荐流，七猫没有榜单接口，用「热门题材关键词 + 搜索」凑出一条推书流。
 */
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onBook: (Book) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(if (app.settings.value.defaultSource == 1) Source.QIMAO else Source.FANQIE) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var keyword by remember { mutableStateOf(QIMAO_HOT_WORDS.first()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var nonce by remember { mutableIntStateOf(0) }
    val gender = remember { Gender.MALE }

    LaunchedEffect(source, keyword, nonce) {
        loading = true
        error = null
        books = emptyList()
        runCatching {
            when (source) {
                Source.FANQIE -> app.api.recommend(gender, Random.nextInt(0, 60))
                Source.QIMAO -> app.qimao.search(keyword)
            }
        }.onSuccess { books = it }
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

            // 双源切换
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dims.gridGap),
                ) {
                    SourceTab("番茄", source == Source.FANQIE, Modifier.weight(1f)) {
                        source = Source.FANQIE
                    }
                    SourceTab("七猫", source == Source.QIMAO, Modifier.weight(1f)) {
                        source = Source.QIMAO
                        if (books.isEmpty()) nonce++
                    }
                }
            }

            // 七猫：题材关键词
            if (source == Source.QIMAO) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        QIMAO_HOT_WORDS.take(3).forEach { w ->
                            Pill(text = w, selected = w == keyword, onClick = { keyword = w })
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (loading) "正在找书…" else "热门推荐 · ${books.size} 本",
                    fontSize = dims.label,
                    color = LocalPalette.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!loading && source == Source.FANQIE) {
                item {
                    TopicCard(onClick = {
                        if (!loading) nonce++
                    })
                }
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
                            onClick = {
                                if (!loading) {
                                    if (source == Source.QIMAO) {
                                        keyword = QIMAO_HOT_WORDS.random()
                                    }
                                    nonce++
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

@Composable
private fun SourceTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dims = rememberDeviceDims()
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0xFF1B3A5C) else LocalPalette.current.card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = dims.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else LocalPalette.current.muted,
        )
    }
}

@Composable
private fun TopicCard(onClick: () -> Unit) {
    val dims = rememberDeviceDims()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (dims.narrow) 16.dp else 20.dp))
            .background(Color(0xFF5A2F44))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (dims.narrow) 24.dp else 28.dp)
                .clip(CircleShape)
                .background(Color(0xFF7A4A60)),
            contentAlignment = Alignment.Center,
        ) {
            Text("💬", fontSize = dims.label)
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "官方书荒广场 · 话题",
                fontSize = dims.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "点一下换一批话题书单",
                fontSize = dims.label,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        Text("›", fontSize = dims.titleMedium, color = Color.White.copy(alpha = 0.7f))
    }
}
