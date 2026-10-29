package com.fanqie.wear.data.web

import android.content.Context
import android.util.Log
import com.fanqie.wear.data.SettingsStore
import java.io.File

/**
 * 站点仓库：书源规则从 assets 读，用户可以在设置里逐个开关。
 *
 * 也支持"云书源"：把新的 sites.json 放到 `filesDir/sources.json`（未来可由 App 下载），
 * 存在就用它覆盖内置规则——站点改版后不用发版。
 */
class SiteRepository(private val context: Context, private val settings: SettingsStore) {

    @Volatile private var cachedRules: List<SiteRule>? = null

    @Volatile private var cachedDisabled: String = "\u0000"

    @Volatile private var cachedSources: List<DeclarativeSource> = emptyList()

    /** 全部规则（含被关闭的），设置页要展示 */
    fun rules(): List<SiteRule> {
        cachedRules?.let { return it }
        val text = runCatching {
            val override = File(context.filesDir, "sources.json")
            if (override.isFile && override.length() > 32) override.readText()
            else context.assets.open("sources/sites.json").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w("SiteRepository", "读书源配置失败", it)
            ""
        }
        val list = if (text.isBlank()) emptyList() else SiteRules.parse(text)
        cachedRules = list
        return list
    }

    fun disabledKeys(): Set<String> =
        settings.value.disabledSites.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 当前启用的声明式书源 */
    fun sources(): List<DeclarativeSource> {
        val disabled = settings.value.disabledSites
        if (disabled == cachedDisabled) return cachedSources
        val off = disabledKeys()
        val built = rules().filter { it.enabled && it.key !in off }
            .map { DeclarativeSource(it, memoDir = context.filesDir) }
        cachedSources = built
        cachedDisabled = disabled
        return built
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val cur = disabledKeys().toMutableSet()
        if (enabled) cur.remove(key) else cur.add(key)
        settings.update { it.copy(disabledSites = cur.joinToString(",")) }
    }

    fun isEnabled(key: String): Boolean = key !in disabledKeys()
}
