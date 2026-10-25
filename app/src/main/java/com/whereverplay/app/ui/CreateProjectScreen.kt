package com.whereverplay.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.MediaInstaller
import com.whereverplay.app.domain.Project
import com.whereverplay.app.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val MAX_AUDIO_MS = 30L * 60L * 1000L

@Composable
fun CreateProjectScreen(
    installer: MediaInstaller,
    tags: List<String>,
    canAddTag: Boolean,
    maxTags: Int,
    onAddTag: (String) -> Boolean,
    onCancel: () -> Unit,
    onCreated: (Project) -> Unit,
) {
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var audioLabel by remember { mutableStateOf("未选择") }
    var pdfLabel by remember { mutableStateOf("未选择") }
    var coverLabel by remember { mutableStateOf("自动提取音频内嵌封面") }
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    // WP: 新建时就能选乐器标签 —— 之前只有详情页能选，新建出来的项目一律是「未设置」，
    // 首页卡片左下角空着，筛选也筛不到，用户以为是标签功能坏了。
    var tag by remember { mutableStateOf<String?>(null) }
    var pickingTag by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioLabel = installer.displayName(uri) ?: "已选择"
            if (name.isBlank()) name = audioLabel.substringBeforeLast('.')
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pdfUri = uri
            pdfLabel = installer.displayName(uri) ?: "已选择"
        }
    }
    // Photo Picker：不需要任何存储权限
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coverUri = uri
            coverLabel = installer.displayName(uri) ?: "已选择"
        }
    }

    // 某些模拟器/精简系统没有系统文件选择器，直接 launch 会抛异常，这里兜住
    fun launchSaf(block: () -> Unit) {
        runCatching(block).onFailure {
            error = "这台设备没有可用的系统文件选择器。请换真机测试，或在设置中启用「文件」应用。"
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { onCancel() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // WP: 全应用统一用 IconBack 的细 chevron，原来是文字按钮「取消」
            WpIconButton(onClick = onCancel, visual = 0.dp) { tint -> IconBack(tint, 18.dp) }
            Spacer(Modifier.weight(1f))
            Text("新建项目", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            // 右侧留一个和左边箭头等宽的占位（44 热区），标题才真正居中
            Spacer(Modifier.width(44.dp))
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            PickerRow(
                title = "伴奏音频",
                hint = "mp3 / wav / flac，时长不超过 30 分钟",
                value = audioLabel,
                onPick = { launchSaf { pickAudio.launch(arrayOf("audio/*")) } },
            )

            Spacer(Modifier.height(16.dp))

            PickerRow(
                title = "PDF 谱面",
                hint = "请选择包含全部页面的 PDF",
                value = pdfLabel,
                onPick = { launchSaf { pickPdf.launch(arrayOf("application/pdf")) } },
            )

            Spacer(Modifier.height(16.dp))

            PickerRow(
                title = "封面（可选）",
                hint = "默认读取音频自带封面，也可以从相册选一张",
                value = coverLabel,
                onPick = {
                    launchSaf {
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("项目名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("作者（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            // 乐器标签：和详情页同一套选择器，一个项目只能选一个
            OutlinedButton(
                onClick = { pickingTag = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = "乐器标签：" + (tag ?: "未设置（可选）"),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (tag != null) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "音频、PDF 与封面会被复制进应用私有目录，原文件移动或删除都不影响使用。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val a = audioUri
                    val p = pdfUri
                    if (a == null || p == null) {
                        error = "请先选择伴奏音频和 PDF 谱面"
                        return@Button
                    }
                    if (busy) return@Button
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val audioName = installer.copyIn(a, "audio", "mp3")
                                val pdfName = installer.copyIn(p, "pdf", "pdf")
                                val audioFile = installer.fileOf(audioName)
                                val pdfFile = installer.fileOf(pdfName)
                                val duration = MediaInstaller.readDurationMs(audioFile)
                                val pageCount = PdfPageRenderer.pageCountOf(pdfFile)
                                // 封面：用户选了就用用户的，否则尝试音频内嵌封面
                                // 封面统一裁成正方形（512px），列表里永远不会被拉成长条
                                val chosenCover = coverUri
                                val coverName = if (chosenCover != null) {
                                    installer.copyInSquareCover(chosenCover)
                                } else {
                                    MediaInstaller.readEmbeddedCover(audioFile)
                                        ?.let { bytes -> installer.saveSquareCoverFromBytes(bytes) }
                                }
                                Triple(audioName, pdfName, CoverAndMeta(coverName, duration, pageCount))
                            }
                        }
                        busy = false
                        result.onSuccess { (audioName, pdfName, meta) ->
                            fun discardFiles() {
                                runCatching { installer.fileOf(audioName).delete() }
                                runCatching { installer.fileOf(pdfName).delete() }
                                meta.cover?.let { runCatching { installer.fileOf(it).delete() } }
                            }
                            when {
                                meta.duration <= 0L -> {
                                    discardFiles()
                                    error = "无法读取音频时长，请换一个文件试试"
                                }
                                meta.duration > MAX_AUDIO_MS -> {
                                    discardFiles()
                                    error = "音频时长超过 30 分钟上限（当前 ${meta.duration / 60000} 分钟）"
                                }
                                meta.pageCount <= 0 -> {
                                    discardFiles()
                                    error = "无法解析这个 PDF，可能是加密文件"
                                }
                                else -> {
                                    val now = System.currentTimeMillis()
                                    onCreated(
                                        Project(
                                            id = UUID.randomUUID().toString(),
                                            name = name.ifBlank { "未命名项目" },
                                            author = author.trim(),
                                            audioFile = audioName,
                                            pdfFile = pdfName,
                                            coverFile = meta.cover,
                                            pageCount = meta.pageCount,
                                            durationMs = meta.duration,
                                            // 第 1 页固定在 0:00，数据模型的硬约束
                                            marks = listOf(com.whereverplay.app.domain.Mark(page = 1, timeMs = 0L)),
                                            isFavorite = false,
                                            tag = tag,
                                            createdAt = now,
                                            lastOpenedAt = now,
                                        )
                                    )
                                }
                            }
                        }.onFailure {
                            error = "复制文件失败：${it.message}"
                        }
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "处理中…" else "确定", fontSize = 14.sp)
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (pickingTag) {
        TagPickerDialog(
            current = tag,
            tags = tags,
            canAdd = canAddTag,
            maxTags = maxTags,
            onPick = { picked ->
                tag = picked
                pickingTag = false
            },
            onAdd = onAddTag,
            onDismiss = { pickingTag = false },
        )
    }
}

private data class CoverAndMeta(val cover: String?, val duration: Long, val pageCount: Int)

@Composable
private fun PickerRow(title: String, hint: String, value: String, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = onPick,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(value, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
