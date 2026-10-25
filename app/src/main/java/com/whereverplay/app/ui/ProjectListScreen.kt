package com.whereverplay.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.domain.Project
import java.io.File

enum class ProjectSort(val label: String) {
    Recent("最近修改"),
    Name("名称"),
    Created("创建时间"),
}

private enum class ProjectFilter { All, Favorite }

/**
 * WP: 项目列表（首页 tab 0）。
 *
 * 这一版新增的四件事：
 *  1. 顶栏右上角多了「筛选」——按乐器标签过滤，和搜索/收藏叠加；
 *  2. 「＋ 新建」改成下拉：新建项目 / 导入 .wplay；
 *  3. 卡片右下角「⋯」不再直接跳详情页，而是弹出「详情 / 编辑 / 导出 / 删除」；
 *  4. 长按多选里加了「导出」，多选导出走目录选择器一次写多个文件。
 */
@Composable
fun ProjectListScreen(
    projects: List<Project>,
    tags: List<String>,
    onOpen: (Project) -> Unit,
    onToggleFavorite: (Project) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onBatchDelete: (List<Project>) -> Unit,
    onBatchFavorite: (List<Project>) -> Unit,
    onBatchExport: (List<Project>) -> Unit,
    onShowDetail: (Project) -> Unit,
    onEdit: (Project) -> Unit,
    onExport: (Project) -> Unit,
    onDelete: (Project) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ProjectFilter.All) }
    var sort by remember { mutableStateOf(ProjectSort.Recent) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val selection = remember { mutableStateListOf<String>() }
    val selecting = selection.isNotEmpty()

    // 筛选菜单只列「真的有项目在用」的标签 —— 20 个标签全列出来菜单会长到点不动
    val usedTags = remember(projects) {
        projects.mapNotNull { it.tag }.distinct()
    }

    // 多选状态下按返回 = 退出选择，而不是退出到桌面
    androidx.activity.compose.BackHandler(enabled = selecting) { selection.clear() }

    val visible = remember(projects, query, filter, sort, tagFilter) {
        val q = query.trim()
        projects
            .filter { p ->
                val okFilter = when (filter) {
                    ProjectFilter.All -> true
                    ProjectFilter.Favorite -> p.isFavorite
                }
                val okTag = tagFilter == null || p.tag == tagFilter
                okFilter && okTag && (q.isEmpty() ||
                    p.name.contains(q, true) || p.author.contains(q, true))
            }
            .let { list ->
                when (sort) {
                    ProjectSort.Recent -> list.sortedByDescending { it.lastOpenedAt }
                    ProjectSort.Name -> list.sortedBy { it.name.lowercase() }
                    ProjectSort.Created -> list.sortedByDescending { it.createdAt }
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏 ----------
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selection.size}", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onBatchFavorite(visible.filter { it.id in selection })
                    selection.clear()
                }) { Text("收藏", fontSize = 12.sp) }
                TextButton(onClick = {
                    onBatchExport(visible.filter { it.id in selection })
                    selection.clear()
                }) { Text("导出", fontSize = 12.sp) }
                TextButton(onClick = {
                    onBatchDelete(visible.filter { it.id in selection })
                    selection.clear()
                }) { Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80)) }
                TextButton(onClick = { selection.clear() }) { Text("取消", fontSize = 12.sp) }
            }
        } else {
            // 标题撑满把动作推到右侧：筛选 44 + 排序 44 + 新建胶囊
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("项目", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

                // 筛选：激活时图标变强调色，让「列表为什么少了几项」一眼看得出来
                Box {
                    WpIconButton(
                        onClick = { filterMenu = true },
                        visual = 34.dp,
                        background = if (tagFilter != null) Color(0x339EC5FF) else Glass.fillStrong,
                        borderColor = if (tagFilter != null) MaterialTheme.colorScheme.primary else Glass.border,
                        tint = if (tagFilter != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                    ) { tint -> IconFilter(tint, 16.dp) }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        Text(
                            "按乐器筛选",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (tagFilter == null) "✓ " else "   ") + "全部乐器",
                                    fontSize = 12.sp,
                                    color = if (tagFilter == null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground
                                )
                            },
                            onClick = { tagFilter = null; filterMenu = false },
                        )
                        if (usedTags.isEmpty()) {
                            Text(
                                "还没有项目设置标签",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 8.dp)
                            )
                        }
                        usedTags.forEach { tag ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (tagFilter == tag) "✓ " else "   ") + tag,
                                        fontSize = 12.sp,
                                        color = if (tagFilter == tag) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground
                                    )
                                },
                                onClick = { tagFilter = tag; filterMenu = false },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                Box {
                    WpIconButton(
                        onClick = { sortMenu = true },
                        visual = 34.dp,
                        background = Glass.fillStrong,
                        borderColor = Glass.border,
                    ) { tint -> IconSort(tint, 16.dp) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        Text(
                            "排序方式",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
                        )
                        ProjectSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (sort == option) "✓ " else "   ") + option.label,
                                        fontSize = 12.sp,
                                        color = if (sort == option) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground
                                    )
                                },
                                onClick = { sort = option; sortMenu = false },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                Box {
                    WpPillAction(text = "新建", onClick = { plusMenu = true }) { tint ->
                        IconPlus(tint, 13.dp)
                    }
                    DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("新建项目", fontSize = 12.sp) },
                            onClick = { plusMenu = false; onCreate() },
                        )
                        DropdownMenuItem(
                            text = { Text("导入 .wplay 文件", fontSize = 12.sp) },
                            onClick = { plusMenu = false; onImport() },
                        )
                    }
                }
            }
        }

        // ---------- 搜索 ----------
        GlassBox(Modifier.fillMaxWidth().padding(horizontal = 18.dp), corner = 15.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(14.dp)) {
                    val st = 1.3.dp.toPx()
                    drawCircle(iconColor, radius = size.width * 0.32f, center = Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(st))
                    drawLine(iconColor, Offset(size.width * 0.66f, size.height * 0.66f), Offset(size.width * 0.94f, size.height * 0.94f), st)
                }
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("搜索项目、作者", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 筛选行：全部 / 收藏 +（有标签筛选时）当前标签 ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WpChip("全部", { filter = ProjectFilter.All }, selected = filter == ProjectFilter.All, height = 28.dp)
            Spacer(Modifier.width(8.dp))
            WpChip("收藏", { filter = ProjectFilter.Favorite }, selected = filter == ProjectFilter.Favorite, height = 28.dp)
            tagFilter?.let { tag ->
                Spacer(Modifier.width(8.dp))
                WpChip(
                    text = "$tag  ✕",
                    onClick = { tagFilter = null },
                    selected = true,
                    height = 28.dp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            projects.isEmpty() -> "还没有项目"
                            tagFilter != null -> "这个乐器下还没有项目"
                            else -> "没有匹配的项目"
                        },
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (projects.isEmpty()) "点右上角「+ 新建」，选伴奏音频和 PDF 谱" else "换个关键词或筛选条件试试",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        LazyVerticalGrid(
            // WP: 原来 Adaptive(168) 在 360dp 屏算出 1 列 → 整屏一个巨幅方块。
            // (360 − 36 + 12) ÷ (168 + 12) = 1，这就是根因。固定 2 列后每列 162dp。
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visible, key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    selected = p.id in selection,
                    selecting = selecting,
                    onClick = {
                        if (selecting) {
                            if (p.id in selection) selection.remove(p.id) else selection.add(p.id)
                        } else {
                            onOpen(p)
                        }
                    },
                    onLongClick = {
                        if (p.id !in selection) selection.add(p.id)
                    },
                    onToggleFavorite = { onToggleFavorite(p) },
                    onShowDetail = { onShowDetail(p) },
                    onEdit = { onEdit(p) },
                    onExport = { onExport(p) },
                    onDelete = { onDelete(p) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowDetail: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val coverFile = remember(project.coverFile) {
        project.coverFile?.let { File(File(context.filesDir, "media"), it) }
    }
    val cover = rememberCoverBitmap(coverFile, 320)
    val shape = RoundedCornerShape(16.dp)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .clip(shape)
            .background(Glass.fill)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else Glass.border, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box {
            CoverView(
                bitmap = cover,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(11.dp)),
                placeholderColor = placeholderColorOf(project.id),
            )

            // 未打点：封面左上角角标（不占作者那一行）
            if (!project.configured) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3121216))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("未打点", fontSize = 9.sp, color = Color(0xFFF0C070))
                }
            }

            // 乐器标签：封面左下角。跟「未打点」分居两个角，互不遮挡
            project.tag?.let { tag ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB3121216))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(tag, fontSize = 9.sp, color = Color(0xFF9EC5FF), maxLines = 1)
                }
            }

            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", fontSize = 13.sp, color = Color(0xFF07203A))
                }
            } else {
                // 更多：封面右下角浮层（浅色封面上也看得清）。
                // 菜单挂在按钮自己的 Box 里 → 弹窗贴着按钮出现，而不是飘到卡片左上角。
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        IconMoreHorizontal(Color(0xFFE4E6EA), 16.dp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("详情", fontSize = 12.sp) },
                            onClick = { menuOpen = false; onShowDetail() },
                        )
                        DropdownMenuItem(
                            text = { Text("编辑", fontSize = 12.sp) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("导出", fontSize = 12.sp) },
                            onClick = { menuOpen = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80)) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            project.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                project.author.ifBlank { "未知作者" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!selecting) {
                // 44 热区 + 20 图标，独占行尾，不再和「更多」相邻
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { onToggleFavorite() },
                    contentAlignment = Alignment.Center
                ) {
                    IconStar(
                        tint = if (project.isFavorite) Color(0xFFE8C35A)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = project.isFavorite,
                        box = 20.dp,
                    )
                }
            }
        }
    }
}

private fun placeholderColorOf(seed: String): Color {
    val palette = listOf(
        Color(0xFF33404F), Color(0xFF4A3B46), Color(0xFF3A4740),
        Color(0xFF41404F), Color(0xFF473F33),
    )
    return palette[(seed.hashCode() and 0x7fffffff) % palette.size]
}
