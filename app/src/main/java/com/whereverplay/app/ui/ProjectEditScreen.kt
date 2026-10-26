package com.whereverplay.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import com.whereverplay.app.domain.MarkRules
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
 *  - **动文件**（换音频 / 换谱面 / 重新打点）：都是「要重花时间」的重操作；
 *  - **其它**（导出 .wplay / 删除项目）：不常点，但都关系到这个项目的生死，放在详情页会把
 *    「看信息」那页变成操作台。用户要求这些按钮从详情页挪过来，详情页现在只有「播放」一个动作。
 * 动文件这三个动作各过一次确认弹窗。
 *
 * 2026-10-25（0.2.1）行为变更：**换音频 / 换谱面不再清空打点**（用户要求）。
 *  - 换音频：打点按「时间」保留，新伴奏更长时全部照旧可用；更短时超出时长的打点不会触发，
 *    但也不删——换回长版还能接着用。
 *  - 换谱面：打点按「页」保留，只丢掉指向新谱面里已不存在页的那些（否则那些页会永远渲染空白，
 *    因为 PdfPageRenderer.render 越界直接返回 null）。
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
    var confirmRemoveAudio by remember { mutableStateOf(false) }

    /**
     * 移除音频（0.3.0）：文件删掉、audioFile 置空、时间轴回落到 12:00，
     * **打点一条都不删**，只把超过 12:00 的压进 12:00 内（仍满足严格递增 + 300ms）。
     */
    fun removeAudio() {
        val old = project.audioFile ?: return
        val clamped = MarkRules.clampToDuration(project.marks, Project.DEFAULT_DURATION_MS)
        val squeezed = clamped != project.marks
        onUpdate(
            project.copy(
                audioFile = null,
                durationMs = Project.DEFAULT_DURATION_MS,
                marks = clamped,
            )
        )
        runCatching { installer.fileOf(old).delete() }
        onNotify(
            if (squeezed) "已移除音频，时间轴改为 12:00，超出部分的打点已压到 12:00 内"
            else "已移除音频，时间轴改为 12:00，打点保持不变"
        )
    }

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
                        // 0.2.1：不再重置打点，原有打点按时间原样保留
                    )
                )
                old?.let { runCatching { installer.fileOf(it).delete() } }
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
                // 0.2.1：打点保留。只丢掉指向新谱面里不存在的页（越界页会一直渲染空白）
                val kept = project.marks.filter { it.page <= pages }
                val dropped = project.marks.size - kept.size
                onUpdate(
                    project.copy(
                        pdfFile = newName,
                        pageCount = pages,
                        marks = kept.ifEmpty { listOf(Mark(page = 1, timeMs = 0L)) },
                    )
                )
                runCatching { installer.fileOf(old).delete() }
                replaceError = null
                if (dropped > 0) onNotify("已更换谱面，$dropped 个打点超出新页数，已丢弃")
            }.onFailure { replaceError = it.message ?: "更换失败" }
        }
    }

    fun sizeText(fileName: String?): String {
        if (fileName.isNullOrBlank()) return "无"
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
                "换音频/谱面、移除音频都不会清空打点。只有两种情况会变：新谱面页数更少时丢掉越界页，" +
                    "移除音频后超过 12:00 的打点被压到 12:00 内。",
                fontSize = 11.sp,
                color = Color(0xFFF0C070),
            )

            Spacer(Modifier.height(16.dp))

            EditRow(
                title = "伴奏音频",
                detail = if (project.hasAudio) {
                    "${TimeFormat.mmss(project.durationMs)} · ${sizeText(project.audioFile)}"
                } else {
                    "未设置 · 时间轴固定 12:00，仍可打点、可定时翻谱"
                },
                action = if (project.hasAudio) "更换音频" else "添加音频",
                onSave = if (project.hasAudio) {
                    { project.audioFile?.let { askSave(it, "伴奏") } }
                } else {
                    null
                },
                onReplace = { pendingReplace = "audio" },
            )

            // 0.3.0：音频可移除。移除后时间轴回落到 12:00，打点一条不丢（超出的压到 12:00 内）
            if (project.hasAudio) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { confirmRemoveAudio = true },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("移除音频（只留谱面）", fontSize = 11.sp, color = Color(0xFFFF8A80))
                    }
                }
            }

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
            text = {
                Text(
                    if (target == "audio")
                        "现有打点会保留。换了伴奏之后，打点时间可能和新音频对不上，需要自己去打点页核对。"
                    else
                        "现有打点会保留。若新谱面页数比原来少，超出页数的打点会被丢弃。"
                )
            },
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

    if (confirmRemoveAudio) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAudio = false },
            title = { Text("移除这个项目的音频？") },
            text = {
                Text(
                    "只会删掉伴奏文件，谱面和打点都保留。时间轴长度会变成 12:00，" +
                        "超过 12:00 的打点会被压到 12:00 以内（不会删页）。\n\n" +
                        "移除后播放页就是「谱面 + 静音时间轴」：不点播放可以纯看谱，点播放则按打点定时翻谱。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAudio = false
                    removeAudio()
                }) { Text("移除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAudio = false }) { Text("取消") } },
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
 * 避免想备份文件时误触更换（早先更换会清空全部打点，代价太大；0.2.1 起打点虽然保留，
 * 但更换仍然不是能随手点的动作，所以这个布局不改回去）。
 */
@Composable
private fun EditRow(
    title: String,
    detail: String,
    action: String,
    onSave: (() -> Unit)?,
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
        if (onSave != null) {
            TextButton(
                onClick = onSave,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("另存", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
