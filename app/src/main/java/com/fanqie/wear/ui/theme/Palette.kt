package com.fanqie.wear.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 自定义配色（不套用任何现成产品的视觉）。
 *
 * 底色是偏蓝的墨色渐变，卡片是半透明的玻璃层（12% 白边框 + 轻微渐变）；
 * 强调色（主题色）可从设置里换：赤陶 / 青瓷 / 靛蓝 / 琥珀 / 紫藤。
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

/** 主题色：主色 + 副色（列表图标交替使用） */
data class AccentPack(val label: String, val main: Color, val second: Color, val darkMain: Color)

val AccentPacks = listOf(
    AccentPack("赤陶", Color(0xFFE96A4F), Color(0xFF5FB3A1), Color(0xFFC94B33)),
    AccentPack("青瓷", Color(0xFF4FBFA8), Color(0xFF7E9CFF), Color(0xFF2F8A78)),
    AccentPack("靛蓝", Color(0xFF6C8CFF), Color(0xFFE4B45C), Color(0xFF4560C9)),
    AccentPack("琥珀", Color(0xFFE9A13B), Color(0xFF5FB3A1), Color(0xFFB87A1F)),
    AccentPack("紫藤", Color(0xFFA98BFF), Color(0xFFE4A0B8), Color(0xFF7A5FD0)),
)

fun paletteFor(theme: Int, accentIndex: Int = 0): Palette {
    val base = when (theme) {
        1 -> OledPalette
        2 -> PaperPalette
        else -> InkPalette
    }
    val pack = AccentPacks.getOrElse(accentIndex) { AccentPacks[0] }
    val main = if (base.isLight) pack.darkMain else pack.main
    val onAccent = if (base.isLight) Color.White else Color(0xFF120806)
    return base.copy(
        accent = main,
        accentSoft = lerp(main, base.bgTop, 0.78f),
        onAccent = onAccent,
        jade = pack.second,
    )
}

val LocalPalette = staticCompositionLocalOf { InkPalette }

/** 兼容旧调用点：强调色统一走调色板 */
val FanqieRed: Color get() = InkPalette.accent
