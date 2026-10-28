package com.fanqie.wear.ui

import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义背景。
 *
 * 关于模糊：`Modifier.blur` 依赖 RenderEffect，只有 Android 12（API 31）以上才生效，
 * 手表 ROM 大多还在 9~11，用了也是一片清晰 —— 所以这里改成**降采样**：
 * 先用 Coil 把图取成几十像素的小图，再铺满全屏让 GPU 放大，天然就是柔和的糊。
 * 模糊强度直接映射到采样尺寸，低端机也能跑，且不占显存。
 *
 * @param blur 0..3，越大越糊
 * @param dim 0..3，越大越暗（保证文字永远读得清）
 */
@Composable
fun AppBackdrop(
    image: String,
    blur: Int,
    dim: Int,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    if (image.isBlank()) {
        fallback()
        return
    }
    val ctx = LocalContext.current
    // 采样尺寸越小越糊：0 → 240px，3 → 40px
    val samplePx = when (blur.coerceIn(0, 3)) {
        0 -> 240
        1 -> 120
        2 -> 72
        else -> 40
    }
    val bmp by produceState<ImageBitmap?>(initialValue = null, image, samplePx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(ctx)
                    .data(Uri.parse(image))
                    .size(samplePx, samplePx)
                    .allowHardware(false)
                    .build()
                Coil.imageLoader(ctx).execute(request).drawable?.toBitmap()?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(modifier.fillMaxSize()) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val alpha = when (dim.coerceIn(0, 3)) {
                0 -> 0.15f
                1 -> 0.35f
                2 -> 0.55f
                else -> 0.75f
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
        } else {
            fallback()
        }
    }
}
