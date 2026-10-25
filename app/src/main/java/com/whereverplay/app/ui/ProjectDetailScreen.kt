package com.whereverplay.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.MediaInstaller
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import java.io.File

/**
 * WP: 项目详情页 —— 只负责「看信息 + 改元信息」。
 *
 * 按用户的分工拆成两页：
 *  - **详情**（本页）：名称 / 作者 / 标签 / 封面 / 各项统计；
 *  - **编辑**（ProjectEditScreen）：换音频、换谱面、重新打点。
 * 以前一页塞了 6 个按钮（播放/重新打点/换歌/换谱/删除/改封面），层级混乱。
 * 现在主操作只剩「播放」一个实心，编辑入口收在顶栏右侧，删除留在页面最底部。
 */
@Composable
fun ProjectDetailScreen(
    project: Project,
    tags: List<String>,
    canAddTag: Boolean,
    maxTags: Int,
    playlistRefCount: Int,
    installer: MediaInstaller,
    onBack: () -> Unit,
    onOpenPlay: (Project) -> Unit,
    onOpenEdit: (Project) -> Unit,
    onExport: (Project) -> Unit,
    onUpdate: (Project) -> Unit,
    onAddTag: (String) -> Boolean,
    onDelete: (Project) -> Unit,
) {
    val context = LocalContext.current
    var editingInfo by remember { mutableStateOf(false) }
    var pickingTag by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val coverFile = remember(project.coverFile) {
        project.coverFile?.let { File(File(context.filesDir, "media"), it) }
    }
    val cover = rememberCoverBitmap(coverFile, 512)

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            installer.copyInSquareCover(uri)?.let { name ->
                onUpdate(project.copy(coverFile = name))
            }
        }
    }

    fun sizeText(fileName: String): String {
        val f = File(File(context.filesDir, "media"), fileName)
        if (!f.exists()) return "文件缺失"
        val kb = f.length() / 1024.0
        return if (kb > 1024) "%.1f MB".format(kb / 1024) else "%.0f KB".format(kb)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏：返回 / 标题 / 编辑（进文件编辑页） ----------
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WpIconButton(onClick = onBack, visual = 0.dp) { tint -> IconBack(tint, 18.dp) }
            Spacer(Modifier.weight(1f))
            Text("项目详情", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onOpenEdit(project) }) { Text("编辑", fontSize = 12.sp) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ---------- 封面 + 标题 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CoverView(
                        bitmap = cover,
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp)),
                        placeholderColor = Color(0xFF33404F),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                pickCover.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        project.author.ifBlank { "未知作者" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (project.configured) "已完成打点" else "未完成打点",
                        fontSize = 11.sp,
                        color = if (project.configured) Color(0xFF8FD9C4) else Color(0xFFF0C070)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("点封面可换图", fontSize = 10.sp, color = Color(0xFF6E6E78))
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 可改的三项：名称/作者、标签 ----------
            EditableRow(
                label = "名称 / 作者",
                value = "${project.name} · ${project.author.ifBlank { "未知作者" }}",
                actionText = "修改",
            ) { editingInfo = true }

            Spacer(Modifier.height(8.dp))

            EditableRow(
                label = "乐器标签",
                value = project.tag ?: "未设置",
                valueHighlight = project.tag != null,
                actionText = if (project.tag == null) "选择" else "更换",
            ) { pickingTag = true }

            Spacer(Modifier.height(16.dp))

            InfoGroup {
                InfoRow("时长", TimeFormat.mmss(project.durationMs))
                InfoRow("页数", "${project.pageCount} 页")
                InfoRow("打点数", "${project.marks.size} / ${project.pageCount}")
                InfoRow("音频文件", sizeText(project.audioFile))
                InfoRow("PDF 文件", sizeText(project.pdfFile))
                InfoRow("被播放列表引用", if (playlistRefCount > 0) "$playlistRefCount 个" else "无")
            }

            Spacer(Modifier.height(18.dp))

            // 整页唯一实心主操作
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDEDED))
                    .clickable { onOpenPlay(project) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("播放", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF17171A))
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostAction("更换文件 / 重新打点", Modifier.weight(1f)) { onOpenEdit(project) }
                GhostAction("导出 .wplay", Modifier.weight(1f)) { onExport(project) }
            }

            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FF6B6B))
                    .clickable { confirmDelete = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("删除项目", fontSize = 13.sp, color = Color(0xFFFF8A80))
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    // ---------- 改名称 / 作者 ----------
    if (editingInfo) {
        var name by remember { mutableStateOf(project.name) }
        var author by remember { mutableStateOf(project.author) }
        AlertDialog(
            onDismissRequest = { editingInfo = false },
            title = { Text("编辑名称与作者", fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("项目名称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("作者") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(project.copy(name = name.trim().ifBlank { project.name }, author = author.trim()))
                    editingInfo = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingInfo = false }) { Text("取消") } },
        )
    }

    // ---------- 选标签 ----------
    if (pickingTag) {
        TagPickerDialog(
            current = project.tag,
            tags = tags,
            canAdd = canAddTag,
            maxTags = maxTags,
            onPick = { tag ->
                onUpdate(project.copy(tag = tag))
                pickingTag = false
            },
            onAdd = onAddTag,
            onDismiss = { pickingTag = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${project.name}」？") },
            text = {
                Text(
                    buildString {
                        append("会同时删除已复制的音频、PDF 与封面，无法恢复。")
                        if (playlistRefCount > 0) {
                            append("\n\n注意：有 $playlistRefCount 个播放列表引用了它，删除后那些列表里会显示为「不可用」占位。")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(project)
                }) { Text("删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** 一行「标签 + 当前值 + 动作」的可改信息行 */
@Composable
private fun EditableRow(
    label: String,
    value: String,
    actionText: String,
    valueHighlight: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.fill)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                fontSize = 13.sp,
                color = if (valueHighlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(actionText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoGroup(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.fill)
            .padding(vertical = 4.dp)
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.sp)
    }
}

/** 玻璃次按钮（详情页底部的次级动作） */
@Composable
private fun GhostAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Glass.fillStrong)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}
