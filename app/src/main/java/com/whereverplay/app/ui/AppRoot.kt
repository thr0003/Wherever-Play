package com.whereverplay.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.whereverplay.app.data.JsonPlaylistStore
import com.whereverplay.app.data.JsonProjectStore
import com.whereverplay.app.data.MediaInstaller
import com.whereverplay.app.data.SettingsStore
import com.whereverplay.app.data.TagStore
import com.whereverplay.app.data.WplayIO
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.PlaylistEntry
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** WP: 状态路由，页面数量有限且无深链接，不引 navigation-compose */
private sealed interface Route {
    data object Create : Route

    // WP: 详情/编辑/打点只带 id，页面里的 Project 每次从 projects 现查。
    // 带整份对象的话，改完名字后 route 里还留着旧副本，界面要手动回写才不会显示旧值。
    data class Detail(val id: String) : Route
    data class Edit(val id: String) : Route
    data class Mark(val id: String) : Route
    data class Play(
        val queue: List<Project>,
        val startIndex: Int = 0,
        val autoAdvanceMs: Long? = null,
    ) : Route

    data class PlaylistEdit(val playlist: Playlist) : Route
}

/** 一次导出请求：单个走「另存为」，多个走「选目录」 */
private sealed interface ExportRequest {
    val size: Int
    val suggestedName: String

    data class Projects(val list: List<Project>) : ExportRequest {
        override val size get() = list.size
        override val suggestedName get() =
            WplayIO.suggestedFileName(list.firstOrNull()?.name ?: "", playlist = false)
    }

    data class Playlists(val list: List<Playlist>) : ExportRequest {
        override val size get() = list.size
        override val suggestedName get() =
            WplayIO.suggestedFileName(list.firstOrNull()?.name ?: "", playlist = true)
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { JsonProjectStore(context.applicationContext) }
    val playlistRepo = remember { JsonPlaylistStore(context.applicationContext) }
    val installer = remember { MediaInstaller(context.applicationContext) }
    val settings = remember { SettingsStore(context.applicationContext) }
    val tagStore = remember { TagStore(context.applicationContext) }

    var projects by remember { mutableStateOf(repo.all()) }
    var playlists by remember { mutableStateOf(playlistRepo.all()) }
    var tags by remember { mutableStateOf(tagStore.all()) }
    var tab by remember { mutableStateOf(0) }
    var route by remember { mutableStateOf<Route?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }

    fun reload() {
        projects = repo.all()
        playlists = playlistRepo.all()
    }

    fun notify(msg: String) {
        toast = msg
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    fun openProject(p: Project) {
        val touched = p.copy(lastOpenedAt = System.currentTimeMillis())
        repo.save(touched)
        reload()
        route = if (touched.configured) Route.Play(listOf(touched)) else Route.Mark(touched.id)
    }

    /** 把内容写进目标 Uri（导出流程最后一步，跑在 IO 线程） */
    fun writeExport(request: ExportRequest, uri: Uri) {
        when (request) {
            is ExportRequest.Projects -> WplayIO.exportProjectTo(context, request.list.first(), uri)
            is ExportRequest.Playlists -> WplayIO.exportPlaylistTo(context, request.list.first(), projects, uri)
        }
    }

    // ---------- 导出：单个文件（另存为） ----------
    val fileExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(WplayIO.MIME)
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeExport(request, uri) } }
            result.onSuccess { notify("已导出 ${request.suggestedName}") }
                .onFailure { notify("导出失败：${it.message ?: "未知原因"}") }
        }
    }

    // ---------- 导出：多选 → 选目录，逐个写入 ----------
    val treeExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        val request = pendingExport
        pendingExport = null
        if (tree == null || request == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { writeExportToTree(context, tree, request, projects) }.getOrElse { 0 }
            }
            if (count > 0) notify("已导出 $count 个文件") else notify("导出失败（没有写入任何文件）")
        }
    }

    // ---------- 导入：.wplay / .wplist 共用入口，按 manifest 里的 format 分派 ----------
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WplayIO.importFrom(context, uri, repo, playlistRepo) }
            }
            reload()
            result.onSuccess { r ->
                // 只导入播放列表时自动切到第二个 tab，省得用户以为没成功
                if (r.playlists > 0 && r.projects == 0) tab = 1
                notify(
                    when {
                        r.projects == 0 && r.playlists == 0 -> "文件里没有可导入的内容"
                        r.playlists == 0 -> "已导入 ${r.projects} 个项目"
                        r.projects == 0 -> "已导入 1 个播放列表"
                        else -> "已导入 ${r.projects} 个项目、1 个播放列表"
                    }
                )
            }.onFailure { notify("导入失败：${it.message ?: "未知原因"}") }
        }
    }

    fun startExport(request: ExportRequest) {
        if (request.size <= 0) return
        pendingExport = request
        if (request.size > 1) treeExportPicker.launch(null)
        else fileExportPicker.launch(request.suggestedName)
    }

    fun deleteProject(p: Project) {
        deleteProjectFiles(context.filesDir, p)
        repo.delete(p.id)
        reload()
    }

    // 兜底返回：子页面自己注册的 BackHandler 优先（后注册者优先）
    androidx.activity.compose.BackHandler(enabled = route != null) { route = null }

    MaterialTheme(colorScheme = WpColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (val r = route) {
                        null -> HomeScreen(
                            projects = projects,
                            playlists = playlists,
                            tags = tags,
                            settings = settings,
                            tab = tab,
                            onTabChange = { tab = it },
                            onOpenProject = { openProject(it) },
                            onToggleFavorite = { p ->
                                repo.save(p.copy(isFavorite = !p.isFavorite))
                                reload()
                            },
                            onCreateProject = { route = Route.Create },
                            onImport = { importPicker.launch(arrayOf("*/*")) },
                            onClearAll = {
                                projects.forEach { repo.delete(it.id) }
                                playlists.forEach { playlistRepo.delete(it.id) }
                                File(context.filesDir, "media").deleteRecursively()
                                reload()
                            },
                            onBatchDeleteProjects = { list ->
                                list.forEach { deleteProject(it) }
                                reload()
                            },
                            onBatchFavoriteProjects = { list ->
                                list.forEach { repo.save(it.copy(isFavorite = true)) }
                                reload()
                            },
                            onBatchExportProjects = { list -> startExport(ExportRequest.Projects(list)) },
                            onShowDetail = { route = Route.Detail(it.id) },
                            onEditProject = { route = Route.Edit(it.id) },
                            onExportProject = { startExport(ExportRequest.Projects(listOf(it))) },
                            onDeleteProject = { p ->
                                deleteProject(p)
                                notify("已删除「${p.name}」")
                            },
                            onCreatePlaylist = { name ->
                                val now = System.currentTimeMillis()
                                val playlist = Playlist(
                                    id = UUID.randomUUID().toString(),
                                    name = name,
                                    projectIds = emptyList(),
                                    advanceMode = Playlist.ADVANCE_MANUAL,
                                    gapMs = 3000L,
                                    coverFile = null,
                                    createdAt = now,
                                    lastOpenedAt = now,
                                )
                                playlistRepo.save(playlist)
                                reload()
                                route = Route.PlaylistEdit(playlist)
                            },
                            onOpenPlaylist = { route = Route.PlaylistEdit(it) },
                            onImportPlaylist = { importPicker.launch(arrayOf("*/*")) },
                            onBatchDeletePlaylists = { list ->
                                list.forEach { playlistRepo.delete(it.id) }
                                reload()
                            },
                            onBatchExportPlaylists = { list -> startExport(ExportRequest.Playlists(list)) },
                        )

                        is Route.Detail -> {
                            val p = projects.firstOrNull { it.id == r.id }
                            if (p == null) {
                                MissingProject { route = null }
                            } else {
                                ProjectDetailScreen(
                                    project = p,
                                    tags = tags,
                                    canAddTag = tagStore.canAdd(),
                                    maxTags = TagStore.MAX,
                                    playlistRefCount = playlists.count { p.id in it.projectIds },
                                    installer = installer,
                                    onBack = { route = null },
                                    onOpenPlay = { route = Route.Play(listOf(it)) },
                                    onOpenEdit = { route = Route.Edit(it.id) },
                                    onExport = { startExport(ExportRequest.Projects(listOf(it))) },
                                    onUpdate = { updated ->
                                        repo.save(updated)
                                        reload()
                                    },
                                    onAddTag = { name ->
                                        val ok = tagStore.add(name)
                                        if (ok) tags = tagStore.all()
                                        ok
                                    },
                                    onDelete = { target ->
                                        deleteProject(target)
                                        route = null
                                    },
                                )
                            }
                        }

                        is Route.Edit -> {
                            val p = projects.firstOrNull { it.id == r.id }
                            if (p == null) {
                                MissingProject { route = null }
                            } else {
                                ProjectEditScreen(
                                    project = p,
                                    installer = installer,
                                    onBack = { route = null },
                                    onOpenMarks = { route = Route.Mark(it.id) },
                                    onUpdate = { updated ->
                                        repo.save(updated)
                                        reload()
                                    },
                                )
                            }
                        }

                        Route.Create -> CreateProjectScreen(
                            installer = installer,
                            onCancel = { route = null },
                            onCreated = { p ->
                                repo.save(p)
                                reload()
                                route = Route.Mark(p.id)
                            },
                        )

                        is Route.Mark -> {
                            val p = projects.firstOrNull { it.id == r.id }
                            if (p == null) {
                                MissingProject { route = null }
                            } else {
                                MarkingScreen(
                                    project = p,
                                    onExit = { updated ->
                                        repo.save(updated)
                                        reload()
                                        route = null
                                    },
                                    onFinished = { updated ->
                                        repo.save(updated)
                                        reload()
                                        route = Route.Play(listOf(updated))
                                    },
                                )
                            }
                        }

                        is Route.Play -> PlaybackScreen(
                            queue = r.queue,
                            startIndex = r.startIndex,
                            autoAdvanceMs = r.autoAdvanceMs,
                            settings = settings,
                            onExit = {
                                reload()
                                route = null
                            },
                            onRematch = { route = Route.Mark(r.queue[r.startIndex].id) },
                        )

                        is Route.PlaylistEdit -> PlaylistEditScreen(
                            playlist = r.playlist,
                            projects = projects,
                            onBack = {
                                reload()
                                route = null
                            },
                            onSave = { updated ->
                                playlistRepo.save(updated.copy(lastOpenedAt = System.currentTimeMillis()))
                                playlists = playlistRepo.all()
                                route = Route.PlaylistEdit(updated)
                            },
                            onDelete = { p ->
                                playlistRepo.delete(p.id)
                                reload()
                                route = null
                            },
                            onPlay = { p ->
                                // 只把仍可用的项目放进播放队列，失效项直接跳过
                                val available = p.resolve(projects.associateBy { it.id })
                                    .filterIsInstance<PlaylistEntry.Available>()
                                    .map { it.project }
                                if (available.isEmpty()) {
                                    notify("这个播放列表里没有可用项目")
                                } else {
                                    route = Route.Play(
                                        queue = available,
                                        startIndex = 0,
                                        autoAdvanceMs = if (p.advanceMode == Playlist.ADVANCE_AUTO) p.gapMs else null,
                                    )
                                }
                            },
                        )
                    }
                }

                // ---------- 一次性提示条（导入 / 导出的结果） ----------
                toast?.let { msg ->
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xE617171E))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            msg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE8E8EC),
                        )
                    }
                }
            }
        }
    }
}

/** 打开的项目已被删掉：自动退回首页，别让用户卡在空白页 */
@Composable
private fun MissingProject(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("项目已不存在", fontSize = 14.sp, color = Color(0xFF8A8A94))
    }
}

/**
 * 多选导出：用户选一个目录，逐个 createDocument 写入。
 * 没用 ACTION_SEND_MULTIPLE 是因为那要额外配 FileProvider（多一份 manifest + res/xml），
 * 而「导出到某个文件夹」本来就是用户更想要的结果。
 */
private fun writeExportToTree(
    context: Context,
    tree: Uri,
    request: ExportRequest,
    projects: List<Project>,
): Int {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return 0
    val dir = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    val resolver = context.contentResolver
    var written = 0

    fun createChild(name: String): Uri? = runCatching {
        DocumentsContract.createDocument(resolver, dir, WplayIO.MIME, name)
    }.getOrNull()

    when (request) {
        is ExportRequest.Projects -> request.list.forEach { p ->
            val child = createChild(WplayIO.suggestedFileName(p.name, playlist = false)) ?: return@forEach
            runCatching { WplayIO.exportProjectTo(context, p, child) }
                .onSuccess { written += 1 }
        }

        is ExportRequest.Playlists -> request.list.forEach { pl ->
            val child = createChild(WplayIO.suggestedFileName(pl.name, playlist = true)) ?: return@forEach
            runCatching { WplayIO.exportPlaylistTo(context, pl, projects, child) }
                .onSuccess { written += 1 }
        }
    }
    return written
}

/** 删除项目时一并清掉它引用的媒体文件，避免私有目录越堆越大 */
private fun deleteProjectFiles(filesDir: File, project: Project) {
    val media = File(filesDir, "media")
    listOfNotNull(project.audioFile, project.pdfFile, project.coverFile).forEach { name ->
        runCatching { File(media, name).delete() }
    }
}
