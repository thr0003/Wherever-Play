package com.whereverplay.app.domain

/**
 * WP: 一个「项目」= 1 个音频 + 1 份 PDF + 一组打点。
 * 纯 Kotlin 领域模型，不依赖 android.*，将来可直接被 KMP 复用到 iOS。
 *
 * 文件字段只存「私有媒体目录下的文件名」，不存绝对路径
 * （应用私有目录的绝对路径在重装/系统升级后可能变化）。
 */
data class Project(
    val id: String,
    val name: String,
    val author: String,
    val audioFile: String,
    val pdfFile: String,
    val coverFile: String?,
    val pageCount: Int,
    val durationMs: Long,
    val marks: List<Mark>,
    val isFavorite: Boolean,
    /**
     * WP: 乐器标签（单选）。null = 未设置。
     * 之所以存字符串而不是枚举：用户可以在标签库里自建冷门乐器，
     * 枚举一旦写死就没法扩展；可选值列表由 data/TagStore 统一管理。
     */
    val tag: String? = null,
    val createdAt: Long,
    val lastOpenedAt: Long,
) {
    /** 是否已完成打点（页数齐全且顺序合法） */
    val configured: Boolean
        get() = MarkRules.validate(marks, pageCount) is MarkValidation.Ok

    fun markForPage(page: Int): Mark? = marks.firstOrNull { it.page == page }
}

/** 打点：第 page 页从 timeMs 开始显示 */
data class Mark(val page: Int, val timeMs: Long)
