package com.whereverplay.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import java.io.Closeable
import java.io.File

/**
 * WP: PDF 单页渲染 + LRU 缓存。
 *
 * 关键约束：
 *  1. [PdfRenderer] 内部不是线程安全的，所有访问都在 [lock] 里串行化。
 *     配套要求：调用方必须把渲染放在同一个线程（见 ui/PdfPreview.kt 的单线程调度器）。
 *  2. 目标是「翻页无迟滞」，所以必须在播放头到达之前把下一页渲染好（预渲染）。
 *  3. 位图必须是 **ARGB_8888**：PdfRenderer.Page.render() 只接受 ARGB_8888 的目标位图，
 *     传 RGB_565 会抛 IllegalArgumentException（曾经踩过：表现为「渲染 3ms + 界面全黑」）。
 *     所以省内存不能靠降色深，只能靠「按需采样宽度 + 限制缓存字节数」。
 */
class PdfPageRenderer(file: File) : Closeable {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private var closed = false

    /** 最近一次渲染失败的原因，供界面显示（不要静默失败） */
    var lastError: String? = null
        private set

    val pageCount: Int = renderer.pageCount

    /**
     * 第 1 页的宽高比（width / height）。
     *
     * 播放页靠它「按可用高度反推渲染宽度」（清单 #22）：A4 竖版约 0.707，
     * 双页跨页（landscape）约 1.414，两者要的宽度差一倍，写死任何一个都不对。
     * 读不到就退回 A4 竖版的比例（宁可窄一点，也别渲染一张用不上的大图）。
     */
    val pageAspect: Float = run {
        var raw: Float? = null
        val page = runCatching { renderer.openPage(0) }.getOrNull()
        if (page != null) {
            try {
                if (page.height > 0) raw = page.width.toFloat() / page.height.toFloat()
            } catch (_: Throwable) {
                raw = null
            } finally {
                runCatching { page.close() }
            }
        }
        raw?.takeIf { it in 0.1f..10f } ?: 0.707f
    }

    private val cache = object : LruCache<Int, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** 已缓存的页，命中时调用方可以直接拿到，无需等待 */
    fun cached(index: Int): Bitmap? = synchronized(lock) {
        if (closed) null else cache.get(index)
    }

    /**
     * 渲染第 [index] 页（0-based）到指定宽度。
     * 返回的 Bitmap 被缓存持有，调用方不要主动 recycle。
     */
    fun render(index: Int, targetWidthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        if (index < 0 || index >= pageCount) return null
        cache.get(index)?.let { return it }

        val startNs = System.nanoTime()
        val page = renderer.openPage(index)
        try {
            val width = targetWidthPx.coerceAtLeast(64)
            val height = (width.toFloat() * page.height / page.width.toFloat())
                .toInt().coerceAtLeast(64)
            // WP: 必须 ARGB_8888，见类注释
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(index, bitmap)
            lastError = null
            Log.d(TAG, "render page=$index ${width}x$height cost=${(System.nanoTime() - startNs) / 1_000_000}ms")
            bitmap
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "render page=$index failed", t)
            null
        } finally {
            runCatching { page.close() }
        }
    }

    /** 预渲染若干页（用于消掉翻页迟滞），调用方需放在后台线程 */
    fun prefetch(indices: Collection<Int>, targetWidthPx: Int) {
        indices.forEach { render(it, targetWidthPx) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cache.evictAll()
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        private const val TAG = "WP-PDF"

        /**
         * 缓存上限。
         *
         * 原来固定 24MB，按 3.3MB/页估的「7 页」—— **估错了**：那按 400dp 宽、密度 2 算的。
         * 高密度手机（密度 3）上一页是 1200×1697 ARGB ≈ 8.1MB，24MB 只装得下 2 页多一点，
         * 而播放页同时要「上一页 / 当前页 / 下一页」三张 —— 预渲染邻居时正好把**当前页**挤掉，
         * 当前页于是永远停在「正在渲染第 N 页…」（没有东西会再触发它渲染）。
         * 平板密度低、一页才 2MB 左右，所以同样的代码在平板上看起来没问题。
         *
         * 现在按系统版本分档：API 26 起 Bitmap 像素走 native 堆，可以放宽到 48MB（≈5 页高密度）；
         * 26 以下像素还在 Java 堆里，保持保守值，避免老机器 OOM。
         */
        private val MAX_CACHE_BYTES: Int = run {
            val mb = if (android.os.Build.VERSION.SDK_INT >= 26) 48 else 20
            mb * 1024 * 1024
        }

        /**
         * **单页位图的字节预算**（清单 #22）。
         *
         * 谱面改成「按可用高度反推宽度」后，一页比以前宽 20%~30%，字节数按平方涨：
         * 1080p 竖屏、A4 谱面从 1080px 宽（≈9.2MB）涨到 ≈1360px（≈12.5MB）。
         * 播放页同时要「上一页 / 当前页 / 下一页」三张，所以一页必须留出
         * 48MB ÷ 3 ≈ 16MB 以内 —— 这里取 12MB，给预渲染和曲间遮罩底图留余量。
         *
         * 超预算时**降宽而不是降色深**（PdfRenderer 只接受 ARGB_8888，见类注释）。
         */
        const val PAGE_BYTES_BUDGET: Int = 12 * 1024 * 1024

        /**
         * 按页面宽高比算出「不超过 [PAGE_BYTES_BUDGET]」的最大渲染宽度（像素）。
         * 一页字节数 = w × h × 4，而 h = w / aspect，所以 w = √(budget × aspect / 4)。
         */
        fun maxWidthForBudget(aspect: Float): Int {
            val a = aspect.coerceIn(0.1f, 10f)
            return kotlin.math.sqrt(PAGE_BYTES_BUDGET.toDouble() * a / 4.0).toInt().coerceAtLeast(64)
        }

        /** 只读取页数，用于创建项目时校验（会立刻释放句柄） */
        fun pageCountOf(file: File): Int = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)
    }
}
