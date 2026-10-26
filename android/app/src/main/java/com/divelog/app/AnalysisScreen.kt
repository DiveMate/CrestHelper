package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分析页：数据治理 + 多算法对拍 + 隐藏风险。
 *
 * 规则与桌面端 tools/analyze.py 一致：
 *  - 合并：水面间隔 <= 600s 视为同一次潜水（253 条 -> 198 组）
 *  - 无效：组内任一记录浅于 2m 或短于 60s（198 -> 185 有效）
 *  - 对拍：每个算法档位算"有减压义务"的潜次数
 *  - 隐藏风险：ZHL-16C 无义务、但保守算法（GF30/85 或 GF50/70）有义务
 */

/** 一个合并组的结果。 */
data class GroupAnalysis(
    val main: Dive,
    val group: List<Dive>,
    val durationS: Int,
    val maxDepth: Double,
    val results: Map<String, Deco.ProfileAnalysis>,
) {
    fun res(id: String): Deco.ProfileAnalysis? = results[id]
}

data class AnalysisResult(
    val raw: Int,
    val groups: Int,
    val invalid: Int,
    val validGroups: List<GroupAnalysis>,
    val algoCounts: List<Pair<Deco.Algorithm, Int>>,
    val hidden: List<GroupAnalysis>,
    val gf99Worst: Double,
    val siThresholdS: Int = DiveMerger.DEFAULT_SI_THRESHOLD,
    val excluded: Int = 0,
)

object Analysis {

    /**
     * @param siThreshold 自动合并的水面间隔阈值（与列表页设置联动）
     * @param excluded    被用户清理掉的记录（不参与分析）
     */
    fun compute(
        data: DiveDataSet,
        siThreshold: Int = DiveMerger.DEFAULT_SI_THRESHOLD,
        excluded: Set<String> = emptySet(),
    ): AnalysisResult {
        val dives = data.dives.filter { it.file !in excluded }
        val groups = Analytics.mergeGroups(dives, siThreshold)
        val valid = ArrayList<GroupAnalysis>()

        for (g in groups) {
            // 与 analyze.py 一致：任一成员浅/短，或合并后仍浅/短 → 整组无效
            val anyBad = g.any { Analytics.invalidFlags(it).isNotEmpty() }
            val maxD = g.maxOf { it.maxdepthM }
            val first = g.minByOrNull { Analytics.startMillis(it) } ?: g.first()
            val last = g.maxByOrNull { Analytics.startMillis(it) } ?: g.last()
            val span = ((Analytics.startMillis(last) + last.divetimeS * 1000L -
                Analytics.startMillis(first)) / 1000L).toInt()
            if (anyBad || maxD < DiveMerger.DEFAULT_MIN_DEPTH || span < DiveMerger.MIN_DURATION_S) continue

            val records = g.map { d ->
                DiveMerger.DiveRecord(
                    id = d.no,
                    startMillis = Analytics.startMillis(d),
                    durationSec = d.divetimeS,
                    maxDepth = d.maxdepthM,
                    samples = data.samplesOf(d).map { Triple(it.t, it.depth, it.temp) },
                )
            }
            val merged = DiveMerger.mergeGroup(records, mergedId = first.no) ?: continue
            if (merged.profile.isEmpty()) continue

            val pts = merged.profile.map { it.first to it.second }
            val results = Deco.ALGORITHMS.associate { a -> a.id to Deco.analyzeProfile(pts, a) }
            valid.add(
                GroupAnalysis(
                    main = g.maxByOrNull { it.maxdepthM } ?: first,
                    group = g,
                    durationS = merged.durationSec,
                    maxDepth = merged.maxDepth,
                    results = results,
                )
            )
        }

        val algoCounts = Deco.ALGORITHMS.map { a ->
            a to valid.count { g -> g.res(a.id)?.decoDuty == true }
        }
        val hidden = valid.filter { g ->
            g.res("zhl16c")?.decoDuty == false &&
                (g.res("gf50_70")?.decoDuty == true || g.res("gf30_85")?.decoDuty == true)
        }.sortedByDescending { g -> g.res("gf30_85")?.ceilingMax ?: 0.0 }

        return AnalysisResult(
            raw = dives.size,
            groups = groups.size,
            invalid = groups.size - valid.size,
            validGroups = valid,
            algoCounts = algoCounts,
            hidden = hidden,
            gf99Worst = valid.maxOfOrNull { g -> g.res("zhl16c")?.gf99Max ?: 0.0 } ?: 0.0,
            siThresholdS = siThreshold,
            excluded = excluded.size,
        )
    }
}

@Composable
fun AnalysisScreen(
    data: DiveDataSet,
    settings: DiveSettings,
    excluded: Set<String>,
    onOpen: (Dive) -> Unit,
) {
    var result by remember(data) { mutableStateOf<AnalysisResult?>(null) }
    LaunchedEffect(data, settings, excluded) {
        result = null
        result = withContext(Dispatchers.Default) {
            Analysis.compute(data, settings.siThresholdS, excluded)
        }
    }

    val r = result
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在计算…", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { GovernanceCard(r) }
        item { AlgoCompareCard(r) }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("隐藏风险", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "ZHL-16C 原始算法下无减压义务、但保守设置下有义务的潜次（共 ${r.hidden.size} 条）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(r.hidden.take(10), key = { it.main.file }) { g ->
            Card(onClick = { onOpen(g.main) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(g.main.dateText, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Fmt.one(g.maxDepth)} m · ${Fmt.duration(g.durationS)} · " +
                                if (g.group.size > 1) "${g.group.size} 条合并" else "单条记录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("GF30/85", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${Fmt.one(g.res("gf30_85")?.ceilingMax ?: 0.0)} m",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun GovernanceCard(r: AnalysisResult) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("数据治理", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GovItem("原始记录", "${r.raw}")
                GovItem("合并后", "${r.groups} 组")
                GovItem("无效", "${r.invalid}")
                GovItem("有效", "${r.validGroups.size}")
            }
            HorizontalDivider()
            Text(
                "合并规则：水面间隔 ≤ ${r.siThresholdS} 秒视为同一次潜水；无效：浅于 2 m 或短于 60 秒。" +
                    if (r.excluded > 0) "已清理 ${r.excluded} 条记录（不计入上表）。" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GovItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlgoCompareCard(r: AnalysisResult) {
    val maxCount = (r.algoCounts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("多算法对拍", style = MaterialTheme.typography.titleSmall)
            Text(
                "同一批潜次在不同保守度下的减压义务（共 ${r.validGroups.size} 组有效）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.algoCounts.forEach { (algo, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        algo.name,
                        Modifier.width(104.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(16.dp)
                            .padding(horizontal = 4.dp),
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val frac = n.toFloat() / maxCount
                            drawRect(
                                color = Color(0xFF5E35B1),
                                size = androidx.compose.ui.geometry.Size(size.width * frac, size.height),
                            )
                        }
                    }
                    Text("$n 条", Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "差异越大，说明越依赖算法设置——原厂 App 只给一套结论，这里可以自己看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
