package com.fanqie.wear.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 页面栈转场（右滑返回）。
 *
 * 这一版彻底删掉了「PixelCopy 快照」那套机制。旧做法的两个毛病：
 *  1. 出栈后下层页面是**重新组合**的，数据要重新拉，第一帧是空白 —— 快照一撤就闪；
 *  2. 上层缩放时同时淡出，快照又从下面透出来，两页内容叠在一起，字全糊。
 *
 * 现在的做法：
 *  - 页面栈始终保留**栈顶两层**在组合树里，下层一直活着（滚动位置、已加载的数据都在），
 *    返回时它原地就位，不需要重建，也就不可能闪；
 *  - 平时下层整体平移到屏幕外（translationX = -width），既不绘制也不拦截触摸，
 *    只有转场（[active]）时才归位 0；
 *  - 上层退出时**只缩小 + 右移出屏，全程不透明**，任何一帧都是完整画面，
 *    不会再出现两个半透明页面重合。
 *
 * 数值约定：所有值都是上层/下层的直接变换量，由绘制层读取，不触发重组。
 */
@Stable
class NavMotion(private val scope: CoroutineScope) {

    /** 是否处于转场（入场动画 / 跟手 / 回弹 / 离场动画） */
    var active by mutableStateOf(false)
        private set

    /** 上层缩放 */
    var topScale by mutableFloatStateOf(1f)
        private set

    /** 上层横向位移（px），退出时一路滑到屏宽 */
    var topShift by mutableFloatStateOf(0f)
        private set

    /** 下层缩放：被压住时略小，浮上来时回到 1 */
    var belowScale by mutableFloatStateOf(1f)
        private set

    private var anim: Job? = null

    // 手势起点快照：回弹动画没走完又按下时，从当前位置接着走，不会跳回原位
    private var baseShift = 0f
    private var baseScale = 1f

    /** 新页面入场：从略小撑满。不做淡入，淡入会透出下层 */
    fun forward() {
        anim?.cancel()
        active = true
        topShift = 0f
        topScale = 0.97f
        belowScale = 1f
        anim = scope.launch {
            animate(topScale, 1f, animationSpec = tween(200, easing = EnterEase)) { v, _ -> topScale = v }
            active = false
        }
    }

    /** 开始跟手：记下当前位置作为基准，上层满屏时下层才开始被压小 */
    fun dragStart() {
        anim?.cancel()
        anim = null
        active = true
        baseShift = topShift
        baseScale = topScale
        if (topScale >= 0.999f) {
            topShift = 0f
            baseShift = 0f
        }
    }

    /** 跟手：p 为 0..1 的阈值进度 */
    fun dragTo(p: Float, maxShiftPx: Float) {
        topScale = baseScale - 0.05f * p
        topShift = baseShift + maxShiftPx * p
        // 下层同步被压小一点（0.94 封底），营造"返回页在后面等着"的层次
        belowScale = 1f - 0.06f * p
    }

    /** 没到阈值，回弹 */
    fun cancel() {
        anim?.cancel()
        anim = scope.launch {
            coroutineScope {
                launch { animate(topScale, 1f, animationSpec = tween(180, easing = EnterEase)) { v, _ -> topScale = v } }
                launch { animate(topShift, 0f, animationSpec = tween(180, easing = EnterEase)) { v, _ -> topShift = v } }
                launch { animate(belowScale, 1f, animationSpec = tween(180, easing = EnterEase)) { v, _ -> belowScale = v } }
            }
            reset()
            active = false
        }
    }

    /**
     * 过阈值：上层缩小并整体滑出右边，下层放大到 1。
     * 上层不淡出 —— 不透明地滑走才是干净的。
     */
    fun exit(widthPx: Float, onDone: () -> Unit) {
        anim?.cancel()
        active = true
        // 返回键/点返回按钮触发时（没有手势位移），给下层一个"被压着"的起点，
        // 这样上层滑走时下层会一边放大一边浮上来。手势路径则保持当前值，避免跳。
        if (topShift < 0.5f) belowScale = 0.94f
        // 从手势接过来时 belowScale 已经在 0.94~1 之间，这里不要再赋初值，
        // 否则下层会当场跳一下（此刻上层只缩了 5%，边上是看得见它的）
        anim = scope.launch {
            coroutineScope {
                launch { animate(topShift, widthPx, animationSpec = tween(220, easing = ExitEase)) { v, _ -> topShift = v } }
                launch { animate(topScale, 0.94f, animationSpec = tween(220, easing = ExitEase)) { v, _ -> topScale = v } }
                launch { animate(belowScale, 1f, animationSpec = tween(220, easing = ExitEase)) { v, _ -> belowScale = v } }
            }
            onDone()
        }
    }

    /** 出栈完成：复位。调用方已经在同一帧把上层从栈里摘掉，不会看到跳变 */
    fun settle() {
        anim?.cancel()
        anim = null
        reset()
        active = false
    }

    private fun reset() {
        topScale = 1f
        topShift = 0f
        belowScale = 1f
    }
}

/** 进场：起手快、收尾稳 */
private val EnterEase = CubicBezierEasing(0.22f, 0f, 0.16f, 1f)

/** 退场：起始斜率大，最后一段干脆落位 */
private val ExitEase = CubicBezierEasing(0.24f, 0.86f, 0.24f, 1f)

/**
 * 右滑返回手势。
 *
 * 触发条件刻意做得很严，避免"手一按左边就被当成返回"：
 *  - 必须在左缘 [edgeDp] 之内按下；
 *  - 必须先移动超过 touchSlop；
 *  - 必须明显是向右的横向手势（|dx| > |dy| * 1.6）；
 *  - 一旦发现子组件已经消费了事件（列表滚动等），立刻让路。
 *
 * 只有四条同时满足才接管并把页面交出去，其余情况一律不干预、不消费。
 */
@Composable
fun Modifier.navSwipeBack(
    enabled: Boolean,
    motion: NavMotion,
    onStart: () -> Unit,
    onCommit: () -> Unit,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val widthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val edgePx = with(density) { 30.dp.toPx() }.coerceAtMost(widthPx * 0.18f)
    val maxShift = widthPx * 0.28f
    val threshold = (widthPx * 0.26f).coerceAtLeast(with(density) { 72.dp.toPx() })
    val commit by rememberUpdatedState(onCommit)
    val start by rememberUpdatedState(onStart)

    return this.pointerInput(enabled, widthPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (down.position.x > edgePx) return@awaitEachGesture

            var dx = 0f
            var dy = 0f
            var claimed = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                // 子组件（列表滚动、阅读页翻页）已经接管了这次手势，直接让路
                if (!claimed && change.isConsumed) break

                val d = change.positionChangeIgnoreConsumed()
                dx += d.x
                dy += d.y

                if (!claimed) {
                    val slop = viewConfiguration.touchSlop
                    if (abs(dx) > slop || abs(dy) > slop) {
                        val horizontal = dx > 0f && abs(dx) > abs(dy) * 1.6f
                        if (!horizontal) break
                        claimed = true
                        start()
                    }
                }
                if (claimed) {
                    change.consume()
                    motion.dragTo((dx / threshold).coerceIn(0f, 1f), maxShift)
                }
            }

            if (claimed) {
                if (dx >= threshold) commit() else motion.cancel()
            }
        }
    }
}

/** 记住一个转场状态；页面重组不会重建 */
@Composable
fun rememberNavMotion(scope: CoroutineScope): NavMotion = remember { NavMotion(scope) }

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
