package com.fanqie.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

/**
 * 全局主题。字体只给 M3 组件兜底，业务界面统一用 DeviceDims 的字号，
 * 这样「界面缩放」「安全区」两个设置才能真正生效。
 */
@Composable
fun FanqieWearTheme(theme: Int = 0, accent: Int = 0, content: @Composable () -> Unit) {
    val palette = paletteFor(theme, accent)
    CompositionLocalProvider(
        LocalPalette provides palette,
        androidx.wear.compose.material3.LocalContentColor provides palette.text,
    ) {
        MaterialTheme(
            typography = WearTypography,
            content = content,
        )
    }
}

private val WearTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 9.sp, lineHeight = 11.sp),
)
