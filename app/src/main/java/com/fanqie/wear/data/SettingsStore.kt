package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** 字号档位 0/1/2/3 */
    val fontStep: Int = 1,
    /** 行距档位 0/1/2/3 */
    val lineStep: Int = 1,
    /** 段间距档位 0/1/2 */
    val paraStep: Int = 1,
    /** 阅读背景：0 纯黑 1 米黄 2 护眼绿 3 深灰 4 青灰 5 纸白 */
    val readerBg: Int = 0,
    /** 阅读字色：0 默认 1 米白 2 浅灰 3 暖黄 4 淡青 5 墨黑 */
    val charColor: Int = 0,
    /** 正文两端对齐 */
    val justify: Boolean = false,
    /** 阅读时保持屏幕常亮 */
    val keepAwake: Boolean = true,

    /** 阅读方式：0 滚动 1 翻页 */
    val pageMode: Int = 0,
    /** 翻页动画：0 无 1 淡入 2 滑动 */
    val pageAnim: Int = 1,
    /** 旋转表冠翻章 */
    val crownTurn: Boolean = true,
    /** 表冠速度：0 慢 1 标准 2 快 */
    val crownSpeed: Int = 1,
    /** 读到章末自动下一章 */
    val autoNext: Boolean = true,
    /** 预加载下一章 */
    val preloadNext: Boolean = true,
    /** 启动直接进入上次阅读 */
    val startContinue: Boolean = false,
    /** 阅读页底部显示进度 */
    val bottomProgress: Boolean = true,

    /** 界面主题：0 墨夜 1 纯黑 2 纸白 */
    val theme: Int = 0,
    /** 主题色：0 赤陶 1 青瓷 2 靛蓝 3 琥珀 4 紫藤 */
    val accent: Int = 0,
    /** 界面缩放：0 标准 1 大 2 超大 3 特大 */
    val uiScale: Int = 0,
    /** 安全区：0 自动 1 紧凑 2 标准 3 宽松 4 最宽 */
    val safeArea: Int = 0,
    /** 列表里显示封面 */
    val showCover: Boolean = true,
    /** 默认书源，存 Source.key，默认笔趣阁（免登录） */
    val sourceKey: String = Source.BIQUGE.key,
    /** 番茄网页登录 Cookie，为空表示未登录 */
    val cookie: String = "",

    /** 听书语速 0.6~1.6 */
    val ttsSpeed: Float = 1.0f,
    /** 听书音高 0.8~1.2 */
    val ttsPitch: Float = 1.0f,
    /** 听书自动下一章 */
    val ttsAutoNext: Boolean = true,
    /** 朗读引擎：auto 有系统中文语音就用系统，否则内置 eSpeak；system 只用系统；espeak 只用内置 */
    val ttsEngine: String = "auto",

    /** 滚动/点击震动反馈 */
    val haptics: Boolean = true,
    /** 左边缘滑动返回 */
    val edgeBack: Boolean = true,
)

/** 设置持久化：字段多、读写频繁，SharedPreferences + StateFlow 最省事。 */
class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val value: AppSettings get() = _state.value

    private fun read(): AppSettings = AppSettings(
        fontStep = sp.getInt("fontStep", 1),
        lineStep = sp.getInt("lineStep", 1),
        paraStep = sp.getInt("paraStep", 1),
        readerBg = sp.getInt("readerBg", 0),
        charColor = sp.getInt("charColor", 0),
        justify = sp.getBoolean("justify", false),
        keepAwake = sp.getBoolean("keepAwake", true),
        pageMode = sp.getInt("pageMode", 0),
        pageAnim = sp.getInt("pageAnim", 1),
        crownTurn = sp.getBoolean("crownTurn", true),
        crownSpeed = sp.getInt("crownSpeed", 1),
        autoNext = sp.getBoolean("autoNext", true),
        preloadNext = sp.getBoolean("preloadNext", true),
        startContinue = sp.getBoolean("startContinue", false),
        bottomProgress = sp.getBoolean("bottomProgress", true),
        theme = sp.getInt("theme", 0),
        accent = sp.getInt("accent", 0),
        uiScale = sp.getInt("uiScale", 0),
        safeArea = sp.getInt("safeArea", 0),
        showCover = sp.getBoolean("showCover", true),
        sourceKey = sp.getString("sourceKey", Source.BIQUGE.key) ?: Source.BIQUGE.key,
        cookie = sp.getString("cookie", "").orEmpty(),
        ttsSpeed = sp.getFloat("ttsSpeed", 1.0f),
        ttsPitch = sp.getFloat("ttsPitch", 1.0f),
        ttsAutoNext = sp.getBoolean("ttsAutoNext", true),
        ttsEngine = sp.getString("ttsEngine", "auto") ?: "auto",
        haptics = sp.getBoolean("haptics", true),
        edgeBack = sp.getBoolean("edgeBack", true),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(_state.value)
        _state.value = n
        sp.edit()
            .putInt("fontStep", n.fontStep)
            .putInt("lineStep", n.lineStep)
            .putInt("paraStep", n.paraStep)
            .putInt("readerBg", n.readerBg)
            .putInt("charColor", n.charColor)
            .putBoolean("justify", n.justify)
            .putBoolean("keepAwake", n.keepAwake)
            .putInt("pageMode", n.pageMode)
            .putInt("pageAnim", n.pageAnim)
            .putBoolean("crownTurn", n.crownTurn)
            .putInt("crownSpeed", n.crownSpeed)
            .putBoolean("autoNext", n.autoNext)
            .putBoolean("preloadNext", n.preloadNext)
            .putBoolean("startContinue", n.startContinue)
            .putBoolean("bottomProgress", n.bottomProgress)
            .putInt("theme", n.theme)
            .putInt("accent", n.accent)
            .putInt("uiScale", n.uiScale)
            .putInt("safeArea", n.safeArea)
            .putBoolean("showCover", n.showCover)
            .putString("sourceKey", n.sourceKey)
            .putString("cookie", n.cookie)
            .putFloat("ttsSpeed", n.ttsSpeed)
            .putFloat("ttsPitch", n.ttsPitch)
            .putBoolean("ttsAutoNext", n.ttsAutoNext)
            .putString("ttsEngine", n.ttsEngine)
            .putBoolean("haptics", n.haptics)
            .putBoolean("edgeBack", n.edgeBack)
            .apply()
    }

    fun reset() {
        sp.edit().clear().apply()
        _state.value = AppSettings()
    }
}
