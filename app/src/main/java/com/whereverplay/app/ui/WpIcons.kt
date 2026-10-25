package com.whereverplay.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * WP: 全部图标自绘。
 *
 * 工程没有引 material-icons（那个包很大，而且风格与现状的 1.5~1.8dp 细线不搭），
 * 设计稿也明确要求「图标必须是能徒手画的简单几何」。
 * 统一约定：1.6~1.8dp 线宽、圆头、图形占画布 68%~84%，四周留白给 44 热区。
 *
 * 所有函数的 [box] 是图标自身画布尺寸，不是热区；热区由 WpIconButton 的 44 容器负责。
 */

/** 返回箭头（细 chevron，不是「←」字形） */
@Composable
fun IconBack(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val st = 1.8.dp.toPx()
    val w = size.width
    val h = size.height
    drawLine(tint, Offset(w * 0.62f, h * 0.17f), Offset(w * 0.31f, h * 0.5f), st, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.31f, h * 0.5f), Offset(w * 0.62f, h * 0.83f), st, StrokeCap.Round)
}

/**
 * 竖排三点。
 * WP: 这里曾经是 4dp 画布 + 1.6dp 半径 → 三点纵向间距只有 2dp，视觉上糊成一条竖线
 * （实机截图里看起来像「一个点」）。现在改成 18dp 画布 + 5dp 间距，点直径 3dp。
 */
@Composable
fun IconMoreVertical(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val r = 1.5.dp.toPx()
    val gap = 5.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (k in -1..1) {
        drawCircle(tint, radius = r, center = Offset(cx, cy + k * gap))
    }
}

/** 横排三点（打点页「更多」用） */
@Composable
fun IconMoreHorizontal(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val r = 1.5.dp.toPx()
    val gap = 5.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (k in -1..1) {
        drawCircle(tint, radius = r, center = Offset(cx + k * gap, cy))
    }
}

@Composable
fun IconPlus(tint: Color, box: Dp = 16.dp) = Canvas(Modifier.size(box)) {
    val st = 1.7.dp.toPx()
    val w = size.width
    val h = size.height
    drawLine(tint, Offset(w * 0.12f, h / 2f), Offset(w * 0.88f, h / 2f), st, StrokeCap.Round)
    drawLine(tint, Offset(w / 2f, h * 0.12f), Offset(w / 2f, h * 0.88f), st, StrokeCap.Round)
}

@Composable
fun IconMinus(tint: Color, box: Dp = 16.dp) = Canvas(Modifier.size(box)) {
    val st = 1.7.dp.toPx()
    val w = size.width
    val h = size.height
    drawLine(tint, Offset(w * 0.12f, h / 2f), Offset(w * 0.88f, h / 2f), st, StrokeCap.Round)
}

/** 排序（漏斗式三横线，长短递减） */
@Composable
fun IconSort(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val st = 1.5.dp.toPx()
    val w = size.width
    val h = size.height
    val x0 = w * 0.12f
    drawLine(tint, Offset(x0, h * 0.26f), Offset(w * 0.88f, h * 0.26f), st, StrokeCap.Round)
    drawLine(tint, Offset(x0, h * 0.5f), Offset(w * 0.66f, h * 0.5f), st, StrokeCap.Round)
    drawLine(tint, Offset(x0, h * 0.74f), Offset(w * 0.44f, h * 0.74f), st, StrokeCap.Round)
}

/**
 * 筛选（漏斗）。
 * WP: 和 IconSort 的三条横线要能一眼区分 —— 排序是「线」，筛选必须是「形」，
 * 所以这里画闭合漏斗轮廓而不是再加一组横线。
 */
@Composable
fun IconFilter(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val st = 1.6.dp.toPx()
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.12f, h * 0.22f)
        lineTo(w * 0.88f, h * 0.22f)
        lineTo(w * 0.58f, h * 0.56f)
        lineTo(w * 0.58f, h * 0.84f)
        lineTo(w * 0.42f, h * 0.92f)
        lineTo(w * 0.42f, h * 0.56f)
        close()
    }
    drawPath(path, tint, style = Stroke(width = st, join = StrokeJoin.Round))
}

@Composable
fun IconSearch(tint: Color, box: Dp = 15.dp) = Canvas(Modifier.size(box)) {
    val st = 1.4.dp.toPx()
    val w = size.width
    val h = size.height
    drawCircle(tint, radius = w * 0.31f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(st))
    drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.93f, h * 0.93f), st, StrokeCap.Round)
}

@Composable
fun IconPlay(tint: Color, box: Dp = 22.dp) = Canvas(Modifier.size(box)) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(w * 0.2f, h * 0.1f)
            lineTo(w * 0.92f, h * 0.5f)
            lineTo(w * 0.2f, h * 0.9f)
            close()
        },
        tint,
    )
}

@Composable
fun IconPause(tint: Color, box: Dp = 22.dp) = Canvas(Modifier.size(box)) {
    val w = size.width
    val h = size.height
    val bar = w * 0.24f
    val r = CornerRadius(bar * 0.28f, bar * 0.28f)
    drawRoundRect(tint, Offset(w * 0.18f, h * 0.12f), Size(bar, h * 0.76f), r)
    drawRoundRect(tint, Offset(w * 0.58f, h * 0.12f), Size(bar, h * 0.76f), r)
}

/** 上一首：左竖条 + 左向三角 */
@Composable
fun IconSkipPrev(tint: Color, box: Dp = 20.dp) = Canvas(Modifier.size(box)) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        tint,
        Offset(w * 0.14f, h * 0.16f),
        Size(w * 0.1f, h * 0.68f),
        CornerRadius(w * 0.05f, w * 0.05f),
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.86f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.84f)
            lineTo(w * 0.32f, h * 0.5f)
            close()
        },
        tint,
    )
}

/** 下一首：右向三角 + 右竖条 */
@Composable
fun IconSkipNext(tint: Color, box: Dp = 20.dp) = Canvas(Modifier.size(box)) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(w * 0.14f, h * 0.16f)
            lineTo(w * 0.14f, h * 0.84f)
            lineTo(w * 0.68f, h * 0.5f)
            close()
        },
        tint,
    )
    drawRoundRect(
        tint,
        Offset(w * 0.76f, h * 0.16f),
        Size(w * 0.1f, h * 0.68f),
        CornerRadius(w * 0.05f, w * 0.05f),
    )
}

@Composable
fun IconClose(tint: Color, box: Dp = 16.dp) = Canvas(Modifier.size(box)) {
    val st = 1.6.dp.toPx()
    val w = size.width
    val h = size.height
    drawLine(tint, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), st, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), st, StrokeCap.Round)
}

/** 拖动把手：2 列 × 3 行圆点（纯视觉提示，不单独响应事件） */
@Composable
fun IconDragHandle(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    val r = 1.3.dp.toPx()
    val dx = 2.6.dp.toPx()
    val dy = 4.4.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (row in -1..1) {
        for (col in 0..1) {
            drawCircle(tint, r, Offset(cx + (if (col == 0) -dx else dx) * 0.5f, cy + row * dy))
        }
    }
}

/** 撤销 / 重做：四分之三圆弧 + 切向箭头 */
@Composable
fun IconUndo(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    drawArcArrow(tint, clockwise = false)
}

@Composable
fun IconRedo(tint: Color, box: Dp = 18.dp) = Canvas(Modifier.size(box)) {
    drawArcArrow(tint, clockwise = true)
}

private fun DrawScope.drawArcArrow(color: Color, clockwise: Boolean) {
    val r = minOf(size.width, size.height) * 0.31f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val sw = 1.6.dp.toPx()
    val startDeg = -60f
    val sweep = if (clockwise) 300f else -300f
    drawArc(
        color = color,
        startAngle = startDeg,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = sw, cap = StrokeCap.Round),
    )
    val endRad = Math.toRadians((startDeg + sweep).toDouble())
    val ex = cx + r * cos(endRad).toFloat()
    val ey = cy + r * sin(endRad).toFloat()
    val sign = if (clockwise) 1f else -1f
    // 圆周切向（顺时针为角度增大方向）
    val tx = -sin(endRad).toFloat() * sign
    val ty = cos(endRad).toFloat() * sign
    val nx = -ty
    val ny = tx
    val headLen = sw * 2.6f
    val headWide = sw * 1.6f
    drawPath(
        Path().apply {
            moveTo(ex + tx * headLen, ey + ty * headLen)
            lineTo(ex + nx * headWide, ey + ny * headWide)
            lineTo(ex - nx * headWide, ey - ny * headWide)
            close()
        },
        color,
    )
}

/** 星标。filled=false 时是描边空心星 */
@Composable
fun IconStar(tint: Color, filled: Boolean, box: Dp = 20.dp) = Canvas(Modifier.size(box)) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = minOf(size.width, size.height) * 0.48f
    val inner = outer * 0.44f
    val path = Path()
    for (i in 0 until 10) {
        val rad = Math.toRadians((-90.0 + i * 36.0))
        val rr = if (i % 2 == 0) outer else inner
        val x = cx + rr * cos(rad).toFloat()
        val y = cy + rr * sin(rad).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) {
        drawPath(path, tint)
    } else {
        drawPath(path, tint, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}
