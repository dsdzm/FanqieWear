package com.fanqie.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fanqie.wear.R
import com.fanqie.wear.app
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

@Composable
fun HomeScreen(
    onRank: () -> Unit,
    onExplore: () -> Unit,
    onShelf: () -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onContinue: (String, String, String, Source) -> Unit,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val state = rememberScalingLazyListState()
    val items = remember { app.shelf.all() }
    val last = items.firstOrNull()?.takeIf { !it.lastChapterId.isNullOrBlank() }
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sourceLabel = Source.of(settings.sourceKey).label

    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "书阁",
                        fontSize = dims.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                    )
                    Text(
                        text = buildString {
                            append("书源 ").append(sourceLabel)
                            append(if (items.isEmpty()) " · 书架还空着" else " · 书架 ${items.size} 本")
                        },
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (last != null) {
                item {
                    ContinueCard(
                        cover = last.cover,
                        title = last.title,
                        chapter = last.lastChapterTitle,
                        progress = ((last.chapterIndex + 1).coerceAtLeast(1) / 40f).coerceIn(0.05f, 1f),
                        onClick = { onContinue(last.id, last.lastChapterId!!, last.title, Source.of(last.source)) },
                    )
                }
            }

            item { SectionLabel("发现") }
            if (settings.homeGrid) {
                // 双列：一行两张，图标在上、文字在下，窄屏也能一眼看全
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dims.gridGap),
                    ) {
                        GridCard(
                            title = "分类书库",
                            icon = R.drawable.ic_rank,
                            tint = p.accent,
                            modifier = Modifier.weight(1f),
                            onClick = onRank,
                        )
                        GridCard(
                            title = "书荒广场",
                            icon = R.drawable.ic_compass,
                            tint = p.jade,
                            modifier = Modifier.weight(1f),
                            onClick = onExplore,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dims.gridGap),
                    ) {
                        GridCard(
                            title = "搜索",
                            icon = R.drawable.ic_search,
                            tint = p.jade,
                            modifier = Modifier.weight(1f),
                            onClick = onSearch,
                        )
                        GridCard(
                            title = "我的书架",
                            icon = R.drawable.ic_book,
                            tint = p.accent,
                            modifier = Modifier.weight(1f),
                            onClick = onShelf,
                        )
                    }
                }
            } else {
                item {
                    NavCard(
                        title = "分类书库",
                        subtitle = "按题材翻书 · 可加载更多",
                        icon = R.drawable.ic_rank,
                        tint = p.accent,
                        onClick = onRank,
                    )
                }
                item {
                    NavCard(
                        title = "书荒广场",
                        subtitle = "按书源随机推荐",
                        icon = R.drawable.ic_compass,
                        tint = p.jade,
                        onClick = onExplore,
                    )
                }
                item {
                    NavCard(
                        title = "搜索",
                        subtitle = "书名、作者",
                        icon = R.drawable.ic_search,
                        tint = p.jade,
                        onClick = onSearch,
                    )
                }
            }

            item { SectionLabel("书架") }
            item {
                NavCard(
                    title = "我的书架",
                    subtitle = if (items.isEmpty()) "还没有收藏" else "共 ${items.size} 本",
                    icon = R.drawable.ic_book,
                    tint = p.accent,
                    onClick = onShelf,
                )
            }
            item {
                NavCard(
                    title = "阅读历史",
                    subtitle = "上次看到的地方",
                    icon = R.drawable.ic_history,
                    tint = p.jade,
                    onClick = onHistory,
                )
            }

            item { SectionLabel("应用") }
            item {
                NavCard(
                    title = "下载与缓存",
                    subtitle = "空间与清理",
                    icon = R.drawable.ic_download,
                    tint = p.jade,
                    onClick = onDownload,
                )
            }
            item {
                NavCard(
                    title = "阅读统计",
                    subtitle = "在读书目进度",
                    icon = R.drawable.ic_person,
                    tint = p.accent,
                    onClick = onProfile,
                )
            }
            item {
                NavCard(
                    title = "设置",
                    subtitle = "外观 / 操作 / 安全区",
                    icon = R.drawable.ic_settings,
                    tint = p.jade,
                    onClick = onSettings,
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** 顶部「继续阅读」卡：封面 + 书名 + 上次章节 + 细进度条 */
@Composable
private fun ContinueCard(
    cover: String?,
    title: String,
    chapter: String,
    progress: Float,
    onClick: () -> Unit,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dims.corner + 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = if (dims.narrow) 36.dp else 42.dp, height = if (dims.narrow) 50.dp else 58.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(p.cardHigh),
                ) {
                    if (cover != null) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("继续阅读", fontSize = dims.label, color = p.accent, fontWeight = FontWeight.Medium)
                    Text(
                        text = title,
                        fontSize = dims.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = p.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!chapter.isNullOrBlank()) {
                        Text(
                            text = chapter,
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.divider),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(p.accent),
                )
            }
        }
    }
}

/** 首页双列的方形入口 */
@Composable
private fun GridCard(
    title: String,
    icon: Int,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.card)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = dims.label,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
