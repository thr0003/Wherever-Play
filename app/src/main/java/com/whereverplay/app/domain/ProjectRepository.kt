package com.whereverplay.app.domain

/**
 * WP: 存储抽象。Demo 阶段用 JSON 文件实现；v1 换 Room 时只需换实现，上层不动。
 * 这也是「domain 层不依赖具体存储技术」的体现。
 */
interface ProjectRepository {
    fun all(): List<Project>
    fun get(id: String): Project?
    fun save(project: Project)
    fun delete(id: String)
}
