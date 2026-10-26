package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

/**
 * 日志列表：年份/月份筛选 + 筛选后的统计 + 合并/清理入口 + 多选合并。
 *
 * 顶部三联指标永远是**当前筛选结果**的统计（不是全库），这样切到"2024 年 10 月"
 * 就能立刻看到那个月的潜次和累计时长。
 */
@Composable
fun DiveListScreen(
    entries: List<DiveEntry>,
    rawCount: Int,
    settings: DiveSettings,
    onOpen: (DiveEntry) -> Unit,
    onOpenMergeSettings: () -> Unit,
    onOpenCleanup: () -> Unit,
    onMergeSelected: (List<DiveEntry>) -> Unit,
) {
    var year by remember { mutableStateOf<Int?>(null) }
    var month by remember { mutableStateOf<Int?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val years = remember(entries) { entries.map { it.year }.filter { it > 0 }.distinct().sortedDescending() }
    val months = remember(entries, year) {
        val y = year ?: return@remember emptyList<Int>()
        entries.filter { it.year == y }.map { it.month }.filter { it in 1..12 }.distinct().sorted()
    }
    val shown = remember(entries, year, month) {
        entries.filter { e ->
            (year == null || e.year == year) && (month == null || e.month == month)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SummaryBar(shown, year, month)

        // 年份
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = year == null,
                    onClick = { year = null; month = null; selecting = false },
                    label = { Text("全部年份") },
                )
            }
            items(years) { y ->
                FilterChip(
                    selected = year == y,
                    onClick = { year = y; month = null; selecting = false },
                    label = { Text("$y") },
                )
            }
        }

        // 月份（选了年份才出现，且只列该年有数据的月份）
        if (year != null && months.size > 1) {
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = month == null,
                        onClick = { month = null },
                        label = { Text("全年") },
                    )
                }
                items(months) { m ->
                    FilterChip(
                        selected = month == m,
                        onClick = { month = m },
                        label = { Text("${m}月") },
                    )
                }
            }
        }

        // 操作行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selecting) {
                TextButton(onClick = onOpenMergeSettings) { Text("合并设置") }
                TextButton(onClick = onOpenCleanup) { Text("清理垃圾") }
                TextButton(onClick = { selecting = true }) { Text("多选合并") }
            } else {
                Text(
                    "已选 ${selected.size} 组",
                    Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = selected.size >= 2,
                    onClick = {
                        onMergeSelected(entries.filter { it.id in selected })
                        selected = emptySet()
                        selecting = false
                    },
                ) { Text("合并为一次") }
                TextButton(onClick = { selected = emptySet(); selecting = false }) { Text("取消") }
            }
            Spacer(Modifier.weight(1f))
            if (!selecting) {
                Text(
                    "$rawCount 条记录 → ${entries.size} 组",
                    Modifier.padding(end = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        "当前筛选下没有潜水记录",
                        Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.id }) { e ->
                DiveCard(
                    entry = e,
                    flags = buildList {
                        if (e.isMerged) add("合并 ${e.mergedCount} 条")
                        addAll(Analytics.invalidFlags(e.primary))
                    },
                    selecting = selecting,
                    checked = e.id in selected,
                    onClick = {
                        if (selecting) {
                            selected = if (e.id in selected) selected - e.id else selected + e.id
                        } else {
                            onOpen(e)
                        }
                    },
                )
            }
        }
    }
}

/** 顶部三联：当前筛选结果的潜次/时长/最深。 */
@Composable
private fun SummaryBar(shown: List<DiveEntry>, year: Int?, month: Int?) {
    val hours = shown.sumOf { it.durationS.toLong() } / 3600.0
    val deepest = shown.maxOfOrNull { it.maxDepthM } ?: 0.0
    val label = when {
        year == null -> "全部记录"
        month == null -> "$year 年"
        else -> "$year 年 $month 月"
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard("潜次", "${shown.size}", Modifier.weight(1f))
            StatCard("累计时长", "${Fmt.one(hours)} h", Modifier.weight(1f))
            StatCard("最大深度", "${Fmt.one(deepest)} m", Modifier.weight(1f))
        }
        Text(
            "当前范围：$label",
            Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 单条潜水卡片（可能是合并后的组合）。 */
@Composable
private fun DiveCard(
    entry: DiveEntry,
    flags: List<String>,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.dateText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(entry.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniMetric("时长", entry.durationText)
                    MiniMetric("最深", entry.depthText)
                    MiniMetric("平均", entry.avgDepthText)
                    MiniMetric("水温", entry.tempText)
                }
                if (flags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        flags.forEach { f ->
                            Text(
                                f,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (f.startsWith("合并")) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
