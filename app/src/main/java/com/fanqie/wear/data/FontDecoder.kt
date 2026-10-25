package com.fanqie.wear.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 番茄小说网页端用私有区字符（U+E000–U+F8FF）替换常用汉字，配合自定义 woff2 字体渲染。
 * 这里内置一份「码点 -> 汉字」映射表（由字体字形比对还原），在本地完成解码。
 */
object FontDecoder {

    private var table: Map<Char, String> = emptyMap()
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    fun load(context: Context) {
        if (loaded) return
        runCatching {
            val text = context.assets.open("fontmap.json").bufferedReader().use { it.readText() }
            val obj = Json.parseToJsonElement(text).jsonObject
            val map = HashMap<Char, String>(obj.size * 2)
            obj.forEach { (k, v) ->
                val cp = k.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return@forEach
                map[cp.toChar()] = v.jsonPrimitive.content
            }
            table = map
            loaded = map.isNotEmpty()
        }
    }

    fun decode(input: CharSequence): String {
        if (table.isEmpty()) return input.toString()
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c in '\uE000'..'\uF8FF') sb.append(table[c] ?: c) else sb.append(c)
        }
        return sb.toString()
    }

    fun hasPrivateChar(s: CharSequence): Boolean = s.any { it in '\uE000'..'\uF8FF' }
}
