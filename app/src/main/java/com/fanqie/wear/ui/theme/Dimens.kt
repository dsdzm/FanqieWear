package com.fanqie.wear.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 安全区档位：手表圆角/弧边差异极大，交给用户自己调最靠谱。
 * AUTO 会根据圆屏/方屏给出厂值，其余档位在出厂值上乘以系数。
 */
enum class SafeArea(val label: String, val factor: Float) {
    AUTO("自动", 1f),
    COMPACT("紧凑", 0.5f),
    NORMAL("标准", 1.0f),
    ROOMY("宽松", 1.55f),
    WIDEST("最宽", 2.1f),
    ;

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { AUTO }
    }
}

/** 界面缩放：字号与卡片高度一起放大，避免只放大文字把布局挤爆 */
enum class UiScale(val label: String, val factor: Float) {
    STANDARD("标准", 1f),
    LARGE("大", 1.12f),
    XL("超大", 1.25f),
    ;

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { STANDARD }
    }
}

/** 全局显示配置，由 Nav 从设置里注入，任何屏幕都能读到 */
@Immutable
data class DisplayConfig(
    val safeArea: SafeArea = SafeArea.AUTO,
    val uiScale: UiScale = UiScale.STANDARD,
    val showCover: Boolean = true,
    val haptics: Boolean = true,
)

val LocalDisplayConfig = staticCompositionLocalOf { DisplayConfig() }

/**
 * 屏幕适配参数。
 *
 * 320×320@2x 的圆表只有 160dp 可用宽，圆形屏两侧还会被弧线切掉，
 * 所以边距不能写死，必须按「宽度比例 × 安全区档位」算。
 */
@Immutable
data class DeviceDims(
    val isRound: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    val narrow: Boolean,
    val sidePadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val gridGap: Dp,
    val cardHeight: Dp,
    val iconBubble: Dp,
    val iconSize: Dp,
    val buttonHeight: Dp,
    val corner: Dp,
    val safeArea: SafeArea,
    val titleLarge: TextUnit,
    val titleMedium: TextUnit,
    val titleSmall: TextUnit,
    val body: TextUnit,
    val label: TextUnit,
) {
    /** 列表统一内边距 */
    val listPadding: PaddingValues
        get() = PaddingValues(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding)

    /** 内切圆半径：圆屏时用来判断内容是否越界 */
    val innerRadius: Dp get() = (widthDp / 2).dp - sidePadding
}

@Composable
fun rememberDeviceDims(): DeviceDims {
    val cfg = LocalConfiguration.current
    val display = LocalDisplayConfig.current
    val width = cfg.screenWidthDp
    val height = cfg.screenHeightDp
    return remember(width, height, cfg.isScreenRound, display) {
        val narrow = width <= 190
        val round = cfg.isScreenRound
        // 出厂边距：圆屏按宽度的 7.5%，方屏 4.5%
        val base = width * (if (round) 0.078f else 0.058f)
        val side = (base * display.safeArea.factor).toFloat().coerceIn(3f, width * 0.22f).dp
        val s = display.uiScale.factor
        DeviceDims(
            isRound = round,
            widthDp = width,
            heightDp = height,
            narrow = narrow,
            sidePadding = side,
            topPadding = (side * 0.85f).coerceAtLeast(2.dp),
            bottomPadding = (side * 1.1f).coerceIn(2.dp, 26.dp),
            gridGap = (if (narrow) 6.dp else 8.dp) * s,
            cardHeight = ((if (narrow) 52.dp else 60.dp) * s),
            iconBubble = ((if (narrow) 32.dp else 38.dp) * s),
            iconSize = ((if (narrow) 17.dp else 20.dp) * s),
            buttonHeight = ((if (narrow) 40.dp else 46.dp) * s),
            corner = ((if (narrow) 18.dp else 22.dp) * s),
            safeArea = display.safeArea,
            titleLarge = (if (narrow) 17f else 20f).times(s).sp,
            titleMedium = (if (narrow) 14f else 16f).times(s).sp,
            titleSmall = (if (narrow) 12.5f else 14f).times(s).sp,
            body = (if (narrow) 11.5f else 13f).times(s).sp,
            label = (if (narrow) 10f else 11f).times(s).sp,
        )
    }
}
