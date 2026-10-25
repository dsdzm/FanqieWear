package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.app
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.FanqieRed
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBook: (Book) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var keyword by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(Source.FANQIE) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var trigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(trigger) {
        if (trigger == 0 || keyword.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        runCatching {
            when (source) {
                Source.FANQIE -> app.api.search(keyword)
                Source.QIMAO -> app.qimao.search(keyword)
            }
        }.onSuccess {
            books = it
            searched = true
        }.onFailure { error = it.message ?: "搜索失败" }
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
            item { TopBar(title = "搜索", onBack = onBack) }

            item {
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

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Pill("番茄", selected = source == Source.FANQIE, onClick = { source = Source.FANQIE })
                    Pill("七猫", selected = source == Source.QIMAO, onClick = { source = Source.QIMAO })
                }
            }

            item {
                Pill("开始搜索", selected = true, onClick = { trigger++ })
            }

            if (loading) item { LoadingBox("正在搜索") }
            if (error != null) item { HintBox(error!!, "重试") { trigger++ } }
            if (!loading && searched && books.isEmpty() && error == null) {
                item { HintBox("没有找到相关作品") }
            }

            items(books.size) { i ->
                BookCard(book = books[i], onClick = { onBook(books[i]) })
            }

            if (books.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
