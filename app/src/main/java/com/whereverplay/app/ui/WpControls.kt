package com.whereverplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WP: 图标按钮 —— 「44 容器 + 34 视觉」。
 *
 * 设计稿的硬约束：视觉可以小于 44，热区必须 ≥ 44。
 * 之前工程里是 `Box(Modifier.size(34.dp).clickable{})`，热区只有 34，实测容易点不中。
 */
@Composable
fun WpIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 视觉圆直径；传 0.dp 表示「无底纯图标」 */
    visual: Dp = 34.dp,
    background: Color? = null,
    borderColor: Color? = null,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    content: @Composable (Color) -> Unit,
) {
    val resolved = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant
    WpTapArea(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp),
        // 涟漪按「视觉圆」裁，不是按 44dp 热区 —— 否则四个角会露出方框。
        // 无底纯图标（visual = 0）没有圆底，给图标自身那一档，范围略大于图标但不至于铺满 44。
        rippleShape = wpRippleCircle(if (visual.value > 0f) visual else 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (visual.value <= 0f) {
            content(resolved)
        } else {
            Box(
                Modifier
                    .size(visual)
                    .clip(CircleShape)
                    .then(if (background != null) Modifier.background(background) else Modifier)
                    .then(
                        if (borderColor != null) {
                            Modifier.border(0.5.dp, borderColor, CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                content(resolved)
            }
        }
    }
}

/** WP: 玻璃胶囊文字按钮（首页「＋」这类带图标/文字的次要动作）。
 *  [text] 传空串 = 只有图标（首页右上角那个 `+` 就是这么用的）。 */
@Composable
fun WpPillAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    val tint = MaterialTheme.colorScheme.onBackground
    WpTapArea(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        // 视觉胶囊 36 高，热区 44 —— 上下各缩 4dp 让涟漪正好压住胶囊
        rippleShape = wpRippleShape(18.dp, insetY = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(Glass.fillStrong)
                .border(0.5.dp, Glass.border, shape)
                .padding(horizontal = if (text.isEmpty()) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.invoke(tint)
            if (text.isNotEmpty()) Text(text, fontSize = 12.sp, color = tint, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * WP: 小号玻璃胶囊（筛选 chip / 微调 chip / 模式 chip）。
 * 高度由调用方定（筛选 28、微调 28、模式 32、标签 32）。
 *
 * [leadingIcon] 是给乐器标签预留的图标槽：传 null 时布局和以前完全一致，
 * 以后标签配上具象化图标只需要在 TagPicker 的 `tagIconOf()` 里补 case，这里不用动。
 */
@Composable
fun WpChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 28.dp,
    fontSize: TextUnit = 11.sp,
    horizontalPadding: Dp = 14.dp,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val contentColor = if (selected) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onSurfaceVariant
    WpTapArea(
        onClick = onClick,
        modifier = modifier.height(height),
        // 热区与视觉同尺寸，直接按 14dp 圆角裁
        rippleShape = wpRippleShape(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(height)
                .clip(shape)
                .background(if (selected) Glass.fillStrong else Glass.fill)
                .border(0.5.dp, if (selected) Glass.borderStrong else Glass.border, shape)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            leadingIcon?.invoke(contentColor)
            Text(
                text,
                fontSize = fontSize,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** WP: 一屏唯一的主操作（实心 #EDEDED 底 / #17171A 字） */
@Composable
fun WpSolidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
) {
    WpTapArea(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        rippleShape = wpRippleShape(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFEDEDED))
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = fontSize, fontWeight = FontWeight.Medium, color = Color(0xFF17171A))
        }
    }
}

/** WP: 玻璃次按钮 —— 详情/编辑页底部的次级动作（导出、删除那类） */
@Composable
fun WpGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    fontSize: TextUnit = 12.sp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Glass.fillStrong)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = fontSize, color = MaterialTheme.colorScheme.onBackground)
    }
}
