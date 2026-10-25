package com.whereverplay.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * WP: 把用户通过 SAF 选中的文件复制进应用私有目录。
 *
 * 需求（§9）：媒体一律复制进私有目录，不记外部路径——否则用户移动/删除原文件、
 * 或者换手机之后，项目就全废了。
 */
class MediaInstaller(private val context: Context) {

    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }

    fun fileOf(name: String): File = File(mediaDir, name)

    /**
     * 复制一个 SAF Uri 进来，返回私有目录内的文件名。
     * @param baseName 生成文件名的前缀，如 "audio" / "pdf"
     */
    fun copyIn(uri: Uri, baseName: String, fallbackExt: String): String {
        val displayName = queryDisplayName(uri)
        val ext = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: fallbackExt
        val target = File(mediaDir, "$baseName-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return target.name
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /** 按目标尺寸采样解码，避免大图直接进内存 */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / sample > targetPx) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    /** 供 UI 显示真实的文件名（SAF 的 lastPathSegment 是一串 docId，不能直接展示） */
    fun displayName(uri: Uri): String? = queryDisplayName(uri)

    /** 把内存里的字节（如音频内嵌封面）落成媒体目录里的文件 */
    fun saveBytes(bytes: ByteArray, baseName: String, ext: String): String {
        val target = File(mediaDir, "$baseName-${System.currentTimeMillis()}.$ext")
        target.outputStream().use { it.write(bytes) }
        return target.name
    }

    /**
     * WP: 封面统一存成正方形。
     * 不做交互式裁剪 UI（成本高），直接取中心正方形再缩放 —— 对专辑封面/谱面截图都够用，
     * 好处是列表里永远不会出现被拉成长条的卡片。
     */
    fun saveSquareCover(source: android.graphics.Bitmap, sizePx: Int = 512): String? = runCatching {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = android.graphics.Bitmap.createBitmap(source, left, top, side, side)
        val scaled = android.graphics.Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        val target = File(mediaDir, "cover-${System.currentTimeMillis()}.jpg")
        target.outputStream().use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (square !== source) square.recycle()
        if (scaled !== square) scaled.recycle()
        target.name
    }.getOrNull()

    /** 从 SAF Uri 读图并裁成正方形封面 */
    fun copyInSquareCover(uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        val bitmap = decodeSampled(bytes, 1024) ?: return@runCatching null
        val name = saveSquareCover(bitmap)
        bitmap.recycle()
        name
    }.getOrNull()

    /** 从音频内嵌封面字节裁成正方形封面 */
    fun saveSquareCoverFromBytes(bytes: ByteArray): String? = runCatching {
        val bitmap = decodeSampled(bytes, 1024) ?: return@runCatching null
        val name = saveSquareCover(bitmap)
        bitmap.recycle()
        name
    }.getOrNull()


    companion object {
        /** 读取音频时长（毫秒）；失败返回 0 */
        fun readDurationMs(file: File): Long {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (t: Throwable) {
                0L
            } finally {
                runCatching { retriever.release() }
            }
        }

        /** 读取内嵌封面（mp3/flac 命中率高，wav 通常没有） */
        fun readEmbeddedCover(file: File): ByteArray? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.embeddedPicture
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
