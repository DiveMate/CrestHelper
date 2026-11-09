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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 日志列表：年份/月份筛选 + 收藏筛选 + 筛选后的统计 + 多选（合并 / 批量删除）+ 长按快捷操作。
 *
 * 顶部三联指标永远是**当前筛选结果**的统计（不是全库），这样切到"2024 年 10 月"
 * 就能立刻看到那个月的潜次和累计时长。
 *
 * 多选模式同时管两件事：合并成一次、批量删除。全选只作用于**当前筛选结果**，
 * 筛完 "2024 年" 再全选，删的就是那一年，不会把整库带走。
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
    /** 批量删除：删除只是打标，条目进回收站。 */
    onDeleteSelected: (List<DiveEntry>) -> Unit = {},
    /** 剖面后台载入进度（null = 已载完，不显示进度条）。 */
    profileProgress: Float? = null,
    profileLoaded: Int = 0,
    profileTotal: Int = 0,
) {
    // 筛选/多选：旋转屏幕后要原样恢复，否则用户刚筛好的年份、勾好的一批记录全丢
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var month by rememberSaveable { mutableStateOf<Int?>(null) }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet()) }
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
                    onClick = { year = null; month = null; selecting = false; selected = emptySet() },
                    label = { Text("全部年份") },
                )
            }
            items(years) { y ->
                FilterChip(
                    selected = year == y,
                    onClick = { year = y; month = null; selecting = false; selected = emptySet() },
                    label = { Text("$y") },
                )
            }
            item {
                FilterChip(
                    selected = favOnly,
                    onClick = { favOnly = !favOnly; selected = emptySet() },
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
                        onClick = { month = m; selected = emptySet() },
                        label = { Text("${m}月") },
                    )
                }
            }
        }

        // 操作行：非选中态给「多选」入口；选中态给全选 / 批量删除 / 合并
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            if (!selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selecting = true; pressed = null }) { Text("多选") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$rawCount 条 → ${entries.size} 组",
                        Modifier.padding(end = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val allSelected = shown.isNotEmpty() && shown.all { it.id in selected }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已选 ${selected.size} 组",
                        Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            selected = if (allSelected) emptySet() else shown.mapTo(HashSet()) { it.id }
                        },
                    ) { Text(if (allSelected) "取消全选" else "全选") }
                    TextButton(onClick = { selected = emptySet(); selecting = false }) { Text("取消") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            // 用 entries 而不是 shown：勾选后如果又切了筛选，
                            // 用 shown 会漏删「已选但不是当前筛选」的那几条。
                            onDeleteSelected(entries.filter { it.id in selected })
                            selected = emptySet()
                            selecting = false
                        },
                    ) {
                        Text(
                            "删除选中" + if (selected.isNotEmpty()) "（${selected.size}）" else "",
                            color = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = selected.size >= 2,
                        onClick = {
                            onMergeSelected(entries.filter { it.id in selected })
                            selected = emptySet()
                            selecting = false
                        },
                    ) { Text("合并为一次") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "回收站可找回",
                        Modifier.padding(end = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        when {
                            // 完全没有记录（刚装好、release 包不带示例数据）和"筛选后为空"是两回事
                            entries.isEmpty() -> "库里还没有记录。去「设备」页同步手表，或者导入 .bin 文件。"
                            favOnly -> "还没有收藏的潜水"
                            else -> "当前筛选下没有潜水记录"
                        },
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
