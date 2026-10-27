package com.fanqie.wear.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.fanqie.wear.data.ShelfItem
import com.fanqie.wear.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

@Composable
fun ShelfScreen(
    onBack: () -> Unit,
    onBook: (String, String, Source) -> Unit,
) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    var items by remember { mutableStateOf(app.shelf.all()) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<String?>(null) }

    // 选一个 txt 导入；解析、切章、写入都放 IO 线程，进度用一行文字反馈
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(uri) ?: "本地书.txt"
        importing = "导入中…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val book = app.sources.localLibrary.import(uri, name)
                    app.shelf.add(
                        ShelfItem(
                            id = book.id,
                            title = book.title,
                            author = book.author,
                            lastChapterId = "0",
                            lastChapterTitle = "",
                            updatedAt = System.currentTimeMillis(),
                            source = Source.LOCAL.key,
                        ),
                    )
                    book
                }
            }
            importing = null
            result.onSuccess {
                items = app.shelf.all()
                Toast.makeText(ctx, "已导入《${it.title}》${it.chapterCount} 章", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
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
            item { TopBar(title = "书架 ${items.size}", onBack = onBack) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalPalette.current.card)
                        .clickable(enabled = importing == null) {
                            picker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (importing != null) importing ?: "导入中…" else "＋ 导入本地书",
                        fontSize = dims.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = LocalPalette.current.accent,
                        maxLines = 1,
                    )
                    Text(
                        text = "选一个 txt，自动识别编码并分章",
                        fontSize = dims.label,
                        color = LocalPalette.current.muted,
                        maxLines = 1,
                    )
                }
            }

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
                        .clickable { onBook(it.id, it.title, Source.of(it.source)) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = it.title,
                            fontSize = dims.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MiniTag(Source.of(it.source).label, LocalPalette.current.jade)
                    }
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

/** 取文件的显示名：SAF 的 uri 直接读不到文件名，要查 OpenableColumns */
private fun queryName(uri: Uri): String? = runCatching {
    val ctx = app
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
