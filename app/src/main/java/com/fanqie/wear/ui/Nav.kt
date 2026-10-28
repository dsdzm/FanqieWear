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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRestorer
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
    val motion = rememberNavMotion(scope)

    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    val nav = remember { NavController().apply { reset(initial) } }
    // 幂等赋值，每次组合都刷新一遍，保证回调里拿到的是最新的
    nav.motion = motion
    nav.stateHolder = stateHolder
    nav.widthPx = widthPx
    nav.exitApp = { runCatching { context.findActivity()?.finish() } }

    val stack = nav.stack

    val settings by app.settings.state.collectAsStateWithLifecycle()
    val display = DisplayConfig(
        safeArea = SafeArea.of(settings.safeArea),
        uiScale = UiScale.of(settings.uiScale),
        showCover = settings.showCover,
        haptics = settings.haptics,
    )

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    FanqieWearTheme(theme = settings.theme, accent = settings.accent, style = settings.uiStyle) {
        CompositionLocalProvider(LocalDisplayConfig provides display) {
            val p = LocalPalette.current
            // 两个固定槽位常驻组合树：谁在上层只看栈序，页面本身不挪位置（挪位置 = 重建 = 闪）
            val slots = nav.slots
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
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
                                // 上层被移除后把焦点还给下层（否则返回后表冠/焦点全丢）
                                .focusRestorer()
                                // 变换层常驻：以前只在转场时才挂 graphicsLayer，
                                // 挂/卸那一刻整页要重建图层，就是"控件闪一下"的来源
                                .graphicsLayer {
                                    if (isTop) {
                                        scaleX = motion.topScale
                                        scaleY = motion.topScale
                                        translationX = motion.topShift
                                    } else {
                                        scaleX = motion.belowScale
                                        scaleY = motion.belowScale
                                        // 非转场时整页挪到屏幕外：不参与绘制，也不会被点到
                                        translationX = if (motion.active) 0f else -size.width
                                    }
                                }
                                .then(
                                    if (isTop) {
                                        Modifier.navSwipeBack(
                                            enabled = settings.edgeBack,
                                            motion = motion,
                                            onStart = { motion.dragStart() },
                                            onCommit = { nav.back() },
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
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
}

/**
 * 导航栈。
 *
 * 关键点：Compose 按「位置」复用组合子树。如果直接把 `stack.takeLast(2)` 画出来，
 * 返回时栈顶-1 页会挪到原来栈顶的位置上，整棵子树被重建 —— 数据重新拉一遍，
 * 于是"退回来先白一下再刷出内容"，看起来就是闪一下；`remember` 的中间状态也会串页。
 *
 * 这里改成给每个页面绑定固定槽位（0/1），出栈时谁在上层只改 zIndex，页面不挪窝：
 *  - 返回时新栈顶原本就占着某个槽位，一直在组合树里，逐像素完整，不可能闪；
 *  - 栈深 3 层以上时最底下那层被移出渲染，等它重新需要时也是先出现在**下层**
 *    （屏幕外、不可见），等它变成栈顶时早已组合完毕。
 */
@Stable
private class NavController {

    var stack by mutableStateOf<List<Entry>>(emptyList())
        private set

    /** 两个渲染槽位，null 表示空 */
    var slots by mutableStateOf<List<Entry?>>(listOf(null, null))
        private set

    var motion: NavMotion? = null
    var stateHolder: SaveableStateHolder? = null
    var widthPx: Float = 0f
    var exitApp: () -> Unit = {}

    private var seq = 0L

    /** 正在执行出栈/退场，防止重入。注意不能用 motion.active：手势拖拽期间它本来就是 true */
    private var transitioning = false

    fun reset(initial: List<Dest>) {
        if (stack.isNotEmpty()) return
        stack = initial.map { Entry(seq++, it) }
        assignSlots()
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
        val m = motion ?: return
        // 动画没走完不接新指令，避免连点叠两层
        if (transitioning || m.active) return
        val e = Entry(seq++, d)
        stack = stack + e
        assignSlots()
        m.forward()
    }

    fun pop() {
        val m = motion ?: return
        if (stack.size <= 1 || transitioning) return
        transitioning = true
        val leaving = stack.last()
        m.exit(widthPx) {
            // 上层滑出屏幕后在同一帧摘掉它，新栈顶（刚才的下层）本来就完整
            stack = stack.dropLast(1)
            assignSlots()
            if (stack.none { it.dest.key() == leaving.dest.key() }) {
                stateHolder?.removeState(leaving.dest.key())
            }
            m.settle()
            transitioning = false
        }
    }

    /** 右滑提交：还有上一页就返回，没有就退出应用 */
    fun back() {
        val m = motion ?: return
        if (stack.size > 1) {
            pop()
            return
        }
        if (transitioning) return
        transitioning = true
        m.exit(widthPx) {
            exitApp()
            m.settle()
            transitioning = false
        }
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
