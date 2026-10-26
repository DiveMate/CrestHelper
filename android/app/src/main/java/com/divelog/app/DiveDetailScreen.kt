package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 详情页：单次潜水的完整复盘。
 *
 * 指标：最大/平均深度、最低/最高水温、GF99、最小安全深度、NDL；
 * 图：深度剖面（叠加水温与最小安全深度）、NDL 剩余、GF99；
 * 算法切换会重新积分整条剖面（数值与 deco.py 一致，见 DecoTest）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveDetailScreen(
    entry: DiveEntry,
    onBack: () -> Unit,
    canUnmerge: Boolean = false,
    onUnmerge: () -> Unit = {},
) {
    var algoIdx by remember(entry.id) { mutableIntStateOf(2) }          // 默认 GF 50/70
    val algo = Deco.ALGORITHMS[algoIdx]
    val series = remember(entry.id, algoIdx) { Deco.series(entry.samples, algo) }
    val analysis = remember(entry.id, algoIdx) {
        Deco.analyzeProfile(entry.samples.map { it.t to it.depth }, algo)
    }
    val ndlMin = series.minOfOrNull { it.ndl } ?: 0
    val ndlEnd = series.lastOrNull()?.ndl ?: 0
    val maxTemp = series.mapNotNull { it.temp }.maxOrNull() ?: entry.tempMaxC
    val minTemp = series.mapNotNull { it.temp }.minOrNull() ?: entry.tempMinC

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (entry.isMerged) "${entry.dateText} · 合并 ${entry.mergedCount} 条"
                        else entry.dateText,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 深度与时长
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("时长", entry.durationText, Modifier.weight(1f))
                MetricCard("最大深度", entry.depthText, Modifier.weight(1f))
                MetricCard("平均深度", entry.avgDepthText, Modifier.weight(1f))
            }
            // 温度与采样
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("最低水温", if (minTemp != null) "${Fmt.one(minTemp)} °C" else "—", Modifier.weight(1f))
                MetricCard("最高水温", if (maxTemp != null) "${Fmt.one(maxTemp)} °C" else "—", Modifier.weight(1f))
                MetricCard("采样点", "${entry.primary.nsamples}", Modifier.weight(1f))
            }

            // 算法切换
            Text("减压算法", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Deco.ALGORITHMS) { a ->
                    FilterChip(
                        selected = a.id == algo.id,
                        onClick = { algoIdx = Deco.ALGORITHMS.indexOf(a) },
                        label = { Text(a.name) },
                    )
                }
            }
            Text(algo.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 减压结论
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    "最小安全深度",
                    if (analysis.ceilingMax > 0) "${Fmt.one(analysis.ceilingMax)} m" else "无",
                    Modifier.weight(1f),
                )
                MetricCard("减压时长", if (analysis.decoSec > 0) Fmt.duration(analysis.decoSec) else "0:00", Modifier.weight(1f))
                MetricCard("GF99 峰值", "${Fmt.one(analysis.gf99Max)}%", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("最大上升", "${Fmt.one(analysis.ascentMax)} m/min", Modifier.weight(1f))
                MetricCard("NDL 最低", ndlText(ndlMin), Modifier.weight(1f))
                MetricCard("NDL 出水", ndlText(ndlEnd), Modifier.weight(1f))
            }
            Text(
                "最小安全深度 = 当前算法下的天花板，不能比它更浅；" +
                    "NDL = 继续停留到出现减压义务的时间，「已减压」表示该时刻已背着义务。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 剖面图
            SectionTitle("深度剖面", "含水温曲线与最小安全深度")
            Card {
                Column(Modifier.padding(8.dp)) {
                    DepthProfileChart(series = series)
                    Legend()
                }
            }

            // NDL
            SectionTitle("NDL 剩余", "底部红条 = 该时段已进入减压；虚线是 20 分钟余量参考")
            Card {
                Column(Modifier.padding(8.dp)) {
                    NdlChart(series)
                }
            }

            // GF99
            SectionTitle("GF99 曲线", "超过 100% 即越过原始 M 值线")
            Card {
                Column(Modifier.padding(8.dp)) {
                    Gf99Chart(series)
                }
            }

            // 其他信息
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoLine("开始时间", entry.primary.datetime)
                    InfoLine("水下时长", entry.durationText + if (entry.isMerged) "（含水面间隔）" else "")
                    InfoLine("采样点", "${entry.samples.size}")
                    InfoLine("原始大小", "${entry.records.sumOf { it.bytes }} 字节")
                    if (entry.isMerged) {
                        InfoLine("合并记录", entry.records.joinToString(", ") { "#${it.no}" })
                        if (canUnmerge) {
                            TextButton(onClick = onUnmerge) { Text("拆开这次合并") }
                        } else {
                            Text(
                                "由「自动合并」产生，可在列表的「合并设置」里调小间隔或关闭",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** NDL 秒数 -> 展示文案。 */
private fun ndlText(sec: Int): String = when {
    sec <= 0 -> "已减压"
    sec >= Deco.NDL_CAP -> "99+ 分"
    else -> "${sec / 60} 分"
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem("深度", MaterialTheme.colorScheme.primary)
        LegendItem("水温", Color(0xFFEF6C00))
        LegendItem("最小安全深度", Color(0xFFE53935))
    }
}

@Composable
private fun LegendItem(text: String, color: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("●", color = color, style = MaterialTheme.typography.labelMedium)
        Text(" $text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
