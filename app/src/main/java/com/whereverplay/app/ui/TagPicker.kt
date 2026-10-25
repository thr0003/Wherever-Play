package com.whereverplay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
 * 乐器标签 → 前置图标（**预留接口**）。
 *
 * 现在还没有具象化图标资源，统一返回 null，也就是 chip 的布局与改造前完全一致。
 * 以后配图标只改这一个函数，TagPicker 与 WpChip 都不用动：
 *
 * ```
 * "鼓"     -> { tint -> IconDrum(tint, 14.dp) }
 * "电吉他" -> { tint -> IconElectricGuitar(tint, 14.dp) }
 * ```
 *
 * 图标按「标签名」映射、**不落盘**，所以 TagStore 的存储结构不用改，加图标不需要数据迁移。
 */
private fun tagIconOf(tag: String): (@Composable (Color) -> Unit)? = when (tag) {
    else -> null
}

/**
 * WP: 乐器标签选择器（单选 + 新建）。
 *
 * 2026-10-25 改版：
 *  - 布局从「固定 3 列等宽」改成 **FlowRow 流式**。标签名字有长有短（「鼓」vs「尤克里里」），
 *    等宽三列会让短标签左边一大片空白、长标签被挤到换行，看着比流式更乱。
 *    FlowRow 在当前 Compose 版本已稳定，不需要 opt-in。
 *  - 说明文案规范化：先说「单选」，再说「不够用怎么办」，最后给上限。
 *  - 每个 chip 预留了前置图标槽（[tagIconOf]），后续给部分乐器配具象化图标时
 *    **只改那个函数**，这里一行不用动。
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
                        "单选。没有合适的乐器时，点「新建标签」自行添加（上限 $maxTags 个，含内置标签）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    // 流式排布：标签按自身宽度排列，末尾自然换行，不再强求每行几个
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            WpChip(
                                text = tag,
                                onClick = { onPick(tag) },
                                selected = tag == current,
                                height = 32.dp,
                                fontSize = 12.sp,
                                horizontalPadding = 14.dp,
                                leadingIcon = tagIconOf(tag),
                            )
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
