package com.whereverplay.app.data

import android.content.Context

/**
 * WP: 乐器标签库。
 *
 * 设计取舍：
 *  - 内置 8 个常用乐器**不落盘**，永远排在最前 —— 这样即使用户把自定义标签删到 0，
 *    界面也不会变成空白；
 *  - 用户自建的冷门乐器（如「尺八」「合成器」）存 SharedPreferences 里一段换行分隔的文本，
 *    不引 JSON、不引数据库，和 SettingsStore 保持同一套做法；
 *  - 标签总数（内置 + 自建）上限 [MAX]，超过后「新建标签」直接禁用 ——
 *    标签是给筛选用的，太多了筛选菜单本身就没法点。
 *
 * 注意：这个类不持有 Project，项目只存标签名字符串；改名/删标签不会回溯修改项目。
 */
class TagStore(context: Context) {

    private val prefs = context.getSharedPreferences("wp_tags", Context.MODE_PRIVATE)

    /** 目前可用的全部标签：内置在前，自建在后，保持创建顺序 */
    fun all(): List<String> {
        val custom = customTags()
        return (DEFAULTS + custom)
            .distinct()
            .take(MAX)
    }

    /** 自建标签（不含内置） */
    fun customTags(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 新建标签。
     * @return true = 新建成功；false = 名字为空 / 已存在 / 已达上限
     */
    fun add(name: String): Boolean {
        val clean = name.trim()
            .replace('\n', ' ')
            .take(MAX_NAME_LEN)
        if (clean.isBlank()) return false
        if (all().any { it.equals(clean, true) }) return false
        if (!canAdd()) return false

        val custom = customTags().toMutableList()
        custom.add(clean)
        prefs.edit().putString(KEY_CUSTOM, custom.joinToString("\n")).apply()
        return true
    }

    /** 还能不能再建一个 */
    fun canAdd(): Boolean = all().size < MAX

    companion object {
        /** 标签总数上限（内置 + 自建） */
        const val MAX = 20

        private const val MAX_NAME_LEN = 12
        private const val KEY_CUSTOM = "custom_tags"

        /** 内置乐器。顺序即筛选菜单里的顺序，别随便调 */
        val DEFAULTS = listOf(
            "人声", "电吉他", "木吉他", "贝斯", "鼓", "键盘", "尤克里里", "其它",
        )
    }
}
