package com.whereverplay.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.TimeFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * WP: 打点页时间轴。
 *
 * 交互分区（用户明确定义，与剪映不同）：
 *  - **只有点在圆点所在的那一横条上** → 选中/拖动这个圆点
 *  - 点在轨道（片段/进度条本体）任意位置 → 光标跳到该处（不选中任何圆点）
 *
 * 精度问题的修法（关键）：
 *  拖动期间**冻结视窗**。原先视窗跟着播放头居中移动，而手指位置又按绝对坐标反算时间，
 *  两者互相推动形成正反馈 —— 放大后一拖就飞。冻结之后 1px 恒等固定时长，想停哪就停哪。
 */
@Composable
fun MarkingTimeline(
    marks: List<Mark>,
    durationMs: Long,
    playheadMs: Long,
    zoom: Float,
    selectedIndex: Int?,
    onZoom: (Float) -> Unit,
    /**
     * 拖动 / 单击轨道都走这里。
     * [fromTap] = true 表示「单击跳转」（播放中可以不打断播放），false = 拖动擦洗。
     */
    onScrub: (Long, Boolean) -> Unit,
    onSelectMark: (Int?) -> Unit,
    onMoveMark: (Int, Long) -> Unit,
    onMarkDragStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val padPx = with(density) { 16.dp.toPx() }
    val rulerLabelY = with(density) { 14.dp.toPx() }
    val rulerTickTop = with(density) { 19.dp.toPx() }
    val rulerTickBottom = with(density) { 27.dp.toPx() }
    val dotY = with(density) { 39.dp.toPx() }
    val dotR = with(density) { 9.dp.toPx() }
    // 圆点专属命中带：纵向范围 + 横向容差
    val dotBandTop = with(density) { 22.dp.toPx() }
    val dotBandBottom = with(density) { 54.dp.toPx() }
    val hitSlop = with(density) { 22.dp.toPx() }
    val trackTop = with(density) { 56.dp.toPx() }
    val trackH = with(density) { 50.dp.toPx() }

    val labelSize = with(density) { 10.sp.toPx() }
    val pageSize = with(density) { 13.sp.toPx() }

    val rulerPaint = remember(labelSize) {
        Paint().apply {
            isAntiAlias = true
            textSize = labelSize
            color = android.graphics.Color.rgb(130, 130, 142)
        }
    }
    val pagePaint = remember(pageSize) {
        Paint().apply {
            isAntiAlias = true
            textSize = pageSize
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.rgb(16, 18, 22)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val visibleMs = (safeDuration / zoom.coerceIn(1f, 40f)).coerceAtLeast(1000f)
    val maxStart = (safeDuration - visibleMs).coerceAtLeast(0f)

    /**
     * 2026-10-26（用户第 4 条）：**播放头不再钉在视窗中间**。
     *
     * 视窗改成「分段翻页」：可视范围固定 [visibleMs]，视窗起点 = 光标所在**段的起点**。
     *  - 播放时视窗一动不动，光标从左往右走；
     *  - 光标走到最右端（= 下一段起点）时，视窗整段切到下一段，光标出现在最左端。
     * 比原来的「followStart = 光标 − 半屏」省掉了一整类正反馈问题：
     * 拖动期间照样冻结视窗（见 [frozenStart]），1px 仍恒等固定时长。
     */
    val segmentStart = ((playheadMs / visibleMs).toInt() * visibleMs).coerceIn(0f, maxStart)

    // 拖动期间冻结视窗，松手后回到「光标所在段」的起点
    var frozenStart by remember { mutableStateOf<Float?>(null) }
    val viewStart = frozenStart ?: segmentStart

    val marksRef = rememberUpdatedState(marks)
    val durationRef = rememberUpdatedState(safeDuration)
    val visibleRef = rememberUpdatedState(visibleMs)
    val zoomRef = rememberUpdatedState(zoom)
    val frozenRef = rememberUpdatedState(frozenStart)
    // pointerInput(Unit) 里的 lambda 只会在首次组合时捕获变量，segmentStart 也会过期
    val segmentRef = rememberUpdatedState(segmentStart)

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    val multiTouch = remember { mutableStateOf(false) }

    // WP: 边缘自动滚动 —— 手指停在左右边缘时视窗持续平移，
    // 解决「冻结视窗后拖到边界就动不了」的问题。
    var dragActive by remember { mutableStateOf(false) }
    var edgeDir by remember { mutableStateOf(0) }
    // 0..1：越靠边缘越大，用来做速度的二次加速
    var edgeFactor by remember { mutableStateOf(0f) }
    var fingerX by remember { mutableStateOf(0f) }
    var canvasW by remember { mutableStateOf(0f) }
    val edgeZoneMin = with(density) { 40.dp.toPx() }

    fun timeToX(t: Long, width: Float, start: Float, visible: Float): Float {
        val content = (width - 2 * padPx).coerceAtLeast(1f)
        return padPx + (t - start) / visible * content
    }

    fun xToTime(x: Float, width: Float, start: Float, visible: Float): Long {
        val content = (width - 2 * padPx).coerceAtLeast(1f)
        val f = ((x - padPx) / content).coerceIn(0f, 1f)
        return (start + f * visible).roundToLong().coerceIn(0L, durationRef.value)
    }

    /** 命中圆点：必须纵向落在圆点带内，且横向距离最近且在容差内 */
    fun hitDot(x: Float, y: Float, width: Float): Int {
        if (y < dotBandTop || y > dotBandBottom) return -1
        var best = -1
        var bestDist = Float.MAX_VALUE
        marksRef.value.forEachIndexed { i, m ->
            val dx = abs(x - timeToX(m.timeMs, width, frozenRef.value ?: segmentRef.value, visibleRef.value))
            if (dx < hitSlop && dx < bestDist) {
                best = i; bestDist = dx
            }
        }
        return best
    }

    // WP: 手指停在边缘不动时没有事件，必须靠这个循环持续推进视窗
    LaunchedEffect(dragActive, edgeDir) {
        if (!dragActive || edgeDir == 0) return@LaunchedEffect
        while (dragActive && edgeDir != 0) {
            delay(16)
            val current = frozenRef.value ?: segmentRef.value
            val maxStart = (safeDuration - visibleRef.value).coerceAtLeast(0f)
            // 基础速度 0.12%/帧，贴到边缘时最高 0.87%/帧（约 2 秒扫过一屏）
            val f = edgeFactor
            val step = visibleRef.value * (0.0012f + 0.0075f * f * f) * edgeDir
            val next = (current + step).coerceIn(0f, maxStart)
            if (next == current) {
                edgeDir = 0
            } else {
                frozenStart = next
                onScrub(xToTime(fingerX, canvasW, next, visibleRef.value), false)
            }
        }
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (multiTouch.value) return@detectDragGestures
                        val w = size.width.toFloat()
                        canvasW = w
                        fingerX = offset.x
                        dragActive = true
                        edgeDir = 0
                        edgeFactor = 0f
                        val dot = hitDot(offset.x, offset.y, w)
                        if (dot >= 0 && dot != 0) {
                            // 命中圆点（第 1 页固定 0:00，不参与拖动）
                            draggingIndex = dot
                            onMarkDragStart()
                            onSelectMark(dot)
                        } else {
                            // 点在轨道上：进入擦洗，冻结视窗保证精度
                            draggingIndex = null
                            frozenStart = segmentRef.value
                            onSelectMark(null)
                        }
                    },
                    onDrag = { change, _ ->
                        if (multiTouch.value) return@detectDragGestures
                        val w = size.width.toFloat()
                        fingerX = change.position.x
                        val start = frozenRef.value ?: segmentRef.value
                        val idx = draggingIndex
                        if (idx != null) {
                            onMoveMark(idx, xToTime(change.position.x, w, start, visibleRef.value))
                            edgeDir = 0
                        } else {
                            onScrub(xToTime(change.position.x, w, start, visibleRef.value), false)
                            // WP: 边缘 10% 区域开始自动滚动，越靠边越快（二次曲线），有上限
                            val zone = (w * 0.10f).coerceAtLeast(edgeZoneMin)
                            val distLeft = change.position.x - padPx
                            val distRight = (padPx + (w - 2 * padPx)) - change.position.x
                            when {
                                distLeft < zone -> {
                                    edgeDir = -1
                                    edgeFactor = ((zone - distLeft) / zone).coerceIn(0f, 1f)
                                }
                                distRight < zone -> {
                                    edgeDir = 1
                                    edgeFactor = ((zone - distRight) / zone).coerceIn(0f, 1f)
                                }
                                else -> {
                                    edgeDir = 0
                                    edgeFactor = 0f
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        draggingIndex = null
                        dragActive = false
                        edgeDir = 0
                        edgeFactor = 0f
                        frozenStart = null
                    },
                    onDragCancel = {
                        draggingIndex = null
                        dragActive = false
                        edgeDir = 0
                        edgeFactor = 0f
                        frozenStart = null
                    },
                )
            }
            .pointerInput(Unit) {
                // 单击：圆点带内 → 选中；轨道区 → 光标跳转（不选中）
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val dot = hitDot(offset.x, offset.y, w)
                    if (dot >= 0) {
                        onSelectMark(dot)
                    } else {
                        onSelectMark(null)
                        onScrub(xToTime(offset.x, w, segmentRef.value, visibleRef.value), true)
                    }
                }
            }
            .pointerInput(Unit) {
                // 双指缩放
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    multiTouch.value = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                multiTouch.value = true
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    onZoom((zoomRef.value * zoomChange).coerceIn(1f, 40f))
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        multiTouch.value = false
                    }
                }
            }
    ) {
        val w = size.width
        val content = (w - 2 * padPx).coerceAtLeast(1f)
        val endX = padPx + content

        // ---------- 刻度尺 ----------
        val step = chooseStep(visibleMs, content)
        var t = (viewStart / step).toLong() * step
        while (t <= viewStart + visibleMs) {
            if (t >= 0) {
                val x = timeToX(t, w, viewStart, visibleMs)
                if (x >= padPx - 1f && x <= endX + 1f) {
                    drawLine(
                        color = Color(0xFF3C3C46),
                        start = Offset(x, rulerTickTop),
                        end = Offset(x, rulerTickBottom),
                        strokeWidth = 1f,
                    )
                    drawIntoCanvas { c ->
                        rulerPaint.textAlign = Paint.Align.CENTER
                        c.nativeCanvas.drawText(TimeFormat.mmss(t), x, rulerLabelY, rulerPaint)
                    }
                }
            }
            t += step
        }

        // ---------- 页片段 ----------
        marks.forEachIndexed { i, m ->
            val endMs = marks.getOrNull(i + 1)?.timeMs ?: safeDuration
            val x1 = timeToX(m.timeMs, w, viewStart, visibleMs)
            val x2 = timeToX(endMs, w, viewStart, visibleMs)
            val left = x1.coerceAtLeast(padPx)
            val right = x2.coerceAtMost(endX)
            if (right - left >= 1f) {
                val isSelected = selectedIndex == i
                drawRoundRect(
                    color = if (isSelected) Color(0xFF39485C) else Color(0xFF2A2F38),
                    topLeft = Offset(left, trackTop),
                    size = Size(right - left, trackH),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                if (isSelected) {
                    drawRoundRect(
                        color = Color(0xFF9EC5FF),
                        topLeft = Offset(left, trackTop),
                        size = Size(right - left, trackH),
                        cornerRadius = CornerRadius(6f, 6f),
                        style = Stroke(width = 2f),
                    )
                }
                if (right - left > pageSize * 1.6f) {
                    drawIntoCanvas { c ->
                        val baseline = trackTop + trackH / 2f - (pagePaint.descent() + pagePaint.ascent()) / 2f
                        c.nativeCanvas.drawText(m.page.toString(), (left + right) / 2f, baseline, pagePaint)
                    }
                }
            }
        }

        // ---------- 圆点（只有这一横条可被点中/拖动）----------
        marks.forEachIndexed { i, m ->
            val x = timeToX(m.timeMs, w, viewStart, visibleMs)
            if (x < padPx - dotR || x > endX + dotR) return@forEachIndexed
            val isSelected = selectedIndex == i
            val radius = if (isSelected) dotR + 3f else dotR
            drawCircle(color = pageColor(m.page), radius = radius, center = Offset(x, dotY))
            if (isSelected) {
                drawCircle(
                    color = Color(0xFFEDEDED),
                    radius = radius + 5f,
                    center = Offset(x, dotY),
                    style = Stroke(width = 2.5f),
                )
            }
            drawIntoCanvas { c ->
                val baseline = dotY - (pagePaint.descent() + pagePaint.ascent()) / 2f
                c.nativeCanvas.drawText(m.page.toString(), x, baseline, pagePaint)
            }
        }

        // ---------- 播放头 ----------
        val px = timeToX(playheadMs, w, viewStart, visibleMs)
        drawLine(
            color = Color(0xFFEDEDED),
            start = Offset(px, rulerTickBottom),
            end = Offset(px, trackTop + trackH + 8f),
            strokeWidth = 1.5f,
        )
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(px - 5f, rulerTickBottom)
                lineTo(px + 5f, rulerTickBottom)
                lineTo(px, rulerTickBottom + 7f)
                close()
            },
            color = Color(0xFFEDEDED),
        )

        // ---------- 底行：视窗范围 + 缩放倍率 ----------
        drawIntoCanvas { c ->
            rulerPaint.textAlign = Paint.Align.LEFT
            c.nativeCanvas.drawText(
                "${TimeFormat.mmss(viewStart.toLong())} – ${TimeFormat.mmss((viewStart + visibleMs).toLong())}",
                padPx,
                size.height - 6f,
                rulerPaint,
            )
            rulerPaint.textAlign = Paint.Align.RIGHT
            c.nativeCanvas.drawText(
                "${"%.1f".format(zoom)}x",
                endX,
                size.height - 6f,
                rulerPaint,
            )
        }
    }
}

/**
 * 黄金角色相：hue = (页码−1) × 137.5°，相邻两页色差恒定，
 * 页数再多也不会出现「第 8 页和第 1 页一个颜色」的问题。
 *
 * internal 而不是 private：打点页底栏「添加第 N 页」前面那个页码色点要用同一个颜色，
 * 两边各写一套迟早会跑偏。
 */
internal fun pageColor(page: Int): Color {
    val hue = (((page - 1) * 137.508f) % 360f + 360f) % 360f
    return Color.hsv(hue, 0.52f, 0.96f)
}

/** 刻度步长：保证可视范围内大约有 4–8 个标签 */
private fun chooseStep(visibleMs: Float, contentPx: Float): Long {
    val candidates = longArrayOf(
        500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000,
        120_000, 300_000, 600_000, 1_800_000,
    )
    val target = visibleMs / (contentPx / 260f).coerceAtLeast(3f)
    return candidates.firstOrNull { it >= target } ?: candidates.last()
}
