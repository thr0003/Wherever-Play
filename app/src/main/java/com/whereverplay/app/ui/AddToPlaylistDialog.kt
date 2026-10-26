package com.whereverplay.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.domain.Playlist
import com.whereverplay.app.domain.Project

/**
 * WP: 把「首页多选的项目」批量加进播放列表（清单 #21）。
 *
 * 两种结果都由调用方落地（[onAppend] / [onCreate]），这里只负责选：
 *  - 选一个已有列表 → 追加到末尾，**保持列表原有顺序**，已在里面的不重复加（去重在这里就算好，
 *    因为要显示「其中 N 首已在列表里」，调用方只需按 ids 追加）；
 *  - 新建一个列表 → 直接以这批项目为初始曲目。
 *
 * 一个列表都没有时直接进入新建模式 —— 否则用户会看到一个空弹窗，还得再点一次「新建」。
 */
@Composable
fun AddToPlaylistDialog(
    selected: List<Project>,
    playlists: List<Playlist>,
    onAppend: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    val selectedIds = remember(selected) { selected.map { it.id }.toSet() }

    fun submitCreate() {
        val name = input.trim()
        if (name.isBlank()) {
            hint = "名字不能为空"
            return
        }
        if (playlists.any { it.name == name }) {
            hint = "已经有同名的播放列表了"
            return
        }
        onCreate(name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新建播放列表" else "添加到播放列表", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "已选 ${selected.size} 个项目，追加到列表末尾。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                if (creating) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(20); hint = null },
                        label = { Text("播放列表名称（最多 20 字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    hint?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // 列表多时（十几场演出）弹窗不能顶出屏幕，限高 + 内部滚动
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        playlists.forEach { playlist ->
                            val hit = playlist.projectIds.count { it in selectedIds }
                            val all = hit == selected.size
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { if (!all) onAppend(playlist) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (all) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onBackground,
                                    )
                                    Text(
                                        buildString {
                                            append("${playlist.projectIds.size} 首")
                                            if (hit > 0) append(" · 其中 $hit 首已在列表里")
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (all) "已在" else "加入",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (all) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = { submitCreate() }) { Text("创建并加入") }
            } else {
                TextButton(onClick = { creating = true }) { Text("新建播放列表") }
            }
        },
        dismissButton = {
            if (creating && playlists.isNotEmpty()) {
                TextButton(onClick = { creating = false; hint = null }) { Text("返回") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
