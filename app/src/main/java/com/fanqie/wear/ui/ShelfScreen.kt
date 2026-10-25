package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.app
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

@Composable
fun ShelfScreen(
    onBack: () -> Unit,
    onBook: (String, String) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var items by remember { mutableStateOf(app.shelf.all()) }

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
            item { TopBar(title = "书架 ${items.size}", onBack = onBack) }

            if (items.isEmpty()) {
                item { HintBox("书架还空着\n去排行榜挑一本吧") }
            }

            items(items.size) { i ->
                val it = items[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalPalette.current.card)
                        .clickable { onBook(it.id, it.title) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = it.title,
                        fontSize = dims.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (it.lastChapterTitle.isNotBlank()) {
                        Text(
                            text = "读到：${it.lastChapterTitle}",
                            fontSize = dims.label,
                            color = LocalPalette.current.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Pill(text = "刷新", onClick = { items = app.shelf.all() })
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
