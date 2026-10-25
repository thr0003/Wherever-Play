package com.whereverplay.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * WP: Demo 阶段的「随身仪表盘」。
 *
 * 为什么要有它：MuMu 之类模拟器不一定开了 adb 端口，Logcat 拿不到。
 * 把关键指标直接画在界面上，测试的人截图就能反馈，不依赖任何工具链。
 * v1 会删掉（或者收进设置的「开发者信息」里）。
 */
object DebugStats {
    /** 最近一次页面渲染耗时（毫秒）；命中缓存时为 0 */
    var lastRenderMs by mutableStateOf(0L)

    /** 最近一次渲染的页码（1-based，方便直接对照界面） */
    var lastRenderPage by mutableStateOf(0)

    /** 最近一次是否命中缓存 */
    var cacheHit by mutableStateOf(false)

    /** 最近一次翻页的实际偏差（毫秒，正数表示翻晚了） */
    var lastDiffMs by mutableStateOf<Long?>(null)

    /** 本次播放共翻了几次页 */
    var switchCount by mutableStateOf(0)

    /** 渲染失败原因；为 null 表示正常 */
    var renderError by mutableStateOf<String?>(null)

    fun resetForNewPlayback() {
        lastDiffMs = null
        switchCount = 0
        renderError = null
    }
}
