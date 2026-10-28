package com.fanqie.wear.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 页面栈转场（右滑返回）。
 *
 * 旧实现把 `translationX` 挂在整屏根布局上、下层又没有内容可露，
 * 所以右滑看起来就是「整个应用窗口跟着手指走」——在定制 ROM 的手表上尤其像系统在拖窗口。
 *
 * 现在改成：
 *  - 上层页面**缩小 + 轻微右移**，不整屏平移；
 *  - 下层画的是「返回后看到的页面」的**快照**，逐像素还原被遮住的画面；
 *  - 全程只组合一个页面（快照是位图），手表不会为了转场多渲染一整页。
 *
 * 数值约定：
 *  - [keep] 1 = 页面完全展开，0 = 收到最小并淡出；
 *  - [shift] 上层右移像素（跟手，回弹时归零）；
 *  - [fade] 上层不透明度（只在真正离场时淡出，跟手阶段保持 1）。
 */
@Stable
class NavMotion(private val scope: CoroutineScope) {

    /** 下层页面快照；null 表示没有（栈底、或截图失败），此时只露背景 */
    var below by mutableStateOf<ImageBitmap?>(null)

    /** 是否正在转场：转场期间才画下层，平时不白画一层位图 */
    var active by mutableStateOf(false)
        private set

    var keep by mutableFloatStateOf(1f)
        private set
    var shift by mutableFloatStateOf(0f)
        private set
    var fade by mutableFloatStateOf(1f)
        private set

    private var anim: Job? = null

    /** 上层缩放：1 → 0.66（跟手）→ 0.52（离场） */
    val topScale: Float get() = 0.52f + 0.48f * keep

    /** 下层缩放：被盖住时略小，露出来时回到 1 */
    val belowScale: Float get() = 0.94f + 0.06f * (1f - keep)

    /** 下层圆角（跟手越深越圆，看起来像上面那张卡浮起来） */
    val belowRadiusDp: Float get() = 10f + 8f * (1f - keep)

    /**
     * 新页面入场：从略小撑到满屏。
     * 不做淡入 —— 淡入期间会透出下层，看起来像闪一下背景。
     */
    fun forward() {
        anim?.cancel()
        active = true
        keep = 0.86f
        fade = 1f
        shift = 0f
        anim = scope.launch {
            animate(keep, 1f, animationSpec = tween(230, easing = EnterEase)) { v, _ -> keep = v }
            active = false
        }
    }

    /** 开始右滑：清掉上一段动画，准备跟手 */
    fun dragStart() {
        anim?.cancel()
        anim = null
        active = true
        fade = 1f
        shift = 0f
        keep = 1f
    }

    /** 跟手：p 为 0..1 的阈值进度 */
    fun dragTo(p: Float, maxShiftPx: Float) {
        keep = 1f - 0.34f * p
        // 抓不到下层快照时不做位移，只缩小淡出 —— 否则又像"整块窗口在滑"
        shift = if (below != null) maxShiftPx * p else 0f
        fade = 1f
    }

    /** 没到阈值，回弹 */
    fun cancel() {
        anim?.cancel()
        anim = scope.launch {
            coroutineScope {
                launch { animate(keep, 1f, animationSpec = tween(170, easing = EnterEase)) { v, _ -> keep = v } }
                launch { animate(shift, 0f, animationSpec = tween(170)) { v, _ -> shift = v } }
            }
            active = false
        }
    }

    /** 过阈值：缩着淡出，结束后再真正出栈 */
    fun exit(onDone: () -> Unit) {
        anim?.cancel()
        active = true
        anim = scope.launch {
            coroutineScope {
                launch { animate(keep, 0f, animationSpec = tween(190, easing = ExitEase)) { v, _ -> keep = v } }
                launch { animate(fade, 0f, animationSpec = tween(160)) { v, _ -> fade = v } }
            }
            onDone()
        }
    }

    /** 出栈完成：立刻复位，新页面顶上来时正好是 1.0/1.0，不会闪 */
    fun settle() {
        anim?.cancel()
        anim = null
        keep = 1f
        fade = 1f
        shift = 0f
        active = false
    }
}

/** 起手快、收尾稳 */
private val EnterEase = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)

/** 退出果断：起始斜率大，最后一段干脆落位 */
private val ExitEase = CubicBezierEasing(0.16f, 0.94f, 0.26f, 1f)

/**
 * 右滑返回手势。
 *
 * 只从屏幕左缘 56dp 内起手才接管（避免和列表滚动、阅读页翻页打架），
 * 滑过屏宽 30% 触发返回。手势只驱动 [NavMotion]，不再改布局的 translationX。
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
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val startZonePx = widthPx * 0.45f
    val maxShift = with(density) { 30.dp.toPx() }
    val threshold = (widthPx * 0.30f).coerceAtLeast(with(density) { 90.dp.toPx() })
    val commit by rememberUpdatedState(onCommit)
    val start by rememberUpdatedState(onStart)

    return this.pointerInput(enabled, widthPx) {
        var fromEdge = false
        var accum = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                fromEdge = offset.x <= startZonePx
                accum = 0f
                if (fromEdge) start()
            },
            onDragEnd = {
                if (fromEdge) {
                    if (accum >= threshold) commit() else motion.cancel()
                }
                fromEdge = false
                accum = 0f
            },
            onDragCancel = {
                if (fromEdge) motion.cancel()
                fromEdge = false
                accum = 0f
            },
        ) { change, dragAmount ->
            if (!fromEdge) return@detectHorizontalDragGestures
            // 往回滑就当成放弃：进度不倒退到负值，但也不锁死在 1
            accum = (accum + dragAmount).coerceIn(0f, threshold)
            change.consume()
            motion.dragTo(accum / threshold, maxShift)
        }
    }
}

/** 记住一个转场状态；页面重组不会重建 */
@Composable
fun rememberNavMotion(scope: CoroutineScope): NavMotion = remember { NavMotion(scope) }

/**
 * 抓一张当前窗口的画面，用作转场的"下层页面"。
 *
 * 为什么不用 Compose 的 GraphicsLayer 录制：那等于给整页加一层离屏纹理，
 * 每帧都要多渲染一遍，手表上会直接吃掉帧率 —— 用户刚反馈过列表卡。
 * PixelCopy 只在真正要转场时抓一次，平时零开销。
 *
 * 抓不到（窗口还没画、系统限制）返回 null，调用方退化成只露背景，不会崩。
 */
suspend fun captureWindow(context: Context): ImageBitmap? {
    val activity = context.findActivity() ?: return null
    val window = activity.window ?: return null
    val view = window.decorView
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return null

    val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    val ok = runCatching {
        suspendCancellableCoroutine { cont ->
            PixelCopy.request(
                window,
                bmp,
                { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        }
    }.getOrDefault(false)

    if (!ok || isBlank(bmp)) {
        runCatching { bmp.recycle() }
        return null
    }
    return bmp.asImageBitmap()
}

/** 全黑/全透明说明没抓到内容（个别 ROM 会这样），当作失败 */
private fun isBlank(bmp: Bitmap): Boolean = runCatching {
    val xs = intArrayOf(bmp.width / 4, bmp.width / 2, bmp.width * 3 / 4)
    val ys = intArrayOf(bmp.height / 4, bmp.height / 2, bmp.height * 3 / 4)
    for (x in xs) for (y in ys) {
        val c = bmp.getPixel(x, y)
        if ((c ushr 24) != 0 && (c and 0x00FFFFFF) != 0) return false
    }
    true
}.getOrDefault(false)

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
