package com.whereverplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.SettingsStore

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    projectCount: Int,
    onClearAll: () -> Unit,
) {
    var offset by remember { mutableStateOf(settings.pageTurnOffsetMs) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var anim by remember { mutableStateOf(settings.pageTurnAnimation) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Medium)
        }

        GroupTitle("播放与翻页")
        GlassGroup {
            SettingRow("翻页提前量", "正值 = 提前翻页，用来补偿「翻晚了」") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        offset = (offset - 100).coerceIn(-500, 500)
                        settings.pageTurnOffsetMs = offset
                    }) { Text("-100", fontSize = 12.sp) }
                    Text("${offset}ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = {
                        offset = (offset + 100).coerceIn(-500, 500)
                        settings.pageTurnOffsetMs = offset
                    }) { Text("+100", fontSize = 12.sp) }
                }
            }
            DividerLine()
            SettingRow("翻页动画", animationLabel(anim)) {
                TextButton(onClick = {
                    anim = (anim + 1) % 3
                    settings.pageTurnAnimation = anim
                }) { Text("切换", fontSize = 12.sp) }
            }
            DividerLine()
            SettingRow("播放时保持屏幕常亮", null) {
                Switch(checked = keepOn, onCheckedChange = {
                    keepOn = it
                    settings.keepScreenOn = it
                })
            }
        }

        GroupTitle("数据")
        GlassGroup {
            SettingRow("项目数量", "$projectCount 个") {}
            DividerLine()
            SettingRow("清空所有项目", "删除全部项目与打点记录", danger = true) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("清空", fontSize = 12.sp, color = Color(0xFFFF8A80))
                }
            }
        }

        GroupTitle("关于")
        GlassGroup {
            SettingRow("版本", "${com.whereverplay.app.BuildConfig.VERSION_NAME}（${com.whereverplay.app.BuildConfig.VERSION_CODE}）")
            DividerLine()
            SettingRow("数据存储", "全部保存在本机，不联网、不上传")
            DividerLine()
            SettingRow("包名", "com.whereverplay.app")
        }

        Text(
            "字体大小、主界面背景图、浅色主题将在下一阶段接入。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
        )
        Spacer(Modifier.height(96.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空所有项目？") },
            text = { Text("会删除全部项目、打点记录和已复制的音频/PDF 文件，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("确认清空", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

private fun animationLabel(anim: Int): String = when (anim) {
    0 -> "无（直接切换）"
    1 -> "淡入淡出"
    else -> "滑动"
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun GlassGroup(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .border(0.5.dp, Glass.border, shape)
            .clickable(enabled = false) {}
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    danger: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (danger) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun DividerLine() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0x1AFFFFFF))
        )
    }
}
