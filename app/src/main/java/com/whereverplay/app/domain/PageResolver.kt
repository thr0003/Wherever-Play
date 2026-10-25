package com.whereverplay.app.domain

/**
 * WP: 时间 → 页码 的解析。这是整个 App 的核心一行逻辑：
 * 取「timeMs ≤ 当前时刻」的最后一个打点，它就是当前应该显示的页。
 */
object PageResolver {

    fun pageAt(marks: List<Mark>, timeMs: Long): Int {
        if (marks.isEmpty()) return 1
        var page = marks.first().page
        for (m in marks) {
            if (m.timeMs <= timeMs) page = m.page else break
        }
        return page
    }

    /** 当前时刻对应的、即将到来的下一个翻页点（用于预渲染） */
    fun nextMark(marks: List<Mark>, timeMs: Long): Mark? = marks.firstOrNull { it.timeMs > timeMs }

    /** 页码 → marks 下标 */
    fun indexOfPage(marks: List<Mark>, page: Int): Int = marks.indexOfFirst { it.page == page }
}

/** 时间码格式化：12:34 / 12:34.5 */
object TimeFormat {

    fun mmss(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    fun mmssTenth(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val total = safe / 1000
        val tenth = (safe % 1000) / 100
        val m = total / 60
        val s = total % 60
        return "%d:%02d.%d".format(m, s, tenth)
    }
}
