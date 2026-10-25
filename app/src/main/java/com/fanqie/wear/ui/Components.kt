package com.fanqie.wear.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.fanqie.wear.data.Book
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.DeviceDims
import com.fanqie.wear.ui.theme.LocalDisplayConfig
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.rememberDeviceDims

/**
 * 卡片底：玻璃质感的圆角块。
 * 视觉签名 = 竖向渐变 + 1dp 高光描边 + 左侧圆形图标，不用任何现成 App 的版式。
 */
@Composable
fun GlassCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val haptic = LocalHapticFeedback.current
    val enabled = LocalDisplayConfig.current.haptics
    val corner = shape ?: RoundedCornerShape(dims.corner)
    Box(
        modifier = modifier
            .clip(corner)
            .background(Brush.verticalGradient(listOf(p.cardHigh, p.card)))
            .border(1.dp, p.cardBorder, corner)
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        if (enabled) haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        onClick()
                    }
                } else Modifier,
            ),
    ) {
        content()
    }
}

/** 圆形图标位：淡色底 + 纯色图标，比整块彩色方块轻很多 */
@Composable
fun IconBubble(@DrawableRes icon: Int, tint: Color = LocalPalette.current.jade, size: Dp? = null) {
    val dims = rememberDeviceDims()
    val box = size ?: dims.iconBubble
    Box(
        modifier = Modifier
            .size(box)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(dims.iconSize),
        )
    }
}

/** 页面标题条：左侧圆形返回 + 标题，动作放在右侧 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(dims.buttonHeight * 0.92f)
                    .clip(CircleShape)
                    .background(p.cardHigh)
                    .border(1.dp, p.cardBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = dims.titleMedium, color = p.text)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            fontSize = dims.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.width(6.dp))
            Pill(text = action, onClick = onAction, compact = true)
        }
    }
}

/** 胶囊：手表上比 Button 更省高度，最小触控高度 30dp */
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp? = null,
    compact: Boolean = false,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    val bg = if (selected) p.accent else p.cardHigh
    val fg = if (selected) p.onAccent else p.text
    Box(
        modifier = modifier
            .then(if (minWidth != null) Modifier.width(minWidth) else Modifier)
            .heightIn(min = if (compact) 28.dp else 32.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else p.cardBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = dims.label,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 全宽导航卡片：图标 + 标题 + 副标题 + 箭头（首页/设置主入口都用它） */
@Composable
fun NavCard(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = LocalPalette.current.jade,
    trailing: String? = null,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    GlassCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dims.cardHeight),
        shape = RoundedCornerShape(dims.cardHeight / 2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(icon = icon, tint = tint)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = dims.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    fontSize = dims.label,
                    color = p.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Text("›", fontSize = dims.titleMedium, color = p.muted, modifier = Modifier.padding(end = 4.dp))
            }
        }
    }
}

/** 书籍行：封面（可关）+ 书名 + 作者 + 标签 */
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    index: Int = 0,
    showCover: Boolean = LocalDisplayConfig.current.showCover,
) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    GlassCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCover) {
                CoverBox(book.cover, dims)
                Spacer(Modifier.width(9.dp))
            } else if (index > 0) {
                Text(
                    text = "$index",
                    fontSize = dims.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (index <= 3) p.accent else p.muted,
                    modifier = Modifier.width(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title.ifBlank { "未知书名" },
                    fontSize = dims.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = buildLine(book)
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    if (book.source == Source.QIMAO) MiniTag("七猫", p.jade)
                    if (book.category.isNotBlank()) MiniTag(book.category.take(4), p.accent.copy(alpha = 0.75f))
                    if (book.score.isNotBlank()) {
                        Text("★${book.score}", fontSize = dims.label, color = p.accent, maxLines = 1)
                    }
                    if (book.finished) {
                        Text("完结", fontSize = dims.label, color = p.muted, maxLines = 1)
                    }
                }
            }
            Text("›", fontSize = dims.titleMedium, color = p.muted, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

private fun buildLine(book: Book): String {
    val parts = mutableListOf<String>()
    if (book.author.isNotBlank()) parts += book.author
    if (book.wordCount.isNotBlank()) parts += book.wordCount
    return parts.joinToString(" · ")
}

@Composable
fun CoverBox(url: String?, dims: DeviceDims) {
    val w = if (dims.narrow) 30.dp else 36.dp
    val h = if (dims.narrow) 42.dp else 50.dp
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .clip(RoundedCornerShape(6.dp))
            .background(LocalPalette.current.cardHigh),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MiniTag(text: String, color: Color) {
    val dims = rememberDeviceDims()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text = text, fontSize = dims.label, color = color, maxLines = 1)
    }
}

/** 设置入口行（保留旧签名，内部换成新卡片样式） */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    NavCard(title = title, icon = icon, subtitle = subtitle, tint = tint, onClick = onClick)
}

@Composable
fun LoadingBox(text: String = "加载中") {
    val dims = rememberDeviceDims()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = dims.label, color = LocalPalette.current.muted)
    }
}

@Composable
fun HintBox(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val dims = rememberDeviceDims()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            fontSize = dims.body,
            color = LocalPalette.current.muted,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Pill(text = action, selected = true, onClick = onAction)
        }
    }
}

/** 小标题：左侧一根 3dp 的强调竖条 + 文字 */
@Composable
fun SectionLabel(text: String) {
    val dims = rememberDeviceDims()
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = (dims.label.value * 1.1f).dp)
                .clip(CircleShape)
                .background(p.accent),
        )
        Spacer(Modifier.width(5.dp))
        Text(text = text, fontSize = dims.label, color = p.muted, maxLines = 1)
    }
}
