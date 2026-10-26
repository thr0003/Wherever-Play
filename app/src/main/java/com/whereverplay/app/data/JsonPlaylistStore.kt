package com.whereverplay.app.data

import android.content.Context
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.PlaylistRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** WP: 播放列表的 JSON 存储，与 JsonProjectStore 同构（v1 数据量小，够用） */
class JsonPlaylistStore(context: Context) : PlaylistRepository {

    private val file = File(context.filesDir, "playlists.json")
    private val lock = Any()

    override fun all(): List<Playlist> = synchronized(lock) {
        readAll().sortedByDescending { it.lastOpenedAt }
    }

    override fun get(id: String): Playlist? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    override fun save(playlist: Playlist) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == playlist.id } + playlist)
    }

    override fun delete(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    private fun readAll(): List<Playlist> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun toJson(p: Playlist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("projectIds", JSONArray(p.projectIds))
        put("advanceMode", p.advanceMode)
        put("gapMs", p.gapMs)
        put("coverFile", p.coverFile ?: "")
        put("createdAt", p.createdAt)
        put("lastOpenedAt", p.lastOpenedAt)
        // 0.2.2：手动翻谱偏好（三态），只在设过时写
        p.manualTurn?.let { put("manualTurn", it) }
    }

    private fun fromJson(o: JSONObject): Playlist {
        val ids = o.optJSONArray("projectIds") ?: JSONArray()
        val list = (0 until ids.length()).mapNotNull { i ->
            ids.optString(i, "").takeIf { it.isNotBlank() }
        }
        val created = o.optLong("createdAt", 0L)
        return Playlist(
            id = o.getString("id"),
            name = o.optString("name", "未命名列表"),
            projectIds = list,
            advanceMode = o.optInt("advanceMode", Playlist.ADVANCE_MANUAL),
            gapMs = o.optLong("gapMs", 3000L),
            coverFile = o.optString("coverFile", "").takeIf { it.isNotBlank() },
            createdAt = created,
            lastOpenedAt = o.optLong("lastOpenedAt", created),
            manualTurn = if (o.has("manualTurn")) o.optBoolean("manualTurn", false) else null,
        )
    }
}
