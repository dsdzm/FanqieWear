package com.fanqie.wear.data

import android.content.Context
import android.os.Environment
import com.fanqie.wear.data.web.AggregateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「找别的站，导出一本 TXT」。
 *
 * 场景：番茄独占的书，在第三方站点能搜到；这里按书名+作者去所有站点找，
 * 挑一个章节最全的来源，把整本抓下来拼成 txt，存到本地并自动加进书架（本地书）。
 */
class TxtExporter(
    private val context: Context,
    private val registry: SourceRegistry,
    private val library: LocalLibrary,
) {

    suspend fun export(
        title: String,
        author: String,
        onProgress: (String) -> Unit,
    ): LocalBook = withContext(Dispatchers.IO) {
        onProgress("在其他站点搜索…")
        val agg = registry.of(Source.AGG) as? AggregateSource
            ?: throw ApiException("聚合源不可用")
        val candidates = runCatching { agg.alternatives(title, author) }.getOrElse { emptyList() }
        if (candidates.isEmpty()) throw ApiException("其他站点没有搜到《$title》")

        // 逐个试，取章节最多的那个
        var bestId: String? = null
        var bestCount = 0
        for (c in candidates.take(6)) {
            onProgress("检查 ${c.author.ifBlank { "来源" }}…")
            val chapters = runCatching { agg.catalog(c.id) }.getOrElse { emptyList() }
            if (chapters.size > bestCount) {
                bestCount = chapters.size
                bestId = c.id
            }
            if (bestCount >= 200) break
        }
        val id = bestId ?: throw ApiException("这些站点都没有可读目录")
        if (bestCount < 3) throw ApiException("只找到 $bestCount 章，可能不是这本书")

        val chapters = agg.catalog(id)
        onProgress("共 $bestCount 章，开始抓取…")

        val sb = StringBuilder(1 shl 20)
        sb.append("《$title》\n")
        if (author.isNotBlank()) sb.append("作者：$author\n")
        sb.append("来源：第三方站点（书阁导出）\n\n")

        var ok = 0
        chapters.forEachIndexed { i, ch ->
            onProgress("抓取 ${i + 1}/$bestCount")
            val body = runCatching { agg.chapter(id, ch.itemId) }.getOrNull()
            val text = body?.content.orEmpty().trim()
            if (text.isNotEmpty()) {
                sb.append(ch.title.trim()).append("\n\n").append(text).append("\n\n")
                ok++
            }
            if (i >= 2000) return@forEachIndexed
        }
        if (ok == 0) throw ApiException("目录能取到，但正文全是空的（可能该站要登录/有反爬）")

        onProgress("写入 TXT…")
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40)
        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TXT")
        outDir.mkdirs()
        val file = File(outDir, "$safeName.txt")
        file.writeText(sb.toString())

        onProgress("加入书架…")
        library.importText(sb.toString(), title, "$safeName.txt")
    }

    /** 导出的 txt 放在这里，方便用数据线拷走 */
    fun exportDir(): File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TXT")
}
