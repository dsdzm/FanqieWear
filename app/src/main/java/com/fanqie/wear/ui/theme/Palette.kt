package com.fanqie.wear.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 自定义配色（不套用任何现成产品的视觉）。
 *
 * 主色取「赤陶」#E96A4F，副色「青瓷」#5FB3A1，底子是偏蓝的墨色渐变，
 * 卡片是半透明的玻璃层（12% 白边框 + 轻微渐变），在 OLED 上层次清楚又不刺眼。
 */
@Immutable
data class Palette(
    val bgTop: Color,
    val bgBottom: Color,
    val card: Color,
    val cardHigh: Color,
    val cardBorder: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val jade: Color,
    val divider: Color,
    val isLight: Boolean,
)

val InkPalette = Palette(
    bgTop = Color(0xFF0A1014),
    bgBottom = Color(0xFF121E25),
    card = Color(0xFF16232B),
    cardHigh = Color(0xFF1D2D36),
    cardBorder = Color(0x1FFFFFFF),
    text = Color(0xFFE9F0F2),
    muted = Color(0xFF8FA2AB),
    accent = Color(0xFFE96A4F),
    accentSoft = Color(0xFF3A211C),
    onAccent = Color(0xFF160B08),
    jade = Color(0xFF5FB3A1),
    divider = Color(0x14FFFFFF),
    isLight = false,
)

val OledPalette = InkPalette.copy(
    bgTop = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    card = Color(0xFF0D1012),
    cardHigh = Color(0xFF15191C),
    cardBorder = Color(0x1AFFFFFF),
)

val PaperPalette = Palette(
    bgTop = Color(0xFFF5F2EB),
    bgBottom = Color(0xFFEAE5DA),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFF0ECE3),
    cardBorder = Color(0x14000000),
    text = Color(0xFF1B1F21),
    muted = Color(0xFF6B767B),
    accent = Color(0xFFC94B33),
    accentSoft = Color(0xFFF7DED6),
    onAccent = Color(0xFFFFFFFF),
    jade = Color(0xFF2F7F70),
    divider = Color(0x12000000),
    isLight = true,
)

fun paletteFor(theme: Int): Palette = when (theme) {
    1 -> OledPalette
    2 -> PaperPalette
    else -> InkPalette
}

val LocalPalette = staticCompositionLocalOf { InkPalette }

/** 兼容旧调用点：强调色统一走调色板 */
val FanqieRed: Color get() = InkPalette.accent
