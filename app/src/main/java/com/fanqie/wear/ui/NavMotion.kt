package com.fanqie.wear.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
 * ## 手势照网易云那套
 *
 * 全屏任意位置右滑（不再限定左缘几十 dp），阈值只有 [THRESHOLD_DP]，
 * 跟手是「缩小 + 平滑淡出」而不是横向位移 —— 纯缩放不会把两个页面并排挤在一起。
 * `detectHorizontalDragGestures` 自带方向锁：竖向拖动会被列表先消费、判定直接返回 null，
 * 不会抢列表滚动。
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

    /** 跟手行程：缩放 1 → 0.80，透明度 1 → 0.45 */
    const val DRAG_SCALE = 0.20f
    const val DRAG_ALPHA = 0.55f

    /** 返回阈值（dp）。手指移动这么远松手就返回 */
    const val THRESHOLD_DP = 36f
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
    /** 缩放（1 = 原始大小） */
    var scale by mutableFloatStateOf(1f)
        internal set

    /** 不透明度（1 = 完全不透明） */
    var alpha by mutableFloatStateOf(1f)
        internal set
}

/** 手势宿主：把导航控制器接进来，避免 Compose 层直接依赖它的内部结构 */
interface NavBackHost {
    fun onDragStart(thresholdPx: Float)
    fun onDrag(dx: Float)
    fun onDragEnd()
    fun onDragCancel()
}

/**
 * 右滑返回手势。
 *
 * 挂在**整页最外层**（而不是每个页面自己），保证任何时刻只有一个手势检测器：
 *  - 全屏起手，不再要求「按在左边 30dp 内」——小圆屏上根本对不准，这正是上一版"更难返回"的原因；
 *  - [detectHorizontalDragGestures] 内部先等 touchSlop 再锁方向，竖滑会被子列表消费掉，
 *    这里判定直接返回 null，不会和列表抢；
 *  - 一旦接管就 `consume`，拖动期间上层（列表）不会再收到移动事件。
 */
@Composable
fun Modifier.navSwipeBack(enabled: Boolean, host: NavBackHost): Modifier {
    if (!enabled) return this
    val threshold = with(LocalDensity.current) { NavFx.THRESHOLD_DP.dp.toPx() }
    return this.pointerInput(enabled, threshold) {
        detectHorizontalDragGestures(
            onDragStart = { host.onDragStart(threshold) },
            onDragEnd = { host.onDragEnd() },
            onDragCancel = { host.onDragCancel() },
        ) { change, dragAmount ->
            change.consume()
            host.onDrag(dragAmount)
        }
    }
}

/** 供 NavController 使用的小工具 */
internal fun smoothstep(p: Float): Float = p * p * (3f - 2f * p)

/** 启动一个页面的转场动画（缩放 + 透明度并行） */
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
