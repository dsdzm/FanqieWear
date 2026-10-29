package com.fanqie.wear.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.timeTextCurvedText
import com.fanqie.wear.ui.theme.LocalDisplayConfig
import com.fanqie.wear.ui.theme.LocalPalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 统一列表容器。把 Wear OS 的几条「原生手感」一次性补齐：
 *
 * 1. [ScalingLazyColumn]：滚到屏幕中央的条目自动放大、靠近上下边缘缩小（中心突出）；
 * 2. `snapFlingBehavior`：松手吸附到最近条目，不会停在两个卡片之间；
 * 3. `rotaryScrollableBehavior`：旋转表冠逐条滚动并带手感反馈；
 * 4. 中央条目变化时触发一次轻震动（可在设置里关）。
 */
@Composable
fun WearList(
    state: ScalingLazyListState = rememberScalingLazyListState(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: ScalingLazyListScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val display = LocalDisplayConfig.current

    LaunchedEffect(state, display.haptics) {
        if (!display.haptics) return@LaunchedEffect
        snapshotFlow { state.centerItemIndex }
            .distinctUntilChanged()
            .collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    // snapFlingBehavior / snapBehavior 本身就是 @Composable，内部已经 remember 住了返回值，
    // 直接调用不会每次造新对象。真正每次现造的是 scalingParams 和 autoCentering（普通函数），
    // 它们必须记住 —— 新对象会让列表认为参数变了，把可见条目全部重新测量一遍。
    val scaling = remember {
        ScalingLazyColumnDefaults.scalingParams(
            minElementHeight = 0.34f,
            maxElementHeight = 1f,
            edgeScale = 0.5f,
        )
    }
    val centering = remember { AutoCenteringParams() }

    ScalingLazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior = ScalingLazyColumnDefaults.snapFlingBehavior(state = state),
        scalingParams = scaling,
        rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(scrollableState = state),
        autoCentering = centering,
        content = content,
    )
}

/** 时间文字：跟随调色板，纸白主题下不会白字白底 */
@Composable
fun WearTimeText() {
    val color = LocalPalette.current.text
    androidx.wear.compose.material3.TimeText { time ->
        timeTextCurvedText(
            time,
            androidx.wear.compose.foundation.CurvedTextStyle(color = color, fontSize = 13.sp),
        )
    }
}
