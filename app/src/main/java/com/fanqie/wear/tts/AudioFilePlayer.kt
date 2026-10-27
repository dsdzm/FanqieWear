package com.fanqie.wear.tts

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播 mp3 文件（在线语音合成出来的就是 mp3）。
 *
 * 起播位置可指定：暂停时记住毫秒数，续听从那一毫秒接着播，
 * 不会像 PCM 那样只能按块重来。
 */
class AudioFilePlayer(private val context: Context) {

    /**
     * 播完或被打断后返回当前进度（毫秒）。
     * [onProgress] 每 100ms 回一次，供上层记录续播点。
     */
    suspend fun play(file: File, startMs: Int, onProgress: (Int) -> Unit): Int =
        withContext(Dispatchers.Main) {
            var pos = startMs.coerceAtLeast(0)
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                val dur = runCatching { mp.duration }.getOrDefault(0)
                if (startMs in 1 until dur) runCatching { mp.seekTo(startMs) }
                mp.start()
                while (currentCoroutineContext().isActive) {
                    val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                    pos = runCatching { mp.currentPosition }.getOrDefault(pos)
                    onProgress(pos)
                    if (!playing && pos > 0) break
                    delay(100)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                onProgress(pos)
                throw c
            } catch (t: Throwable) {
                TtsLog.e("mp3", t)
            } finally {
                runCatching { mp.stop() }
                runCatching { mp.release() }
            }
            pos
        }
}
