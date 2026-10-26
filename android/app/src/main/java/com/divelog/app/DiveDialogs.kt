package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** 合并设置：自动合并开关 + 间隔档位。改动立即生效（列表会当场重排）。 */
@Composable
fun MergeSettingsDialog(
    entries: List<DiveEntry>,
    rawCount: Int,
    settings: DiveSettings,
    onApply: (DiveSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val mergedCount = entries.count { it.isMerged }
    val mergedRecords = entries.filter { it.isMerged }.sumOf { it.mergedCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日志合并") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动合并", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "水面间隔在阈值内的相邻记录视为同一次潜水",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoMerge,
                        onCheckedChange = { onApply(settings.copy(autoMerge = it)) },
                    )
                }

                if (settings.autoMerge) {
                    Text("水面间隔阈值", style = MaterialTheme.typography.labelMedium)

                    // 自由输入：数值 + 单位（秒 / 分钟），支持小数（如 45 秒、2.5 分钟）。
                    // 任何合法输入立即生效，下面的组数会当场重算。
                    var useMinutes by remember { mutableStateOf(DiveSettings.siUseMinutes(settings.siThresholdS)) }
                    var text by remember { mutableStateOf(DiveSettings.siInputText(settings.siThresholdS, useMinutes)) }
                    val sec = DiveSettings.siFromInput(text, useMinutes)
                    val valid = sec != null && DiveSettings.siValid(sec)

                    OutlinedTextField(
                        value = text,
                        onValueChange = { raw ->
                            // 只收数字和最多一个小数点，挡住输入法塞进来的奇怪字符
                            if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                                text = raw
                                val s = DiveSettings.siFromInput(raw, useMinutes)
                                if (s != null && DiveSettings.siValid(s)) {
                                    onApply(settings.copy(siThresholdS = s))
                                }
                            }
                        },
                        singleLine = true,
                        isError = !valid,
                        label = { Text("间隔") },
                        suffix = { Text(if (useMinutes) "分钟" else "秒") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("单位", style = MaterialTheme.typography.labelMedium)
                        FilterChip(
                            selected = !useMinutes,
                            onClick = {
                                if (useMinutes) {
                                    val s = DiveSettings.siFromInput(text, useMinutes)
                                    useMinutes = false
                                    if (s != null) text = DiveSettings.siInputText(s, false)
                                }
                            },
                            label = { Text("秒") },
                        )
                        FilterChip(
                            selected = useMinutes,
                            onClick = {
                                if (!useMinutes) {
                                    val s = DiveSettings.siFromInput(text, useMinutes)
                                    useMinutes = true
                                    if (s != null) text = DiveSettings.siInputText(s, true)
                                }
                            },
                            label = { Text("分钟") },
                        )
                    }

                    Text(
                        if (valid) "当前 = ${DiveSettings.siText(sec!!)}　阈值越大，合并越激进"
                        else "请输入 1 秒 ~ 24 小时之间的数值",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                Text(
                    "$rawCount 条原始记录 → ${entries.size} 组" +
                        if (mergedCount > 0) "（其中 $mergedCount 组由 $mergedRecords 条记录合并）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "手动合并：列表右上角「多选合并」勾选多条，再点「合并为一次」。" +
                        "合并后的剖面会保留中间的水面段，减压计算才准确。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 清理垃圾日志：按"时长 ≤ X 秒 或 最深 < Y 米"筛出候选，下面实时列出将被删除的记录，
 * 确认后从列表/统计/分析里一并移除（软删除，可恢复）。
 */
@Composable
fun CleanupDialog(
    entries: List<DiveEntry>,
    rule: JunkRule,
    deletedCount: Int,
    onRuleChange: (JunkRule) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val matched = remember(entries, rule) {
        entries.filter { rule.matches(it) }.sortedByDescending { it.startMillis }
    }
    val matchedMinutes = matched.sumOf { it.bottomTimeS } / 60.0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(Modifier.padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("清理垃圾日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "筛出误触/试机记录，删除后不再出现在列表、统计和分析里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                var durSec by remember(rule) { mutableFloatStateOf(rule.maxDurationS.toFloat()) }
                var depthM by remember(rule) { mutableFloatStateOf(rule.maxDepthM.toFloat()) }

                Text("时长不超过 ${durSec.roundToInt()} 秒", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = durSec,
                    onValueChange = { v ->
                        durSec = v
                        onRuleChange(rule.copy(maxDurationS = v.roundToInt()))
                    },
                    valueRange = 0f..600f,
                    steps = 19,
                )
                Text("最深不足 ${Fmt.one(depthM.toDouble())} 米", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = depthM,
                    onValueChange = { v ->
                        val snapped = (v * 2).roundToInt() / 2f
                        depthM = snapped
                        onRuleChange(rule.copy(maxDepthM = snapped.toDouble()))
                    },
                    valueRange = 0f..10f,
                    steps = 19,
                )

                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "命中 ${matched.size} 组" + if (matched.isNotEmpty()) " · 合计 ${Fmt.one(matchedMinutes)} 分钟" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (matched.isEmpty()) {
                            Text(
                                "当前阈值下没有需要清理的记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(
                                Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                            ) {
                                matched.take(40).forEach { e ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            e.dateText + " " + e.timeText.take(5),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${e.durationText} · ${e.depthText}" +
                                                if (e.isMerged) " · 合并${e.mergedCount}" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                if (matched.size > 40) {
                                    Text(
                                        "…还有 ${matched.size - 40} 组",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (deletedCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已删除 $deletedCount 条",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRestoreAll) { Text("全部恢复") }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = matched.isNotEmpty(),
                        onClick = { onDelete(matched.map { it.id }.toSet()) },
                    ) { Text(if (matched.isEmpty()) "无可删除" else "删除 ${matched.size} 组") }
                }
            }
        }
    }
}
