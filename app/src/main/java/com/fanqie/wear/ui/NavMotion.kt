package com.fanqie.wear.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 页面转场（右滑返回 + 入栈/出栈动画）。
 *
 * ## 为什么改成「每页一个状态」
 *
 * 旧版整个应用只有**一个** [NavMotion]，上层的缩放/位移/透明度全放在里面。
 * 出栈那一帧栈顶少了一个，原来那个「下层」变成了「栈顶」，同一个 graphicsLayer
 * 就从读 `belowScale` 切换成读 `topScale` —— 而此刻 `topScale` 还是**正在退出的那一页**
 * 留下的值（0.94），于是保留下来的页面当场被缩了一下，下一帧才被复位。
 * 真机上看到的就是「返回完成，所有控件闪一下」。
 *
 * 现在每个页面（栈条目）持有自己的 [PageFx]，状态跟着页面走、永不串位：
 *  - 入场：只有新页从 0.94 放大到 1，旧页一动不动（被完全盖住，不需要处理）；
 *  - 出场：只有要走的那页缩小并淡出，**下层页面 scale / alpha 恒为 1、不做任何变换**；
 *  - 出栈被移除的那一刻，屏幕上本来就是下层页面 1:1 的完整画面，没有一帧需要"补"，
 *    所以既不会闪，也不会出现两页内容叠在一起。
 *
 * ## 手势照网易云那套，但拖动过程完全不透
 *
 * 全屏任意位置右滑（不再限定左缘几十 dp），阈值只有 [NavFx.THRESHOLD_DP]。
 * 但跟手不再是「缩小 + 淡出」—— 那会让栈顶页变半透明，直接透出下面那一页的内容，
 * 误触时非常突兀。现在拖动只做**水平橡皮筋位移**（[NavFx.DRAG_MAX_DP] 封顶、带阻尼），
 * 缩放恒为 1、不透明度恒为 1、下层不绘制：整段拖动画面里只有这一页，透不出任何东西。
 * 松手过阈值才播真正的退场（缩到 0.5 并淡出），那一刻才把下层露出来。
 */
internal object NavFx {
    /** 入栈：新页从这里放大到 1 */
    const val ENTER_SCALE = 0.94f
    const val ENTER_MS = 180

    /** 出栈：退场页收向这里（网易云退页实测收到 50%） */
    const val EXIT_SCALE = 0.50f
    const val EXIT_MS = 190

    /** 没到阈值的回弹 */
    const val SPRING_MS = 150

    /** 跟手行程：x 位移最多这么远（dp），带阻尼，误触时只是轻轻一动 */
    const val DRAG_MAX_DP = 30f
    const val DRAG_DAMP = 0.55f

    /** 返回阈值（dp）。手指累计右移这么远松手就返回 */
    const val THRESHOLD_DP = 34f

    /** 横向优先倍数：|dx| 必须大于 |dy| 的这个倍数才认作返回手势，斜着划列表不会误触 */
    const val DIR_LOCK = 1.8f
}

/** 入栈曲线：起手快、收尾稳 */
private val EnterEase = CubicBezierEasing(0.22f, 0f, 0.16f, 1f)

/**
 * 出栈曲线：起始斜率约 5.9 —— 一松手就迅速收掉大半行程，最后一段干脆落位。
 * 起点平缓的缓动（比如 ease-in-out）在真机上读起来就是「犹豫」。
 */
private val ExitEase = CubicBezierEasing(0.16f, 0.94f, 0.26f, 1f)

/**
 * 单个页面的转场状态。**属于页面本身，不共享**。
 *
 * 三个值都在绘制阶段被 `graphicsLayer` 读取（mutableFloatStateOf），
 * 拖动时只触发重绘，不触发重组。
 */
@Stable
class PageFx {
    /** 缩放（1 = 原始大小）。只在入栈/退场时变，拖动时**不动** —— 缩放会露出四周，看着像页面碎了 */
    var scale by mutableFloatStateOf(1f)
        internal set

    /** 不透明度（1 = 完全不透明）。只有退场才淡出，拖动时不淡 */
    var alpha by mutableFloatStateOf(1f)
        internal set

    /** 拖动时的水平位移（px），橡皮筋式，上限 [NavFx.DRAG_MAX_DP] */
    var shiftX by mutableFloatStateOf(0f)
        internal set
}

/** 手势宿主：把导航控制器接进来，避免 Compose 层直接依赖它的内部结构 */
interface NavBackHost {
    fun onDragStart(thresholdPx: Float)
    fun onDrag(dx: Float, maxShift: Float)
    fun onDragEnd()
    fun onDragCancel()
}

/**
 * 右滑返回手势。
 *
 * 挂在**整页最外层**（而不是每个页面自己），保证任何时刻只有一个手势检测器：
 *  - 全屏起手，不要求「按在左边 30dp 内」—— 小圆屏上根本对不准；
 *  - 方向锁比默认更严（[NavFx.DIR_LOCK] 倍）：手指移动超过 touchSlop 后，
 *    必须「横向位移 > 竖向位移 × 1.8」才算返回手势，否则立刻放手让列表继续滚。
 *    斜着划列表被误判成返回，正是上一版"按一下左边就退出去"的来源；
 *  - 锁定之前**一个事件都不消费**，所以列表滚动完全不受影响；
 *  - 锁定之后才 `consume`，拖动期间上层（列表）收不到移动事件。
 */
@Composable
fun Modifier.navSwipeBack(enabled: Boolean, host: NavBackHost): Modifier {
    if (!enabled) return this
    val threshold = with(LocalDensity.current) { NavFx.THRESHOLD_DP.dp.toPx() }
    val maxShift = with(LocalDensity.current) { NavFx.DRAG_MAX_DP.dp.toPx() }
    return this.pointerInput(enabled, threshold, maxShift) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var totalX = 0f
            var totalY = 0f
            var locked = false
            var pointer = down

            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == pointer.id } ?: break
                if (ch.changedToUpIgnoreConsumed()) break
                val dx = ch.positionChange().x
                val dy = ch.positionChange().y

                if (!locked) {
                    totalX += dx
                    totalY += dy
                    if (abs(totalX) + abs(totalY) > slop) {
                        // 只认「向右、且明显偏横」的拖动；竖划、斜划一律放行给列表
                        val horizontal = abs(totalX) > abs(totalY) * NavFx.DIR_LOCK
                        if (totalX > 0f && horizontal) {
                            locked = true
                            host.onDragStart(threshold)
                            host.onDrag(totalX, maxShift)
                            ch.consume()
                        } else {
                            break
                        }
                    }
                } else {
                    if (ch.pressed) {
                        ch.consume()
                        host.onDrag(dx, maxShift)
                    }
                }
                if (!ch.pressed) break
            }
            if (locked) host.onDragEnd()
        }
    }
}

/** 启动一个页面的转场动画（缩放 + 透明度 + 位移并行） */
internal fun animatePage(
    scope: kotlinx.coroutines.CoroutineScope,
    jobs: MutableMap<Long, Job>,
    id: Long,
    fx: PageFx,
    toScale: Float,
    toAlpha: Float,
    ms: Int,
    easing: Easing,
    onDone: (() -> Unit)? = null,
) {
    jobs.remove(id)?.cancel()
    jobs[id] = scope.launch {
        coroutineScope {
            launch {
                animate(fx.scale, toScale, animationSpec = tween(ms, easing = easing)) { v, _ ->
                    fx.scale = v
                }
            }
            launch {
                animate(fx.alpha, toAlpha, animationSpec = tween(ms, easing = easing)) { v, _ ->
                    fx.alpha = v
                }
            }
            launch {
                val from = fx.shiftX
                if (from != 0f) {
                    animate(from, 0f, animationSpec = tween(ms, easing = easing)) { v, _ ->
                        fx.shiftX = v
                    }
                }
            }
        }
        onDone?.invoke()
    }
}

internal val enterEase: Easing get() = EnterEase
internal val exitEase: Easing get() = ExitEase

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
