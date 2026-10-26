package com.whereverplay.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.SettingsStore
import com.whereverplay.app.domain.Mark
import com.whereverplay.app.domain.PageResolver
import com.whereverplay.app.domain.Project
import com.whereverplay.app.domain.TimeFormat
import com.whereverplay.app.pdf.PdfPageRenderer
import com.whereverplay.app.player.MediaPlayerAudioPlayer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * WP: 播放页 —— 电子书式全屏阅读。
 *
 * 翻页用「跟手」模型（不是定时长转场）：
 *  - 手指移动多少，页面就偏移多少；手指停住页面就停住
 *  - 松手时超过屏宽 12%（或快而短的一甩）就翻过去，否则弹回
 *  - 自动翻页（音乐跨过打点）由同一个偏移量驱动：新页从侧面滑入
 *
 * 三页并排绘制（上一页 / 当前页 / 下一页），全部取自同一个 renderer 的 LRU 缓存，
 * 所以翻页过程中不会出现「黑屏一下」——那一闪通常是某页 bitmap 还没就绪。
 *
 * 布局按 ui/work/按钮分布与交互动画-开发对照.md §1 重排：
 *  顶部栏 56 / 谱面区撑满 / 底部控制区两行（进度行 + 控制行）。
 *  上一首·下一首只在有队列（播放列表串烧）时出现，单曲时整组不渲染（不是置灰）。
 */
@Composable
fun PlaybackScreen(
    queue: List<Project>,
    startIndex: Int,
    /** 非空 = 播完自动接下一首（间隔毫秒）；空 = 播完暂停等用户点 */
    autoAdvanceMs: Long?,
    settings: SettingsStore,
    /**
     * 0.2.2 手动翻谱：当前**作用域**（播放列表 / 单个项目）里存的值。
     * null = 这个列表/项目还没单独设置过 → 回退到设置页的全局默认。
     */
    manualTurnSetting: Boolean?,
    /** 作用域标识：列表播放 = 列表 id，单曲播放 = `project:<id>`。换歌时不重置手动模式靠它 */
    manualScopeKey: String,
    /** 用户在播放页左下角拨了开关 → 交给 AppRoot 落到列表或项目上 */
    onManualTurnChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    onRematch: () -> Unit,
) {
    var index by remember { mutableStateOf(startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))) }
    val project = queue.getOrNullOr(index) ?: return
    /** 有队列才给上一首 / 下一首；单曲时这两个按钮整组不渲染 */
    val multi = queue.size > 1

    var pendingAutoPlay by remember { mutableStateOf(false) }
    // 曲间遮罩：非空表示「本首已播完，正在等下一首」
    var pendingNextIndex by remember { mutableStateOf<Int?>(null) }
    // WP: 正在切歌。切歌那一帧旧 player 会被 release，播放循环若恰好读到
    // isPlaying=false，会把「切歌」误判成「本首播完」，进而把进度写成上一首的总时长。
    // 这个标记只用于抑制那一帧的误判。
    var switching by remember { mutableStateOf(false) }
    // 遮罩背景：提前把下一首的第 1 页渲染出来
    var nextPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // >0 = 自动衔接的剩余毫秒；-1 = 手动衔接（等用户点）
    var countdownMs by remember { mutableStateOf(0L) }

    val context = LocalContext.current
    // 0.3.0：音频可空。没有音频时播放页 = 谱面 + 静音时间轴（不初始化 MediaPlayer）
    val audioFile = remember(project.id) {
        project.audioFile?.let { File(File(context.filesDir, "media"), it) }
    }
    val hasAudio = audioFile != null
    val pdfFile = remember(project.id) { File(File(context.filesDir, "media"), project.pdfFile) }

    val player = remember(project.id) { MediaPlayerAudioPlayer() }
    // WP: 真实时长只能在 load() 之后拿到。之前写在 remember(player) 里，
    // 那时 MediaPlayer 还没 prepare，永远读到 0 → 一直 fallback 到 project.durationMs。
    var playerDuration by remember(project.id) { mutableStateOf(0L) }
    val pdfRenderer = remember(project.id) { runCatching { PdfPageRenderer(pdfFile) }.getOrNull() }
    val pdfDispatcher = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "wp-pdf-play") }.asCoroutineDispatcher()
    }
    // WP: 三者生命周期不同，必须分开释放 —— 曾经绑在一个 DisposableEffect 里，
    // 切歌时把**共用的单线程渲染池**一起 close 了，导致新曲的渲染任务永远提交不进去，
    // 界面卡在「正在渲染第 1 页」。这也是为什么遮罩底图能渲染（那时还没切歌）而进去就空白。
    DisposableEffect(project.id) {
        audioFile?.let {
            player.load(it.absolutePath)
            playerDuration = player.durationMs
        }
        onDispose { player.release() }
    }
    DisposableEffect(pdfRenderer) {
        onDispose { pdfRenderer?.close() }
    }
    DisposableEffect(pdfDispatcher) {
        onDispose { pdfDispatcher.close() }
    }

    val scope = rememberCoroutineScope()

    var position by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var manualPage by remember { mutableStateOf<Int?>(null) }

    // ---------- #23 手动翻谱（0.2.2 重做）----------
    /**
     * 打开后完全忽略打点，页码只由手指（滑 / 双击）决定。
     *
     * 作用域：单曲播放 = 这个项目；播放列表播放 = **整个列表**（所以 key 用 [manualScopeKey]，
     * 列表里换歌不会把手动模式重置掉）。作用域没设置过就跟随设置页的全局默认。
     */
    var manualMode by remember(manualScopeKey) {
        mutableStateOf(manualTurnSetting ?: settings.manualTurn)
    }
    /**
     * 手动模式下唯一的页码来源。切进手动模式时对齐当前页 —— 否则一开开关就会跳页。
     * 换曲时重置（见 LaunchedEffect(index)）。
     */
    var manualHoldPage by remember(project.id) { mutableStateOf(1) }

    // ---------- #24 谱面缩放 ----------
    /** 1f = 原尺寸。> 1 时单指改为平移、翻页手势整组关闭（否则两套手势必然打架） */
    var zoom by remember(project.id) { mutableStateOf(1f) }
    var panX by remember(project.id) { mutableStateOf(0f) }
    var panY by remember(project.id) { mutableStateOf(0f) }

    // 翻页偏移（像素）：手指拖动量 / 动画中的量，二选一
    var dragOffset by remember { mutableStateOf(0f) }
    var dragStartedAt by remember { mutableStateOf(0L) }
    var dragPeak by remember { mutableStateOf(0f) }
    var animOffset by remember { mutableStateOf(0f) }
    var animating by remember { mutableStateOf(false) }

    // WP: 不再用单张 pageBitmap（它异步更新，翻页瞬间还是旧图 → 闪一下上一页）。
    // 改为「渲染完成后自增计数触发重绘，绘制时一律从 renderer 缓存取图」。
    var renderTick by remember { mutableStateOf(0) }

    // SharedPreferences 不是 Compose 状态，用本地镜像保证界面即时刷新
    var offsetMs by remember { mutableStateOf(settings.pageTurnOffsetMs) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var showStats by remember { mutableStateOf(settings.showDebugStats) }
    var offsetInputOpen by remember { mutableStateOf(false) }
    var offsetInput by remember { mutableStateOf("0") }
    var lastSeekAt by remember { mutableStateOf(0L) }

    val duration = playerDuration.takeIf { it > 0 } ?: project.durationMs
    val safeDuration = duration.coerceAtLeast(1L)

    /**
     * WP: 换曲的唯一入口。
     * 所有 index 变更都必须走这里 —— 否则容易漏掉 [switching] 标记，
     * 或者忘了把「当前是否在播放」传给下一首。
     */
    fun switchTo(target: Int, autoPlay: Boolean) {
        if (target !in queue.indices || target == index) return
        switching = true
        pendingNextIndex = null
        countdownMs = 0L
        pendingAutoPlay = autoPlay
        index = target
    }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = previous }
    }

    LaunchedEffect(index) {
        switching = false
        position = 0L
        manualPage = null
        // 换曲把手工状态一起清掉：新的一首从第 1 页、1:1 开始（#23 / #24）
        manualHoldPage = 1
        zoom = 1f
        panX = 0f
        panY = 0f
        if (pendingAutoPlay) {
            pendingAutoPlay = false
            player.seekTo(0L)
            player.play()
            isPlaying = true
        }
    }

    // WP: 这个循环必须跟着「当前 player」走。切歌时 index 变 → project 变 → player 重建，
    // 所以 key 里带上 project.id；同时用 rememberUpdatedState 读 player 与时长，
    // 避免 lambda 捕获已经 release 的旧实例。
    // （旧写法只在 key=isPlaying 上重启，切歌后 isPlaying 仍是 true → 循环继续用旧 player，
    //   旧 player 已 release → positionMs=0、isPlaying=false → 被误判为「播完」，
    //   于是 position 被写成上一首的总时长，界面就停在下一首的 3:29 处。）
    val playerNow = rememberUpdatedState(player)
    val durationNow = rememberUpdatedState(safeDuration)
    LaunchedEffect(isPlaying, project.id) {
        // 一首播完的统一出口：有音频靠播放器状态、无音频靠时钟走满，两条路都走这里
        fun finishTrack() {
            isPlaying = false
            position = durationNow.value
            if (index < queue.lastIndex && pendingNextIndex == null) {
                if (autoAdvanceMs != null && autoAdvanceMs <= 0L) {
                    // 间隔 0 秒：不停留，直接进下一首
                    countdownMs = 0L
                    switchTo(index + 1, autoPlay = true)
                } else {
                    countdownMs = autoAdvanceMs ?: -1L
                    pendingNextIndex = index + 1
                }
            }
        }
        var lastTick = android.os.SystemClock.elapsedRealtime()
        while (isPlaying) {
            if (hasAudio) {
                val p = playerNow.value
                if (!scrubbing) position = p.positionMs.coerceAtMost(durationNow.value)
                if (!p.isPlaying && !scrubbing && !switching) finishTrack()
            } else {
                // 0.3.0 无音频：没有播放器可读，按真实流逝时间推进 —— 「静音时间轴」。
                // 打点照旧生效，所以这仍然是一个能自动翻谱的播放态。
                val now = android.os.SystemClock.elapsedRealtime()
                if (!scrubbing) {
                    position = (position + (now - lastTick)).coerceAtMost(durationNow.value)
                }
                lastTick = now
                if (position >= durationNow.value && !scrubbing) finishTrack()
            }
            delay(16)
        }
    }

    val effective = (position + offsetMs).coerceAtLeast(0L)

    // 拖进度条时冻结自动翻页：否则「拖一下就翻走」，页面在手指下面乱闪
    var frozenPage by remember { mutableStateOf<Int?>(null) }
    val computedPage = PageResolver.pageAt(project.marks, effective)
    // #23 手动翻谱：整条打点链路短路 —— 既不跟播放位置，也不管拖动进度条时的冻结页
    val autoPage = if (manualMode) manualHoldPage else (frozenPage ?: computedPage)

    LaunchedEffect(autoPage) { manualPage = null }
    val displayPage = (manualPage ?: autoPage).coerceIn(1, project.pageCount.coerceAtLeast(1))

    /**
     * 拨「手动翻谱」开关（0.2.2：开关在控制行左下角）。
     * 切进来时把页码锚在屏上那一页 —— 否则一开开关就会跳回打点算出来的页。
     * 结果立刻落到作用域（项目或播放列表）里，下次进这个项目/列表还保持。
     */
    fun toggleManualTurn() {
        val next = !manualMode
        manualMode = next
        manualPage = null
        if (next) manualHoldPage = displayPage
        onManualTurnChange(next)
    }

    /**
     * 手动翻到某一页（#23 / #24 共用）。
     * 同时写 [manualPage]（当前显示）与 [manualHoldPage]（手动模式下的锚点）：
     * 两者不一致的话，双击/换曲清掉 manualPage 会立刻跳回旧页。
     */
    fun goToPage(target: Int) {
        val p = target.coerceIn(1, project.pageCount.coerceAtLeast(1))
        manualPage = p
        manualHoldPage = p
    }

    /** #24 双击回正（带一点动画，直接跳回去会显得很生硬） */    fun resetZoom() {
        if (zoom <= 1.001f && panX == 0f && panY == 0f) return
        val z0 = zoom
        val x0 = panX
        val y0 = panY
        scope.launch {
            try {
                animate(0f, 1f, animationSpec = tween(WpMotion.TURN_BACK_MS, easing = WpMotion.TurnEasing)) { t, _ ->
                    zoom = z0 + (1f - z0) * t
                    panX = x0 * (1f - t)
                    panY = y0 * (1f - t)
                }
            } finally {
                zoom = 1f
                panX = 0f
                panY = 0f
            }
        }
    }

    val density = LocalDensity.current
    // #22：谱面按「整屏可用区域」反推渲染宽度 —— 控制栏隐藏时谱面本来就铺满整屏，
    // 按整屏算才不会出现「放大到全屏后糊一层」。字节预算在 rememberPageRenderWidth 里封顶。
    val screenConfig = LocalConfiguration.current
    val targetWidthPx = rememberPageRenderWidth(
        aspect = pdfRenderer?.pageAspect ?: 0.707f,
        availWidthPx = with(density) { screenConfig.screenWidthDp.dp.roundToPx() },
        availHeightPx = with(density) { screenConfig.screenHeightDp.dp.roundToPx() },
    )
    val barRisePx = with(density) { WpMotion.BAR_RISE_DP.dp.roundToPx() }

    // 当前页渲染 + 邻居页预渲染
    LaunchedEffect(project.id, displayPage, targetWidthPx) {
        val r = pdfRenderer ?: return@LaunchedEffect
        if (targetWidthPx <= 0) return@LaunchedEffect
        try {
            val hit = r.cached(displayPage - 1)
            if (hit == null) withContext(pdfDispatcher) { r.render(displayPage - 1, targetWidthPx) }
            DebugStats.cacheHit = hit != null
            DebugStats.lastRenderPage = displayPage
            // 渲染失败要能被看见：以前这里从不写 renderError，界面永远停在
            // 「正在渲染第 N 页…」，看起来像卡死，实际是渲染抛了异常
            DebugStats.renderError =
                if (r.cached(displayPage - 1) == null) (r.lastError ?: "渲染返回空位图") else null

            withContext(pdfDispatcher) {
                val neighbours = ArrayList<Int>(2)
                if (displayPage < r.pageCount) neighbours.add(displayPage)
                if (displayPage - 2 >= 0) neighbours.add(displayPage - 2)
                r.prefetch(neighbours, targetWidthPx)
            }
            // 预渲染可能把**当前页**挤出缓存（三页并排时缓存正好在临界）。
            // 挤出后没有任何东西会再触发它渲染，界面就永远停在占位文字上 —— 这里补回来。
            if (r.cached(displayPage - 1) == null) {
                withContext(pdfDispatcher) { r.render(displayPage - 1, targetWidthPx) }
            }
        } finally {
            // 这个 effect 被取消（快速连翻 / 切歌）时也要重绘一次：
            // 位图可能已经进了缓存，但界面不会自己刷新
            renderTick++
        }
    }

    // 自动翻页动画：由打点触发时，新页从侧面滑入
    var lastPage by remember { mutableStateOf(displayPage) }
    var skipAutoAnim by remember { mutableStateOf(false) }
    LaunchedEffect(displayPage) {
        if (skipAutoAnim) {
            skipAutoAnim = false
            lastPage = displayPage
            return@LaunchedEffect
        }
        if (displayPage != lastPage && settings.pageTurnAnimation != 0) {
            val forward = displayPage > lastPage
            animating = true
            val start = if (forward) 1f else -1f
            try {
                animate(
                    start,
                    0f,
                    animationSpec = tween(WpMotion.TURN_FORWARD_MS, easing = WpMotion.TurnEasing),
                ) { v, _ -> animOffset = v }
            } finally {
                // 被下一次翻页打断时 animate() 会抛 CancellationException，
                // 不在这里清就会留下「animating=true + 半个屏的偏移」，
                // 表现是谱面整体偏到一边、只露出另一页的一条边（手机上一度以为是渲染坏了）
                animating = false
                animOffset = 0f
            }
        } else {
            // 关闭翻页动画 / 页码没变：把可能残留的偏移清干净
            animating = false
            animOffset = 0f
        }
        lastPage = displayPage
    }

    LaunchedEffect(displayPage) {
        val mark = project.markForPage(displayPage)
        if (mark != null) {
            val diff = effective - mark.timeMs
            DebugStats.lastDiffMs = diff
            if (manualPage == null) DebugStats.switchCount += 1
            Log.d("WP-SYNC", "page=$displayPage pos=$position mark=${mark.timeMs} offset=$offsetMs diff=$diff")
        }
    }

    fun advanceToNext() {
        val next = pendingNextIndex ?: return
        if (next > queue.lastIndex) return
        switchTo(next, autoPlay = true)
    }

    fun goToSong(target: Int) {
        // WP: 播放中点上一首/下一首 = 立刻从头播新的一首；
        // 暂停状态下保持暂停（停在新曲的第 1 页 0:00），不擅自开始播放。
        switchTo(target, autoPlay = isPlaying)
    }

    /**
     * 把「跟手位移 / 动画位移」全部复位。
     *
     * WP: 手势被系统或重组打断时不会走 onDragEnd，拖到一半的 dragOffset 会**永久留着**，
     * 表现是整个谱面偏到一边、只露出相邻页的一条边，看起来像渲染错位。
     * 所有退出路径（正常松手 / 取消 / 动画被打断）都必须经过这里。
     */
    fun clearPageOffset() {
        dragOffset = 0f
        dragPeak = 0f
        animOffset = 0f
        animating = false
    }

    // 遮罩出现时，后台渲染下一首的第 1 页作为底色
    val nextProjectForCover = pendingNextIndex?.let { queue.getOrNullOr(it) }
    LaunchedEffect(nextProjectForCover?.id, targetWidthPx) {
        nextPageBitmap = null
        val p = nextProjectForCover ?: return@LaunchedEffect
        if (targetWidthPx <= 0) return@LaunchedEffect
        val file = File(File(context.filesDir, "media"), p.pdfFile)
        val renderer = runCatching { PdfPageRenderer(file) }.getOrNull() ?: return@LaunchedEffect
        try {
            nextPageBitmap = renderer.cached(0)
                ?: withContext(pdfDispatcher) { renderer.render(0, targetWidthPx) }
        } finally {
            runCatching { renderer.close() }
        }
    }

    // 自动衔接：每 200ms 递减，归零即切歌；手动衔接（-1）不做任何事，等用户点
    LaunchedEffect(pendingNextIndex) {
        if (pendingNextIndex == null || countdownMs <= 0) return@LaunchedEffect
        while (countdownMs > 0) {
            delay(200)
            countdownMs -= 200
        }
        advanceToNext()
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        player.pause()
        onExit()
    }

    fun togglePlay() {
        if (isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            if (position >= safeDuration - 50) {
                position = 0L
                if (hasAudio) player.seekTo(0L)
            } else if (hasAudio) {
                player.seekTo(position)
            }
            // 无音频时 player 是空的：play() 什么都不做，进度由静音时钟推
            if (hasAudio) player.play()
            isPlaying = true
        }
    }

    /** 提前量：先改本地镜像，再写 SharedPreferences（避免读盘导致界面迟一拍） */
    fun applyOffset(value: Long) {
        offsetMs = value.coerceIn(-500L, 500L)
        settings.pageTurnOffsetMs = offsetMs
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val pageOffset = if (animating) animOffset * widthPx else dragOffset

        // ---------- 谱面：三页并排，跟手位移 ----------
        // #22 谱面尽量占满全屏：
        //  - 控制栏显示时，谱面区 = 屏幕 − 顶栏 56 − 控制区 100（和以前一样，谱面不被压住，
        //    排练记号/页码不会被挡 —— 这是当初特意留白的原因）；
        //  - 点一下隐藏控制栏后，谱面区**动画长到整屏**，得到真正的全屏谱面。
        // 留白现在是个动画量，但它只跟「控制栏显隐」走，翻页期间纹丝不动，
        // 所以坑 #3 的「翻页时谱面上下跳」不会复现（那一版是把留白跟翻页绑在一起变）。
        val controlBarHeight = 100.dp
        val topInset by animateDpAsState(
            if (controlsVisible) 56.dp else 0.dp,
            animationSpec = tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing),
            label = "pageTopInset",
        )
        val bottomInset by animateDpAsState(
            if (controlsVisible) controlBarHeight else 0.dp,
            animationSpec = tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing),
            label = "pageBottomInset",
        )
        val zoomed = zoom > 1.001f
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = topInset, bottom = bottomInset)
                .clipToBounds()
                .pointerInput(widthPx, displayPage, zoomed) {
                    // #24 放大之后单指改成平移，翻页手势整组让位 —— 同一根手指只能有一种含义
                    if (zoomed) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            clearPageOffset()
                            dragStartedAt = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            // 位移到 12% 就翻；轻扫（快而短）也翻，避免"要滑很长才行"
                            val elapsed = (System.currentTimeMillis() - dragStartedAt).coerceAtLeast(1)
                            val speed = abs(dragPeak) / elapsed // px/ms
                            val threshold = widthPx * 0.12f
                            val flick = abs(dragPeak) > widthPx * 0.05f && speed > 0.55f
                            val direction = when {
                                (dragOffset < -threshold || (flick && dragOffset < 0)) &&
                                    displayPage < project.pageCount -> 1
                                (dragOffset > threshold || (flick && dragOffset > 0)) &&
                                    displayPage > 1 -> -1
                                else -> 0
                            }
                            scope.launch {
                                val startFrac = dragOffset / widthPx
                                val targetFrac = if (direction == 0) 0f else -direction.toFloat()
                                animating = true
                                try {
                                    animate(
                                        initialValue = startFrac,
                                        targetValue = targetFrac,
                                        animationSpec = tween(
                                            if (direction == 0) WpMotion.TURN_BACK_MS
                                            else WpMotion.TURN_FORWARD_MS,
                                            easing = WpMotion.TurnEasing,
                                        ),
                                    ) { v, _ -> animOffset = v }
                                    if (direction != 0) {
                                        // 翻页完成后 dragOffset 直接归零，不接第二段动画（否则会看到「翻两下」）
                                        skipAutoAnim = true
                                        // 手动翻谱模式下这个页码就是「锚点」，必须一起写（#23）
                                        goToPage(displayPage + direction)
                                    }
                                } finally {
                                    // 被打断（离场 / 又被拖了一下）也必须归位
                                    clearPageOffset()
                                }
                            }
                        },
                        onDragCancel = {
                            // WP: 手势被系统抢走（返回手势、多指、窗口失焦）时**不会**走 onDragEnd。
                            // 不处理的话 dragOffset 停在拖到一半的位置，谱面就整体偏在一边。
                            clearPageOffset()
                        },
                    ) { _, delta ->
                        dragOffset = (dragOffset + delta).coerceIn(-widthPx, widthPx)
                        if (abs(dragOffset) > abs(dragPeak)) dragPeak = dragOffset
                    }
                }
                // 放大状态下的单指平移（翻页手势此时已被上面那个 return 关掉）
                .pointerInput(zoomed) {
                    if (!zoomed) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        val (nx, ny) = clampPan(
                            x = panX + drag.x,
                            y = panY + drag.y,
                            zoom = zoom,
                            contentW = size.width.toFloat(),
                            contentH = size.height.toFloat(),
                        )
                        panX = nx
                        panY = ny
                    }
                }
                // #24 双指缩放 + 平移：真的落下第二根手指才接管，单指手势完全不受影响
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var twoFinger = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 && !twoFinger) {
                                twoFinger = true
                                // 第二根手指落下的瞬间，单指翻页可能已经拖出一点位移，清掉
                                clearPageOffset()
                            }
                            if (twoFinger && pressed >= 1) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val old = zoom
                                    val next = (old * zoomChange).coerceIn(1f, 4f)
                                    val k = next / old
                                    // 以捏合中心为锚点：手指下面那一段谱面缩放前后停在原地
                                    val nx = k * panX + (1f - k) * (centroid.x - size.width / 2f) + panChange.x
                                    val ny = k * panY + (1f - k) * (centroid.y - size.height / 2f) + panChange.y
                                    zoom = next
                                    val (cx, cy) = clampPan(
                                        x = nx,
                                        y = ny,
                                        zoom = next,
                                        contentW = size.width.toFloat(),
                                        contentH = size.height.toFloat(),
                                    )
                                    panX = cx
                                    panY = cy
                                }
                                event.changes.forEach { it.consume() }
                            }
                            if (pressed == 0) break
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        // 双击：放大状态回正，原尺寸时仍是「取消手动选中的页」
                        onDoubleTap = { if (zoom > 1.001f) resetZoom() else manualPage = null },
                    )
                }
        ) {
            val tick = renderTick // 读取以订阅重绘
            // #24 缩放/平移作用在「三页整体」这一层：
            // 翻页位移仍写在内容坐标系里（每页 ±widthPx），放大后翻页距离自然跟着放大，
            // 不会出现「放大到 3 倍翻页却只挪了三分之一屏」。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                    }
            ) {
                for (rel in -1..1) {
                    val page = displayPage + rel
                    if (page < 1 || page > project.pageCount) continue
                    val bmp = pdfRenderer?.cached(page - 1)
                    if (bmp == null && rel != 0) continue
                    PdfPageView(
                        bmp,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = pageOffset + rel * widthPx },
                        emptyHint = if (rel == 0 && bmp == null) {
                            DebugStats.renderError ?: "正在渲染第 $page 页…"
                        } else null,
                    )
                }
            }
        }

        // ---------- 顶部栏（56）----------
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)) +
                slideInVertically(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)) { -barRisePx },
            exit = fadeOut(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF0B0B0E))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WpIconButton(
                    onClick = {
                        player.pause()
                        onExit()
                    },
                    visual = 0.dp,
                ) { tint -> IconBack(tint, 18.dp) }

                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        project.name,
                        fontSize = 12.sp,
                        color = Color(0xFFD8D8DE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            if (multi) append("${index + 1} / ${queue.size} · ")
                            append("第 $displayPage 页 / ${project.pageCount} 页")
                            if (!hasAudio) append(" · 无音频")
                            if (manualMode) append(" · 手动翻谱")
                            else if (manualPage != null) append(" · 手动")
                            if (zoom > 1.001f) append(" · ${"%.1f".format(zoom)}×")
                        },
                        fontSize = 9.sp,
                        color = Color(0xFF8A8A94)
                    )
                }

                Box {
                    WpIconButton(onClick = { menuOpen = true }, visual = 0.dp) { tint ->
                        IconMoreVertical(tint, 18.dp)
                    }
                    // 低频动作全部收在这里。2026-10-25 起是 5 项：翻页提前量进了菜单首位
                    // （原来常驻底部控制行，360dp 上和播放键抢位置）。
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "翻页提前量　${if (offsetMs > 0) "+" else ""}${offsetMs}ms",
                                    fontSize = 12.sp,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                // 把当前值带进弹窗的输入框，省得用户自己对着数字敲
                                offsetInput = offsetMs.toString()
                                offsetInputOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (keepOn) "关闭屏幕常亮" else "开启屏幕常亮", fontSize = 12.sp) },
                            onClick = {
                                menuOpen = false
                                keepOn = !keepOn
                                settings.keepScreenOn = keepOn
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showStats) "隐藏仪表盘" else "显示仪表盘", fontSize = 12.sp) },
                            onClick = {
                                menuOpen = false
                                showStats = !showStats
                                settings.showDebugStats = showStats
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重新打点", fontSize = 12.sp) },
                            onClick = { menuOpen = false; player.pause(); onRematch() },
                        )
                        DropdownMenuItem(
                            text = { Text("返回项目列表", fontSize = 12.sp) },
                            onClick = { menuOpen = false; player.pause(); onExit() },
                        )
                    }
                }
            }
        }

        // ---------- 曲间遮罩 ----------
        pendingNextIndex?.let { nextIdx ->
            InterludeOverlay(
                next = queue.getOrNullOr(nextIdx),
                background = nextPageBitmap,
                countdownMs = countdownMs,
                onContinue = { advanceToNext() },
                onBack = { player.pause(); onExit() },
            )
        }

        // ---------- 底部控制区（两行）----------
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)) +
                slideInVertically(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)) { barRisePx },
            exit = fadeOut(tween(WpMotion.BAR_MS, easing = WpMotion.BarEasing)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0B0E))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 行 1：进度条独占一行 2:14 ——●—— 5:29
                // （提前量组已搬到右上角菜单，这一行两种模式都是 22dp）
                Row(
                    Modifier.fillMaxWidth().height(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        TimeFormat.mmss(position),
                        fontSize = 10.sp,
                        color = Color(0xFFB0B0BA),
                        modifier = Modifier.width(36.dp),
                    )
                    PlaybackProgress(
                        positionMs = position,
                        durationMs = safeDuration,
                        marks = project.marks,
                        onScrubStart = {
                            scrubbing = true
                            frozenPage = PageResolver.pageAt(
                                project.marks,
                                (position + offsetMs).coerceAtLeast(0L),
                            )
                        },
                        onScrub = { t ->
                            position = t
                            // 拖动会高频回调，每帧 seek 让播放器卡顿 → 节流到 80ms
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastSeekAt > 80) {
                                lastSeekAt = now
                                player.seekTo(t)
                            }
                        },
                        onScrubEnd = {
                            player.seekTo(position)
                            scrubbing = false
                            frozenPage = null
                        },
                        modifier = Modifier.weight(1f).height(22.dp).padding(horizontal = 2.dp),
                    )
                    Text(
                        TimeFormat.mmss(duration),
                        fontSize = 10.sp,
                        color = Color(0xFFB0B0BA),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(36.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // 行 2：上一首 · 播放 · 下一首（播放键严格居中）
                //
                // WP: 提前量组原来挂在这一行右端，多曲时还被挤到进度行 —— 360dp 上
                // 「两个 44 热区 + 56 播放键 + 提前量组 124」必然压到播放键。
                // 现在整组收进右上角「⋮」菜单，这一行只剩播放控制本身，
                // 单曲（只有播放键居中）和多曲（上/下一首对称分居两侧）用同一套布局。
                //
                // 0.2.2：手动翻谱开关放在**左下角**（用户指定）。中间那组是居中的，
                // 左边到 92dp 都是空的，塞一个 60dp 宽的胶囊不会和播放键打架。
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    ManualTurnChip(
                        manual = manualMode,
                        onClick = { toggleManualTurn() },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    if (multi) {
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WpIconButton(
                                onClick = { goToSong(index - 1) },
                                enabled = index > 0,
                                visual = 0.dp,
                            ) { tint -> IconSkipPrev(tint, 20.dp) }
                            Spacer(Modifier.width(4.dp))
                            PlayPauseButton(isPlaying) { togglePlay() }
                            Spacer(Modifier.width(4.dp))
                            WpIconButton(
                                onClick = { goToSong(index + 1) },
                                enabled = index < queue.lastIndex,
                                visual = 0.dp,
                            ) { tint -> IconSkipNext(tint, 20.dp) }
                        }
                    } else {
                        // 单曲：上/下一首整组不渲染，播放键居中
                        PlayPauseButton(
                            isPlaying,
                            modifier = Modifier.align(Alignment.Center),
                        ) { togglePlay() }
                    }
                }

                // 仪表盘（默认关闭，从「更多」打开）
                if (showStats) {
                    Text(
                        buildString {
                            append("渲染 ")
                            append(if (DebugStats.cacheHit) "缓存" else "${DebugStats.lastRenderMs}ms")
                            append(" · 偏差 ")
                            append(DebugStats.lastDiffMs?.let { if (it >= 0) "+${it}ms" else "${it}ms" } ?: "—")
                            append(" · 切换 ${DebugStats.switchCount} 次")
                        },
                        fontSize = 9.sp,
                        color = Color(0xFF5E5E68),
                        modifier = Modifier.padding(top = 2.dp).align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }

    if (offsetInputOpen) {
        AlertDialog(
            onDismissRequest = { offsetInputOpen = false },
            title = { Text("翻页提前量", fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        "正数 = 提前翻页，负数 = 延后。范围 −500 ~ 500ms。",
                        fontSize = 11.sp,
                        color = Color(0xFFA8A8B2),
                    )
                    Spacer(Modifier.height(14.dp))
                    // WP: `− 值 ＋` 从底部控制行搬进这个弹窗，交互一点没减：
                    // 单击 ±100、长按 100ms 连发（首延迟 300）、松手吸附 50ms 网格。
                    // 点中间的数值不会关弹窗，只是把当前值同步到下面的输入框。
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OffsetStepper(
                            offsetMs = offsetMs,
                            onStep = { applyOffset(offsetMs + it) },
                            onSettle = { applyOffset((offsetMs / 50.0).roundToLong() * 50L) },
                            onValueTap = { offsetInput = offsetMs.toString() },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("或直接输入毫秒数", fontSize = 11.sp, color = Color(0xFFA8A8B2))
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = offsetInput,
                        onValueChange = { offsetInput = it.filter { c -> c == '-' || c.isDigit() }.take(5) },
                        singleLine = true,
                        suffix = { Text("ms", fontSize = 11.sp) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    offsetInput.toLongOrNull()?.let { applyOffset(it) }
                    offsetInputOpen = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    applyOffset(0L)
                    offsetInputOpen = false
                }) { Text("归零") }
            },
        )
    }
}

private fun <T> List<T>.getOrNullOr(index: Int): T? =
    if (index in indices) this[index] else null

/**
 * 控制行左下角的「手动翻谱」开关（0.2.2）。
 *
 * 用胶囊而不是 Material Switch：这里空间只有 60dp 宽，Switch 光本体就 52dp，
 * 再配文字必然压到居中的播放键。胶囊的选中态沿用工程里的约定 —— 底色 + 描边 + 强调色文字，
 * 不打钩。热区 44dp（视觉 28dp），符合「视觉可小于 44、热区必须 ≥ 44」。
 */
@Composable
private fun ManualTurnChip(
    manual: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    WpTapArea(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        rippleShape = wpRippleShape(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .height(28.dp)
                .clip(shape)
                .background(if (manual) Color(0x339EC5FF) else Glass.fill)
                .border(0.5.dp, if (manual) accent else Glass.border, shape)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (manual) "手动翻谱" else "自动翻谱",
                fontSize = 10.sp,
                fontWeight = if (manual) FontWeight.Medium else FontWeight.Normal,
                color = if (manual) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * #24：把平移量夹在「放大后多出来的那部分」以内 ——
 * 2 倍放大时最多往左右各推半屏，再多就把谱面推出边界、露出一片黑。
 * 缩回 1 倍时上限自动变 0，所以捏回原尺寸不需要另外清平移。
 */
private fun clampPan(
    x: Float,
    y: Float,
    zoom: Float,
    contentW: Float,
    contentH: Float,
): Pair<Float, Float> {
    val maxX = (contentW * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (contentH * (zoom - 1f) / 2f).coerceAtLeast(0f)
    return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
}

/* ================= 进度条 ================= */

/**
 * WP: 进度条 —— 独占一行。
 *
 * 打点提示做成**竖线**而不是彩色圆点：
 *  - 圆点会被误读成「可点的按钮」（时间轴上那些圆点确实是可拖的），
 *    而这里只是告诉你「这一段会翻页」；
 *  - 竖线只占 1.5dp 宽，30 页打点也不会把轨道糊满；
 *  - 颜色取「轨道白 18%」和「已播过段近白」之间的 55%，看得出但不刺眼。
 *
 * 竖线不参与命中：整个 22dp 高的条只有「按位置 seek」这一种交互。
 */
@Composable
private fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    marks: List<Mark>,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safe = durationMs.coerceAtLeast(1L)
    val durationRef = rememberUpdatedState(safe)
    val startRef = rememberUpdatedState(onScrubStart)
    val scrubRef = rememberUpdatedState(onScrub)
    val endRef = rememberUpdatedState(onScrubEnd)

    Box(
        modifier.pointerInput(Unit) {
            // 点到哪跳到哪 + 按住拖动连续 seek，一次手势处理完，不需要 tap/drag 两套打架
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun timeAt(x: Float): Long {
                    val inset = 7.dp.toPx()
                    val w = (size.width - inset * 2f).coerceAtLeast(1f)
                    return (((x - inset) / w).coerceIn(0f, 1f) * durationRef.value).toLong()
                }
                startRef.value()
                scrubRef.value(timeAt(down.position.x))
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    scrubRef.value(timeAt(change.position.x))
                    change.consume()
                }
                endRef.value()
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 7.dp.toPx()
            val trackW = (size.width - inset * 2f).coerceAtLeast(1f)
            val cy = size.height / 2f
            val th = 3.dp.toPx()
            val frac = (positionMs.toFloat() / safe).coerceIn(0f, 1f)
            val r = CornerRadius(th / 2f, th / 2f)

            drawRoundRect(
                color = WpProgress.track,
                topLeft = Offset(inset, cy - th / 2f),
                size = Size(trackW, th),
                cornerRadius = r,
            )
            if (frac > 0f) {
                drawRoundRect(
                    color = WpProgress.played,
                    topLeft = Offset(inset, cy - th / 2f),
                    size = Size((trackW * frac).coerceAtLeast(th), th),
                    cornerRadius = r,
                )
            }

            // 打点竖线：纯提示，不参与任何命中判定
            val tickH = 9.dp.toPx()
            marks.forEach { m ->
                if (m.page <= 1) return@forEach   // 第 1 页固定 0:00，画在起点没有信息量
                val f = (m.timeMs.toFloat() / safe).coerceIn(0f, 1f)
                val x = inset + trackW * f
                drawLine(
                    color = WpProgress.tick,
                    start = Offset(x, cy - tickH / 2f),
                    end = Offset(x, cy + tickH / 2f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawCircle(
                color = WpProgress.thumb,
                radius = 5.dp.toPx(),
                center = Offset(inset + trackW * frac, cy),
            )
        }
    }
}

/** 进度条的一组颜色。对比度目标：竖线看得见，但不抢当前播放头 */
private object WpProgress {
    val track = Color(0x2EFFFFFF)   // 白 18%
    val played = Color(0xFFD9DCE3)  // 已播过段
    val tick = Color(0x8CFFFFFF)    // 白 55%，介于轨道与已播段之间
    val thumb = Color(0xFFF2F3F5)
}

/* ================= 播放键 ================= */

/** 整屏唯一的实心主按钮（56） */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    WpTapArea(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        // 涟漪裁成 56 圆，和实心播放键完全重叠（不裁的话四角会露出方框）
        rippleShape = wpRippleCircle(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFFEDEDED)),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) IconPause(Color(0xFF17171A), 20.dp) else IconPlay(Color(0xFF17171A), 20.dp)
        }
    }
}

/* ================= 提前量 ================= */

/**
 * WP: 提前量组 `− 值 ＋`。
 *
 * **2026-10-25 起只出现在「⋮ → 翻页提前量」的弹窗里**，不再常驻底部控制行
 * （360dp 上它和居中的播放键抢位置，多曲时还被挤到进度行，导致两种模式布局不一致）。
 *
 * 为什么不用 Slider：演出前调的是「几十毫秒」级别，滑块一动就是几百，
 * 反而找不到原位。−/＋ 单击 100ms、长按 100ms 连发（首延迟 300ms）、
 * 松手吸附 50ms 网格（设计稿 §1.3）。
 */
@Composable
private fun OffsetStepper(
    offsetMs: Long,
    onStep: (Long) -> Unit,
    onSettle: () -> Unit,
    onValueTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepperKey(
            plus = false,
            enabled = offsetMs > -500L,
            label = { tint -> IconMinus(tint, 14.dp) },
            onStep = { onStep(-100L) },
            onSettle = onSettle,
        )
        Box(
            Modifier
                .height(28.dp)
                .width(56.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Glass.fill)
                .border(0.5.dp, Glass.border, RoundedCornerShape(9.dp))
                .clickable { onValueTap() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (if (offsetMs > 0) "+" else "") + offsetMs,
                fontSize = 10.sp,
                color = Color(0xFFC9C9D2),
            )
        }
        StepperKey(
            plus = true,
            enabled = offsetMs < 500L,
            label = { tint -> IconPlus(tint, 14.dp) },
            onStep = { onStep(100L) },
            onSettle = onSettle,
        )
    }
}

@Composable
private fun StepperKey(
    plus: Boolean,
    enabled: Boolean,
    label: @Composable (Color) -> Unit,
    onStep: () -> Unit,
    onSettle: () -> Unit,
) {
    val stepRef = rememberUpdatedState(onStep)
    val settleRef = rememberUpdatedState(onSettle)
    val tint = if (enabled) Color(0xFFC9C9D2) else Color(0xFF5A5A64)

    // 长按连发用「状态 + LaunchedEffect」驱动，而不是在手势里自己开协程：
    // 松手时 holding 变 false，下面的 effect 自动取消，不会漏掉一个还在跑的循环。
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        delay(300)                 // 首延迟：避免想点一下就变成长按
        while (true) {
            stepRef.value()
            delay(100)
        }
    }

    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.pointerInput(plus) {
                        detectTapGestures(
                            onPress = {
                                stepRef.value()   // 按下立刻走一步（单击就是这个作用）
                                holding = true
                                tryAwaitRelease()
                                holding = false
                                settleRef.value() // 松手吸附 50ms 网格
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        label(tint)
    }
}

/* ================= 曲间遮罩 ================= */

/**
 * WP: 曲间遮罩 —— 一首播完后盖住整屏。
 * 手动衔接：中间提示「点击屏幕继续下一首」；
 * 自动衔接：多一行倒计时（每秒脉冲一次），归零自动走，点屏幕也可以提前走。
 */
@Composable
private fun InterludeOverlay(
    next: Project?,
    background: Bitmap?,
    countdownMs: Long,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    // 倒计时数字每秒 1.0 → 1.06 → 1.0（各 120ms），让「还在动」这件事看得见
    val pulse = rememberInfiniteTransition(label = "interludePulse")
    val countScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1.06f at 120
                1f at 240
            }
        ),
        label = "countScale",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0D))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onContinue() }
    ) {
        // 底图：下一首谱面的第一页，压一层暗色，既看得出是哪首、又不抢文字的注意力
        if (background != null) {
            PdfPageView(background, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Color(if (background != null) 0xC00A0A0D else 0xFF0A0A0D)))

        // 左上角返回
        Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
            WpIconButton(onClick = onBack, visual = 0.dp) { tint -> IconBack(tint, 18.dp) }
        }

        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (countdownMs > 0) "即将自动播放下一首" else "点击屏幕继续下一首",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFEDEDED)
            )
            Spacer(Modifier.height(10.dp))
            if (countdownMs > 0) {
                Text(
                    "${((countdownMs + 999) / 1000)} 秒后开始",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9EC5FF),
                    modifier = Modifier.scale(countScale),
                )
                Spacer(Modifier.height(14.dp))
            }
            if (next != null) {
                Text(
                    next.name,
                    fontSize = 17.sp,
                    color = Color(0xFFD8D8DE),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${next.author.ifBlank { "未知作者" }} · ${TimeFormat.mmss(next.durationMs)} · ${next.pageCount} 页",
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A94)
                )
            }
            Spacer(Modifier.height(28.dp))
            Text("点左上角返回可回到列表", fontSize = 11.sp, color = Color(0xFF6E6E78))
        }
    }
}
