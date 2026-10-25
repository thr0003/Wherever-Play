package com.whereverplay.app.data

import android.content.Context
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.ProjectRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * WP: 极简存储 —— 项目列表序列化成一个 JSON 文件。
 *
 * 为什么还不是 Room：v1 的数据量在数百条以内，JSON 完全够用；
 * 而建表会把 author / cover / isFavorite / sourceType 这些字段一次性定死，
 * 与 P3 曲库的工作重叠。等曲库定型后一次性迁 Room，少改一遍结构。
 * 上层只依赖 [ProjectRepository] 接口，替换不影响 UI。
 */
class JsonProjectStore(context: Context) : ProjectRepository {

    private val file = File(context.filesDir, "projects.json")
    private val lock = Any()

    override fun all(): List<Project> = synchronized(lock) {
        readAll().sortedByDescending { it.lastOpenedAt }
    }

    override fun get(id: String): Project? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    override fun save(project: Project) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == project.id } + project)
    }

    override fun delete(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    // ---------- 序列化 ----------

    private fun readAll(): List<Project> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { projectFromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(list: List<Project>) {
        val arr = JSONArray()
        list.forEach { arr.put(projectToJson(it)) }
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免写入过程中被杀导致文件损坏
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun projectToJson(p: Project): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("author", p.author)
        put("audioFile", p.audioFile)
        put("pdfFile", p.pdfFile)
        put("coverFile", p.coverFile ?: "")
        put("pageCount", p.pageCount)
        put("durationMs", p.durationMs)
        put("isFavorite", p.isFavorite)
        put("tag", p.tag ?: "")
        put("createdAt", p.createdAt)
        put("lastOpenedAt", p.lastOpenedAt)
        put("marks", JSONArray().apply {
            p.marks.forEach { m ->
                put(JSONObject().apply { put("page", m.page); put("timeMs", m.timeMs) })
            }
        })
    }

    private fun projectFromJson(o: JSONObject): Project {
        val marksArr = o.optJSONArray("marks") ?: JSONArray()
        val marks = (0 until marksArr.length()).map { i ->
            val m = marksArr.getJSONObject(i)
            Mark(page = m.getInt("page"), timeMs = m.getLong("timeMs"))
        }
        val created = o.optLong("createdAt", 0L)
        return Project(
            id = o.getString("id"),
            name = o.optString("name", "未命名"),
            author = o.optString("author", ""),
            audioFile = o.getString("audioFile"),
            pdfFile = o.getString("pdfFile"),
            coverFile = o.optString("coverFile", "").takeIf { it.isNotBlank() },
            pageCount = o.optInt("pageCount", 1),
            durationMs = o.optLong("durationMs", 0L),
            marks = marks,
            isFavorite = o.optBoolean("isFavorite", false),
            // 老数据没有 tag 字段 → optString 返回 "" → null，天然向后兼容
            tag = o.optString("tag", "").takeIf { it.isNotBlank() },
            createdAt = created,
            lastOpenedAt = o.optLong("lastOpenedAt", created),
        )
    }
}
