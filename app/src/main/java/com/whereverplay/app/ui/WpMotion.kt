package com.whereverplay.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * WP: 动效 token。
 *
 * 全部数值来自 ui/work/按钮分布与交互动画-开发对照.md §4，
 * 集中在这里的意义是「以后调手感只改一处」，而不是散在十几个 composable 里。
 */
object WpMotion {

    // ---------- 01 跟手翻页 ----------
    /** 松手未过阈值 → 弹回 */
    const val TURN_BACK_MS = 160

    /** 松手过阈值 → 翻过去 */
    const val TURN_FORWARD_MS = 220

    val TurnEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ---------- 02 控制栏显隐 ----------
    const val BAR_MS = 180
    val BarEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 控制栏淡入时同时上移的距离 */
    const val BAR_RISE_DP = 4

    // ---------- 03 按压反馈 ----------
    const val PRESS_IN_MS = 90
    const val PRESS_OUT_MS = 140
    const val PRESS_SCALE = 0.96f

    // ---------- 04 底栏 Tab ----------
    const val TAB_MS = 320

    // ---------- 05 打点圆点 ----------
    const val DOT_IN_MS = 220
    const val RING_MS = 240
    const val SELECT_MS = 180

    // ---------- 06 曲间遮罩 ----------
    const val INTERLUDE_MS = 200
}

/**
 * WP: 统一的可点区域。
 *
 * 为什么不用裸 `Modifier.clickable`：设计稿要求「按下 scale 0.96 / 90ms、回弹 140ms」，
 * 而每个按钮自己建一遍 interactionSource 太啰嗦，也容易漏。
 * 这里把「热区 + 点击 + 按压缩放」打包，调用方只关心内容。
 *
 * 注意：自带 pointerInput 的区域（谱面翻页、时间轴）不要套这个，
 * 否则 clickable 会抢走手势。
 */
@Composable
fun WpTapArea(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scaleDown: Float = WpMotion.PRESS_SCALE,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = if (pressed) {
            tween(WpMotion.PRESS_IN_MS)
        } else {
            spring(dampingRatio = 0.85f, stiffness = 600f)
        },
        label = "wpPressScale",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}
