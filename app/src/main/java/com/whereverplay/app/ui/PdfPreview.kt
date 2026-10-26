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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

/**
 * WP: 谱面渲染的目标宽度 = min(400dp, 屏幕宽度)。
 *
 * 为什么不能写死 400dp：高密度机的逻辑宽度可能不到 400dp（本机模拟器
 * 1440×2560 @ 640dpi → 密度 4 → 屏幕上只有 360dp 宽）。这时按 400dp 渲染等于
 * 多渲染 23% 的像素、再缩回去显示 —— 纯浪费内存，而内存恰恰是三页并排最紧的地方
 * （这才是「手机上一直在渲染、平板上没事」的根因之一）。
 */
@Composable
fun rememberPageRenderWidth(): Int {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    return remember(density, screenWidthDp) {
        val byDp = with(density) { 400.dp.toPx() }
        val byScreen = with(density) { screenWidthDp.toPx() }
        minOf(byDp, byScreen).toInt()
    }
}

/**
 * WP: **按可用区域反推渲染宽度**（清单 #22，播放页专用）。
 *
 * 旧的「min(400dp, 屏宽)」是按宽度定的，竖版 A4 谱面在竖屏手机上会被高度卡住，
 * 于是渲染出来的图比显示的大一圈（或者反过来，宽高比大的跨页谱渲染得太小、上屏再放大）。
 * 这里改成：先把「整块可用区域」交给调用方，再按**页面自身的宽高比**算出铺满所需的宽度，
 * 取 `min(可用宽, 可用高 × 宽高比)` —— 正好等于页面在屏幕上的实际像素尺寸。
 *
 * 最后再被 [PdfPageRenderer.maxWidthForBudget] 封顶：谱面铺满全屏后一页的字节数是以前的
 * 1.5 倍左右，不封顶会把 48MB 缓存挤爆（老坑：「正在渲染第 N 页…」永远不消失）。
 */
@Composable
fun rememberPageRenderWidth(aspect: Float, availWidthPx: Int, availHeightPx: Int): Int {
    return remember(aspect, availWidthPx, availHeightPx) {
        val byArea = minOf(availWidthPx, (availHeightPx * aspect).toInt())
        minOf(byArea, PdfPageRenderer.maxWidthForBudget(aspect)).coerceAtLeast(64)
    }
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
