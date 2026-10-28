package com.fanqie.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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

/**
 * 极简导航栈：层级浅（最多 4~5 层），用列表保存即可。
 *
 * 返回只走两条：页面自带的返回按钮、系统返回键（BackHandler）。
 *
 * 右滑关闭**不再由应用实现**：手表 ROM 的"整窗右滑关闭"是系统级手势，由主题里的
 * `android:windowSwipeToDismiss` 控制（见 themes.xml，已置 false）。
 * 应用内再写一层跟手位移，只会和系统手势互相打架 —— 上一个版本就是这么翻车的。
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

    fun push(d: Dest) { stack = stack + d }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    val settings by app.settings.state.collectAsStateWithLifecycle()
    val display = DisplayConfig(
        safeArea = SafeArea.of(settings.safeArea),
        uiScale = UiScale.of(settings.uiScale),
        showCover = settings.showCover,
        haptics = settings.haptics,
    )

    BackHandler(enabled = stack.size > 1) { pop() }

    FanqieWearTheme(theme = settings.theme, accent = settings.accent) {
        CompositionLocalProvider(LocalDisplayConfig provides display) {
            val p = LocalPalette.current
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(brush = Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
                }
                Screen(dest = stack.last(), push = ::push, pop = ::pop)
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
