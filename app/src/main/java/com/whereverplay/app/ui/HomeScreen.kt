package com.whereverplay.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.SettingsStore
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.Project

/**
 * WP: 主界面容器 —— 背景 + 三个 tab + 玻璃胶囊底栏。
 * 背景目前是纯色加两团低透明度色斑（对应「自定义背景图」未接入前的占位）；
 * 接图后只需把这里换成 Image + 暗遮罩，玻璃层不用动。
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
    onOpenPlaylist: (Playlist) -> Unit,
    onImportPlaylist: () -> Unit,
    onBatchDeletePlaylists: (List<Playlist>) -> Unit,
    onBatchExportPlaylists: (List<Playlist>) -> Unit,
) {
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
            Box(Modifier.weight(1f)) {
                when (tab) {
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
                        onOpen = onOpenPlaylist,
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
            BottomGlassBar(tab = tab, onTabChange = onTabChange)
        }
    }
}

@Composable
private fun BottomGlassBar(tab: Int, onTabChange: (Int) -> Unit) {
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
            // WP: 选中块在胶囊内「滑」过去，而不是换个背景色 —— 位置连续变化才能被眼睛跟上
            val indicatorX by animateDpAsState(
                targetValue = tabW * tab,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                label = "tabIndicator",
            )
            Box(
                Modifier
                    .offset(x = indicatorX)
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

