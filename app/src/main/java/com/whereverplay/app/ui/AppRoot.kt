package com.whereverplay.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateListOf
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
        /** 0.2.2：非空 = 这一场是「播放列表播放」，手动翻谱开关作用于整个列表而不是单曲 */
        val playlist: Playlist? = null,
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

    // 首页项目列表的视图状态（搜索词 / 收藏筛选 / 标签筛选 / 排序 / 升降序）。
    // 之前是 ProjectListScreen 的局部 remember —— 点进项目路由切走、组件销毁，
    // 回来就全变回默认值（排序跳回「最近修改」就是这来的）。提升到这里常驻不丢。
    var projectQuery by remember { mutableStateOf("") }
    var projectFilter by remember { mutableStateOf(ProjectFilter.All) }
    var projectTagFilter by remember { mutableStateOf<String?>(null) }
    var projectSort by remember { mutableStateOf(ProjectSort.Recent) }
    var projectSortAsc by remember { mutableStateOf(false) }

    // 两个 tab 的滚动位置与多选选中集合 —— 同一批「路由切走就丢」的问题，一起提升。
    // 注意 LazyGridState / LazyListState / SnapshotStateList 都是普通对象，
    // remember 一次即可，路由切换期间它们只是不被组合，值不会丢。
    val projectGridState = rememberLazyGridState()
    val playlistListState = rememberLazyListState()
    val projectSelection = remember { mutableStateListOf<String>() }
    val playlistSelection = remember { mutableStateListOf<String>() }

    fun reload() {
        projects = repo.all()
        playlists = playlistRepo.all()
    }

    /**
     * WP: 项目保存的统一出口 —— 内容字段有任何变化就把 [Project.updatedAt] 刷成现在，
     * 这是首页「最近修改」排序的唯一依据。点进去看 / 播放只动 lastOpenedAt，不算修改。
     *
     * 对比时排除 createdAt / lastOpenedAt / updatedAt 三个时间字段：以后 Project 加新字段
     * 会自动被纳入「算修改」的范围，不用再回来补这里。
     */
    fun stampUpdate(p: Project): Project {
        val prev = projects.firstOrNull { it.id == p.id } ?: return p
        val changed = p.copy(
            createdAt = prev.createdAt,
            lastOpenedAt = prev.lastOpenedAt,
            updatedAt = prev.updatedAt,
            // 0.2.2：手动翻谱是「播放偏好」，跟时间字段一样不计入内容修改，
            // 否则在播放页点一下开关，这个项目就窜到「最近修改」第一位了
            manualTurn = prev.manualTurn,
            // 2026-10-26（用户第 1 条）：收藏星标属于「整理」而不是「改内容」。
            // 点一下星，项目不该窜到首页「最近修改」第一位 —— 单点星与长按多选批量收藏
            // 都走这个出口，在这里排除一次即可全覆盖。
            isFavorite = prev.isFavorite,
        ) != prev
        return if (changed) p.copy(updatedAt = System.currentTimeMillis()) else p
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
        repo.save(stampUpdate(touched))
        reload()
        route = if (touched.configured) Route.Play(listOf(touched)) else Route.Mark(touched.id)
    }

    /**
     * WP: 进入「播放列表播放态」的唯一入口。
     *
     * 列表页点整行、编辑页底部「开始播放」都走这里。这段「过滤失效项 → 组队列」原来
     * 内联在 PlaylistEditScreen.onPlay 里，多了一个入口后不抽出来就会有两份逻辑各自演化。
     */
    fun startPlaylist(list: Playlist) {
        // 只把仍可用的项目放进播放队列，失效项直接跳过
        val available = list.resolve(projects.associateBy { it.id })
            .filterIsInstance<PlaylistEntry.Available>()
            .map { it.project }
        if (available.isEmpty()) {
            notify("这个播放列表里没有可用项目")
            return
        }
        // 开播即算「最近打开过」，首页按时间排序才有意义
        val opened = list.copy(lastOpenedAt = System.currentTimeMillis())
        playlistRepo.save(opened)
        playlists = playlistRepo.all()
        route = Route.Play(
            queue = available,
            startIndex = 0,
            autoAdvanceMs = if (list.advanceMode == Playlist.ADVANCE_AUTO) list.gapMs else null,
            // 0.2.2：把列表本身带过去 —— 手动翻谱开关要落到「这个列表」上，而不是列表里的某一首
            playlist = opened,
        )
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

    /**
     * WP: 首页多选 → 批量加入播放列表（清单 #21）。
     *
     * 只追加「列表里还没有」的项目，新的一律接在末尾（原有顺序不动）；
     * 全都在里面时**不写盘**，只给一句提示 —— 否则用户会以为操作失败了。
     */
    fun appendToPlaylist(playlist: Playlist, selected: List<Project>) {
        val existing = playlist.projectIds.toSet()
        val fresh = selected.map { it.id }.filter { it !in existing }
        if (fresh.isEmpty()) {
            notify("选中的 ${selected.size} 首都已经在「${playlist.name}」里了")
            return
        }
        playlistRepo.save(playlist.copy(projectIds = playlist.projectIds + fresh))
        playlists = playlistRepo.all()
        val skipped = selected.size - fresh.size
        notify(
            if (skipped == 0) "已加入「${playlist.name}」${fresh.size} 首"
            else "已加入「${playlist.name}」${fresh.size} 首，$skipped 首已在列表里"
        )
    }

    /** #21 的「现建一个列表」：建完**不进编辑页**（用户还在多选流程里），只弹提示 */
    fun createPlaylistWith(name: String, selected: List<Project>) {
        val now = System.currentTimeMillis()
        playlistRepo.save(
            Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                projectIds = selected.map { it.id },
                advanceMode = Playlist.ADVANCE_MANUAL,
                gapMs = 3000L,
                coverFile = null,
                createdAt = now,
                lastOpenedAt = now,
            )
        )
        playlists = playlistRepo.all()
        notify("已新建「$name」，加入 ${selected.size} 首")
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
                            projectQuery = projectQuery,
                            projectFilter = projectFilter,
                            projectTagFilter = projectTagFilter,
                            projectSort = projectSort,
                            projectSortAsc = projectSortAsc,
                            onProjectQueryChange = { projectQuery = it },
                            onProjectFilterChange = { projectFilter = it },
                            onProjectTagFilterChange = { projectTagFilter = it },
                            onProjectSortChange = { projectSort = it },
                            onProjectSortAscChange = { projectSortAsc = it },
                            projectGridState = projectGridState,
                            playlistListState = playlistListState,
                            projectSelection = projectSelection,
                            playlistSelection = playlistSelection,
                            settings = settings,
                            tab = tab,
                            onTabChange = { tab = it },
                            onOpenProject = { openProject(it) },
                            onToggleFavorite = { p ->
                                repo.save(stampUpdate(p.copy(isFavorite = !p.isFavorite)))
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
                                list.forEach { repo.save(stampUpdate(it.copy(isFavorite = true))) }
                                reload()
                            },
                            onBatchExportProjects = { list -> startExport(ExportRequest.Projects(list)) },
                            onBatchAppendToPlaylist = { playlist, list -> appendToPlaylist(playlist, list) },
                            onBatchCreatePlaylistFor = { name, list -> createPlaylistWith(name, list) },
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
                            onPlayPlaylist = { startPlaylist(it) },
                            onEditPlaylist = { route = Route.PlaylistEdit(it) },
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
                                    onUpdate = { updated ->
                                        repo.save(stampUpdate(updated))
                                        reload()
                                    },
                                    onAddTag = { name ->
                                        val ok = tagStore.add(name)
                                        if (ok) tags = tagStore.all()
                                        ok
                                    },
                                    onNotify = { notify(it) },
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
                                    playlistRefCount = playlists.count { p.id in it.projectIds },
                                    onBack = { route = null },
                                    onOpenMarks = { route = Route.Mark(it.id) },
                                    onUpdate = { updated ->
                                        repo.save(stampUpdate(updated))
                                        reload()
                                    },
                                    onExport = { startExport(ExportRequest.Projects(listOf(it))) },
                                    onDelete = { target ->
                                        deleteProject(target)
                                        route = null
                                    },
                                    onNotify = { notify(it) },
                                )
                            }
                        }

                        Route.Create -> CreateProjectScreen(
                            installer = installer,
                            tags = tags,
                            canAddTag = tagStore.canAdd(),
                            maxTags = TagStore.MAX,
                            onAddTag = { name ->
                                val ok = tagStore.add(name)
                                if (ok) tags = tagStore.all()
                                ok
                            },
                            onCancel = { route = null },
                            // 0.2.2：新建完**不再自动进打点页**（用户明确），回首页列表，用户自己决定下一步
                            onCreated = { p ->
                                repo.save(stampUpdate(p))
                                reload()
                                route = null
                                notify("已创建「${p.name}」")
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
                                        repo.save(stampUpdate(updated))
                                        reload()
                                        route = null
                                    },
                                    onFinished = { updated ->
                                        repo.save(stampUpdate(updated))
                                        reload()
                                        route = Route.Play(listOf(updated))
                                    },
                                )
                            }
                        }

                        is Route.Play -> {
                            // 0.2.2：手动翻谱的作用域 —— 播放列表播放时是整个列表，单曲播放时是这个项目。
                            // 作用域里没设置过（null）就由播放页回退到设置页的全局默认。
                            val currentId = r.queue.getOrNull(r.startIndex)?.id
                            val scopedProject = projects.firstOrNull { it.id == currentId }
                                ?: r.queue.getOrNull(r.startIndex)
                            PlaybackScreen(
                                queue = r.queue,
                                startIndex = r.startIndex,
                                autoAdvanceMs = r.autoAdvanceMs,
                                settings = settings,
                                manualTurnSetting = r.playlist?.manualTurn ?: scopedProject?.manualTurn,
                                manualScopeKey = r.playlist?.id ?: "project:${scopedProject?.id ?: ""}",
                                onManualTurnChange = { value ->
                                    val pl = r.playlist
                                    if (pl != null) {
                                        // 列表播放：改的是列表自己的偏好，列表里换歌不会重置
                                        val updated = pl.copy(manualTurn = value)
                                        playlistRepo.save(updated)
                                        playlists = playlistRepo.all()
                                        route = r.copy(playlist = updated)
                                    } else {
                                        // 单曲播放：改的是这个项目的偏好（不刷新 updatedAt）
                                        val p = scopedProject
                                        if (p != null) {
                                            repo.save(stampUpdate(p.copy(manualTurn = value)))
                                            reload()
                                        }
                                        route = r.copy(
                                            queue = r.queue.map {
                                                if (it.id == currentId) it.copy(manualTurn = value) else it
                                            },
                                        )
                                    }
                                },
                                onExit = {
                                    reload()
                                    route = null
                                },
                                onRematch = { route = Route.Mark(r.queue[r.startIndex].id) },
                            )
                        }

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
                            // WP: 编辑页改完顺序 / 衔接方式后直接试播，和列表页点行共用同一个入口
                            onPlay = { startPlaylist(it) },
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
