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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * **涟漪的裁剪（2026-10-25 修）**：
 * ripple 的绘制边界默认是**触摸热区**这个矩形，而按钮的视觉是 34dp 圆 / 14dp 圆角胶囊，
 * 于是四个角会露出淡灰色方块（深色玻璃底上尤其脏）。想让涟漪只出现在按钮身上，
 * 唯一的办法是把 `clip(shape)` 放在 `clickable` **之前**，让涟漪被裁进那个形状里 ——
 * 这正是底栏 tab 的做法（`clip(RoundedCornerShape(18.dp)).clickable{}`），所以它一直好看。
 *
 * 于是这里多一个 [rippleShape]：**传按钮的视觉形状**，而不是热区形状。
 *  - 圆形图标按钮 → [wpRippleCircle]（按 34dp 视觉裁圆，而不是 44dp 热区）
 *  - 胶囊 / 圆角块 → [wpRippleShape]（可内缩，让涟漪贴住视觉块而不是外圈热区）
 * 不传就退化成矩形（等于旧行为），只建议给「视觉本来就是矩形」的容器用。
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
    rippleShape: Shape = RectangleShape,
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
            // clip 必须在 clickable 之前：涟漪是 clickable 画出来的，顺序反了就裁不到
            .clip(rippleShape)
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

/**
 * 涟漪形状：圆角矩形，可向内缩。
 *
 * [insetX] / [insetY] 用来处理「视觉块比热区小一圈」的按钮 ——
 * 比如 [WpPillAction] 的热区 44 高、视觉胶囊只有 36 高，上下各多 4dp。
 * 不内缩的话涟漪会溢出视觉块 4dp，看着像「高亮块比按钮大」。
 */
@Composable
fun wpRippleShape(corner: Dp, insetX: Dp = 0.dp, insetY: Dp = 0.dp): Shape {
    val density = LocalDensity.current
    return remember(corner, insetX, insetY, density) {
        val c = with(density) { corner.toPx() }
        val ix = with(density) { insetX.toPx() }
        val iy = with(density) { insetY.toPx() }
        GenericShape { size, _ ->
            addRoundRect(
                RoundRect(
                    left = ix,
                    top = iy,
                    right = size.width - ix,
                    bottom = size.height - iy,
                    radiusX = c,
                    radiusY = c,
                )
            )
        }
    }
}

/**
 * 涟漪形状：居中圆。
 * [diameter] 传**视觉直径**（图标按钮传 34dp，而不是 44dp 热区；无底的纯图标按钮传图标那一档）。
 */
@Composable
fun wpRippleCircle(diameter: Dp): Shape {
    val density = LocalDensity.current
    return remember(diameter, density) {
        val r = with(density) { diameter.toPx() } / 2f
        GenericShape { size, _ ->
            addOval(Rect(center = Offset(size.width / 2f, size.height / 2f), radius = r))
        }
    }
}
