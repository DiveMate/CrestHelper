package com.divelog.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
 * 日志列表：年份/月份筛选 + 收藏筛选 + 筛选后的统计 + 多选合并 + 长按快捷操作。
 *
 * 顶部三联指标永远是**当前筛选结果**的统计（不是全库），这样切到"2024 年 10 月"
 * 就能立刻看到那个月的潜次和累计时长。
 *
 * 长按一条记录会就地展开两个图标（收藏 / 删除），不弹菜单：
 * 手机上一只手就能点，也不会遮住列表。
 */
@Composable
fun DiveListScreen(
    entries: List<DiveEntry>,
    rawCount: Int,
    favorites: Set<String>,
    onOpen: (DiveEntry) -> Unit,
    onMergeSelected: (List<DiveEntry>) -> Unit,
    onToggleFavorite: (DiveEntry) -> Unit,
    onDelete: (DiveEntry) -> Unit,
    /** 剖面后台载入进度（null = 已载完，不显示进度条）。 */
    profileProgress: Float? = null,
    profileLoaded: Int = 0,
    profileTotal: Int = 0,
) {
    var year by remember { mutableStateOf<Int?>(null) }
    var month by remember { mutableStateOf<Int?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 长按展开的那一条（同一时刻只展开一条）
    var pressed by remember { mutableStateOf<String?>(null) }

    val years = remember(entries) { entries.map { it.year }.filter { it > 0 }.distinct().sortedDescending() }
    val months = remember(entries, year) {
        val y = year ?: return@remember emptyList<Int>()
        entries.filter { it.year == y }.map { it.month }.filter { it in 1..12 }.distinct().sorted()
    }
    val shown = remember(entries, year, month, favOnly, favorites) {
        entries.filter { e ->
            (year == null || e.year == year) &&
                (month == null || e.month == month) &&
                (!favOnly || e.records.any { it.file in favorites })
        }
    }

    Column(Modifier.fillMaxSize()) {
        SummaryBar(shown, year, month, favOnly)

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
            item {
                FilterChip(
                    selected = favOnly,
                    onClick = { favOnly = !favOnly },
                    label = { Text("★ 收藏") },
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

        // 操作行：合并合并 / 选中态操作 / 右侧记录数
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selecting) {
                TextButton(onClick = { selecting = true; pressed = null }) { Text("多选合并") }
                Text(
                    "长按一条可收藏 / 删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
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
                    "$rawCount 条 → ${entries.size} 组",
                    Modifier.padding(end = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 剖面在后台分块载入：列表本身只吃摘要，所以这里只是告知进度，
        // 不挡任何操作——想看的这条多半已经在前面几块里了。
        if (profileProgress != null && profileProgress < 1f) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { profileProgress },
                    modifier = Modifier.weight(1f).height(3.dp),
                )
                Text(
                    "剖面 $profileLoaded/$profileTotal",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (favOnly) "还没有收藏的潜水：长按一条记录，点星标即可收藏"
                        else "当前筛选下没有潜水记录",
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
                        if (e.isNitrox) add(e.gasLabel)
                        addAll(Analytics.invalidFlags(e.primary))
                    },
                    favorite = e.records.any { it.file in favorites },
                    showActions = pressed == e.id,
                    selecting = selecting,
                    checked = e.id in selected,
                    onClick = {
                        when {
                            selecting -> selected =
                                if (e.id in selected) selected - e.id else selected + e.id
                            pressed == e.id -> pressed = null
                            else -> onOpen(e)
                        }
                    },
                    onLongClick = {
                        if (!selecting) pressed = if (pressed == e.id) null else e.id
                    },
                    onToggleFavorite = {
                        onToggleFavorite(e)
                        pressed = null
                    },
                    onDelete = {
                        onDelete(e)
                        pressed = null
                    },
                )
            }
        }
    }
}

/** 顶部三联：当前筛选结果的潜次/时长/最深。 */
@Composable
private fun SummaryBar(shown: List<DiveEntry>, year: Int?, month: Int?, favOnly: Boolean) {
    val hours = shown.sumOf { it.durationS.toLong() } / 3600.0
    val deepest = shown.maxOfOrNull { it.maxDepthM } ?: 0.0
    val label = when {
        year == null -> "全部记录"
        month == null -> "$year 年"
        else -> "$year 年 $month 月"
    } + if (favOnly) "（只看收藏）" else ""

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiveCard(
    entry: DiveEntry,
    flags: List<String>,
    favorite: Boolean,
    showActions: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (favorite) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "已收藏",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(
                            entry.dateText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        entry.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                color = when {
                                    f.startsWith("合并") -> MaterialTheme.colorScheme.tertiary
                                    f.startsWith("EAN") || f.startsWith("Tx") || f.startsWith("空气") ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }

            // 长按后就地展开：收藏 / 删除
            if (showActions) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (favorite) "取消收藏" else "收藏",
                        tint = if (favorite) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
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
