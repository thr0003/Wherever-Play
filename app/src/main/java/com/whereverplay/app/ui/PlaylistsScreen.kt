package com.whereverplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.PlaylistEntry
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import com.whereverplay.app.domain.resolve
import java.io.File
import kotlin.math.roundToInt

/* ============ 列表页 ============ */

/**
 * WP: 播放列表首页。
 * 新增：右上角「＋」下拉（新建 / 导入 .wplist）、长按多选（批量导出 / 删除）。
 */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    projects: Map<String, Project>,
    onCreate: (String) -> Unit,
    onImport: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onExport: (List<Playlist>) -> Unit,
    onBatchDelete: (List<Playlist>) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var plusMenu by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }
    val selecting = selection.isNotEmpty()

    androidx.activity.compose.BackHandler(enabled = selecting) { selection.clear() }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selection.size}", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onExport(playlists.filter { it.id in selection })
                    selection.clear()
                }) { Text("导出", fontSize = 12.sp) }
                TextButton(onClick = {
                    onBatchDelete(playlists.filter { it.id in selection })
                    selection.clear()
                }) { Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80)) }
                TextButton(onClick = { selection.clear() }) { Text("取消", fontSize = 12.sp) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("播放列表", fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box {
                    WpPillAction(text = "新建", onClick = { plusMenu = true }) { tint ->
                        IconPlus(tint, 13.dp)
                    }
                    DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("新建播放列表", fontSize = 12.sp) },
                            onClick = { plusMenu = false; newName = ""; creating = true },
                        )
                        DropdownMenuItem(
                            text = { Text("导入 .wplist 文件", fontSize = 12.sp) },
                            onClick = { plusMenu = false; onImport() },
                        )
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有播放列表", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "把几首曲子串成一场演出，播完自动接下一首",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(playlists, key = { it.id }) { p ->
                val entries = p.resolve(projects)
                val missing = entries.count { it is PlaylistEntry.Missing }
                PlaylistRow(
                    playlist = p,
                    total = entries.size,
                    missing = missing,
                    selecting = selecting,
                    selected = p.id in selection,
                    onClick = {
                        if (selecting) {
                            if (p.id in selection) selection.remove(p.id) else selection.add(p.id)
                        } else {
                            onOpen(p)
                        }
                    },
                    onLongClick = { if (p.id !in selection) selection.add(p.id) },
                )
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建播放列表") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCreate(newName.trim().ifBlank { "未命名列表" })
                    creating = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    total: Int,
    missing: Int,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.fill)
            .border(
                if (selected) 1.5.dp else 0.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else Glass.border,
                shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF3A3F4C)),
            contentAlignment = Alignment.Center
        ) { Text("≡", fontSize = 18.sp, color = Color(0xFFC9CED8)) }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(playlist.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append("$total 首")
                    if (missing > 0) append(" · $missing 项不可用")
                    append(if (playlist.advanceMode == Playlist.ADVANCE_AUTO) " · 自动衔接" else " · 手动衔接")
                },
                fontSize = 11.sp,
                color = if (missing > 0) Color(0xFFF0C070) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (selecting) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", fontSize = 13.sp, color = Color(0xFF07203A))
            }
        } else {
            Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ============ 编辑页 ============ */

@Composable
fun PlaylistEditScreen(
    playlist: Playlist,
    projects: List<Project>,
    onBack: () -> Unit,
    onSave: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    val context = LocalContext.current
    val projectMap = remember(projects) { projects.associateBy { it.id } }
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var ids by remember(playlist.id) { mutableStateOf(playlist.projectIds) }
    var advanceMode by remember(playlist.id) { mutableStateOf(playlist.advanceMode) }
    var gapMs by remember(playlist.id) { mutableStateOf(playlist.gapMs) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 长按拖动排序：dragFrom 是被拖的行，dragDy 是相对起点的位移（像素）
    var dragFrom by remember(playlist.id) { mutableStateOf<Int?>(null) }
    var dragDy by remember(playlist.id) { mutableStateOf(0f) }
    // 行高 = 内容 58 + 间距 8，用来把位移换算成「换几位」
    val rowPx = with(LocalDensity.current) { 66.dp.toPx() }

    fun persist(newIds: List<String> = ids) {
        ids = newIds
        onSave(
            playlist.copy(
                name = name.trim().ifBlank { playlist.name },
                projectIds = newIds,
                advanceMode = advanceMode,
                gapMs = gapMs,
            )
        )
    }

    // 放在 persist 之后声明：Kotlin 局部函数必须先声明后使用
    androidx.activity.compose.BackHandler(enabled = true) { persist(); onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { persist(); onBack() }) { Text("←", fontSize = 17.sp) }
            Spacer(Modifier.weight(1f))
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80)) }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("列表名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Text("曲目（${ids.size}）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            if (ids.isEmpty()) {
                Text("还没有曲目，点下面「添加曲目」", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ids.forEachIndexed { index, id ->
                val project = projectMap[id]
                val dragging = dragFrom == index
                Column(
                    Modifier
                        // 拖动中的行抬到最上层并跟手位移；松手才真正换位（不在拖动过程中重排，
                        // 否则手指下面的行会不停抽换，越拖越乱）
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragDy else 0f
                            shadowElevation = if (dragging) 12f else 0f
                            shape = RoundedCornerShape(12.dp)
                        }
                ) {
                    EntryRow(
                        index = index,
                        project = project,
                        dragging = dragging,
                        onDragStart = {
                            dragFrom = index
                            dragDy = 0f
                        },
                        onDrag = { dy -> if (dragFrom != null) dragDy += dy },
                        onDragEnd = {
                            val from = dragFrom
                            dragFrom = null
                            if (from != null) {
                                val target = (from + (dragDy / rowPx).roundToInt())
                                    .coerceIn(0, ids.lastIndex)
                                dragDy = 0f
                                if (target != from) {
                                    persist(ids.toMutableList().also { it.add(target, it.removeAt(from)) })
                                }
                            } else {
                                dragDy = 0f
                            }
                        },
                        onRemove = { persist(ids.toMutableList().also { it.removeAt(index) }) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallAction("添加曲目", Modifier.weight(1f)) { adding = true }
                if (ids.any { it !in projectMap }) {
                    SmallAction("移除失效项", Modifier.weight(1f), danger = true) {
                        persist(ids.filter { it in projectMap })
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("衔接方式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip("播完暂停", advanceMode == Playlist.ADVANCE_MANUAL) {
                    advanceMode = Playlist.ADVANCE_MANUAL
                    persist()
                }
                ModeChip("自动接下一首", advanceMode == Playlist.ADVANCE_AUTO) {
                    advanceMode = Playlist.ADVANCE_AUTO
                    persist()
                }
            }
            if (advanceMode == Playlist.ADVANCE_AUTO) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("间隔", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${gapMs / 1000} 秒",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                androidx.compose.material3.Slider(
                    value = (gapMs / 1000f).coerceIn(0f, 120f),
                    onValueChange = { gapMs = (it * 1000).toLong() },
                    onValueChangeFinished = { persist() },
                    valueRange = 0f..120f,
                    steps = 119,
                )
                Text(
                    "0 秒 = 立即接下一首，最长 120 秒",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDEDED))
                    .clickable { persist(); onPlay(playlist.copy(projectIds = ids, advanceMode = advanceMode, gapMs = gapMs)) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("开始播放", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF17171A))
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (adding) {
        val existing = ids.toSet()
        var picked by remember { mutableStateOf(existing) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加曲目") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    if (projects.isEmpty()) {
                        Text("还没有项目", fontSize = 12.sp)
                    }
                    projects.forEach { p ->
                        val checked = p.id in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (checked) picked - p.id else picked + p.id
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (checked) "✓" else "○",
                                fontSize = 14.sp,
                                color = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${TimeFormat.mmss(p.durationMs)} · ${p.pageCount} 页",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 保持原有顺序，新加的追加到末尾
                    val ordered = ids + projects.map { it.id }.filter { it in picked && it !in ids }
                    persist(ordered)
                    adding = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个播放列表？") },
            text = { Text("只删除列表本身，里面的项目不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(playlist)
                }) { Text("删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/**
 * WP: 单行曲目。
 *
 * 原来右侧是 `↑ ↓ ✕` 三个文字按钮（行内 5 个可点区域），排序要点两次。
 * 现在：长按整行直接拖（把手是提示，不是按钮），右侧只留一个删除图标。
 */
@Composable
private fun EntryRow(
    index: Int,
    project: Project?,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val coverFile = remember(project?.coverFile) {
        project?.coverFile?.let { File(File(context.filesDir, "media"), it) }
    }
    val cover = rememberCoverBitmap(coverFile, 160)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (project == null) Color(0x14FF6B6B) else Glass.fill)
            .border(
                width = if (dragging) 1.dp else 0.5.dp,
                color = if (dragging) Glass.borderStrong else Color.Transparent,
                shape = shape,
            )
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            }
            .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 把手：纯视觉提示（真正的交互是长按整行）
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            IconDragHandle(Color(0xFF767680), 18.dp)
        }
        Spacer(Modifier.width(6.dp))
        CoverView(
            bitmap = cover,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)),
            placeholderColor = if (project == null) Color(0xFF4A3A3A) else Color(0xFF33404F),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                project?.name ?: "项目已删除",
                fontSize = 13.sp,
                color = if (project == null) Color(0xFFF0A0A0) else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (project == null) "不可用 · 重新导入该项目可自动恢复"
                else "${TimeFormat.mmss(project.durationMs)} · ${project.pageCount} 页",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        WpIconButton(onClick = onRemove, visual = 32.dp) { tint ->
            IconClose(tint, 14.dp)
        }
    }
}

@Composable
private fun SmallAction(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (danger) Color(0x22FF6B6B) else Glass.fillStrong)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (danger) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Glass.fillStrong else Glass.fill)
            .border(0.5.dp, if (selected) Glass.borderStrong else Glass.border, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
