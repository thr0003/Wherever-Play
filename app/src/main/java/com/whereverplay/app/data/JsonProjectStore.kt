package com.whereverplay.app.data

import android.content.Context
import com.whereverplay.app.domain.BeatInfo
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
        // 0.3.0：无音频项目写空串（保持键存在，老版本读到空串也知道"没音频"）
        put("audioFile", p.audioFile ?: "")
        put("pdfFile", p.pdfFile)
        put("coverFile", p.coverFile ?: "")
        put("pageCount", p.pageCount)
        put("durationMs", p.durationMs)
        put("isFavorite", p.isFavorite)
        put("tag", p.tag ?: "")
        put("createdAt", p.createdAt)
        put("lastOpenedAt", p.lastOpenedAt)
        put("updatedAt", p.updatedAt)
        // 0.2.1：BPM 存 ×10 整数、第一拍存毫秒。只为「设过」的字段写键，
        // 没设就不写 —— 读的时候缺键即 null，老数据天然兼容，不需要迁移。
        BeatInfo.sanitizeBpm(p.bpm)?.let { put("bpmX10", BeatInfo.toStoredTenths(it)) }
        BeatInfo.sanitizeFirstBeat(p.firstBeatMs)?.let { put("firstBeatMs", it) }
        // 0.2.2：手动翻谱偏好，三态 —— 只在「设过」时写键，null 不写（读的时候缺键即 null）
        p.manualTurn?.let { put("manualTurn", it) }
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
        val lastOpened = o.optLong("lastOpenedAt", created)
        return Project(
            id = o.getString("id"),
            name = o.optString("name", "未命名"),
            author = o.optString("author", ""),
            // 0.3.0：空串 / 缺键都表示「没有音频」，不再是必填
            audioFile = o.optString("audioFile", "").takeIf { it.isNotBlank() },
            pdfFile = o.getString("pdfFile"),
            coverFile = o.optString("coverFile", "").takeIf { it.isNotBlank() },
            pageCount = o.optInt("pageCount", 1),
            // 0.3.0：时长为 0 的脏数据按默认 12 分钟时间轴兜底（无音频项目本来就是这个值）
            durationMs = o.optLong("durationMs", 0L).takeIf { it > 0L } ?: Project.DEFAULT_DURATION_MS,
            marks = marks,
            isFavorite = o.optBoolean("isFavorite", false),
            // 老数据没有 tag 字段 → optString 返回 "" → null，天然向后兼容
            tag = o.optString("tag", "").takeIf { it.isNotBlank() },
            createdAt = created,
            lastOpenedAt = lastOpened,
            // 0.2.1 起老数据没有 updatedAt → 回退用 lastOpenedAt，老项目的排序位置保持不变
            updatedAt = o.optLong("updatedAt", lastOpened),
            // 缺键 / 0 / 负数一律当未设置；第一拍的 0 是合法值，所以用 has() 判存在
            bpm = BeatInfo.fromStoredTenths(o.optInt("bpmX10", 0)),
            firstBeatMs = if (o.has("firstBeatMs")) {
                BeatInfo.sanitizeFirstBeat(o.optLong("firstBeatMs", 0L))
            } else null,
            // 0.2.2：缺键 = 没单独设置过（null），老数据天然兼容
            manualTurn = if (o.has("manualTurn")) o.optBoolean("manualTurn", false) else null,
        )
    }
}
