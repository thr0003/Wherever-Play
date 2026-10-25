package com.whereverplay.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WP: 全屏看图。
 *
 * 交互按「最省事」定：铺满屏幕、点任意空白处 / 返回键关闭，不接手势缩放
 * （缩放要和「点击关闭」抢手势，收益不值；要看细节直接双指放大系统截图即可）。
 *
 * 底部两个操作：
 *  - **另存图片**（实心）= 存进系统相册；
 *  - **更换图片**（玻璃）= 拉起系统图片选择器。
 * 两者都由调用方注入 —— 这个组件只负责「显示 + 两个按钮」，不碰 IO 和选择器。
 */
@Composable
fun ImageViewerOverlay(
    bitmap: Bitmap?,
    title: String,
    busy: Boolean = false,
    hint: String? = null,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onReplace: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClose() },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(vertical = 72.dp),
            )
        } else {
            Text(
                "没有封面图",
                fontSize = 13.sp,
                color = Color(0xFF8A8A94),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 顶部标题 + 关闭
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WpIconButton(onClick = onClose, visual = 0.dp) { tint -> IconClose(tint, 16.dp) }
            Spacer(Modifier.weight(1f))
            Text(
                title,
                fontSize = 12.sp,
                color = Color(0xFFD8D8DE),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }

        // 底部操作条
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hint != null) {
                Text(
                    hint,
                    fontSize = 11.sp,
                    color = Color(0xFF9EC5FF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WpSolidButton(
                    text = if (busy) "保存中…" else "另存图片",
                    onClick = { if (!busy) onSave() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                )
                WpGhostButton(
                    text = "更换图片",
                    onClick = { if (!busy) onReplace() },
                    modifier = Modifier.weight(1f).height(48.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("点任意处关闭", fontSize = 10.sp, color = Color(0xFF6E6E78))
        }
    }
}
