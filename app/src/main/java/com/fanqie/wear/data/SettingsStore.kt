package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** 字号档位 0/1/2 */
    val fontStep: Int = 1,
    /** 行距档位 0/1/2 */
    val lineStep: Int = 1,
    /** 段间距档位 0/1/2 */
    val paraStep: Int = 1,
    /** 阅读背景：0 纯黑 1 米黄 2 护眼绿 3 深灰 4 青灰 5 纸白 */
    val readerBg: Int = 0,
    /** 正文两端对齐 */
    val justify: Boolean = false,
    /** 阅读时保持屏幕常亮 */
    val keepAwake: Boolean = true,
    /** 翻页方式：0 滚动 1 上下翻页 */
    val pageMode: Int = 0,
    /** 旋转表冠翻章 */
    val crownTurn: Boolean = true,
    /** 读到章末自动下一章 */
    val autoNext: Boolean = true,
    /** 预加载下一章，减少翻章等待 */
    val preloadNext: Boolean = true,

    /** 界面主题：0 墨夜 1 纯黑 2 纸白 */
    val theme: Int = 0,
    /** 界面缩放：0 标准 1 大 2 超大 */
    val uiScale: Int = 0,
    /** 安全区：0 自动 1 紧凑 2 标准 3 宽松 4 最宽 */
    val safeArea: Int = 0,
    /** 列表里显示封面 */
    val showCover: Boolean = true,
    /** 默认书源 0 番茄 1 七猫 */
    val defaultSource: Int = 0,

    /** 听书语速 0.6~1.6 */
    val ttsSpeed: Float = 1.0f,
    /** 听书音高 0.8~1.2 */
    val ttsPitch: Float = 1.0f,
    /** 听书自动下一章 */
    val ttsAutoNext: Boolean = true,

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
        justify = sp.getBoolean("justify", false),
        keepAwake = sp.getBoolean("keepAwake", true),
        pageMode = sp.getInt("pageMode", 0),
        crownTurn = sp.getBoolean("crownTurn", true),
        autoNext = sp.getBoolean("autoNext", true),
        preloadNext = sp.getBoolean("preloadNext", true),
        theme = sp.getInt("theme", 0),
        uiScale = sp.getInt("uiScale", 0),
        safeArea = sp.getInt("safeArea", 0),
        showCover = sp.getBoolean("showCover", true),
        defaultSource = sp.getInt("defaultSource", 0),
        ttsSpeed = sp.getFloat("ttsSpeed", 1.0f),
        ttsPitch = sp.getFloat("ttsPitch", 1.0f),
        ttsAutoNext = sp.getBoolean("ttsAutoNext", true),
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
            .putBoolean("justify", n.justify)
            .putBoolean("keepAwake", n.keepAwake)
            .putInt("pageMode", n.pageMode)
            .putBoolean("crownTurn", n.crownTurn)
            .putBoolean("autoNext", n.autoNext)
            .putBoolean("preloadNext", n.preloadNext)
            .putInt("theme", n.theme)
            .putInt("uiScale", n.uiScale)
            .putInt("safeArea", n.safeArea)
            .putBoolean("showCover", n.showCover)
            .putInt("defaultSource", n.defaultSource)
            .putFloat("ttsSpeed", n.ttsSpeed)
            .putFloat("ttsPitch", n.ttsPitch)
            .putBoolean("ttsAutoNext", n.ttsAutoNext)
            .putBoolean("haptics", n.haptics)
            .putBoolean("edgeBack", n.edgeBack)
            .apply()
    }

    fun reset() {
        sp.edit().clear().apply()
        _state.value = AppSettings()
    }
}
