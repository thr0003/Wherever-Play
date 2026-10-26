package com.whereverplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereverplay.app.data.SettingsStore
import kotlin.math.roundToInt

/**
 * WP: 设置页（需求 6「条目很杂乱，优化一下」——保守整理，不重做信息架构）。
 *
 * 改的是「三种控件长得各不相同」和「留白各自为政」：
 *  1. 翻页提前量：`-100 / +100` 两个文字按钮 → **滑块**（50ms 一档）+ 右侧数值；
 *  2. 翻页动画：「切换」文字按钮（还得一个个点过去才知道有哪些值）→ **三段胶囊**，三个选项一次看全；
 *  3. 分组容器从「只有一条描边」→ **带底色的小卡片**（Glass.fill），和列表页的卡片是同一档；
 *  4. 行高统一 `heightIn(min = 52)`，分隔线内缩 16dp 且只在行与行之间出现；
 *  5. 关于区只留「版本 + 数据存储」，包名对用户没意义，删掉。
 *
 * 没动：分组划分、开关的位置、清空数据的确认弹窗。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    projectCount: Int,
    onClearAll: () -> Unit,
) {
    var offset by remember { mutableStateOf(settings.pageTurnOffsetMs) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var anim by remember { mutableStateOf(settings.pageTurnAnimation) }
    var manualDefault by remember { mutableStateOf(settings.manualTurn) }
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
            // 提前量用滑块：范围 −500…+500、50ms 一档（和播放页那个「翻页提前量」弹窗同一套网格）
            SettingRow(
                title = "翻页提前量",
                subtitle = "正值 = 提前翻页，用来补偿「翻晚了」",
            ) {
                Text(
                    formatOffset(offset),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SliderRow(
                value = offset.toFloat(),
                onValue = { raw ->
                    // 吸附到 50ms 网格。offset / pageTurnOffsetMs 都是 Long（见 SettingsStore）
                    val snapped = ((raw / OFFSET_STEP).roundToInt() * OFFSET_STEP).toLong()
                    if (snapped != offset) {
                        offset = snapped
                        settings.pageTurnOffsetMs = snapped
                    }
                },
            )
            DividerLine()
            SettingRow("翻页动画", "谱面切换时的过渡方式")
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                SegmentedPill(
                    options = listOf("无", "淡入淡出", "滑动"),
                    selectedIndex = anim,
                    onSelect = {
                        anim = it
                        settings.pageTurnAnimation = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DividerLine()
            // 0.2.2：手动翻谱的**全局默认**。单项/单个列表可以在播放页左下角单独覆盖，
            // 所以这里写「默认」而不是「全部」—— 说「所有项目都手动」会让人以为改不了单个。
            SettingRow(
                "默认手动翻谱",
                "默认值：不跟打点，自己翻。单个项目或播放列表可以在播放页左下角单独改",
                trailingDivider = true,
            ) {
                Switch(
                    checked = manualDefault,
                    onCheckedChange = {
                        manualDefault = it
                        settings.manualTurn = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = Color(0x1FFFFFFF),
                        uncheckedBorderColor = Glass.border,
                    ),
                )
            }
            // 开关行：trailing 是 Switch，末行不需要再画分隔线
            SettingRow("播放时保持屏幕常亮", null) {
                Switch(
                    checked = keepOn,
                    onCheckedChange = {
                        keepOn = it
                        settings.keepScreenOn = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = Color(0x1FFFFFFF),
                        uncheckedBorderColor = Glass.border,
                    ),
                )
            }
        }

        GroupTitle("数据")
        GlassGroup {
            SettingRow("项目数量", null, trailingDivider = true) {
                Text(
                    "$projectCount 个",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingRow("清空所有项目", "删除全部项目与打点记录，无法恢复", danger = true) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("清空", fontSize = 12.sp, color = Color(0xFFFF8A80))
                }
            }
        }

        GroupTitle("关于")
        GlassGroup {
            SettingRow(
                "版本",
                "${com.whereverplay.app.BuildConfig.VERSION_NAME}（${com.whereverplay.app.BuildConfig.VERSION_CODE}）",
                trailingDivider = true,
            )
            SettingRow("数据存储", "全部保存在本机，不联网、不上传")
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

/** 滑块档位：50ms 一档，和播放页提前量弹窗的吸附网格一致 */
private const val OFFSET_STEP = 50f
private const val OFFSET_MIN = -500f
private const val OFFSET_MAX = 500f

private fun formatOffset(ms: Long): String = if (ms > 0) "+$ms ms" else "$ms ms"

@Composable
private fun SliderRow(value: Float, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = OFFSET_MIN..OFFSET_MAX,
            // 20 个区间 = 21 个停靠点，即 50ms 一档
            steps = ((OFFSET_MAX - OFFSET_MIN) / OFFSET_STEP).toInt() - 1,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0x26FFFFFF),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("−500 ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("+500 ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 三段胶囊（无 / 淡入淡出 / 滑动）。
 *
 * 为什么替掉「切换」文字按钮：按钮循环切值，用户点之前看不到有哪些选项，
 * 也回不去指定值。三段一次摊开，选中只靠底色 + 字重，不打钩。
 * 每段热区撑满 44 高（视觉底块内缩 3dp 画），符合「视觉可小于 44、热区必须 ≥ 44」。
 */
@Composable
private fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(Glass.fill)
            .border(0.5.dp, Glass.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            WpTapArea(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // 每段的视觉底块内缩 3dp，涟漪也跟着内缩，否则会溢到隔壁段
                rippleShape = wpRippleShape(9.dp, insetX = 3.dp, insetY = 3.dp),
            ) {
                val segment = RoundedCornerShape(9.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(segment)
                        .background(if (selected) Glass.fillStrong else Color.Transparent)
                        .then(
                            if (selected) Modifier.border(0.5.dp, Glass.borderStrong, segment)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
    )
}

/** 分组卡片：底色 + 细描边（原来只有描边，几条线漂在背景上，看着散） */
@Composable
private fun GlassGroup(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(shape)
            .background(Glass.fill)
            .border(0.5.dp, Glass.border, shape),
    ) {
        content()
    }
}

/**
 * 统一的行：固定最小高度 52，标题 14sp / 副标题 11sp，控件右对齐。
 * [trailingDivider] 只影响「本行下面是否画分隔线」——不用再手写 DividerLine 的位置。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    danger: Boolean = false,
    trailingDivider: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (danger) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
    if (trailingDivider) DividerLine()
}

@Composable
private fun DividerLine() {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
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
