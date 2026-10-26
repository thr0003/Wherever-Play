package com.whereverplay.app.domain

/**
 * WP: 播放列表 —— 一场演出的曲目顺序。
 * 只存 projectId 的引用，不复制项目本身；项目被删时这里保留占位（id 仍在），
 * 这样界面能显示「N 项不可用」，用户重新导入同一项目（id 相同）还能自动恢复。
 */
data class Playlist(
    val id: String,
    val name: String,
    val projectIds: List<String>,
    /** 0 = 一首播完暂停，点击继续；1 = 暂停 gapMs 后自动下一首 */
    val advanceMode: Int,
    val gapMs: Long,
    val coverFile: String?,
    val createdAt: Long,
    val lastOpenedAt: Long,
    /**
     * WP: 这个列表播放时是否**手动翻谱**。三态含义与 [Project.manualTurn] 完全一致：
     * `null` = 跟随设置页的全局默认；`true/false` = 在播放页左下角点过，以它为准。
     * 作用于整个列表（列表里换歌不重置），跟着 `.wplist` 一起走。
     */
    val manualTurn: Boolean? = null,
) {
    companion object {
        const val ADVANCE_MANUAL = 0
        const val ADVANCE_AUTO = 1
    }
}

/** 播放列表存储抽象（与 ProjectRepository 同构，方便以后一起换 Room） */
interface PlaylistRepository {
    fun all(): List<Playlist>
    fun get(id: String): Playlist?
    fun save(playlist: Playlist)
    fun delete(id: String)
}

/** 一个播放列表里某一项的运行时状态 */
sealed interface PlaylistEntry {
    /** 正常可用 */
    data class Available(val project: Project) : PlaylistEntry

    /** 引用的项目已被删除 —— 保留占位，显示为灰色不可用 */
    data class Missing(val projectId: String) : PlaylistEntry
}

/** 把 projectIds 解析成可渲染的条目 */
fun Playlist.resolve(projects: Map<String, Project>): List<PlaylistEntry> =
    projectIds.map { id ->
        projects[id]?.let { PlaylistEntry.Available(it) } ?: PlaylistEntry.Missing(id)
    }
