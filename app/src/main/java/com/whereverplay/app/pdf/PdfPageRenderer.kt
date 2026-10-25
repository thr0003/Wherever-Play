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

        /** 缓存上限：24MB，约可容纳 7 页 A4 谱（按 3.3MB/页算） */
        private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

        /** 只读取页数，用于创建项目时校验（会立刻释放句柄） */
        fun pageCountOf(file: File): Int = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)
    }
}
