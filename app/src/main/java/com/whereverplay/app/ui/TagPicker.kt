package com.whereverplay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 标签选择器里的弱化文字色（与其它页的 0xFF6E6E78 保持同一档） */
private val TagMuted = Color(0xFF6E6E78)

/**
 * WP: 乐器标签选择器（单选 + 新建）。
 *
 * 用「固定 3 列」而不是 FlowRow：FlowRow 在这个 Compose 版本里还是实验 API，
 * 而标签最多 20 个、名字最长 12 字，三列等宽已经够整齐，没必要为此引入 opt-in。
 *
 * @param onAdd 返回 false 表示没加成（重名 / 已满），弹窗会给出对应提示而不是静默失败
 */
@Composable
fun TagPickerDialog(
    current: String?,
    tags: List<String>,
    canAdd: Boolean,
    maxTags: Int,
    onPick: (String?) -> Unit,
    onAdd: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    fun submitNew() {
        val name = input.trim()
        if (name.isBlank()) {
            hint = "名字不能为空"
            return
        }
        if (onAdd(name)) {
            onPick(name)
        } else {
            hint = if (!canAdd) "标签已达上限（$maxTags 个）" else "「$name」已经存在"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "新建标签" else "乐器标签", fontSize = 16.sp) },
        text = {
            Column {
                if (adding) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(12); hint = null },
                        label = { Text("标签名（最多 12 字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    hint?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "一个项目只能选一个乐器。冷门乐器可以自己加，总共最多 $maxTags 个。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 固定三列，最后一行不足三个用空占位补齐，避免 chip 忽宽忽窄
                    tags.chunked(3).forEach { rowTags ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTags.forEach { tag ->
                                WpChip(
                                    text = tag,
                                    onClick = { onPick(tag) },
                                    selected = tag == current,
                                    height = 32.dp,
                                    fontSize = 12.sp,
                                    horizontalPadding = 4.dp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowTags.size) {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                    if (!canAdd) {
                        Spacer(Modifier.height(8.dp))
                        Text("已达 $maxTags 个上限", fontSize = 10.sp, color = TagMuted)
                    }
                }
            }
        },
        confirmButton = {
            if (adding) {
                TextButton(onClick = { submitNew() }) { Text("创建并选中") }
            } else {
                TextButton(onClick = { adding = true }, enabled = canAdd) { Text("新建标签") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("清除") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
