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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.FileSaver
import com.whereverplay.app.data.MediaInstaller
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WP: 项目详情页 —— **只读信息 + 播放**。
 *
 * 按用户的分工：这一页只负责「看」，所有改动都去编辑页。
 *  - **详情**（本页）：封面 / 名称 / 作者 / 乐器标签 / 各项统计 / 播放；
 *  - **编辑**（ProjectEditScreen）：换音频、换谱面、重新打点、导出 .wplay、删除项目。
 * 曾经详情页底部还挂着「更换文件 / 重新打点」「导出」「删除」三个按钮，
 * 和右上角的「编辑」入口完全重复，还让「看信息」这一页看着像操作台 —— 已全部下放到编辑页。
 *
 * 2026-10-25：点封面不再是「直接换图」，改成**全屏看图**，换图变成看图页底部的一个动作
 * （另存图片 / 更换图片）。理由是换图是低频动作，而「想看清封面」才是点它的直觉。
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
    onUpdate: (Project) -> Unit,
    onAddTag: (String) -> Boolean,
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingInfo by remember { mutableStateOf(false) }
    var pickingTag by remember { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveHint by remember { mutableStateOf<String?>(null) }

    val coverFile = remember(project.coverFile) {
        project.coverFile?.let { File(File(context.filesDir, "media"), it) }
    }
    val cover = rememberCoverBitmap(coverFile, 512)
    // 全屏看时才按大图解码，避免列表/详情页常驻一张 1600px 的位图
    val bigCover = if (viewerOpen) rememberCoverBitmap(coverFile, 1600) else null

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            installer.copyInSquareCover(uri)?.let { name ->
                onUpdate(project.copy(coverFile = name))
                viewerOpen = false
            }
        }
    }

    /** 存进系统相册（API<29 需要 WRITE_EXTERNAL_STORAGE，由下面的权限启动器兜底） */
    fun saveCoverToGallery() {
        val file = coverFile
        if (file == null || !file.exists()) {
            onNotify("这个项目还没有封面图")
            return
        }
        saving = true
        saveHint = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FileSaver.saveImageToGallery(
                        context = context,
                        source = file,
                        displayName = FileSaver.safeBaseName(project.name, "封面") + "-封面.jpg",
                    )
                }
            }
            saving = false
            result.onSuccess { saveHint = "已保存到相册 Pictures/WhereverPlay" }
                .onFailure { saveHint = "保存失败：${it.message ?: "未知原因"}" }
        }
    }

    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveCoverToGallery() else onNotify("没有存储权限，无法保存到相册")
    }

    fun startSaveCover() {
        if (FileSaver.needsLegacyWritePermission(context)) {
            legacyPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveCoverToGallery()
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
                            // WP: 点封面 = 全屏看图（换图挪到看图页里）
                            .clickable { viewerOpen = true }
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
                    Text("点封面可看大图", fontSize = 10.sp, color = Color(0xFF6E6E78))
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
            // 换文件 / 重新打点 / 导出 / 删除 全部在编辑页，详情页只留「播放」
            Text(
                "换文件、重新打点、导出、删除都在右上角「编辑」里",
                fontSize = 10.sp,
                color = Color(0xFF6E6E78),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

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

    // ---------- 全屏看图：另存图片 / 更换图片 ----------
    if (viewerOpen) {
        ImageViewerOverlay(
            bitmap = bigCover,
            title = project.name,
            busy = saving,
            hint = saveHint,
            onClose = {
                viewerOpen = false
                saveHint = null
            },
            onSave = { startSaveCover() },
            onReplace = {
                pickCover.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
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
