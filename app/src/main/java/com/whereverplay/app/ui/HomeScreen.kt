package com.whereverplay.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.SettingsStore
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.Project
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

/**
 * WP: 主界面容器 —— 背景 + 三个 tab + 玻璃胶囊底栏。
 * 背景目前是纯色加两团低透明度色斑（对应「自定义背景图」未接入前的占位）；
 * 接图后只需把这里换成 Image + 暗遮罩，玻璃层不用动。
 *
 * 2026-10-25：三个 tab 之间可以**横向滑动**切换（`HorizontalPager`），点底栏仍然有效。
 * 两边共用一个 `pagerState`：
 *  - `tab` 变了（点底栏 / 导入后自动跳转）→ 动画滚到那一页；
 *  - 用户滑到新的一页 → 回写 `onTabChange`，底栏文字色和选中块跟着走。
 * 底栏的选中块直接读 `currentPage + currentPageOffsetFraction`，所以手指拖到一半时
 * 那块底也停在半路，松手才吸附过去 —— 比「换页之后再弹过去」连贯。
 */
@Composable
fun HomeScreen(
    projects: List<Project>,
    playlists: List<Playlist>,
    tags: List<String>,
    settings: SettingsStore,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenProject: (Project) -> Unit,
    onToggleFavorite: (Project) -> Unit,
    onCreateProject: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onBatchDeleteProjects: (List<Project>) -> Unit,
    onBatchFavoriteProjects: (List<Project>) -> Unit,
    onBatchExportProjects: (List<Project>) -> Unit,
    onShowDetail: (Project) -> Unit,
    onEditProject: (Project) -> Unit,
    onExportProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    /** 点播放列表整行 = 直接开播（第 1 首） */
    onPlayPlaylist: (Playlist) -> Unit,
    /** 行右侧 ⋮ 菜单里的「编辑」 */
    onEditPlaylist: (Playlist) -> Unit,
    onImportPlaylist: () -> Unit,
    onBatchDeletePlaylists: (List<Playlist>) -> Unit,
    onBatchExportPlaylists: (List<Playlist>) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = tab, pageCount = { TAB_COUNT })

    // 外部改 tab（点底栏、导入完自动跳到播放列表）→ 滚过去
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab) pagerState.animateScrollToPage(tab)
    }

    // 协程里必须读「最新」的 tab：这个 LaunchedEffect 的 key 是 pagerState，不会随 tab 重启，
    // 直接闭包捕获 tab 会永远拿到首帧那个值（0），于是切到第 1 页再滑回第 0 页就不会回写。
    val liveTab by rememberUpdatedState(tab)

    // 滑到新的一页 → 回写上层，但**只在停稳之后**（isScrollInProgress == false）。
    //
    // 踩过的坑（2026-10-25）：点「设置」是从第 0 页动画滚到第 2 页，中途会经过第 1 页，
    // 如果按 currentPage 实时回写，就会 onTabChange(1) → tab 变成 1 → `LaunchedEffect(tab)` 的
    // key 变了 → **正在跑的滚动动画被取消**，pager 卡在 1 和 2 中间（画面上一半播放列表一半设置），
    // 得再点一次「设置」才走完。加上「停稳才回写」之后，程序性滚动不会自我打断。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .filter { (scrolling, _) -> !scrolling }
            .collect { (_, page) -> if (page != liveTab) onTabChange(page) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 背景色斑（玻璃后面要有东西才看得出层次）
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0x14243043),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.18f, size.height * 0.16f)
            )
            drawCircle(
                color = Color(0x14152E2B),
                radius = size.minDimension * 0.6f,
                center = Offset(size.width * 0.92f, size.height * 0.72f)
            )
        }

        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // 三页全部保持组合：滑动回来时搜索词、滚动位置、多选状态都还在。
                // 三页里最重的只是各自的 Lazy 列表（只组合可见项），设置页十来行，代价可以忽略。
                beyondViewportPageCount = TAB_COUNT - 1,
                key = { it },
            ) { page ->
                when (page) {
                    0 -> ProjectListScreen(
                        projects = projects,
                        tags = tags,
                        onOpen = onOpenProject,
                        onToggleFavorite = onToggleFavorite,
                        onCreate = onCreateProject,
                        onImport = onImport,
                        onBatchDelete = onBatchDeleteProjects,
                        onBatchFavorite = onBatchFavoriteProjects,
                        onBatchExport = onBatchExportProjects,
                        onShowDetail = onShowDetail,
                        onEdit = onEditProject,
                        onExport = onExportProject,
                        onDelete = onDeleteProject,
                    )
                    1 -> PlaylistsScreen(
                        playlists = playlists,
                        projects = remember(projects) { projects.associateBy { it.id } },
                        onCreate = onCreatePlaylist,
                        onImport = onImportPlaylist,
                        onPlay = onPlayPlaylist,
                        onEdit = onEditPlaylist,
                        onExport = onBatchExportPlaylists,
                        onBatchDelete = onBatchDeletePlaylists,
                    )
                    else -> SettingsScreen(
                        settings = settings,
                        projectCount = projects.size,
                        onClearAll = onClearAll,
                    )
                }
            }
            BottomGlassBar(
                tab = tab,
                onTabChange = onTabChange,
                // 传给底栏的是「当前停在几页」，用 lambda 有两点好处：
                // 1) 只有底栏自己重排，滑动时不会让上面两个页面跟着重组；
                // 2) 跟手过程中数值连续变化，选中块能停在两格之间。
                pagePosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            )
        }
    }
}

private const val TAB_COUNT = 3

@Composable
private fun BottomGlassBar(tab: Int, onTabChange: (Int) -> Unit, pagePosition: () -> Float) {
    val labels = listOf("项目", "播放列表", "设置")
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        val shape = RoundedCornerShape(26.dp)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(Glass.fillStrong)
                .border(0.5.dp, Glass.border, shape)
                .padding(5.dp)
        ) {
            val tabW = maxWidth / labels.size
            // 选中块的横坐标直接用 px 算，走 offset 的 lambda 版本 —— 只重排、不重组，
            // 手指拖到两格之间时这块底也停在两格之间。
            val tabWPx = constraints.maxWidth / labels.size.toFloat()
            Box(
                Modifier
                    .offset { IntOffset((tabWPx * pagePosition()).roundToInt(), 0) }
                    .width(tabW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Glass.fillStrong)
            )
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == tab
                    val color by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(180),
                        label = "tabColor",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onTabChange(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

