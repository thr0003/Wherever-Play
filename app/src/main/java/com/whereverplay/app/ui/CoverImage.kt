package com.whereverplay.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * WP: 封面加载。没有引入 Coil——只需要读本地文件并按尺寸采样，
 * 用 BitmapFactory.inSampleSize 就够了，少一个依赖、少几 MB。
 */
@Composable
fun rememberCoverBitmap(file: File?, targetPx: Int = 256): Bitmap? =
    remember(file?.absolutePath, targetPx) {
        val f = file ?: return@remember null
        if (!f.exists()) return@remember null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth > 0 && bounds.outWidth / sample > targetPx) sample *= 2
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

/** 封面：有图就铺满裁切，没图就一个色块 + 音符占位 */
@Composable
fun CoverView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    placeholderColor: Color = Color(0xFF2E3A45),
) {
    Box(modifier.background(placeholderColor), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("♪", fontSize = 26.sp, color = Color(0x66FFFFFF))
        }
    }
}
