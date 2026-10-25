package com.whereverplay.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 拖动时投递给调用方的一个"连续滚动位置"。
 * [index] 永远是**某一行的第一项**，[offsetPx] 是这一行滚过去多少像素。
 */
private data class SeekTarget(val index: Int, val offsetPx: Int)

/**
 * WP: 右侧可上下拖动的滚动条（长列表快速定位）。
 *
 * 为什么不接 state 类型：项目页是 `LazyVerticalGrid`、播放列表页是 `LazyColumn`，
 * 两者 state 不同。这里只收几个数值 + 一个 onSeek，由调用方从自己的 layoutInfo 算：
 *
 * ```
 * val info = state.layoutInfo
 * // 一行/一项占多高：实测相邻两行的 offset 差，别用「item 高 + 间距」手拼
 * val base = info.visibleItemsInfo.firstOrNull()?.offset?.y ?: 0   // 列表用 .offset
 * val stride = info.visibleItemsInfo.firstOrNull { it.offset.y != base }
 *     ?.let { (it.offset.y - base).toFloat() } ?: 0f
 * WpScrollBar(
 *     totalItems = info.totalItemsCount,
 *     itemsPerRow = 2,                          // 列表页填 1
 *     firstVisible = state.firstVisibleItemIndex,
 *     firstVisibleOffsetPx = state.firstVisibleItemScrollOffset,
 *     viewportPx = info.viewportSize.height,    // 稳定值；别拿 visibleCount 算滑块长度
 *     stridePx = stride,
 *     onSeek = { index, offset -> state.scrollToItem(index, offset) },
 * )
 * ```
 *
 * ⚠️ `stridePx` 别写成 `it.size.height + spacingPx`：`IntSize.height` 是成员属性，
 * 一旦为了它 `import androidx.compose.ui.unit.height`，拉进来的其实是 `DpRect.height: Dp`，
 * 直接编译报 `receiver type mismatch`。而且手拼的间距也算不进 `Arrangement.spacedBy` 的误差。
 * 网格（`LazyGridItemInfo`，`offset` 是 `IntOffset`）必须**跳过同一行的其他列**，
 * 同行多列 `offset.y` 相同，不跳过差值就是 0。
 *
 * ## 五次修正记录（都是在修"拖起来不像手"）
 *
 * **① 位置整体偏下**：容器 `contentAlignment` 曾经是 `CenterEnd`，滑块先被垂直居中再叠 `offset`，
 * 等于多走半个行程。→ 改 `TopEnd`（水平照样靠右）。
 *
 * **② 慢速拖动抽搐**：`可滚项数 = totalItems − visibleCount` 会随 `visibleItemsInfo.size` 抖动
 * （一屏 8 项 / 9 项来回变），同一手指位置映射到不同项；滑块位置又靠 `firstVisible` 反算、慢一帧。
 * → 按下瞬间**冻结分母**、拖动期间**滑块直接跟手指**（`dragTopPx`）、按在滑块上走**增量拖动**
 * （`grab`）、松手**延迟 120ms 交还**、滑块长度也冻结（`frozenThumbPx`）。
 *
 * **③ 拖动一下跳一张卡片**：`scrollToItem(index)` 只能按项定位，落在两个卡片中间的位置被丢弃。
 * → 目标改成**亚项精度**：`index` + 行内 `offsetPx`，交给 `scrollToItem(index, offsetPx)`。
 *
 * **④ 不动滑块、只滚列表时滑块长度一突一突**：长度是按 `visibleItemsInfo.size` 算的，
 * 而它是**离散**的（一项刚探进底部 +1、刚完全离开顶部 −1，滚动中几乎每帧在 8/9 翻）。
 * → 长度改用 `viewportPx / stridePx`（连续）。
 *
 * **⑤ 拖滑块时卡片抽搐（来回抽）** —— 本文件最要紧的一条。
 * 曾经把「滚动位置」按 **item 单位**算：`pos = 分数 × (totalItems − 一屏项数)`，
 * 再拆成 `index = floor(pos)` + `offsetPx = 小数 × stride`。单个列表（1 列）没问题，
 * **网格就错**：2 列时第 1 项和第 0 项在**同一行**，`scrollToItem(0, 0)` 与 `scrollToItem(1, 0)`
 * 落点完全一样。于是拖动时 pos 每跨过一个整数，目标就**退回上一行**，序列变成
 * `0 → 0.5行 → 0 → 0.5行 → 1行 → 1.5行 → 1行…` 锯齿，卡片来回抽。
 * 根因：**滚动位置是"行"域，而 item 索引不是行索引**。
 * → 全程改成**像素/行域**：`rows = ceil(totalItems / itemsPerRow)`，
 * `scrollPx = firstRow × stride + firstVisibleOffsetPx`，`fraction = scrollPx / (rows×stride − viewport)`；
 * 反算时 `row = floor(targetPx / stride)`、`index = row × itemsPerRow`（**永远是行首项**），
 * 这样 index 与 offset 单调连续，不会再回退。1 列列表时 `itemsPerRow = 1`，自动退化成原行为。
 *
 * 另外：`onSeek` 是 suspend 的，但 `awaitEachGesture` 里是**受限协程**，不能直接调。
 * 目标走 `Channel(CONFLATED)`：手势侧 `trySend` 不阻塞，另一个协程串行消费；
 * 积压时只处理最新值，所以「手指停下来列表不会再追着跑一阵」。
 */
@Composable
fun WpScrollBar(
    totalItems: Int,
    /** 一行放几项：`LazyColumn` 填 1，`LazyVerticalGrid(GridCells.Fixed(n))` 填 n */
    itemsPerRow: Int,
    firstVisible: Int,
    firstVisibleOffsetPx: Int,
    /**
     * 列表可视区高度（px）。取 `layoutInfo.viewportSize.height`，滚动中恒定，
     * 拿它算「视口占内容多少」比 `visibleItemsInfo.size` 稳。
     */
    viewportPx: Int,
    /** 一行前进对应的像素高度（行高含行间距）。0 = 还没量到，这一帧先不画 */
    stridePx: Float,
    /**
     * 列表上下 `contentPadding` 之和（px）。它也算可滚范围（项目页底部留了 110dp 给浮动栏），
     * 不算进来的话「能滚多远」会少算，滑块拖到头也差一截。
     */
    verticalContentPaddingPx: Float = 0f,
    onSeek: suspend (index: Int, offsetPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val perRow = itemsPerRow.coerceAtLeast(1)
    val rows = if (totalItems <= 0) 0 else (totalItems + perRow - 1) / perRow
    val contentPx = rows * stridePx + verticalContentPaddingPx
    // 装得下就不显示（短列表出现一条滑块反而碍眼）。
    // 注意 gate 在 contentPx 上，不能用 visibleItemsInfo.size —— 它会在 N/N±1 之间跳，
    // 让滑块在边界上一闪一闪。
    if (rows <= 0 || stridePx <= 0f || viewportPx <= 0 || contentPx <= viewportPx) return

    var dragging by remember { mutableStateOf(false) }
    /** 拖动中的滑块顶边（px）。null = 没在拖，靠 fraction 反算 */
    var dragTopPx by remember { mutableStateOf<Float?>(null) }
    /** 拖动中的滑块高度（px）。冻结住，避免拖动中数值变化让滑块长度抖 */
    var frozenThumbPx by remember { mutableStateOf<Float?>(null) }

    val seekRef = rememberUpdatedState(onSeek)
    val rowsRef = rememberUpdatedState(rows)
    val perRowRef = rememberUpdatedState(perRow)
    val strideRef = rememberUpdatedState(stridePx)

    // 能滚多少像素：内容总高 − 视口高。滑块行程、拖动目标都换算到这个量上
    val scrollablePx = (contentPx - viewportPx).coerceAtLeast(1f)
    val scrollableRef = rememberUpdatedState(scrollablePx)
    // 视口占内容的比例 = 滑块长度占比。连续量，滚动中不变
    val visibleFraction = (viewportPx / contentPx).coerceIn(0f, 1f)
    val visibleFractionRef = rememberUpdatedState(visibleFraction)

    // 当前位置换算成像素：第几行 × 行高 + 这一行滚过去多少。
    // 千万别写成 firstVisible（item 索引）× stride —— 2 列网格里差一倍
    val firstRow = firstVisible / perRow
    val scrollPx = (firstRow * stridePx + firstVisibleOffsetPx).coerceIn(0f, scrollablePx)
    val fraction = (scrollPx / scrollablePx).coerceIn(0f, 1f)
    val fractionRef = rememberUpdatedState(fraction)

    // 手势侧只投递目标，真正的 scrollToItem 在这里串行执行（不受受限协程限制）
    val seekQueue = remember { Channel<SeekTarget>(Channel.CONFLATED) }
    LaunchedEffect(seekQueue) {
        for (target in seekQueue) seekRef.value(target.index, target.offsetPx)
    }

    // 松手后**不要立刻**把滑块交还给反算位置：最后一次 seek 可能还在路上，
    // 此时 firstVisible 还是旧值，交还就会让滑块先跳回去再跳回来。
    LaunchedEffect(dragging) {
        if (!dragging) {
            delay(120)
            dragTopPx = null
            frozenThumbPx = null
        }
    }

    // 视觉宽度：轨道 6dp、滑块 6dp，拖动时 9dp。只改画多宽，占位（24dp 触摸带）不变，
    // 所以不会把任何内容挤动 —— 宽度是"盖"在内容右边缘那块留白上的。
    val thumbWidth by animateDpAsState(
        targetValue = if (dragging) 9.dp else 6.dp,
        animationSpec = tween(120),
        label = "scrollThumbWidth",
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.92f else 0.40f,
        animationSpec = tween(160),
        label = "scrollThumbAlpha",
    )

    BoxWithConstraints(
        modifier
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    val trackH = size.height.toFloat()
                    // 滑块高度 = 可视比例，但最短 40dp，否则长列表上细成一根针。
                    // 必须和绘制端用同一个 visibleFraction，否则按下瞬间滑块会跳一下。
                    val thumbH = (trackH * visibleFractionRef.value)
                        .coerceAtLeast(40.dp.toPx())
                        .coerceAtMost(trackH)
                    val freeH = (trackH - thumbH).coerceAtLeast(1f)
                    // 这三个值在整个手势里冻结：手指位置和列表位置才一一对应
                    val scrollableAtDown = scrollableRef.value
                    val stride = strideRef.value
                    val maxRow = (rowsRef.value - 1).coerceAtLeast(0)
                    val perRowAtDown = perRowRef.value
                    val maxOffsetPx = (stride - 1f).toInt().coerceAtLeast(0)

                    // 增量拖动：按在滑块上就沿用它当前位置，只跟手指位移走；
                    // 按在轨道别处才「跳过去」，且让手指落在滑块中心
                    val curTop = freeH * fractionRef.value
                    val hitSlop = 10.dp.toPx()
                    val onThumb = down.position.y >= curTop - hitSlop &&
                        down.position.y <= curTop + thumbH + hitSlop
                    val grab = if (onThumb) down.position.y - curTop else thumbH / 2f

                    var lastIndex = -1
                    var lastOffset = -1

                    // 非挂起：只算位置 + 投递目标，绝不在手势里 await 滚动
                    fun seekAt(y: Float) {
                        val top = (y - grab).coerceIn(0f, freeH)
                        dragTopPx = top
                        // 亚项精度，且**按行**拆：row 是行号，index 永远取行首项。
                        // 取行首才 monotone —— 取 floor(pos) 这种 item 索引会让 2 列网格退回上一行
                        val targetPx = (top / freeH) * scrollableAtDown
                        val row = floor(targetPx / stride).toInt().coerceIn(0, maxRow)
                        val offset = (targetPx - row * stride).roundToInt().coerceIn(0, maxOffsetPx)
                        val index = row * perRowAtDown
                        // 同一位置不重复投递
                        if (index != lastIndex || offset != lastOffset) {
                            lastIndex = index
                            lastOffset = offset
                            seekQueue.trySend(SeekTarget(index, offset))
                        }
                    }

                    dragging = true
                    frozenThumbPx = thumbH
                    try {
                        seekAt(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            seekAt(change.position.y)
                            change.consume()
                        }
                    } finally {
                        // 被系统抢走（返回手势 / 多指 / 窗口失焦）也必须复位，
                        // 否则滑块永远停在「加粗高亮」状态。
                        // dragTopPx / frozenThumbPx 交给上面那个 LaunchedEffect 延迟清。
                        dragging = false
                    }
                }
            },
        // 必须 TopEnd：滑块是 `对齐后再 offset`，如果对齐是 CenterEnd，
        // 它会先被垂直居中（(trackH - thumbH)/2），再叠上位移 ——
        // 结果是「拖到顶时滑块在中间、拖到底时滑块有一半跑出屏幕」。
        // 轨道 fillMaxHeight 不受对齐影响，水平方向 TopEnd / CenterEnd 都是靠右，视觉不变。
        contentAlignment = Alignment.TopEnd,
    ) {
        val trackH = maxHeight
        val density = LocalDensity.current
        // 长度用连续的 visibleFraction 算，滚动中不会因为"某项刚探进来"而突变
        val thumbH = frozenThumbPx?.let { with(density) { it.toDp() } }
            ?: (trackH * visibleFraction).coerceAtLeast(40.dp)
        val freeH = (trackH - thumbH).coerceAtLeast(0.dp)

        // 轨道
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x12FFFFFF))
        )
        // 滑块：拖动时用手指位置（dragTopPx），松手后回到 fraction 反算的位置。
        val thumbTop = dragTopPx?.let { with(density) { it.toDp() } } ?: (freeH * fraction)
        Box(
            Modifier
                .offset(y = thumbTop)
                .width(thumbWidth)
                .height(thumbH)
                // 百分比圆角：滑块宽度在 6dp ↔ 9dp 之间动画，固定半径会让两端一边圆一边方
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(0xFFFFFFFF).copy(alpha = thumbAlpha))
        )
    }
}
