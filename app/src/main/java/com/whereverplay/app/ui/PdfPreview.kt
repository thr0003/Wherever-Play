package com.whereverplay.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.pdf.PdfPageRenderer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * WP: 谱面位图加载器。
 *
 * 单独抽出来的原因：
 *  1. PdfRenderer 必须串行访问 —— 这里给它一个**单线程调度器**，保证所有渲染都在同一线程；
 *  2. 当前页渲染完成后，顺手预渲染下一页和上一页，这是「翻页不迟滞」的关键；
 *  3. 打点页和播放页共用同一套逻辑。
 */
@Composable
fun rememberPdfPage(
    file: File,
    page: Int,
    targetWidthPx: Int,
): Bitmap? {
    val dispatcher = remember {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "wp-pdf").apply { priority = Thread.NORM_PRIORITY }
        }.asCoroutineDispatcher()
    }
    val renderer = remember(file.absolutePath) {
        runCatching { PdfPageRenderer(file) }.getOrNull()
    }

    DisposableEffect(renderer, dispatcher) {
        onDispose {
            renderer?.close()
            dispatcher.close()
        }
    }

    var bitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(renderer, page, targetWidthPx) {
        val r = renderer ?: return@LaunchedEffect
        if (targetWidthPx <= 0) return@LaunchedEffect

        val hit = r.cached(page)
        if (hit != null) {
            // 命中缓存 = 翻页那一刻没有渲染成本，这正是预渲染想要的效果
            DebugStats.cacheHit = true
            DebugStats.lastRenderMs = 0L
            DebugStats.lastRenderPage = page + 1
            bitmap = hit
        } else {
            val start = android.os.SystemClock.elapsedRealtime()
            val rendered = withContext(dispatcher) { r.render(page, targetWidthPx) }
            DebugStats.cacheHit = false
            DebugStats.lastRenderMs = android.os.SystemClock.elapsedRealtime() - start
            DebugStats.lastRenderPage = page + 1
            // 渲染失败要能被看见，不能静默黑屏
            DebugStats.renderError =
                if (rendered == null) (r.lastError ?: "未知原因返回空位图") else null
            bitmap = rendered
        }

        // 预渲染邻居页：下一页优先，上一页其次
        withContext(dispatcher) {
            val neighbours = ArrayList<Int>(2)
            if (page + 1 < r.pageCount) neighbours.add(page + 1)
            if (page - 1 >= 0) neighbours.add(page - 1)
            r.prefetch(neighbours, targetWidthPx)
        }
    }

    return bitmap
}

/** 按容器等比居中绘制谱面；bitmap 为空时显示提示（渲染失败不再静默黑屏） */
@Composable
fun PdfPageView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val bmp = bitmap ?: return@Canvas
            val scale = minOf(size.width / bmp.width, size.height / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            drawImage(
                image = bmp.asImageBitmap(),
                dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
        if (bitmap == null && emptyHint != null) {
            Text(
                emptyHint,
                fontSize = 12.sp,
                color = Color(0xFF8A8A92),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
