package com.whereverplay.app.domain

/**
 * WP: 一个「项目」= 1 份 PDF +（可选）1 个音频 + 一组打点。
 * 纯 Kotlin 领域模型，不依赖 android.*，将来可直接被 KMP 复用到 iOS。
 *
 * 文件字段只存「私有媒体目录下的文件名」，不存绝对路径
 * （应用私有目录的绝对路径在重装/系统升级后可能变化）。
 */
data class Project(
    val id: String,
    val name: String,
    val author: String,
    /**
     * 伴奏音频的文件名。**null = 这个项目没有音频**（0.3.0 起允许）：
     * 播放页退化成「谱面 + 静音时间轴」，时间轴长度取 [DEFAULT_DURATION_MS]。
     */
    val audioFile: String?,
    val pdfFile: String,
    val coverFile: String?,
    val pageCount: Int,
    /**
     * 时间轴长度（毫秒）。有音频时是音频真实时长；
     * 无音频时由上层写成 [DEFAULT_DURATION_MS]（12:00），打点、进度条、打点页都按它算。
     */
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
    /**
     * WP: 属性最后被修改的时刻（毫秒）。首页「最近修改」排序的唯一依据。
     * 点进去看 / 播放**不算**修改（那只刷 [lastOpenedAt]）；改名/改作者/改标签/收藏/换文件/
     * 改打点/改 BPM 等任何内容变化才算 —— 由 AppRoot.saveProject 的集中盖章保证，
     * 不要在各个修改点手工赋值。不属于 .wplay 分享格式（设备本地属性）。
     */
    val updatedAt: Long,
    /**
     * WP: BPM（每分钟拍数），保留 1 位小数，范围 0.1–1000.0。null = 未设置。
     * 0.0 在数学上意味着「拍间隔无穷大」，没有意义，所以 0 一律按未设置处理（见 BeatInfo.sanitizeBpm）。
     * 配合 [firstBeatMs] 定义拍网格：t(n) = firstBeatMs + n × 60000 / bpm。
     */
    val bpm: Float? = null,
    /**
     * WP: 第一拍在音频里的绝对时间（毫秒）。null = 未设置。
     * **0 是合法值**（第一拍就落在 0:00），所以判空不能用 `== 0`。
     */
    val firstBeatMs: Long? = null,
    /**
     * WP: 这个项目播放时是否**手动翻谱**（忽略打点，只跟手指走）。
     * 三态：`null` = 没单独设置过 → 跟随设置页的「默认手动翻谱」；
     * `true / false` = 用户在播放页左下角点过 → 以它为准。
     * 这是**播放偏好**，不算内容修改（[updatedAt] 不为它刷新，见 AppRoot.stampUpdate）。
     * 会跟着 `.wplay` 一起走，换设备后仍然生效。
     */
    val manualTurn: Boolean? = null,
) {
    /** 是否已完成打点（页数齐全且顺序合法） */
    val configured: Boolean
        get() = MarkRules.validate(marks, pageCount) is MarkValidation.Ok

    /** 有没有伴奏音频。无音频 = 纯谱面 + 静音时间轴（见 [DEFAULT_DURATION_MS]） */
    val hasAudio: Boolean
        get() = audioFile != null

    fun markForPage(page: Int): Mark? = marks.firstOrNull { it.page == page }

    companion object {
        /**
         * 无音频项目的默认时间轴长度：**12 分钟**。
         * 用户仍然可以在这条 12 分钟的空时间轴上打点，播放时按打点定时翻谱。
         */
        const val DEFAULT_DURATION_MS = 12L * 60L * 1000L
    }
}

/** 打点：第 page 页从 timeMs 开始显示 */
data class Mark(val page: Int, val timeMs: Long)
