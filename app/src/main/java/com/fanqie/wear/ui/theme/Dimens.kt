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

/** 界面缩放：字号、卡片高度、图标一起放大，避免只放大文字把布局挤爆 */
enum class UiScale(val label: String, val factor: Float) {
    STANDARD("标准", 1f),
    LARGE("大", 1.18f),
    XL("超大", 1.38f),
    XXL("特大", 1.6f),
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
 * 字号不再写死：以 168dp 宽的 320×320 圆表为基准，屏越宽字号越大（0.92×～1.6×），
 * 再叠加用户在设置里选的「界面缩放」。320 表上字不挤，480 表上字不糊。
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
    val uiScale: UiScale,
    val titleLarge: TextUnit,
    val titleMedium: TextUnit,
    val titleSmall: TextUnit,
    val body: TextUnit,
    val label: TextUnit,
) {
    /**
     * 列表统一内边距。
     *
     * 用 lazy 而不是 get()：`PaddingValues` 没有 equals，每次取值都造新对象的话，
     * 列表会认为内边距变了、把可见条目全部重新测量 —— 滚动时肉眼可见地掉帧。
     */
    val listPadding: PaddingValues by lazy {
        PaddingValues(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding)
    }

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
        // 出厂边距：圆屏按宽度的 7.8%，方屏 5.8%
        val base = width * (if (round) 0.078f else 0.058f)
        val side = (base * display.safeArea.factor).toFloat().coerceIn(3f, width * 0.22f).dp
        // 以 320×320（160dp）为基准，按屏宽放大字号
        val screenScale = (width / 168f).coerceIn(0.92f, 1.6f)
        val s = screenScale * display.uiScale.factor
        DeviceDims(
            isRound = round,
            widthDp = width,
            heightDp = height,
            narrow = narrow,
            sidePadding = side,
            topPadding = (side * 0.85f).coerceAtLeast(2.dp),
            bottomPadding = (side * 1.1f).coerceIn(2.dp, 30.dp),
            gridGap = (if (narrow) 6.dp else 8.dp) * s,
            cardHeight = (if (narrow) 54.dp else 62.dp) * s,
            iconBubble = (if (narrow) 33.dp else 38.dp) * s,
            iconSize = (if (narrow) 17.dp else 20.dp) * s,
            buttonHeight = (if (narrow) 40.dp else 46.dp) * s,
            corner = (if (narrow) 18.dp else 22.dp) * s,
            safeArea = display.safeArea,
            uiScale = display.uiScale,
            titleLarge = (18f * s).sp,
            titleMedium = (15f * s).sp,
            titleSmall = (13.5f * s).sp,
            body = (12.5f * s).sp,
            label = (11f * s).sp,
        )
    }
}
