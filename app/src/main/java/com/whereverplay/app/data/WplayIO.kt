package com.whereverplay.app.data

import android.content.Context
import android.net.Uri
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.PlaylistRepository
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.ProjectRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * WP: .wplay / .wplist 打包格式。
 *
 * 为什么是 zip 而不是单一 JSON：一个项目必须自带音频和谱面，否则分享出去对方打不开。
 * 容器结构（两种格式同构）：
 *
 * ```
 * manifest.json          ← 先写入，导入时可以先看格式再决定怎么处理
 * media/audio-xxx.mp3
 * media/pdf-xxx.pdf
 * media/cover-xxx.jpg
 * ```
 *
 * 两条关键约定：
 *  1. **项目导出保留原 id**。导入时若本机没有同 id 项目就沿用 —— 这正是
 *     Playlist 里「不可用」占位能自动恢复的前提（见 domain/Playlist.kt 的注释）。
 *     若 id 已被占用则换成新 UUID，避免覆盖本机已有项目。
 *  2. **播放列表导出会把可用项目一起打包**（`.wplist` 是自包含的）。只存 id 的话，
 *     文件到了别人手机上必然全是失效项 —— 引用关系的意义是「本机」的，不是跨设备的。
 *     导入时若本机已有同 id 项目则直接复用，不重复占空间。
 */
object WplayIO {

    const val EXT_PROJECT = "wplay"
    const val EXT_PLAYLIST = "wplist"

    /** 导出时用这个 MIME：系统文件选择器不会给文件名硬塞 .bin/.zip 后缀 */
    const val MIME = "application/octet-stream"

    private const val FORMAT_PROJECT = "wplay"
    private const val FORMAT_PLAYLIST = "wplist"
    private const val VERSION = 1
    private const val MANIFEST = "manifest.json"

    /** 导入结果：各导入了多少个（界面上给一句话提示用） */
    data class ImportResult(val projects: Int, val playlists: Int)

    /** 建议文件名（去掉路径非法字符），导入/导出选择器上显示的就是它 */
    fun suggestedFileName(name: String, playlist: Boolean): String {
        val fallback = if (playlist) "播放列表" else "项目"
        val safe = name.trim().ifBlank { fallback }
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_")
            .take(60)
        return "$safe.${if (playlist) EXT_PLAYLIST else EXT_PROJECT}"
    }

    // ==================== 导出 ====================

    fun exportProjectTo(context: Context, project: Project, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法写入所选位置")
        out.use { writeProject(context, project, it) }
    }

    fun exportPlaylistTo(context: Context, playlist: Playlist, projects: List<Project>, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法写入所选位置")
        out.use { writePlaylist(context, playlist, projects, it) }
    }

    fun writeProject(context: Context, project: Project, out: OutputStream) {
        val media = File(context.filesDir, "media")
        ZipOutputStream(out.buffered()).use { zip ->
            val (obj, files) = projectJson(project, media)
            val manifest = JSONObject()
                .put("format", FORMAT_PROJECT)
                .put("version", VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("project", obj)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            putFiles(zip, files)
        }
    }

    fun writePlaylist(
        context: Context,
        playlist: Playlist,
        projects: List<Project>,
        out: OutputStream,
    ) {
        val media = File(context.filesDir, "media")
        val byId = projects.associateBy { it.id }
        ZipOutputStream(out.buffered()).use { zip ->
            val arr = JSONArray()
            val files = LinkedHashMap<String, File>()
            // 只打包仍然可用的项目；失效项在导出时就丢掉，而不是让对方看到一个灰条
            val keptIds = playlist.projectIds.filter { byId.containsKey(it) }
            keptIds.forEach { id ->
                val p = byId.getValue(id)
                val (obj, fs) = projectJson(p, media)
                fs.forEach { (entry, file) -> files[entry] = file }
                arr.put(JSONObject().put("refId", p.id).put("project", obj))
            }
            val manifest = JSONObject()
                .put("format", FORMAT_PLAYLIST)
                .put("version", VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put(
                    "playlist", JSONObject()
                        .put("name", playlist.name)
                        .put("advanceMode", playlist.advanceMode)
                        .put("gapMs", playlist.gapMs)
                        .put("projectIds", JSONArray(keptIds)),
                )
                .put("projects", arr)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            putFiles(zip, files)
        }
    }

    private fun putFiles(zip: ZipOutputStream, files: Map<String, File>) {
        files.forEach { (entry, file) ->
            if (!file.exists()) return@forEach
            zip.putNextEntry(ZipEntry(entry))
            file.inputStream().use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
    }

    /**
     * 把一个项目序列化成 manifest 片段 + 需要打包的文件清单。
     * 媒体文件在 zip 里的名字带类型前缀（audio-/pdf-/cover-），避免三类同名互相覆盖。
     */
    private fun projectJson(p: Project, media: File): Pair<JSONObject, Map<String, File>> {
        val files = LinkedHashMap<String, File>()

        fun entryOf(fileName: String?, prefix: String): String? {
            if (fileName.isNullOrBlank()) return null
            val f = File(media, fileName)
            if (!f.exists()) return null
            val entry = "media/$prefix-${f.name}"
            files[entry] = f
            return entry
        }

        val audio = entryOf(p.audioFile, "audio")
        val pdf = entryOf(p.pdfFile, "pdf")
        val cover = entryOf(p.coverFile, "cover")

        val obj = JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("author", p.author)
            .put("tag", p.tag ?: "")
            .put("pageCount", p.pageCount)
            .put("durationMs", p.durationMs)
            .put("isFavorite", p.isFavorite)
            .put("createdAt", p.createdAt)
            .put("audio", audio ?: "")
            .put("pdf", pdf ?: "")
            .put("cover", cover ?: "")
            .put(
                "marks", JSONArray().apply {
                    p.marks.forEach { m ->
                        put(JSONObject().put("page", m.page).put("timeMs", m.timeMs))
                    }
                },
            )
        return obj to files
    }

    // ==================== 导入 ====================

    /**
     * 导入一个 .wplay 或 .wplist。
     *
     * 实现细节：先把 content:// 流落到 cacheDir 的临时 zip 文件，再用 [ZipFile] 随机读 ——
     * 这样 manifest 写在最后也能读（对第三方的、顺序不同的包更宽容），也不会把
     * 几十 MB 的音频整个读进内存。临时文件在 finally 里删掉。
     */
    fun importFrom(
        context: Context,
        uri: Uri,
        projectRepo: ProjectRepository,
        playlistRepo: PlaylistRepository,
    ): ImportResult {
        val tmp = File(context.cacheDir, "wp-import-${System.currentTimeMillis()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }

            ZipFile(tmp).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: error("不是 Wherever Play 的打包文件（缺少 manifest）")
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
                val manifest = JSONObject(text)
                if (manifest.optInt("version", 1) > VERSION) {
                    error("这个文件来自更新版本的 App，请先升级")
                }
                return when (manifest.optString("format")) {
                    FORMAT_PROJECT -> {
                        val project = importProject(context, zip, manifest.getJSONObject("project"), null, projectRepo)
                        projectRepo.save(project)
                        ImportResult(projects = 1, playlists = 0)
                    }

                    FORMAT_PLAYLIST -> importPlaylist(context, zip, manifest, projectRepo, playlistRepo)

                    else -> error("不认识的打包格式：${manifest.optString("format")}")
                }
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun importProject(
        context: Context,
        zip: ZipFile,
        obj: JSONObject,
        forcedId: String?,
        repo: ProjectRepository,
    ): Project {
        val media = File(context.filesDir, "media").apply { mkdirs() }

        fun extract(entryName: String?, prefix: String): String? {
            if (entryName.isNullOrBlank()) return null
            val e = zip.getEntry(entryName) ?: return null
            val ext = entryName.substringAfterLast('.', "").ifBlank { "bin" }
            // 重新起名（带时间戳 + 随机数），永远不覆盖本机已有文件
            val target = File(media, "$prefix-${System.currentTimeMillis()}-${(100..999).random()}.$ext")
            // 必须流式复制：一首 30 分钟的 flac 可能上百 MB，readBytes() 会直接 OOM
            zip.getInputStream(e).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            return target.name
        }

        val audio = extract(obj.optString("audio"), "audio") ?: error("打包文件里缺少音频")
        val pdf = extract(obj.optString("pdf"), "pdf") ?: error("打包文件里缺少谱面")
        val cover = extract(obj.optString("cover"), "cover")

        val marksArr = obj.optJSONArray("marks") ?: JSONArray()
        val marks = (0 until marksArr.length()).map { i ->
            val m = marksArr.getJSONObject(i)
            Mark(page = m.getInt("page"), timeMs = m.getLong("timeMs"))
        }.ifEmpty { listOf(Mark(page = 1, timeMs = 0L)) }

        val originId = forcedId?.takeIf { it.isNotBlank() } ?: obj.optString("id")
        // 原 id 空着就沿用：让「重新导入同一个项目」能自动接上播放列表里的失效项
        val id = originId.takeIf { it.isNotBlank() && repo.get(it) == null }
            ?: UUID.randomUUID().toString()

        val now = System.currentTimeMillis()
        return Project(
            id = id,
            name = obj.optString("name", "未命名项目"),
            author = obj.optString("author", ""),
            audioFile = audio,
            pdfFile = pdf,
            coverFile = cover,
            pageCount = obj.optInt("pageCount", 1).coerceAtLeast(1),
            durationMs = obj.optLong("durationMs", 0L),
            marks = marks,
            isFavorite = obj.optBoolean("isFavorite", false),
            tag = obj.optString("tag", "").takeIf { it.isNotBlank() },
            createdAt = obj.optLong("createdAt", now),
            lastOpenedAt = now,
        )
    }

    private fun importPlaylist(
        context: Context,
        zip: ZipFile,
        manifest: JSONObject,
        projectRepo: ProjectRepository,
        playlistRepo: PlaylistRepository,
    ): ImportResult {
        val plObj = manifest.getJSONObject("playlist")
        val arr = manifest.optJSONArray("projects") ?: JSONArray()

        var importedProjects = 0
        val idMap = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val refId = e.optString("refId")
            val existing = refId.takeIf { it.isNotBlank() }?.let { projectRepo.get(it) }
            val resolved = if (existing != null) {
                // 本机已经有这首 → 直接复用，不重复占空间
                existing
            } else {
                val p = importProject(context, zip, e.getJSONObject("project"), refId, projectRepo)
                projectRepo.save(p)
                importedProjects += 1
                p
            }
            if (refId.isNotBlank()) idMap[refId] = resolved.id
        }

        val rawIds = plObj.optJSONArray("projectIds") ?: JSONArray()
        val ids = (0 until rawIds.length()).mapNotNull { idMap[rawIds.optString(it, "")] }

        val now = System.currentTimeMillis()
        playlistRepo.save(
            Playlist(
                id = UUID.randomUUID().toString(),
                name = plObj.optString("name", "导入的播放列表"),
                projectIds = ids,
                advanceMode = plObj.optInt("advanceMode", Playlist.ADVANCE_MANUAL),
                gapMs = plObj.optLong("gapMs", 3000L),
                coverFile = null,
                createdAt = now,
                lastOpenedAt = now,
            )
        )
        return ImportResult(projects = importedProjects, playlists = 1)
    }
}
