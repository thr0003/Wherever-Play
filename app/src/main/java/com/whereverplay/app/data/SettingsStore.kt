package com.whereverplay.app.data

import android.content.Context

/**
 * WP: 轻量设置存储。用 SharedPreferences 而不是 DataStore：
 * Demo→v1 阶段只有几个标量，SharedPreferences 同步读取、零依赖、最简单。
 * 等外观设置（背景图/字体档位）全做完，再迁 DataStore 也不迟。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wp_settings", Context.MODE_PRIVATE)

    /** 翻页提前量：正数表示提前翻（毫秒） */
    var pageTurnOffsetMs: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(value) = prefs.edit().putLong(KEY_OFFSET, value.coerceIn(-500L, 500L)).apply()

    /** 播放页是否保持屏幕常亮 */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ON, value).apply()

    /** 翻页动画：0 无 / 1 淡入淡出 / 2 滑动 */
    var pageTurnAnimation: Int
        get() = prefs.getInt(KEY_ANIM, 2)
        set(value) = prefs.edit().putInt(KEY_ANIM, value).apply()

    /** 播放页底部性能仪表盘（默认关，从播放页「更多」里开关） */
    var showDebugStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS, value).apply()

    private companion object {
        const val KEY_OFFSET = "page_turn_offset_ms"
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_ANIM = "page_turn_animation"
        const val KEY_STATS = "show_debug_stats"
    }
}
