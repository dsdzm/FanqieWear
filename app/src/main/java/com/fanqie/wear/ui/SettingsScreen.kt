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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.fanqie.wear.ui.theme.LocalPalette
import com.fanqie.wear.ui.theme.SafeArea
import com.fanqie.wear.ui.theme.UiScale
import com.fanqie.wear.ui.theme.rememberDeviceDims

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
    "默认书源、接口状态",
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
fun SettingsDetailScreen(page: Int, onBack: () -> Unit) {
    val dims = rememberDeviceDims()
    val state = rememberScalingLazyListState()
    val p = LocalPalette.current
    val s by app.settings.state.collectAsStateWithLifecycle()
    var cacheText by remember { mutableStateOf("") }
    var resetDone by remember { mutableStateOf(false) }

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
                        ChoiceCard("字号", listOf("小", "中", "大"), s.fontStep) { v ->
                            app.settings.update { it.copy(fontStep = v) }
                        }
                    }
                    item {
                        ChoiceCard("行距", listOf("紧", "标准", "松"), s.lineStep) { v ->
                            app.settings.update { it.copy(lineStep = v) }
                        }
                    }
                    item {
                        ChoiceCard("段间距", listOf("小", "标准", "大"), s.paraStep) { v ->
                            app.settings.update { it.copy(paraStep = v) }
                        }
                    }
                    item { BgSwatchCard(s.readerBg) { v -> app.settings.update { it.copy(readerBg = v) } } }
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
                        SwitchCard("表冠翻章", "旋转表冠切换上一章/下一章", s.crownTurn) {
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
                        Text(
                            text = "提示：阅读页左右滑动可切换章节，双击正文回到目录。",
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
                    item {
                        ChoiceCard(
                            "语速",
                            listOf("0.8x", "1.0x", "1.2x", "1.5x"),
                            when {
                                s.ttsSpeed <= 0.85f -> 0
                                s.ttsSpeed <= 1.05f -> 1
                                s.ttsSpeed <= 1.3f -> 2
                                else -> 3
                            },
                        ) { v ->
                            app.settings.update { it.copy(ttsSpeed = listOf(0.8f, 1.0f, 1.2f, 1.5f)[v]) }
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
                        GlassCard {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("朗读测试", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(6.dp))
                                Pill(text = "试听一句", selected = true, onClick = {
                                    val t = app.tts
                                    val st = app.settings.value
                                    t.load(
                                        "demo", "demo", "番茄阅读",
                                        "你好，这是番茄阅读的听书测试。可以听到说明语音引擎正常。",
                                    )
                                    t.setSpeed(st.ttsSpeed)
                                    t.play()
                                })
                            }
                        }
                    }
                    item {
                        Text(
                            text = "使用系统 TTS 朗读，音色取决于手表已装的中文语音包。",
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
                    item {
                        ChoiceCard("默认书源", listOf("番茄", "七猫"), s.defaultSource) { v ->
                            app.settings.update { it.copy(defaultSource = v) }
                        }
                    }
                    item {
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("接口状态", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(3.dp))
                                StatusLine("番茄 · 榜单/详情/目录/正文", true)
                                StatusLine("七猫 · 搜索/详情/目录", true)
                                StatusLine("七猫 · 正文", false)
                            }
                        }
                    }
                    item {
                        Text(
                            text = "七猫正文接口需要 App 端私有签名，暂未打通；在书荒广场选七猫的书会提示切回番茄源。",
                            fontSize = dims.label,
                            color = p.muted,
                            maxLines = 4,
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
                        SwitchCard("边缘滑动返回", "从左边缘右滑回到上一页", s.edgeBack) {
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
                                    "番茄阅读 · 手表版",
                                    fontSize = dims.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.text,
                                )
                                Text("v1.2.0 · minSdk 26", fontSize = dims.label, color = p.muted)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "数据来自番茄小说、七猫免费小说的公开接口，仅供个人学习研究。\n" +
                                        "小说版权归原作者与平台所有，请支持正版。",
                                    fontSize = dims.label,
                                    color = p.muted,
                                    maxLines = 6,
                                )
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
