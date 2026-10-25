package com.whereverplay.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.MarkRules
import com.whereverplay.app.domain.MarkValidation
import com.whereverplay.app.domain.PageResolver
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import com.whereverplay.app.player.MediaPlayerAudioPlayer
import kotlinx.coroutines.delay
import java.io.File

/**
 * WP: 打点页。
 *
 * 工具栏只保留两个动作：
 *  - 添加翻页（最高频）
 *  - 更多（低频操作收进菜单）
 * 之前塞了「微调 / 切页 / 试听」三个按钮，用户反馈意义不明、
 * 且「试听」与上方的播放按钮重复，已删除。
 * 微调改成「选中圆点后自动出现在时间轴上方」，不需要单独按钮。
 */
@Composable
fun MarkingScreen(
    project: Project,
    onExit: (Project) -> Unit,
    onFinished: (Project) -> Unit,
) {
    val context = LocalContext.current
    val audioFile = remember(project.id) { File(File(context.filesDir, "media"), project.audioFile) }
    val pdfFile = remember(project.id) { File(File(context.filesDir, "media"), project.pdfFile) }

    val player = remember(project.id) { MediaPlayerAudioPlayer() }
    // WP: 真实时长必须 load() 之后再读（同播放页，remember 里读永远是 0）
    var playerDuration by remember(project.id) { mutableStateOf(0L) }
    DisposableEffect(project.id) {
        player.load(audioFile.absolutePath)
        playerDuration = player.durationMs
        onDispose { player.release() }
    }

    var marks by remember(project.id) { mutableStateOf(project.marks) }
    var playhead by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var message by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableStateOf(0L) }

    val undoStack = remember { mutableStateListOf<List<Mark>>() }
    val redoStack = remember { mutableStateListOf<List<Mark>>() }

    fun pushHistory() {
        undoStack.add(marks)
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(marks)
        marks = previous
        selected = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(marks)
        marks = next
        selected = null
    }

    val duration = playerDuration.takeIf { it > 0 } ?: project.durationMs
    val safeDuration = duration.coerceAtLeast(1L)

    LaunchedEffect(isPlaying) {
        // WP: 一按播放就取消选中 —— 否则预览会一直钉在被选中的那一页，
        // 而光标已经在往前走了，看起来像「播放时谱面不动」。
        if (isPlaying) selected = null
        while (isPlaying) {
            playhead = player.positionMs.coerceAtMost(safeDuration)
            if (!player.isPlaying) {
                isPlaying = false
                playhead = safeDuration
            }
            delay(16)
        }
    }

    /**
     * WP: 预览页 = 「选中的那个圆点的页」，没选中时才跟光标。
     *
     * 用户的心智模型：点小圆点 = 我要看这一页（比如确认这页是不是翻早了），
     * 光标位置是「他上次停的地方」，不该抢走预览。
     * 反过来，一碰时间轴轨道（onSelectMark(null)）就回到跟随光标 ——
     * 这就是「点圆点看那页、点轨道看光标那页」的两套语义，互不干扰。
     */
    val playheadPage = PageResolver.pageAt(marks, playhead)
    val currentPage = selected?.let { marks.getOrNull(it)?.page } ?: playheadPage
    val density = LocalDensity.current
    val targetWidthPx = with(density) { 400.dp.toPx() }.toInt()
    val bitmap = rememberPdfPage(pdfFile, currentPage - 1, targetWidthPx)

    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(currentPage) {
        scale = 1f
        panOffset = Offset.Zero
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        player.pause()
        onExit(project.copy(marks = marks))
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏（56）：返回 / 标题组 / 完成 ----------
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WpIconButton(onClick = {
                player.pause()
                onExit(project.copy(marks = marks))
            }, visual = 0.dp) { tint -> IconBack(tint, 18.dp) }

            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    project.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "第 $currentPage 页 · ${marks.size}/${project.pageCount} 已打点" +
                        if (project.configured) "" else " · 未匹配",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 顶栏唯一动作：打点齐全 = 实心主按钮；还差页 = 玻璃胶囊（点了会提示缺哪几页）
            val validation = MarkRules.validate(marks, project.pageCount)
            val ready = validation is MarkValidation.Ok
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (ready) Modifier.background(Color(0xFFEDEDED))
                        else Modifier.background(Glass.fillStrong).border(0.5.dp, Glass.border, RoundedCornerShape(18.dp))
                    )
                    .clickable {
                        when (val v = MarkRules.validate(marks, project.pageCount)) {
                            is MarkValidation.Ok -> {
                                player.pause()
                                onFinished(project.copy(marks = marks))
                            }
                            is MarkValidation.MissingPages ->
                                message = "还有 ${v.pages.size} 页未打点：" + v.pages.joinToString("、")
                            is MarkValidation.Invalid -> message = v.reason
                        }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "完成",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ready) Color(0xFF17171A) else Color(0xFFE8E8EC)
                )
            }
        }

        // ---------- 预览窗 ----------
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF16161C))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(currentPage) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            panOffset = if (scale <= 1.001f) Offset.Zero else panOffset + pan
                        }
                    }
                    .pointerInput(currentPage) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            panOffset = Offset.Zero
                        })
                    }
            ) {
                PdfPageView(
                    bitmap,
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = panOffset.x,
                            translationY = panOffset.y,
                        ),
                    emptyHint = DebugStats.renderError
                        ?: if (bitmap == null) "正在渲染第 $currentPage 页…" else null,
                )
            }

            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC14141A))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("第 $currentPage 页", fontSize = 10.sp, color = Color(0xFFD8D8DE))
            }
        }

        // ---------- 工具行（44）：撤销 / 重做 … 选中信息 + 取消选中 ----------
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WpIconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty(), visual = 0.dp) { tint ->
                IconUndo(tint, 18.dp)
            }
            WpIconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty(), visual = 0.dp) { tint ->
                IconRedo(tint, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            val sel = selected
            val selectedMark = sel?.let { marks.getOrNull(it) }
            if (sel != null && selectedMark != null) {
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Glass.fill)
                        .border(0.5.dp, Glass.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "第 ${selectedMark.page} 页 · ${TimeFormat.mmssTenth(selectedMark.timeMs)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                WpIconButton(onClick = { selected = null }, visual = 0.dp) { tint ->
                    IconClose(tint, 14.dp)
                }
            }
        }

        // ---------- 播放行（36）----------
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFFEDEDED))
                    .clickable {
                        if (isPlaying) {
                            player.pause(); isPlaying = false
                        } else {
                            if (playhead >= safeDuration - 50) playhead = 0L
                            player.seekTo(playhead); player.play(); isPlaying = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) IconPause(Color(0xFF17171A), 12.dp) else IconPlay(Color(0xFF17171A), 12.dp)
            }

            Spacer(Modifier.width(10.dp))
            Text(
                "${TimeFormat.mmssTenth(playhead)} / ${TimeFormat.mmss(duration)}",
                fontSize = 12.sp,
                color = Color(0xFFC8C8D0)
            )
            Spacer(Modifier.weight(1f))
        }

        // ---------- 微调行（选中后自动出现，40 高）----------
        val tuningIdx = selected
        val tuningMark = tuningIdx?.let { marks.getOrNull(it) }
        if (tuningIdx != null && tuningMark != null) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("微调", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tuningMark.page <= 1) {
                    // WP: 第 1 页固定在 0:00（domain 层 MarkRules.move 直接 return），
                    // 以前这里照样显示四个胶囊，点了没反应 —— 看起来像卡死。改成明说。
                    Text(
                        "第 1 页固定在 0:00，不能微调",
                        fontSize = 10.sp,
                        color = Color(0xFFF0C070),
                    )
                } else {
                    listOf(-1000L to "-1s", -100L to "-0.1s", 100L to "+0.1s", 1000L to "+1s")
                        .forEach { (delta, label) ->
                            WpChip(
                                text = label,
                                onClick = {
                                    pushHistory()
                                    val newMarks = MarkRules.move(marks, tuningIdx, marks[tuningIdx].timeMs + delta, safeDuration)
                                    marks = newMarks
                                    playhead = newMarks[tuningIdx].timeMs
                                    player.seekTo(playhead)
                                },
                                height = 28.dp,
                                fontSize = 10.sp,
                                horizontalPadding = 12.dp,
                            )
                        }
                }
            }
        }

        // ---------- 时间轴 ----------
        MarkingTimeline(
            marks = marks,
            durationMs = safeDuration,
            playheadMs = playhead,
            zoom = zoom,
            selectedIndex = selected,
            onZoom = { zoom = it },
            onScrub = { t ->
                if (isPlaying) { player.pause(); isPlaying = false }
                playhead = t
                // WP: 拖动和边缘滚动会高频回调，每帧 seek 会让播放器卡顿，节流到 80ms
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSeekAt > 80) {
                    lastSeekAt = now
                    player.seekTo(t)
                }
            },
            onSelectMark = { selected = it },
            onMoveMark = { idx, t -> marks = MarkRules.move(marks, idx, t, safeDuration) },
            onMarkDragStart = { pushHistory() },
            modifier = Modifier.fillMaxWidth().height(116.dp),
        )

        message?.let {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it, fontSize = 10.sp, color = Color(0xFFF0A0A0), modifier = Modifier.weight(1f))
                TextButton(onClick = { message = null }) { Text("知道了", fontSize = 10.sp) }
            }
        }

        // ---------- 底部工具栏：添加占 2/3，更多固定 104 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canAdd = MarkRules.canAddMore(marks, project.pageCount)
            val nextPage = (marks.maxOfOrNull { it.page } ?: 0) + 1
            BigTool(
                if (canAdd) "添加第 $nextPage 页" else "已全部打点",
                ToolIcon.Add,
                enabled = canAdd,
                dotColor = if (canAdd) pageColor(nextPage) else null,
                modifier = Modifier.weight(1f),
            ) {
                if (!canAdd) {
                    message = "所有页面已创建"
                } else {
                    pushHistory()
                    val t = MarkRules.newMarkTime(marks, playhead, safeDuration)
                    marks = marks + Mark(page = nextPage, timeMs = t)
                    selected = marks.lastIndex
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(104.dp)) {
                BigTool(
                    "更多",
                    ToolIcon.More,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("清空所有打点", fontSize = 12.sp) },
                        onClick = {
                            moreMenu = false
                            pushHistory()
                            marks = listOf(Mark(page = 1, timeMs = 0L))
                            selected = null
                            playhead = 0L
                            player.seekTo(0L)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("跳到最后一页起点", fontSize = 12.sp) },
                        onClick = {
                            moreMenu = false
                            val last = marks.lastOrNull() ?: return@DropdownMenuItem
                            playhead = last.timeMs
                            player.seekTo(last.timeMs)
                        },
                    )
                }
            }
        }

        Text(
            "点圆点选中后可微调 · 双指缩放时间轴 · 拖片段交界处改翻页时刻",
            fontSize = 9.sp,
            color = Color(0xFF6E6E78),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 4.dp)
        )
    }
}

private enum class ToolIcon { Add, More }

@Composable
private fun BigTool(
    label: String,
    icon: ToolIcon,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** 非空时在文字前画一个 10dp 的页码色点（与时间轴同色） */
    dotColor: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (enabled) Glass.fillStrong else Glass.fill)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onBackground
        else MaterialTheme.colorScheme.onSurfaceVariant
        if (dotColor != null) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dotColor))
            Spacer(Modifier.width(7.dp))
        } else {
            Canvas(Modifier.size(14.dp)) {
                val st = 1.6.dp.toPx()
                when (icon) {
                    ToolIcon.Add -> {
                        drawLine(tint, Offset(size.width * 0.1f, size.height / 2f), Offset(size.width * 0.9f, size.height / 2f), st)
                        drawLine(tint, Offset(size.width / 2f, size.height * 0.1f), Offset(size.width / 2f, size.height * 0.9f), st)
                    }
                    ToolIcon.More -> {
                        listOf(0.25f, 0.5f, 0.75f).forEach { fx ->
                            drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(size.width * fx, size.height / 2f))
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 13.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}
