package com.fanqie.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.fanqie.wear.R
import com.fanqie.wear.app
import com.fanqie.wear.data.FanqieApi
import com.fanqie.wear.data.Source
import com.fanqie.wear.tts.EspeakEngine
import com.fanqie.wear.tts.TtsLog
import com.fanqie.wear.ui.theme.AccentPacks
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.SafeArea
import com.fanqie.wear.ui.theme.UiScale
import com.fanqie.wear.ui.theme.rememberDeviceDims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val SETTING_TITLES = listOf(
    "阅读外观", "阅读操作", "界面与安全区", "语音听书",
    "下载与存储", "网络与书源", "交互", "关于与支持",
)
private val SETTING_SUBS = listOf(
    "字号、行距、段距、背景",
    "翻页、表冠、自动读",
    "主题、缩放、安全区、封面",
    "语速、音调、自动续播",
    "空间占用、清理",
    "默认书源、连通性检测",
    "震动、边缘返回",
    "版本、免责、重置",
)
private val SETTING_ICONS = listOf(
    R.drawable.ic_book, R.drawable.ic_history, R.drawable.ic_compass,
    R.drawable.ic_person, R.drawable.ic_download, R.drawable.ic_rank,
    R.drawable.ic_settings, R.drawable.ic_search,
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val p = LocalPalette.current
    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = "设置", onBack = onBack) }
            items(SETTING_TITLES.size) { i ->
                NavCard(
                    title = SETTING_TITLES[i],
                    subtitle = SETTING_SUBS[i],
                    icon = SETTING_ICONS[i],
                    tint = if (i % 2 == 0) p.accent else p.jade,
                    onClick = { onOpen(i) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
fun SettingsDetailScreen(page: Int, onBack: () -> Unit, onLogin: () -> Unit = {}) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val p = LocalPalette.current
    val s by app.settings.state.collectAsStateWithLifecycle()
    var cacheText by remember { mutableStateOf("") }
    var resetDone by remember { mutableStateOf(false) }
    // 书源检测的状态放在这里：LazyListScope 的 lambda 不是 @Composable，分支里不能 remember
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var crashText by remember { mutableStateOf(app.lastCrash()) }
    // 听书自检结果 / 落地日志：手表连不了 adb，出问题就看这两块
    var ttsDiag by remember { mutableStateOf<String?>(null) }
    var ttsDiagRunning by remember { mutableStateOf(false) }
    var ttsTrace by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }

    ScreenScaffold(
            scrollState = state,
            timeText = { WearTimeText() },
            scrollIndicator = { ScrollIndicator(state) },
        ) {
        WearList(
            state = state,
            contentPadding = dims.listPadding,
            verticalArrangement = Arrangement.spacedBy(dims.gridGap),
        ) {
            item { TopBar(title = SETTING_TITLES.getOrElse(page) { "设置" }, onBack = onBack) }

            when (page) {
                0 -> {
                    item {
                        ChoiceCard("字号", listOf("小", "中", "大", "特大"), s.fontStep) { v ->
                            app.settings.update { it.copy(fontStep = v) }
                        }
                    }
                    item {
                        ChoiceCard("行距", listOf("紧", "标准", "松", "很松"), s.lineStep) { v ->
                            app.settings.update { it.copy(lineStep = v) }
                        }
                    }
                    item {
                        ChoiceCard("段间距", listOf("小", "标准", "大"), s.paraStep) { v ->
                            app.settings.update { it.copy(paraStep = v) }
                        }
                    }
                    item { BgSwatchCard(s.readerBg) { v -> app.settings.update { it.copy(readerBg = v) } } }
                    item { CharColorCard(s.charColor) { v -> app.settings.update { it.copy(charColor = v) } } }
                    item {
                        SwitchCard("两端对齐", "正文左右对齐，边缘更整齐", s.justify) {
                            app.settings.update { it.copy(justify = it.justify.not()) }
                        }
                    }
                    item {
                        SwitchCard("屏幕常亮", "阅读时不让屏幕熄灭", s.keepAwake) {
                            app.settings.update { it.copy(keepAwake = it.keepAwake.not()) }
                        }
                    }
                }

                1 -> {
                    item {
                        ChoiceCard("阅读方式", listOf("滚动", "翻页"), s.pageMode) { v ->
                            app.settings.update { it.copy(pageMode = v) }
                        }
                    }
                    item {
                        ChoiceCard("翻页动画", listOf("无", "淡入", "滑动"), s.pageAnim) { v ->
                            app.settings.update { it.copy(pageAnim = v) }
                        }
                    }
                    item {
                        ChoiceCard("表冠速度", listOf("慢", "标准", "快"), s.crownSpeed) { v ->
                            app.settings.update { it.copy(crownSpeed = v) }
                        }
                    }
                    item {
                        SwitchCard("表冠翻章", "滚动模式下转表冠切上一章/下一章", s.crownTurn) {
                            app.settings.update { it.copy(crownTurn = it.crownTurn.not()) }
                        }
                    }
                    item {
                        SwitchCard("自动下一章", "读到底部自动接下一章", s.autoNext) {
                            app.settings.update { it.copy(autoNext = it.autoNext.not()) }
                        }
                    }
                    item {
                        SwitchCard("预加载下一章", "提前取正文，翻章不等待", s.preloadNext) {
                            app.settings.update { it.copy(preloadNext = it.preloadNext.not()) }
                        }
                    }
                    item {
                        SwitchCard("启动后续读", "打开 App 直接回到上次的章节", s.startContinue) {
                            app.settings.update { it.copy(startContinue = it.startContinue.not()) }
                        }
                    }
                    item {
                        SwitchCard("底部进度", "阅读页底部显示进度条与页码", s.bottomProgress) {
                            app.settings.update { it.copy(bottomProgress = it.bottomProgress.not()) }
                        }
                    }
                    item {
                        Text(
                            text = "翻页模式下：左右两侧点按翻页、双击正文进目录、表冠逐页翻。",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 3,
                        )
                    }
                }

                2 -> {
                    item {
                        ChoiceCard("主题", listOf("墨夜", "纯黑", "纸白"), s.theme) { v ->
                            app.settings.update { it.copy(theme = v) }
                        }
                    }
                    item { AccentCard(s.accent) { v -> app.settings.update { it.copy(accent = v) } } }
                    item {
                        ChoiceCard("界面缩放", UiScale.entries.map { it.label }, s.uiScale) { v ->
                            app.settings.update { it.copy(uiScale = v) }
                        }
                    }
                    item { SafeAreaCard(s.safeArea) { v -> app.settings.update { it.copy(safeArea = v) } } }
                    item {
                        SwitchCard("列表显示封面", "关闭后列表更紧凑", s.showCover) {
                            app.settings.update { it.copy(showCover = it.showCover.not()) }
                        }
                    }
                }

                3 -> {
                    item { TtsStatusCard() }
                    item {
                        ChoiceCard(
                            "朗读引擎",
                            listOf("自动", "系统语音", "在线", "内置"),
                            when (s.ttsEngine) {
                                "system" -> 1
                                "online" -> 2
                                "espeak" -> 3
                                else -> 0
                            },
                        ) { v ->
                            app.settings.update {
                                it.copy(ttsEngine = listOf("auto", "system", "online", "espeak")[v])
                            }
                        }
                    }
                    item {
                        Text(
                            text = if (app.tts.systemVoiceAvailable) "有系统语音，自动优先用它" else "自动：在线 → 内置",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 1,
                        )
                    }
                    item {
                        val cache = remember { com.fanqie.wear.tts.TtsCache(ctx) }
                        var sizeMb by remember { mutableStateOf(cache.sizeBytes() / 1048576.0) }
                        GlassCard {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("听书缓存", fontSize = dims.label, color = p.muted)
                                Text(
                                    "%.1f MB".format(sizeMb),
                                    fontSize = dims.label,
                                    color = p.text,
                                    maxLines = 1,
                                )
                                val packMb = app.tts.chapterStore.sizeBytes() / 1048576.0
                                Text(
                                    "语音包 %d 章 · %.1f MB".format(app.tts.chapterStore.chapterCount(), packMb),
                                    fontSize = dims.label,
                                    color = p.text,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row {
                                    Pill(text = "清空缓存", compact = true, onClick = {
                                        cache.clear()
                                        sizeMb = cache.sizeBytes() / 1048576.0
                                    })
                                    Spacer(Modifier.width(6.dp))
                                    Pill(text = "清空语音包", compact = true, onClick = {
                                        app.tts.chapterStore.clear()
                                    })
                                }
                            }
                        }
                    }
                    item {
                        val voices = EspeakEngine.VOICES
                        ChoiceCard(
                            "内置音色",
                            voices.map { it.first },
                            voices.indexOfFirst { it.second == s.ttsVoice }.coerceAtLeast(0),
                        ) { v ->
                            app.settings.update { it.copy(ttsVoice = voices[v].second, ttsEngine = "espeak") }
                        }
                    }
                    item {
                        Pill(
                            text = "试听这个音色",
                            selected = true,
                            compact = true,
                            onClick = {
                                val spec = s.ttsVoice
                                val st = app.settings.value
                                runCatching { EspeakEngine.setVoice(spec) }
                                val t = app.tts
                                t.load("demo", "demo", "音色试听", "他今天去学校了，我们一起走吧。这是当前音色。")
                                t.setSpeed(st.ttsSpeed)
                                t.setPitch(st.ttsPitch)
                                t.play()
                            },
                        )
                    }
                    item {
                        Text(
                            text = "只改音色，不改吐字",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 1,
                        )
                    }
                    item {
                        val speeds = listOf(0.8f, 1.0f, 1.5f, 2.0f, 3.0f)
                        ChoiceCard(
                            "语速",
                            speeds.map { if (it % 1f == 0f) "${it.toInt()}x" else "${it}x" },
                            speeds.indexOfFirst { kotlin.math.abs(it - s.ttsSpeed) < 0.01f }.coerceAtLeast(0),
                        ) { v ->
                            app.settings.update { it.copy(ttsSpeed = speeds[v]) }
                        }
                    }
                    item {
                        ChoiceCard(
                            "音调",
                            listOf("低", "标准", "高"),
                            when {
                                s.ttsPitch < 0.95f -> 0
                                s.ttsPitch <= 1.05f -> 1
                                else -> 2
                            },
                        ) { v ->
                            app.settings.update { it.copy(ttsPitch = listOf(0.88f, 1.0f, 1.12f)[v]) }
                        }
                    }
                    item {
                        SwitchCard("自动续播", "一章读完自动接下一章", s.ttsAutoNext) {
                            app.settings.update { it.copy(ttsAutoNext = it.ttsAutoNext.not()) }
                        }
                    }
                    item {
                        GlassCard {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("朗读测试", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(6.dp))
                                Pill(text = "试听一句", selected = true, onClick = {
                                    val t = app.tts
                                    val st = app.settings.value
                                    t.load(
                                        "demo", "demo", "书阁",
                                        "你好，这是内置语音引擎的试听。能听到这句话，说明离线朗读正常。",
                                    )
                                    t.setSpeed(st.ttsSpeed)
                                    t.setPitch(st.ttsPitch)
                                    t.play()
                                })
                            }
                        }
                    }
                    item {
                        Text(
                            text = "用内置引擎朗读：不联网、不依赖系统语音包，音色是合成腔但吐字清楚。",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 3,
                        )
                    }
                }

                4 -> {
                    item {
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("缓存与空间", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = cacheText.ifBlank { "点击下方按钮统计并清理封面与正文缓存" },
                                    fontSize = dims.body,
                                    color = p.text,
                                )
                            }
                        }
                    }
                    item {
                        Pill(text = "清理缓存", selected = true, onClick = {
                            var freed = 0L
                            app.cacheDir.listFiles()?.forEach { f ->
                                freed += f.length()
                                f.deleteRecursively()
                            }
                            cacheText = "已清理 ${freed / 1024} KB"
                        })
                    }
                }

                5 -> {
                    val current = app.sources.of(Source.of(s.sourceKey))

                    item {
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("当前书源", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = current.displayName,
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.text,
                                )
                                Text(
                                    text = current.desc,
                                    fontSize = dims.label,
                                    color = p.muted,
                                    maxLines = 2,
                                )
                            }
                        }
                    }

                    item { SectionLabel("切换书源") }

                    items(app.sources.all.size) { i ->
                        val src = app.sources.all[i]
                        val inUse = src.source.key == s.sourceKey
                        NavCard(
                            title = src.displayName,
                            subtitle = src.desc,
                            icon = R.drawable.ic_compass,
                            tint = if (inUse) p.jade else p.accent,
                            trailing = if (inUse) "使用中" else "切换",
                            onClick = {
                                if (!inUse) {
                                    app.settings.update { it.copy(sourceKey = src.source.key) }
                                    probeResults = emptyMap()
                                }
                            },
                        )
                    }

                    item { SectionLabel("聚合站点") }
                    items(app.sources.sites.rules().size) { i ->
                        val rule = app.sources.sites.rules()[i]
                        val on = app.sources.sites.isEnabled(rule.key)
                        SwitchCard(rule.name, rule.note.ifBlank { rule.base }, on) {
                            app.sources.sites.setEnabled(rule.key, !on)
                            probeResults = emptyMap()
                        }
                    }

                    item {
                        Pill(
                            text = if (checking) "检测中…" else "检测书源可用性",
                            onClick = {
                                if (!checking) {
                                    checking = true
                                    scope.launch {
                                        val map = linkedMapOf<String, String>()
                                        app.sources.all.forEach { src ->
                                            map[src.source.key] =
                                                runCatching { src.ping() }.getOrElse { it.message ?: "不可用" }
                                            probeResults = map.toMap()
                                        }
                                        app.sources.sites.sources().forEach { site ->
                                            map[site.siteKey] =
                                                runCatching { site.ping() }.getOrElse { it.message ?: "不可用" }
                                            probeResults = map.toMap()
                                        }
                                        checking = false
                                    }
                                }
                            },
                        )
                    }

                    if (probeResults.isNotEmpty()) {
                        item {
                            GlassCard {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text("接口状态", fontSize = dims.label, color = p.muted)
                                    Spacer(Modifier.height(3.dp))
                                    app.sources.all.forEach { src ->
                                        val msg = probeResults[src.source.key] ?: "未检测"
                                        StatusLine(
                                            "${src.displayName} · $msg",
                                            msg.contains("正常"),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (s.sourceKey == Source.FANQIE.key) {
                        item {
                            NavCard(
                                title = "番茄账号",
                                subtitle = when {
                                    s.cookie.isBlank() -> "未登录 · 第 11 章起只有试读"
                                    !FanqieApi.looksLoggedIn(s.cookie) -> "Cookie 缺登录票据 · 点进去重登"
                                    else -> "已保存 Cookie · 点进去验证是否生效"
                                },
                                icon = R.drawable.ic_person,
                                tint = if (s.cookie.isBlank()) p.accent else p.jade,
                                onClick = onLogin,
                            )
                        }
                    }

                    item {
                        Text(
                            text = "番茄未登录只有前 10 章",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 1,
                        )
                    }
                }

                6 -> {
                    item {
                        SwitchCard("震动反馈", "滚动到中心、点击卡片时轻震", s.haptics) {
                            app.settings.update { it.copy(haptics = it.haptics.not()) }
                        }
                    }
                    item {
                        ChoiceCard(
                            "界面风格",
                            listOf("玻璃材质", "M3E"),
                            if (s.uiStyle == "m3e") 1 else 0,
                        ) { v ->
                            app.settings.update { it.copy(uiStyle = if (v == 1) "m3e" else "glass") }
                        }
                    }
                    item {
                        SwitchCard("右滑返回", "在页面左侧向右滑，返回上一页", s.edgeBack) {
                            app.settings.update { it.copy(edgeBack = it.edgeBack.not()) }
                        }
                    }
                    item {
                        Text(
                            text = "表冠滚动与吸附是系统级行为，始终开启。",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 2,
                        )
                    }
                }

                else -> {
                    item {
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    "书阁 · 手表版",
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.text,
                                )
                                Text("v$versionName · minSdk 26", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(2.dp))
                                Text("作者：东山de字幕", fontSize = dims.label, color = p.text, maxLines = 1)
                                Text("联系：2332184782@qq.com", fontSize = dims.label, color = p.text, maxLines = 1)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "数据来自笔趣阁、番茄小说、七猫免费小说的公开页面，仅供个人学习研究。\n" +
                                        "小说版权归原作者与平台所有，请支持正版。",
                                    fontSize = dims.label,
                                    color = p.muted,
                                    maxLines = 6,
                                )
                            }
                        }
                    }
                    item {
                        GlassCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 9.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = "听书自检",
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.text,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = ttsDiag ?: "点一下，依次检查：引擎库 → 语音数据 → 初始化 → 中文音色 → 合成 → 播放。" +
                                        "结果会写进下面的日志，出问题直接把这段发我。",
                                    fontSize = dims.label,
                                    color = p.muted,
                                    maxLines = 18,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Pill(
                                        text = if (ttsDiagRunning) "检查中…" else "开始自检",
                                        compact = true,
                                        selected = ttsDiagRunning,
                                        onClick = {
                                            if (!ttsDiagRunning) {
                                                ttsDiagRunning = true
                                                runCatching { app.tts.pause() }
                                                scope.launch {
                                                    val r = runCatching {
                                                        withContext(Dispatchers.IO) { EspeakEngine.selfTest(ctx) }
                                                    }.getOrElse { "自检异常：" + (it.message ?: it.javaClass.simpleName) }
                                                    ttsDiag = r
                                                    ttsDiagRunning = false
                                                    ttsTrace = TtsLog.read(ctx)
                                                }
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Pill(
                                        text = "刷新日志",
                                        compact = true,
                                        onClick = { ttsTrace = TtsLog.read(ctx) },
                                    )
                                }
                            }
                        }
                    }

                    ttsTrace?.let { trace ->
                        item {
                            GlassCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 9.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = "听书日志",
                                        fontSize = dims.titleSmall,
                                        color = p.accent,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = trace.takeLast(900),
                                        fontSize = dims.label,
                                        color = p.muted,
                                        maxLines = 22,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Pill(
                                        text = "清除日志",
                                        compact = true,
                                        onClick = {
                                            TtsLog.clear(ctx)
                                            ttsTrace = null
                                        },
                                    )
                                }
                            }
                        }
                    }

                    val crash = crashText
                    if (crash != null) {
                        item {
                            GlassCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 9.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = "上次崩溃记录",
                                        fontSize = dims.titleSmall,
                                        color = p.accent,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = crash.take(700),
                                        fontSize = dims.label,
                                        color = p.muted,
                                        maxLines = 16,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Pill(
                                        text = "清除记录",
                                        compact = true,
                                        onClick = {
                                            app.clearCrash()
                                            crashText = null
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Pill(
                            text = if (resetDone) "已恢复默认" else "恢复默认设置",
                            selected = false,
                            onClick = {
                                app.settings.reset()
                                resetDone = true
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (ok) p.jade else p.accent),
        )
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = dims.label, color = if (ok) p.text else p.muted, maxLines = 1)
    }
}

/** 多选一卡片：标题在上，胶囊换行排列，窄屏不溢出 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceCard(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text(label, fontSize = dims.label, color = p.muted, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEachIndexed { i, o ->
                    Pill(text = o, selected = i == selected, onClick = { onSelect(i) }, compact = true)
                }
            }
        }
    }
}

/** 开关卡片 */
@Composable
private fun SwitchCard(label: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val trackColor by animateColorAsState(if (checked) p.accent else p.divider, label = "track")
    val knobShift by animateDpAsState(if (checked) 15.dp else 0.dp, label = "knob")
    GlassCard(onClick = onToggle) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = dims.titleSmall, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = dims.label, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 20.dp)
                    .clip(CircleShape)
                    .background(trackColor),
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 3.dp + knobShift, top = 3.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (checked) p.onAccent else p.muted),
                )
            }
        }
    }
}

private val ReaderBgColors = listOf(
    Color(0xFF000000), Color(0xFFEDE5D3), Color(0xFFC9D6C0),
    Color(0xFF1A1A1C), Color(0xFF15242B), Color(0xFFFDFBF6),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BgSwatchCard(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text("阅读背景", fontSize = dims.label, color = p.muted)
            Spacer(Modifier.height(5.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReaderBgColors.forEachIndexed { i, c ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c)
                            .border(
                                width = if (selected == i) 2.dp else 1.dp,
                                color = if (selected == i) p.accent else p.cardBorder,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(i) },
                    )
                }
            }
        }
    }
}

/**
 * 安全区设置：左边实时预览（圆/方屏形状 + 内缩边距），右边档位胶囊。
 * 预览里的虚线内框就是当前列表内容的实际左边界。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafeAreaCard(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(if (dims.isRound) CircleShape else RoundedCornerShape(12.dp))
                        .background(p.bgTop)
                        .border(1.dp, p.cardBorder, if (dims.isRound) CircleShape else RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding((dims.sidePadding.value / dims.widthDp * 58f).dp.coerceIn(1.dp, 24.dp))
                            .clip(if (dims.isRound) CircleShape else RoundedCornerShape(6.dp))
                            .background(p.jade.copy(alpha = 0.28f))
                            .border(
                                1.dp,
                                p.jade.copy(alpha = 0.6f),
                                if (dims.isRound) CircleShape else RoundedCornerShape(6.dp),
                            ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("安全区", fontSize = dims.titleSmall, color = p.text, maxLines = 1)
                    Text(
                        text = if (dims.isRound) "圆屏 · 左右各留 ${dims.sidePadding.value.toInt()}dp"
                        else "方屏 · 左右各留 ${dims.sidePadding.value.toInt()}dp",
                        fontSize = dims.label,
                        color = p.muted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SafeArea.entries.forEachIndexed { i, o ->
                    Pill(text = o.label, selected = i == selected, onClick = { onSelect(i) }, compact = true)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = "边角被裁、按钮贴边时调「宽松」；想多看两行字就调「紧凑」。",
                fontSize = dims.label,
                color = p.muted,
                maxLines = 3,
            )
        }
    }
}

private val CharColorList = listOf(
    Color(0xFFE6E6E6), Color(0xFFEDE6D8), Color(0xFFB9BEC2),
    Color(0xFFE8C98A), Color(0xFF9FD6C8), Color(0xFF1B1B1B),
)

/** 阅读字色：6 个色块，0 号为「跟随背景」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharColorCard(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (selected == 0) "阅读字色 · 跟随背景" else "阅读字色",
                fontSize = dims.label,
                color = p.muted,
            )
            Spacer(Modifier.height(5.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CharColorList.forEachIndexed { i, c ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selected == i) 2.dp else 1.dp,
                                color = if (selected == i) p.accent else p.cardBorder,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == 0) Text("A", fontSize = dims.label, color = Color(0xFF888888))
                    }
                }
            }
        }
    }
}

/** 主题色：5 套强调色 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentCard(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text("主题色 · ${AccentPacks.getOrElse(selected) { AccentPacks[0] }.label}", fontSize = dims.label, color = p.muted)
            Spacer(Modifier.height(5.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AccentPacks.forEachIndexed { i, pack ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(pack.main)
                            .border(
                                width = if (selected == i) 3.dp else 1.dp,
                                color = if (selected == i) p.text else p.cardBorder,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(i) },
                    )
                }
            }
        }
    }
}

/** 语音引擎状态：引擎随 APK 安装，不需要用户下载任何东西 */
@Composable
private fun TtsStatusCard() {
    val p = LocalPalette.current
    val dims = rememberDeviceDims()
    val ttsState by app.tts.state.collectAsStateWithLifecycle()

    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (ttsState.ready) p.jade else p.accent),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ttsState.ready) "内置语音引擎就绪" else "语音引擎初始化中",
                    fontSize = dims.titleSmall,
                    color = p.text,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(if (ttsState.engine == "system") "系统语音" else "内置 eSpeak")
                    ttsState.error?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                fontSize = dims.label,
                color = p.muted,
                maxLines = 2,
            )
        }
    }
}
