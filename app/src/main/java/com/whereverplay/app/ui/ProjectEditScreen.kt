package com.whereverplay.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import com.whereverplay.app.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WP: 编辑项目页 —— 所有「会改动项目」的动作都收在这里。
 *
 * 分两层：
 *  - **动文件**（换音频 / 换谱面 / 重新打点）：都会让已有打点失效，属于「要重花时间」的重操作；
 *  - **其它**（导出 .wplay / 删除项目）：不常点，但都关系到这个项目的生死，放在详情页会把
 *    「看信息」那页变成操作台。用户要求这些按钮从详情页挪过来，详情页现在只有「播放」一个动作。
 * 动文件这三个动作各过一次确认弹窗。
 */
@Composable
fun ProjectEditScreen(
    project: Project,
    installer: MediaInstaller,
    playlistRefCount: Int,
    onBack: () -> Unit,
    onOpenMarks: (Project) -> Unit,
    onUpdate: (Project) -> Unit,
    onExport: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingReplace by remember { mutableStateOf<String?>(null) } // "audio" | "pdf"
    var replaceError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 另存：记住「要存哪个文件、建议什么名字」，等 SAF 回调回来再写
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var pendingSaveName by remember { mutableStateOf("") }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val file = pendingSaveFile
        pendingSaveFile = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileSaver.copyFileToUri(context, file, uri) }
            }
            result.onSuccess { onNotify("已另存 ${pendingSaveName}") }
                .onFailure { onNotify("另存失败：${it.message ?: "未知原因"}") }
        }
    }

    /** 打开「另存为」对话框；文件名带上项目名，方便用户之后辨认 */
    fun askSave(fileName: String, suffix: String) {
        val file = installer.fileOf(fileName)
        if (!file.exists()) {
            onNotify("文件缺失，无法另存")
            return
        }
        val ext = fileName.substringAfterLast('.', "")
        val base = FileSaver.safeBaseName(project.name, "项目")
        pendingSaveFile = file
        pendingSaveName = if (ext.isBlank()) "$base-$suffix" else "$base-$suffix.$ext"
        savePicker.launch(pendingSaveName)
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val newName = installer.copyIn(uri, "audio", "mp3")
                val dur = MediaInstaller.readDurationMs(installer.fileOf(newName))
                require(dur > 0L) { "无法读取音频时长" }
                require(dur <= 30L * 60L * 1000L) { "音频超过 30 分钟上限" }
                val old = project.audioFile
                onUpdate(
                    project.copy(
                        audioFile = newName,
                        durationMs = dur,
                        // 时间轴变了，旧打点必然失效 → 重置为第 1 页 0:00
                        marks = listOf(Mark(page = 1, timeMs = 0L)),
                    )
                )
                runCatching { installer.fileOf(old).delete() }
                replaceError = null
            }.onFailure { replaceError = it.message ?: "更换失败" }
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val newName = installer.copyIn(uri, "pdf", "pdf")
                val pages = PdfPageRenderer.pageCountOf(installer.fileOf(newName))
                require(pages > 0) { "无法解析这个 PDF" }
                val old = project.pdfFile
                onUpdate(
                    project.copy(
                        pdfFile = newName,
                        pageCount = pages,
                        marks = listOf(Mark(page = 1, timeMs = 0L)),
                    )
                )
                runCatching { installer.fileOf(old).delete() }
                replaceError = null
            }.onFailure { replaceError = it.message ?: "更换失败" }
        }
    }

    fun sizeText(fileName: String): String {
        val f = File(File(context.filesDir, "media"), fileName)
        if (!f.exists()) return "文件缺失"
        val kb = f.length() / 1024.0
        return if (kb > 1024) "%.1f MB".format(kb / 1024) else "%.0f KB".format(kb)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WpIconButton(onClick = onBack, visual = 0.dp) { tint -> IconBack(tint, 18.dp) }
            Spacer(Modifier.weight(1f))
            Text("编辑项目", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                project.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "更换音频或谱面后，现有打点会全部清空，需要重新打点。",
                fontSize = 11.sp,
                color = Color(0xFFF0C070),
            )

            Spacer(Modifier.height(16.dp))

            EditRow(
                title = "伴奏音频",
                detail = "${TimeFormat.mmss(project.durationMs)} · ${sizeText(project.audioFile)}",
                action = "更换歌曲",
                onSave = { askSave(project.audioFile, "伴奏") },
                onReplace = { pendingReplace = "audio" },
            )

            Spacer(Modifier.height(10.dp))

            EditRow(
                title = "PDF 谱面",
                detail = "${project.pageCount} 页 · ${sizeText(project.pdfFile)}",
                action = "更换谱面",
                onSave = { askSave(project.pdfFile, "谱面") },
                onReplace = { pendingReplace = "pdf" },
            )

            replaceError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDEDED))
                    .clickable { onOpenMarks(project) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (project.marks.size <= 1) "开始打点" else "重新打点",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF17171A),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "打点：边听边在每页开始处点「添加第 N 页」。已完成 ${project.marks.size} / ${project.pageCount} 页。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text("其它操作", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            WpGhostButton(
                text = "导出 .wplay",
                onClick = { onExport(project) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FF6B6B))
                    .clickable { confirmDelete = true }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("删除项目", fontSize = 13.sp, color = Color(0xFFFF8A80))
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    pendingReplace?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text(if (target == "audio") "更换歌曲？" else "更换谱面？") },
            text = { Text("更换后时间轴或页数会变，现有打点将全部失效，需要重新打点。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingReplace = null
                    if (target == "audio") pickAudio.launch(arrayOf("audio/*"))
                    else pickPdf.launch(arrayOf("application/pdf"))
                }) { Text("继续更换") }
            },
            dismissButton = { TextButton(onClick = { pendingReplace = null }) { Text("取消") } },
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

/**
 * WP: 一个文件条目：左边是什么文件，右边两个动作。
 * 2026-10-25：整行不再可点（原来点哪都触发「更换」），改成右侧两个独立按钮「另存 / 更换」，
 * 避免想备份文件时误触更换（更换会清空全部打点，代价太大）。
 */
@Composable
private fun EditRow(
    title: String,
    detail: String,
    action: String,
    onSave: () -> Unit,
    onReplace: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.fill)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        TextButton(
            onClick = onSave,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text("另存", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = onReplace,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
