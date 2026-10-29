package com.fanqie.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fanqie.wear.app
import com.fanqie.wear.data.Source
import com.fanqie.wear.ui.theme.DisplayConfig
import com.fanqie.wear.ui.theme.FanqieWearTheme
import com.fanqie.wear.ui.theme.LocalDisplayConfig
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.SafeArea
import com.fanqie.wear.ui.theme.UiScale

sealed interface Dest {
    data object Home : Dest
    data object Library : Dest
    data object Explore : Dest
    data object Settings : Dest
    data class SettingsDetail(val page: Int) : Dest
    data object Shelf : Dest
    data object Search : Dest
    data object Download : Dest
    data object History : Dest
    data object Profile : Dest
    data object Login : Dest
    data object WebLogin : Dest
    data object PhoneLogin : Dest
    data object Listen : Dest
    data class Detail(val bookId: String, val title: String, val source: Source) : Dest
    data class Chapters(val bookId: String, val title: String, val source: Source) : Dest
    data class Reader(val bookId: String, val itemId: String, val title: String, val source: Source) : Dest
}

/** 每个页面一个稳定 key，用来保存/恢复滚动位置等 rememberSaveable 状态 */
private fun Dest.key(): String = toString()

/**
 * 这一层当前是不是栈顶（可见、可交互）。
 *
 * 页面不再因为返回而重建，所以"重新回到前台"这件事得由它自己知道 ——
 * 像阅读页要在重新成为栈顶时把焦点（表冠）要回来。
 */
val LocalPageActive = compositionLocalOf { true }

/**
 * 极简导航栈：层级浅（最多 4~5 层），用列表保存即可。
 *
 * 转场：栈顶两层同时留在组合树里 ——
 *  - 下层平时平移到屏幕外（不绘制、不拦触摸），返回时原地归位，**不重建**，
 *    所以不会出现"退回来先空一帧再刷出内容"的闪屏；
 *  - 上层退出时缩小 + 右移出屏，全程不透明，任何一帧都是完整画面。
 */
@Composable
fun FanqieWearApp() {
    val initial = remember {
        if (app.settings.value.startContinue) {
            val last = app.shelf.all().firstOrNull { !it.lastChapterId.isNullOrBlank() }
            if (last != null) {
                listOf<Dest>(
                    Dest.Home,
                    Dest.Reader(last.id, last.lastChapterId!!, last.title, Source.of(last.source)),
                )
            } else {
                listOf(Dest.Home)
            }
        } else {
            listOf(Dest.Home)
        }
    }

    /**
     * 按页面保存状态。栈顶两层常驻组合树，返回时天然不重建；
     * 这里兜住"第三层被移出组合、之后又退回来"的滚动位置等 rememberSaveable 状态。
     */
    val stateHolder = rememberSaveableStateHolder()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val nav = remember { NavController(scope).apply { reset(initial) } }
    // 幂等赋值，每次组合都刷新一遍，保证回调里拿到的是最新的
    nav.stateHolder = stateHolder
    nav.exitApp = { runCatching { context.findActivity()?.finish() } }

    val stack = nav.stack

    val settings by app.settings.state.collectAsStateWithLifecycle()
    val display = remember(settings.safeArea, settings.uiScale, settings.showCover, settings.haptics) {
        DisplayConfig(
            safeArea = SafeArea.of(settings.safeArea),
            uiScale = UiScale.of(settings.uiScale),
            showCover = settings.showCover,
            haptics = settings.haptics,
        )
    }

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    FanqieWearTheme(theme = settings.theme, accent = settings.accent, style = settings.uiStyle) {
        CompositionLocalProvider(LocalDisplayConfig provides display) {
            val p = LocalPalette.current
            // 两个固定槽位常驻组合树：谁在上层只看栈序，页面本身不挪位置（挪位置 = 重建 = 闪）
            val slots = nav.slots
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    // 手势挂最外层：任何时刻只有一个检测器，而且全屏起手都能返回
                    .navSwipeBack(enabled = settings.edgeBack, host = nav),
            ) {
                AppBackdrop(
                    image = settings.bgImage,
                    blur = settings.bgBlur,
                    dim = settings.bgDim,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(brush = Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
                    }
                }

                slots.forEach { entry ->
                    if (entry == null) return@forEach
                    val isTop = entry === stack.last()
                    // key 用条目 id：同一个槽位换了人就必须重建，同一个人则完全保留
                    key(entry.id) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (isTop) 1f else 0f)
                                // 变换值全部来自**这一页自己的** fx。
                                // 旧版共用一份状态，出栈那一帧身份互换会读到对方的缩放值，
                                // 于是保留下来的页面被缩一下再弹回 —— 就是"所有控件闪一下"。
                                .graphicsLayer {
                                    // 非转场时下层一律不画。
                                    // 页面内容本身多数是透明底（背景由全局 AppBackdrop 提供），
                                    // 下层要是照常绘制，栈顶页的透明区域就会直接透出下面那页的内容。
                                    // 只有「真在退场」时才画下层 —— 那是返回动画本身需要。
                                    val shown = isTop || nav.revealing
                                    if (!shown) {
                                        alpha = 0f
                                        // 双保险：同时挪出可视区，确保任何 ROM 上都被裁掉
                                        translationX = -10000f
                                    } else {
                                        scaleX = entry.fx.scale
                                        scaleY = entry.fx.scale
                                        alpha = entry.fx.alpha
                                        // 拖动时的橡皮筋位移。位移不改透明度和缩放，
                                        // 所以手指按歪了也只是这一页轻轻挪一下，看不到下面任何东西。
                                        translationX = entry.fx.shiftX
                                    }
                                },
                        ) {
                            CompositionLocalProvider(LocalPageActive provides isTop) {
                                stateHolder.SaveableStateProvider(entry.dest.key()) {
                                    Screen(dest = entry.dest, push = nav.pushFn, pop = nav.popFn)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一个栈条目。slot 固定 0/1，出栈时不会换槽位，所以页面不会被重建 */
private class Entry(val id: Long, val dest: Dest) {
    /** 0 = 先声明的一层，1 = 后声明的一层。谁在上层由 zIndex 决定，和槽位无关 */
    var slot: Int = -1

    /** 这一页自己的转场状态。跟着页面走，出栈时不会和别的页面串值 */
    val fx = PageFx()
}

/**
 * 导航栈。
 *
 * ## 页面为什么不会重建
 *
 * Compose 按「位置」复用组合子树。如果直接把 `stack.takeLast(2)` 画出来，
 * 返回时栈顶-1 页会挪到原来栈顶的位置上，整棵子树被重建 —— 数据重新拉一遍，
 * 于是"退回来先白一下再刷出内容"，看起来就是闪；`remember` 的中间状态也会串页。
 *
 * 这里给每个页面绑定固定槽位（0/1），出入栈时**已经在槽里的页面绝不换槽**，
 * 新露出的页面去填空位。于是：
 *  - 返回时新栈顶原本就占着某个槽位、一直在组合树里，逐像素完整；
 *  - 栈深 3 层以上时最底下那层被移出渲染，等它重新需要时也是先出现在**下层**
 *    （被栈顶完全盖住、不可见），等它变成栈顶时早已组合完毕。
 *
 * ## 动画为什么不会闪
 *
 * 转场状态绑在 [Entry.fx] 上，只有"正在入场/出场的那一页"会变：
 *  - 入栈：新页 0.94 → 1，旧页一动不动（被新页完全盖住）；
 *  - 出栈：只有要走的那页缩到 0.5 并淡到 0，**下层页面 scale/alpha 恒为 1**。
 *    它被移除的那一帧，屏幕上本来就是下层 1:1 的完整画面，没有任何一帧需要补。
 */
@Stable
private class NavController(private val scope: CoroutineScope) : NavBackHost {

    var stack by mutableStateOf<List<Entry>>(emptyList())
        private set

    /** 两个渲染槽位，null 表示空 */
    var slots by mutableStateOf<List<Entry?>>(listOf(null, null))
        private set

    /**
     * 是否存在「正在变换的栈顶」：入场动画、出栈动画、手势拖拽都算。
     *
     * 只有在它为 true 时下层页面才需要被绘制（上层缩小/淡出时露出的是它）。
     * 平时下层不绘制 —— 否则页面自身的透明区域会透出下面那页的内容。
     */
    var revealing by mutableStateOf(false)
        private set

    var stateHolder: SaveableStateHolder? = null
    var exitApp: () -> Unit = {}

    private var seq = 0L

    /** 正在执行出栈/退场，防止重入。注意不能用 fx 的值判断：拖拽期间它本来就不是 1 */
    private var transitioning = false

    private val jobs = HashMap<Long, Job>()

    // ---- 手势状态 ----
    private var dragEntry: Entry? = null
    private var dragX = 0f
    private var thresholdPx = 1f
    private var baseShift = 0f

    fun reset(initial: List<Dest>) {
        if (stack.isNotEmpty()) return
        stack = initial.map { Entry(seq++, it) }
        assignSlots()
        revealing = false
    }

    /**
     * 重排槽位：已经在槽里的页面**绝不换槽**，只有新露出的页面去填空位。
     * 这就是"返回不重建"的全部秘密。
     */
    private fun assignSlots() {
        val keep = stack.takeLast(2)
        val next = mutableListOf<Entry?>(null, null)
        for (i in 0..1) {
            val old = slots.getOrNull(i) ?: continue
            if (keep.any { it === old }) next[i] = old
        }
        for (e in keep) {
            if (next.any { it === e }) continue
            val free = next.indexOfFirst { it == null }
            if (free >= 0) next[free] = e
        }
        next.forEachIndexed { i, e -> e?.slot = i }
        slots = next
    }

    fun push(d: Dest) {
        if (transitioning) return
        val e = Entry(seq++, d)
        // 新页从略小开始放大。旧页不动 —— 它已经被新页完全盖住，动它只会白白多画一层
        e.fx.scale = NavFx.ENTER_SCALE
        stack = stack + e
        assignSlots()
        // 入场**不**打开下层绘制：新页从 0.94 放大时四周露出的是背景，
        // 而不是旧页的内容 —— 否则新页里任何透明的地方都会透出下面那页。
        animatePage(
            scope, jobs, e.id, e.fx,
            toScale = 1f, toAlpha = 1f,
            ms = NavFx.ENTER_MS, easing = enterEase,
        ) { jobs.remove(e.id) }
    }

    fun pop() {
        if (stack.size <= 1 || transitioning) return
        leave(stack.last())
    }

    /**
     * 一页退场：缩到 0.5 + 淡到 0，结束后才真正出栈。
     *
     * 缩小而不是横向滑出（照网易云）：滑出会把"两个页面并排"怼到眼前，
     * 缩放 + 淡出则是「这一页收走，露出下面本来就完整的页面」。
     */
    private fun leave(e: Entry, onGone: (() -> Unit)? = null) {
        transitioning = true
        // 退场页缩小淡出的过程中，露出来的必须是下面那页的内容
        revealing = true
        animatePage(
            scope, jobs, e.id, e.fx,
            toScale = NavFx.EXIT_SCALE, toAlpha = 0f,
            ms = NavFx.EXIT_MS, easing = exitEase,
        ) {
            if (onGone != null) {
                onGone()
            } else {
                stack = stack.dropLast(1)
                assignSlots()
                if (stack.none { it.dest.key() == e.dest.key() }) {
                    stateHolder?.removeState(e.dest.key())
                }
            }
            jobs.remove(e.id)
            transitioning = false
            revealing = false
        }
    }

    // ---------------- 手势 ----------------

    override fun onDragStart(thresholdPx: Float) {
        if (transitioning) return
        this.thresholdPx = thresholdPx
        val e = stack.lastOrNull() ?: return
        // 这一页可能还在跑入场/回弹动画，先掐掉，再以当前位移为基准
        jobs.remove(e.id)?.cancel()
        dragEntry = e
        dragX = 0f
        baseShift = e.fx.shiftX
        // 注意：这里**不打开** revealing。拖动过程中下层不绘制，
        // 栈顶页也不变透明、不缩放，所以手指按歪时只有这一页轻轻挪一下，
        // 绝不会透出下面那页的内容。
    }

    override fun onDrag(dx: Float, maxShift: Float) {
        val e = dragEntry ?: return
        dragX = (dragX + dx).coerceAtLeast(0f)
        // 阻尼 + 封顶：手指划再远，页面也只挪一小段，漏出的永远是背景而不是别的页面
        e.fx.shiftX = (baseShift + dragX * NavFx.DRAG_DAMP).coerceAtMost(maxShift)
    }

    override fun onDragEnd() {
        val e = dragEntry ?: return
        dragEntry = null
        if (transitioning) {
            // 极少见：拖动期间被别的路径触发了出栈，直接归位
            springBack(e)
            return
        }
        if (dragX >= thresholdPx) {
            // 栈底右滑 = 退出应用
            if (stack.size <= 1) leave(e) { exitApp() } else leave(e)
        } else {
            // 没到阈值：位移弹回 0，全场只有这一页，不涉及下层
            springBack(e)
        }
    }

    override fun onDragCancel() {
        val e = dragEntry ?: return
        dragEntry = null
        springBack(e)
    }

    private fun springBack(e: Entry) {
        animatePage(
            scope, jobs, e.id, e.fx,
            toScale = 1f, toAlpha = 1f,
            ms = NavFx.SPRING_MS, easing = enterEase,
        ) { revealing = false }
    }

    /** 稳定引用，避免每次组合都造新 lambda 触发整页重组 */
    val pushFn: (Dest) -> Unit = { push(it) }
    val popFn: () -> Unit = { pop() }
}

/** 所有页面集中在这里渲染，导航栈只决定「现在显示哪个」 */
@Composable
private fun Screen(
    dest: Dest,
    push: (Dest) -> Unit,
    pop: () -> Unit,
) {
    when (dest) {
        is Dest.Home -> HomeScreen(
            onRank = { push(Dest.Library) },
            onExplore = { push(Dest.Explore) },
            onShelf = { push(Dest.Shelf) },
            onSearch = { push(Dest.Search) },
            onHistory = { push(Dest.History) },
            onDownload = { push(Dest.Download) },
            onSettings = { push(Dest.Settings) },
            onProfile = { push(Dest.Profile) },
            onContinue = { bookId, itemId, title, source ->
                push(Dest.Reader(bookId, itemId, title, source))
            },
        )

        is Dest.Library -> RankScreen(
            initialMale = true,
            onBack = { pop() },
            onBook = { book -> push(Dest.Detail(book.id, book.title, book.source)) },
        )

        is Dest.Explore -> ExploreScreen(
            onBack = { pop() },
            onBook = { book -> push(Dest.Detail(book.id, book.title, book.source)) },
        )

        is Dest.Settings -> SettingsScreen(
            onBack = { pop() },
            onOpen = { page -> push(Dest.SettingsDetail(page)) },
        )

        is Dest.SettingsDetail -> SettingsDetailScreen(
            page = dest.page,
            onBack = { pop() },
            onLogin = { push(Dest.Login) },
        )

        is Dest.Login -> LoginScreen(
            onBack = { pop() },
            onWebLogin = { push(Dest.WebLogin) },
            onPhoneLogin = { push(Dest.PhoneLogin) },
        )

        is Dest.PhoneLogin -> PhoneLoginScreen(onBack = { pop() })

        is Dest.WebLogin -> WebLoginScreen(
            onBack = { pop() },
            onUsePaste = { pop() },
        )

        is Dest.Shelf -> ShelfScreen(
            onBack = { pop() },
            onBook = { id, title, source -> push(Dest.Detail(id, title, source)) },
        )

        is Dest.Search -> SearchScreen(
            onBack = { pop() },
            onBook = { book -> push(Dest.Detail(book.id, book.title, book.source)) },
        )

        is Dest.Download -> DownloadScreen(onBack = { pop() })

        is Dest.History -> HistoryScreen(
            onBack = { pop() },
            onOpen = { bookId, itemId, title, source ->
                push(Dest.Reader(bookId, itemId, title, source))
            },
        )

        is Dest.Profile -> ProfileScreen(
            onBack = { pop() },
            onShelf = { push(Dest.Shelf) },
            onSettings = { push(Dest.Settings) },
        )

        is Dest.Detail -> DetailScreen(
            bookId = dest.bookId,
            source = dest.source,
            onBack = { pop() },
            onChapters = { push(Dest.Chapters(dest.bookId, dest.title, dest.source)) },
            onRead = { itemId -> push(Dest.Reader(dest.bookId, itemId, dest.title, dest.source)) },
            onSwitchBook = { id, title -> push(Dest.Detail(id, title, Source.AGG)) },
        )

        is Dest.Chapters -> ChapterListScreen(
            bookId = dest.bookId,
            source = dest.source,
            onBack = { pop() },
            onChapter = { itemId -> push(Dest.Reader(dest.bookId, itemId, dest.title, dest.source)) },
        )

        is Dest.Listen -> ListenScreen(onBack = { pop() })

        is Dest.Reader -> ReaderScreen(
            bookId = dest.bookId,
            bookTitle = dest.title,
            itemId = dest.itemId,
            source = dest.source,
            onBack = { pop() },
            onOpenChapters = { push(Dest.Chapters(dest.bookId, dest.title, dest.source)) },
            onLogin = { push(Dest.Login) },
            onOpenTtsSettings = { push(Dest.SettingsDetail(3)) },
            onOpenListen = { push(Dest.Listen) },
        )
    }
}
