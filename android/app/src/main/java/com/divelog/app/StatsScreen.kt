package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 统计页：年度 / 深度 / 时长 / 月度分布（跟随合并与清理后的当前视图）。 */
@Composable
fun StatsScreen(entries: List<DiveEntry>) {
    val years = remember(entries) { Analytics.byYearOf(entries.map { it.year }) }
    val depthBuckets = remember(entries) { Analytics.depthHistogramOf(entries.map { it.maxDepthM }) }
    val durBuckets = remember(entries) { Analytics.durationHistogramOf(entries.map { it.durationS }) }
    val latestYear = remember(entries) { entries.maxOfOrNull { it.year } ?: 0 }
    val monthly = remember(entries, latestYear) {
        Analytics.monthlyOf(entries.map { it.year to it.month }, latestYear)
    }
    val hours = entries.sumOf { it.durationS.toLong() } / 3600.0

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ChartCard("年度潜次", "${entries.size} 组 · 累计 ${Fmt.one(hours)} 小时") {
                BarChart(
                    labels = years.map { "${it.first}" },
                    values = years.map { it.second },
                )
            }
        }
        item {
            ChartCard("深度分布", "按单次最大深度统计") {
                BarChart(
                    labels = depthBuckets.map { it.first },
                    values = depthBuckets.map { it.second },
                )
            }
        }
        item {
            ChartCard("时长分布", "按单次总时长统计") {
                BarChart(
                    labels = durBuckets.map { it.first },
                    values = durBuckets.map { it.second },
                )
            }
        }
        item {
            ChartCard("$latestYear 年月度", "最近一年的潜水节奏") {
                BarChart(
                    labels = (1..12).map { "$it" },
                    values = monthly.toList(),
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
