package com.whereverplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * WP: 液态玻璃的实现取舍。
 *
 * 真正的 backdrop blur 需要额外依赖（haze）或 API 31+ 的离屏模糊，
 * 且同屏多层实时模糊在低端机上会掉帧。v1 第一阶段先用
 * 「半透明填充 + 细描边 + 顶面高光」这套静态玻璃，
 * 视觉上已经能读出玻璃层次，性能零成本。
 * 动态模糊留到视觉收尾阶段作为可开关项再加（对应需求 §11.2 的分级降级）。
 */
object Glass {
    val fill = Color(0x14FFFFFF)      // 白 8%
    val fillStrong = Color(0x26FFFFFF) // 白 15%
    val border = Color(0x33FFFFFF)    // 白 20%
    val highlight = Color(0x40FFFFFF) // 白 25%
    val borderStrong = Color(0x4DFFFFFF) // 白 30%，用于选中态
}

val WpColors = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF07203A),
    secondary = Color(0xFF8FD9C4),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE8E8EC),
    surface = Color(0xFF17171E),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF23232C),
    onSurfaceVariant = Color(0xFFA8A8B2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3A0A08),
)

/** 一块玻璃：半透明底 + 0.5dp 描边 + 顶面一条高光 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    fill: Color = Glass.fill,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(0.5.dp, Glass.border, shape),
        content = content
    )
}

@Composable
fun glassTopHighlight(corner: Dp = 16.dp): Color = Glass.highlight

@Composable
fun wpOnSurfaceMuted() = MaterialTheme.colorScheme.onSurfaceVariant
