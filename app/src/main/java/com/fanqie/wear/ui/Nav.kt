package com.fanqie.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.launch

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
 * 极简导航栈：层级浅（最多 4~5 层），用列表保存即可。
 *
 * 转场按用户要求改成「二级页面右滑缩小、下面露出返回后看到的页面」：
 * 每个页面被压到下一层时截一张快照存进 belowStack，返回时先画这张快照，
 * 上层页面缩小淡出，动画结束再真正出栈。这样：
 *  - 不会像以前那样整屏跟着手指平移（那看着像系统在拖窗口）；
 *  - 也不需要为了转场同时组合两个页面（手表扛不住）。
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
    var stack by remember { mutableStateOf(initial) }
    // 与 stack 对齐：belowStack[i] = 离开 stack[i] 时截下的画面
    var belowStack by remember { mutableStateOf(List<ImageBitmap?>(initial.size) { null }) }

    /**
     * 按页面保存状态。
     *
     * 以前每次 pop 回来页面都是重新组合的，`rememberScalingLazyListState` 从 0 开始，
     * 于是"快照里是滚动到一半的画面、切回真实页却跳到页首"，看起来就是闪一下。
     * 这里用 SaveableStateHolder 按 Dest 作 key 保存，返回时滚动位置原样恢复。
     */
    val stateHolder = rememberSaveableStateHolder()

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val motion = rememberNavMotion(scope)
    var busy by remember { mutableStateOf(false) }

    fun push(d: Dest) {
        if (busy) return
        busy = true
        scope.launch {
            // 先给当前页留一张快照，再压栈：截图失败也照常进页面，只是下层露背景
            val snap = captureWindow(context)
            belowStack = belowStack.dropLast(1) + listOf(snap, null)
            motion.below = snap
            stack = stack + d
            motion.forward()
            busy = false
        }
    }

    fun pop() {
        if (stack.size <= 1 || busy) return
        busy = true
        motion.below = belowStack.getOrNull(stack.size - 2)
        val leaving = stack.last().key()
        motion.exit {
            stack = stack.dropLast(1)
            belowStack = belowStack.dropLast(1)
            // 页面已经出栈，它保存的状态没必要再留着
            stateHolder.removeState(leaving)
            motion.settle()
            busy = false
        }
    }

    val settings by app.settings.state.collectAsStateWithLifecycle()
    val display = DisplayConfig(
        safeArea = SafeArea.of(settings.safeArea),
        uiScale = UiScale.of(settings.uiScale),
        showCover = settings.showCover,
        haptics = settings.haptics,
    )

    BackHandler(enabled = stack.size > 1) { pop() }

    FanqieWearTheme(theme = settings.theme, accent = settings.accent, style = settings.uiStyle) {
        CompositionLocalProvider(LocalDisplayConfig provides display) {
            val p = LocalPalette.current
            Box(modifier = Modifier.fillMaxSize()) {
                AppBackdrop(
                    image = settings.bgImage,
                    blur = settings.bgBlur,
                    dim = settings.bgDim,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(brush = Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
                    }
                }

                // 下层：返回后看到的页面（快照）。只在转场期间画，平时不白画一层位图
                val below = motion.below
                if (motion.active && below != null) {
                    Image(
                        bitmap = below,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f)
                            // 动画值全部在绘制层读：拖动时只重绘，不触发重组
                            .graphicsLayer {
                                val s = motion.belowScale
                                scaleX = s
                                scaleY = s
                                // 被盖住时压暗一点，露出来时回到全亮
                                alpha = (1f - 0.3f * motion.keep).coerceIn(0f, 1f)
                                clip = true
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                    motion.belowRadiusDp.dp,
                                )
                            },
                    )
                }

                // 上层：当前页面。缩放 + 轻微右移，不再整屏平移
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .graphicsLayer {
                            val s = motion.topScale
                            scaleX = s
                            scaleY = s
                            translationX = motion.shift
                            alpha = motion.fade
                            clip = motion.keep < 0.999f
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                ((1f - motion.keep) * 18f).dp,
                            )
                        }
                        .navSwipeBack(
                            enabled = settings.edgeBack && stack.size > 1,
                            motion = motion,
                            onStart = {
                                motion.below = belowStack.getOrNull(stack.size - 2)
                                motion.dragStart()
                            },
                            onCommit = { pop() },
                        ),
                ) {
                    val top = stack.last()
                    stateHolder.SaveableStateProvider(top.key()) {
                        Screen(dest = top, push = ::push, pop = ::pop)
                    }
                }
            }
        }
    }
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
