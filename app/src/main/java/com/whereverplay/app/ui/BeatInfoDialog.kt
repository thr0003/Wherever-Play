package com.whereverplay.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.domain.BeatInfo
import com.whereverplay.app.domain.TimeFormat

/**
 * WP: BPM / 第一拍时间的编辑弹窗（0.2.1）。
 *
 * **故意单独成一个文件**：用户说了这两个属性的入口位置以后可能要搬（详情页 → 编辑页 / 打点页 / 播放页）。
 * 那时只要改调用点，这个文件一行都不用动。所以别把它的状态塞回 ProjectDetailScreen。
 *
 * 输入规则：
 *  - BPM：最多 4 位整数 + 1 位小数（0.1–1000.0）。留空 = 未设置；填 0 视为未设置并提示。
 *  - 第一拍：最多 4 位整数 + 3 位小数（即毫秒精度），单位是**秒**，因为用户是按「第几秒进第一拍」想的。
 *  - 两个字段各自独立：只填一个也能存，另一个就是未设置。
 *
 * @param durationMs 仅用于「第一拍超出音频时长」的提示，**不阻止保存**（换了音频可能还会改回来）
 */
@Composable
fun BeatInfoDialog(
    bpm: Float?,
    firstBeatMs: Long?,
    durationMs: Long,
    onSave: (bpm: Float?, firstBeatMs: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var bpmText by remember { mutableStateOf(bpm?.let { BeatInfo.bpmText(it) } ?: "") }
    // 毫秒 → 秒的输入文本，同样去掉无意义的尾随 0
    var beatText by remember {
        mutableStateOf(firstBeatMs?.let { BeatInfo.secondsText(it).removeSuffix(" 秒") } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val rawBpm = bpmText.trim()
        val rawBeat = beatText.trim()

        var parsedBpm: Float? = null
        if (rawBpm.isNotEmpty()) {
            val v = rawBpm.toFloatOrNull()
            if (v == null || v <= 0f) {
                error = "BPM 要大于 0；不填就是「未设置」"
                return
            }
            if (v > BeatInfo.BPM_MAX) {
                error = "BPM 最大 ${BeatInfo.BPM_MAX.toInt()}"
                return
            }
            parsedBpm = BeatInfo.sanitizeBpm(v)
        }

        var parsedBeat: Long? = null
        if (rawBeat.isNotEmpty()) {
            val v = rawBeat.toFloatOrNull()
            if (v == null || v < 0f) {
                error = "第一拍时间不能是负数"
                return
            }
            val ms = (v * 1000f + 0.5f).toLong()
            if (ms > BeatInfo.FIRST_BEAT_MAX_MS) {
                error = "第一拍时间太大了"
                return
            }
            parsedBeat = BeatInfo.sanitizeFirstBeat(ms)
        }

        onSave(parsedBpm, parsedBeat)
    }

    // 第一拍超出音频时长：只提醒，不拦；换更长的音频后可能就合法了。
    // 按**输入框里的现行值**算，打字过程中实时变，别用传进来的旧值。
    val typedBeatMs = beatText.trim().toFloatOrNull()?.let { (it * 1000f + 0.5f).toLong() }
    val beatOverDuration = typedBeatMs != null && durationMs > 0L && typedBeatMs > durationMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BPM 与第一拍", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "用来描述拍网格：第一拍往后每 60000/BPM 毫秒一拍。" +
                        "伴奏起拍不明显时，" +
                        "靠这两个值将来能配合节拍器做倒数。两项可以只填一个。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = bpmText,
                    onValueChange = { raw ->
                        cleanDecimal(raw, intDigits = 4, decimals = BeatInfo.BPM_DECIMALS)
                            ?.let { bpmText = it; error = null }
                    },
                    label = { Text("BPM") },
                    placeholder = { Text("120.0") },
                    suffix = { Text("BPM", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = beatText,
                    onValueChange = { raw ->
                        cleanDecimal(raw, intDigits = 4, decimals = 3)
                            ?.let { beatText = it; error = null }
                    },
                    label = { Text("第一拍时间（秒）") },
                    placeholder = { Text("0.35") },
                    suffix = { Text("秒", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                if (beatOverDuration) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "第一拍 ${BeatInfo.secondsText(typedBeatMs!!)} 超出了音频时长 " +
                            "${TimeFormat.mmss(durationMs)}，保存后暂时不会生效。",
                        fontSize = 11.sp,
                        color = Color(0xFFF0C070),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "留空 = 未设置。BPM 填 0 也按未设置处理（拍间隔算不出来）。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null, null) }) { Text("清除") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 十进制输入清洗：只留数字和一个小数点，限制整数位/小数位长度，超了就**拒收这次改动**
 * （返回 null，调用方保持原值）。比「截断」的体验好——截断会让光标处的字符凭空消失。
 *
 * 顺便把 `.5` 补成 `0.5`，否则 toFloatOrNull 也能解析，但显示成 ".5" 很怪。
 */
private fun cleanDecimal(raw: String, intDigits: Int, decimals: Int): String? {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    if (filtered.count { it == '.' } > 1) return null
    val intPart = filtered.substringBefore('.')
    val decPart = filtered.substringAfter('.', "")
    if (intPart.length > intDigits) return null
    if (decPart.length > decimals) return null
    return if (filtered.startsWith(".")) "0$filtered" else filtered
}
