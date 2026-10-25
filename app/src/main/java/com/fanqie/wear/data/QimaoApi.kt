package com.fanqie.wear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 七猫免费小说（qimao / wtzw）数据源。
 *
 * App 端接口需要签名：把参数按 key 排序拼成 `k=v&k=v` 再拼固定密钥做 MD5，
 * 请求头同样要签一次。签名逻辑来自社区逆向（7mao-novel-downloader）。
 *
 * 已验证可用：搜索、详情、目录。
 * 正文接口 `chapter/content` 会返回「参数错误」，还需要 App 端才会生成的字段，暂不支持。
 */
class QimaoApi(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------- 公开能力 ----------------

    suspend fun search(keyword: String, page: Int = 1): List<Book> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val params = linkedMapOf(
            "extend" to "",
            "tab" to "0",
            "gender" to "0",
            "refresh_state" to "8",
            "page" to page.toString(),
            "wd" to keyword.trim(),
            "is_short_story_user" to "0",
        )
        val root = get("https://api-bc.wtzw.com/search/v1/words", params) ?: return@withContext emptyList()
        val books = root["data"]?.jsonObject?.get("books") as? JsonArray ?: return@withContext emptyList()
        books.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")
            if (id.isBlank()) null else Book(
                id = id,
                title = stripHtml(o.str("title", "original_title")),
                author = o.str("author"),
                cover = o.str("image_link").ifBlank { null },
                intro = o.str("intro"),
                score = o.str("score"),
                wordCount = parseSubTitle(o.str("sub_title")).first,
                finished = parseSubTitle(o.str("sub_title")).second,
                tags = o.str("ptags").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3),
                source = Source.QIMAO,
            )
        }
    }

    suspend fun detail(bookId: String): Book = withContext(Dispatchers.IO) {
        val root = get("https://api-bc.wtzw.com/api/v1/reader/detail", linkedMapOf("id" to bookId))
            ?: throw ApiException("七猫返回空数据")
        val d = root["data"]?.jsonObject ?: throw ApiException("七猫书籍不存在")
        Book(
            id = d.str("id").ifBlank { bookId },
            title = d.str("title"),
            author = d.str("author"),
            cover = d.str("image_link").ifBlank { null },
            intro = d.str("intro"),
            category = listOf(d.str("category1_name"), d.str("category2_name"))
                .filter { it.isNotBlank() }.joinToString("·"),
            finished = d.str("is_over") == "1",
            lastChapter = d.str("latest_chapter_title"),
            source = Source.QIMAO,
        )
    }

    suspend fun catalog(bookId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val root = get(
            "https://api-ks.wtzw.com/api/v1/chapter/chapter-list",
            linkedMapOf("chapter_ver" to "0", "id" to bookId),
        ) ?: return@withContext emptyList()
        val data = root["data"]?.jsonObject
        val list = data?.get("chapter_lists") as? JsonArray ?: return@withContext emptyList()
        list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")
            if (id.isBlank()) null else Chapter(
                itemId = id,
                title = o.str("title"),
                volume = "",
            )
        }
    }

    suspend fun chapter(itemId: String): ChapterContent =
        throw ApiException("七猫正文接口需要 App 端专有参数，暂未支持；可切换到番茄源阅读")

    // ---------------- 签名与请求 ----------------

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun signOf(map: Map<String, String>): String {
        val sb = StringBuilder()
        map.keys.sorted().forEach { k -> sb.append(k).append('=').append(map[k]) }
        sb.append(SIGN_KEY)
        return md5(sb.toString())
    }

    private fun signedHeaders(): Map<String, String> {
        val h = linkedMapOf(
            "AUTHORIZATION" to "",
            "app-version" to APP_VERSION,
            "application-id" to "com.****.reader",
            "channel" to "unknown",
            "net-env" to "1",
            "platform" to "android",
            "qm-params" to "",
            "reg" to "0",
        )
        return h + ("sign" to signOf(h))
    }

    private suspend fun get(base: String, params: Map<String, String>): JsonObject? =
        withContext(Dispatchers.IO) {
            val sign = signOf(params)
            val query = params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$base?$query&sign=$sign"
            val builder = Request.Builder().url(url).get()
            builder.header("User-Agent", "okhttp/4.12.0")
            signedHeaders().forEach { (k, v) -> builder.header(k, v) }
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) {
                    throw ApiException(if (resp.code == 401) "七猫验签失败" else "七猫接口异常（${resp.code}）")
                }
                val el = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                    ?: throw ApiException("七猫返回格式异常")
                if (el["errors"] != null) throw ApiException("七猫接口返回错误：${el["errors"]}")
                el
            }
        }

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            if (v.content.isNotBlank() && v.content != "null") return v.content
        }
        return ""
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    /** sub_title 形如「影视・智商在线・完结・1306万字」，从中拆出字数与完结标记 */
    private fun parseSubTitle(sub: String): Pair<String, Boolean> {
        if (sub.isBlank()) return "" to false
        val parts = sub.split("・", "·", "|").map { it.trim() }.filter { it.isNotEmpty() }
        val words = parts.firstOrNull { it.endsWith("字") } ?: ""
        val done = parts.any { it.contains("完结") }
        return words to done
    }

    companion object {
        private const val SIGN_KEY = "d3dGiJc651gSQ8w1"
        private const val APP_VERSION = "73720"
    }
}
