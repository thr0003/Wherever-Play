package com.whereverplay.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * WP: 「另存到手机」的两条出口。
 *
 *  - [saveImageToGallery]：封面这种图片直接进系统相册，用户在「相册 / 图片」里就能看到。
 *    API ≥ 29 走 MediaStore + `RELATIVE_PATH`，**不需要任何权限**；
 *    API 24–28 只能往公共 Pictures 目录写，需要 WRITE_EXTERNAL_STORAGE
 *    （manifest 里已加，maxSdkVersion=28，只对老系统生效）。
 *  - [copyFileToUri]：音频 / PDF 这类「用户自己知道想放哪」的文件走 SAF 另存为，零权限。
 *
 * 为什么音频/PDF 不进公共目录：它们可能有几十 MB，用户多半想直接放到某个文件夹（U 盘、
 * 网盘同步目录），SAF 让他自己选；而封面是「想保存下来看图」的场景，进相册才符合直觉。
 */
object FileSaver {

    /** API < 29 且没授权时，存相册前必须先要权限 */
    fun needsLegacyWritePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 把私有目录里的图片写进系统相册。
     * 返回写入后的 Uri；失败抛异常（调用方负责提示），中途失败会把半成品条目删掉。
     */
    fun saveImageToGallery(context: Context, source: File, displayName: String): Uri {
        require(source.exists()) { "源图片不存在" }
        val resolver = context.contentResolver
        val name = ensureExt(displayName, "jpg")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/WhereverPlay",
                )
                // 写完才让其它 App 看见，避免相册里出现半张图
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: error("无法在相册中创建条目")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("无法写入相册")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    /** 把私有目录里的文件写到用户通过 SAF 选定的 Uri */
    fun copyFileToUri(context: Context, source: File, target: Uri) {
        require(source.exists()) { "源文件不存在" }
        context.contentResolver.openOutputStream(target)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: error("无法写入目标文件")
    }

    /** 用户可能在另存对话框里把后缀删了，补齐 */
    fun ensureExt(name: String, ext: String): String =
        if (name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"

    /** 从项目名推一个安全的导出文件名（去掉路径分隔符之类的字符） */
    fun safeBaseName(raw: String, fallback: String): String {
        val cleaned = raw.trim().map { c ->
            if (c in "\\/:*?\"<>|" || c.code < 0x20) '_' else c
        }.joinToString("")
        return cleaned.ifBlank { fallback }
    }
}
