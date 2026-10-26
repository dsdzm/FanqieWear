package com.fanqie.wear.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 听书链路的落地日志。
 *
 * 手表连不了 adb，一旦原生层把进程带走，Java 的崩溃兜底根本来不及写文件。
 * 所以这里每走一步就追加一行并立刻刷到磁盘：下次再闪退，「设置 → 关于」里
 * 能直接看到最后停在哪一步，不用猜。
 */
object TtsLog {

    private const val TAG = "TtsLog"
    private const val FILE = "tts-trace.txt"
    private const val MAX_BYTES = 48 * 1024
    private const val KEEP_LINES = 200

    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun init(context: Context) {
        if (dir == null) dir = context.applicationContext.filesDir
    }

    private fun file(): File? = dir?.let { File(it, FILE) }

    fun i(step: String, msg: String = "") = append("I", step, msg)

    fun w(step: String, msg: String) = append("W", step, msg)

    fun e(step: String, t: Throwable) =
        append("E", step, t.stackTraceToString().take(1200))

    @Synchronized
    private fun append(level: String, step: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$step ${msg.take(400)}"
        Log.println(
            if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO,
            TAG, line,
        )
        val f = file() ?: return
        runCatching {
            f.appendText(line + "\n")
            // 超限时只留最后若干行，手表存储小，别让它长起来
            if (f.length() > MAX_BYTES) {
                val tail = f.readLines().takeLast(KEEP_LINES)
                f.writeText(tail.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun read(context: Context): String? {
        init(context)
        return runCatching {
            file()?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun clear(context: Context) {
        init(context)
        runCatching { file()?.delete() }
    }
}
