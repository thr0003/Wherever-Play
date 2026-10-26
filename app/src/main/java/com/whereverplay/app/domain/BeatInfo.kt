package com.whereverplay.app.domain

/**
 * WP: BPM / 第一拍时间的规则与文案（0.2.1）。
 *
 * 这两个字段描述「拍网格」：t(n) = firstBeatMs + n × 60000 / bpm。
 * 用途是伴奏的起拍不明显时，配合将来的节拍器做倒数、精确卡进第一拍。
 *
 * 为什么要单独一个文件：数值范围、取整规则、显示格式这三件事在
 * ① 存储读写 ② 导入导出 ③ 详情页 ④ 输入弹窗 里都要用到，散着写必有分歧。
 *
 * 这里只管**规则和文案**，不碰 android.*，也不碰 UI。
 */
object BeatInfo {

    const val BPM_MAX = 1000f
    const val BPM_DECIMALS = 1

    /** 第一拍时间上限（毫秒）：9999.999 秒，够任何 30 分钟以内的伴奏 */
    const val FIRST_BEAT_MAX_MS = 9_999_999L

    /**
     * 归一化 BPM：
     *  - null / ≤0 / 非有限数 → null（0 视为未设置，因为 60000/0 无意义）
     *  - 夹到 0.1…1000.0
     *  - 保留 1 位小数（先 ×10 取整再除回来，避免 120.3 这类值在 Float 里来回转换后变脏）
     */
    fun sanitizeBpm(raw: Float?): Float? {
        if (raw == null || raw.isNaN() || raw.isInfinite() || raw <= 0f) return null
        val clamped = raw.coerceAtMost(BPM_MAX)
        val tenths = (clamped * 10f + 0.5f).toInt()
        return tenths / 10f
    }

    /** 归一化第一拍时间：负数当未设置，超上限夹住 */
    fun sanitizeFirstBeat(raw: Long?): Long? =
        raw?.takeIf { it >= 0L }?.coerceAtMost(FIRST_BEAT_MAX_MS)

    /**
     * 存储用：BPM ×10 的整数（120.5 → 1205），0 表示未设置。
     * 存整数而不是浮点，是为了让 `120.3` 这种十进制小数在 JSON 里不会变成 `120.29999…`。
     */
    fun toStoredTenths(bpm: Float?): Int = sanitizeBpm(bpm)?.let { (it * 10f + 0.5f).toInt() } ?: 0

    /** 存储读回：1205 → 120.5f；≤0（含缺字段）→ null */
    fun fromStoredTenths(x: Int): Float? = if (x <= 0) null else sanitizeBpm(x / 10f)

    /** BPM 显示成 "120.5"；固定 1 位小数，整数也带 .0，避免 "120" 和 "120.0" 混着出现 */
    fun bpmText(bpm: Float): String = String.format("%.1f", bpm)

    /**
     * 第一拍显示成秒，去掉没意义的尾随 0：
     * 0.35s → "0.35 秒"、2.000s → "2 秒"、12.5s → "12.5 秒"。
     */
    fun secondsText(ms: Long): String {
        if (ms % 1000L == 0L) return "${ms / 1000} 秒"
        val s = String.format("%.3f", ms / 1000.0).trimEnd('0').trimEnd('.')
        return "$s 秒"
    }

    /**
     * 详情页那一行的一句话摘要。两个都没设 → "未设置"。
     * 只设了一个也照实显示，另一方用 "—"，因为这两项是分别可填的。
     */
    fun summary(bpm: Float?, firstBeatMs: Long?): String {
        if (bpm == null && firstBeatMs == null) return "未设置"
        val b = bpm?.let { "${bpmText(it)} BPM" } ?: "BPM —"
        val f = firstBeatMs?.let { "第一拍 ${secondsText(it)}" } ?: "第一拍 —"
        return "$b · $f"
    }
}
