package com.whereverplay.app.domain

/**
 * WP: 打点规则集中在这里，纯 Kotlin，便于单元测试。
 *
 * 需求约束（见 docs/需求确认稿 §2）：
 *  - 每页恰好出现一次
 *  - 时间严格递增，最小间隔 300ms
 *  - 第 1 页固定在 0:00
 */
object MarkRules {

    const val MIN_GAP_MS = 300L

    /** 校验打点是否合法/完整 */
    fun validate(marks: List<Mark>, pageCount: Int): MarkValidation {
        if (pageCount <= 0) return MarkValidation.Invalid("PDF 页数异常（$pageCount）")
        if (marks.isEmpty()) return MarkValidation.MissingPages((1..pageCount).toList())

        val pages = marks.map { it.page }
        if (pages.toSet().size != pages.size) return MarkValidation.Invalid("同一页出现了多个翻页点")

        val missing = (1..pageCount).filter { it !in pages }
        if (missing.isNotEmpty()) return MarkValidation.MissingPages(missing)

        if (pages != pages.sorted()) return MarkValidation.Invalid("翻页点未按页码顺序排列")

        // 严格递增：第一页必须从 0 开始
        if (marks.first().timeMs != 0L) return MarkValidation.Invalid("第 1 页必须固定在 0:00")

        for (i in 1..marks.lastIndex) {
            val gap = marks[i].timeMs - marks[i - 1].timeMs
            if (gap < MIN_GAP_MS) {
                return MarkValidation.Invalid(
                    "第 ${marks[i].page} 页与上一页的间隔仅 ${gap}ms，至少需要 ${MIN_GAP_MS}ms"
                )
            }
        }
        return MarkValidation.Ok
    }

    /**
     * 拖动 / 微调时把时间夹在相邻两点之间（对应剪映「片段不能穿越邻居」的行为）。
     * index 为 marks 中的下标，marks 需按页码顺序排列。
     */
    fun clampTime(marks: List<Mark>, index: Int, rawTimeMs: Long, durationMs: Long): Long {
        val lower = if (index == 0) 0L else marks[index - 1].timeMs + MIN_GAP_MS
        val upper = if (index == marks.lastIndex) durationMs else marks[index + 1].timeMs - MIN_GAP_MS
        val lo = lower.coerceIn(0L, durationMs)
        val hi = upper.coerceIn(0L, durationMs)
        return if (hi < lo) lo else rawTimeMs.coerceIn(lo, hi)
    }

    /** 移动某个点，返回新的列表（不可变风格，便于撤销栈） */
    fun move(marks: List<Mark>, index: Int, newTimeMs: Long, durationMs: Long): List<Mark> {
        if (index !in marks.indices) return marks
        // WP: 第 1 页的第 1 个点固定死在 0:00 —— 它是整条时间轴的锚点，
        // 允许拖动会直接让项目永远无法通过校验。这条约束放在 domain 层，
        // 而不是靠 UI 禁用，避免将来换了界面又漏。
        if (index == 0) return marks
        val clamped = clampTime(marks, index, newTimeMs, durationMs)
        return marks.toMutableList().also { it[index] = it[index].copy(timeMs = clamped) }
    }

    /** 在播放头处新增「第 N 页」的翻页点。N = 当前最大页 + 1 */
    fun newMarkTime(marks: List<Mark>, playheadMs: Long, durationMs: Long): Long {
        val lastTime = marks.lastOrNull()?.timeMs ?: 0L
        val earliest = lastTime + MIN_GAP_MS
        val t = if (playheadMs < earliest) earliest else playheadMs
        return t.coerceIn(0L, durationMs)
    }

    /** 是否可以继续添加翻页点 */
    fun canAddMore(marks: List<Mark>, pageCount: Int): Boolean = marks.size < pageCount

    /**
     * 把整组打点收进更短的时间轴里，**一页都不删**（0.3.0：移除音频 → 时长回落到 12:00）。
     *
     * 两趟：
     *  1. 从后往前收：超出 [durationMs] 的时间压到时长上限，再往前每点留够 [MIN_GAP_MS]；
     *  2. 从前往后推：把被压得太挤的点往上顶，保证「严格递增 + 最小间隔」仍然成立。
     *
     * 第 1 页永远钉在 0:00（数据模型的硬约束）。
     * 极端情况（页数 × 300ms 已经超过新时长）会顶到上限后不再变化，那时 [validate] 会报错 ——
     * 12 分钟能装 2400 页，现实里不会遇到。
     */
    fun clampToDuration(marks: List<Mark>, durationMs: Long): List<Mark> {
        if (marks.size <= 1) return marks.map { it.copy(timeMs = 0L) }
        val out = marks.toMutableList()

        var limit = durationMs
        for (i in out.lastIndex downTo 1) {
            if (out[i].timeMs > limit) out[i] = out[i].copy(timeMs = limit)
            limit = out[i].timeMs - MIN_GAP_MS
        }
        out[0] = out[0].copy(timeMs = 0L)

        for (i in 1..out.lastIndex) {
            val minT = out[i - 1].timeMs + MIN_GAP_MS
            if (out[i].timeMs < minT) {
                out[i] = out[i].copy(timeMs = minT.coerceAtMost(durationMs))
            }
        }
        return out
    }
}

/** 校验结果 */
sealed interface MarkValidation {
    data object Ok : MarkValidation

    /** 还有这些页没打点 */
    data class MissingPages(val pages: List<Int>) : MarkValidation

    /** 存在结构性问题 */
    data class Invalid(val reason: String) : MarkValidation
}
