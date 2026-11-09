package com.divelog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.divelog.app.data.DiveAnnotation
import com.divelog.app.data.DiveTag
import com.divelog.app.data.GasSource
import com.divelog.app.data.SuitKind
import kotlin.math.abs

/**
 * 详情页：单次潜水的完整复盘。
 *
 * 指标：最大/平均深度、最低/最高水温、GF99、最小安全深度、NDL；
 * 图：深度剖面（叠加水温与最小安全深度）、NDL 剩余、GF99；
 * 算法切换会重新积分整条剖面（数值与 deco.py 一致，见 DecoTest）。
 *
 * 剖面**不从这里查库**：由调用方把 [SampleCache] 的快照传进来。
 * 缓存是分块载入的，所以先进页面时 [samples] 里可能还没有这条 ——
 * 这时指标卡照常显示（走摘要），图区显示「剖面载入中」，
 * 等后台把这条补进来（[SampleCache.ensure]）后自动换成真图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveDetailScreen(
    entry: DiveEntry,
    deco: DecoSettings,
    onDeco: (DecoSettings) -> Unit,
    onGas: (Double?, Double?) -> Unit,
    onBack: () -> Unit,
    canUnmerge: Boolean = false,
    onUnmerge: () -> Unit = {},
    /** 用户补充的简要信息（潜点 / 能见度 / 穿着 / 类型标签）。 */
    onAnnotation: (DiveAnnotation) -> Unit = {},
    /** 剖面快照（file -> 采样点）。缺失表示还没载入，不是"没有剖面"。 */
    samples: Map<String, List<Sample>> = emptyMap(),
    /** 后台是否还在补剖面，用于把「载入中」和「确实没有剖面」区分开。 */
    loading: Boolean = false,
) {
    // 合并组要按真实时间轴拼剖面（中间补水面段），单条直接用
    val profile = remember(entry.id, samples) {
        if (entry.records.size == 1) samples[entry.records[0].file] ?: entry.samples
        else DiveEntries.mergeSamples(entry.records, entry.startMillis) { samples[it.file] ?: emptyList() }
    }
    val algo = deco.algorithm()
    val series = remember(entry.id, deco, entry.gas, profile) { Deco.series(profile, algo, entry.gas) }
    val analysis = remember(entry.id, deco, entry.gas, profile) {
        Deco.analyzeProfile(profile.map { it.t to it.depth }, algo, entry.gas)
    }
    val pending = profile.isEmpty() && (entry.primary.nsamples > 0 || loading)
    val ndlMin = series.minOfOrNull { it.ndl } ?: 0
    val ndlEnd = series.lastOrNull()?.ndl ?: 0
    val maxTemp = series.mapNotNull { it.temp }.maxOrNull() ?: entry.tempMaxC
    val minTemp = series.mapNotNull { it.temp }.minOrNull() ?: entry.tempMinC
    var showGas by remember(entry.id) { mutableStateOf(false) }
    var showGf by remember(entry.id) { mutableStateOf(false) }
    var showInfo by remember(entry.id) { mutableStateOf(false) }

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
            // 编号与时间：四格等宽，时间降到 13sp（360dp 屏每格只有 78dp）
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricCard("编号", "${entry.primary.no}", Modifier.weight(1f))
                MetricCard("开始时间", entry.timeText, Modifier.weight(1f), compact = true)
                MetricCard(
                    "结束时间",
                    Fmt.clock(entry.startMillis + entry.durationS * 1000L),
                    Modifier.weight(1f),
                    compact = true,
                )
                MetricCard("时长", entry.durationText, Modifier.weight(1f))
            }
            // 这一潜的数字：深度与水温
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricCard("最大深度", Fmt.one(entry.maxDepthM), Modifier.weight(1f), unit = "m")
                MetricCard("平均深度", Fmt.one(entry.avgDepthM), Modifier.weight(1f), unit = "m")
                MetricCard(
                    "最低水温",
                    if (minTemp != null) Fmt.one(minTemp) else "—",
                    Modifier.weight(1f),
                    unit = if (minTemp != null) "°C" else null,
                )
                MetricCard(
                    "最高水温",
                    if (maxTemp != null) Fmt.one(maxTemp) else "—",
                    Modifier.weight(1f),
                    unit = if (maxTemp != null) "°C" else null,
                )
            }

            // 合并记录：拆开入口只在这类记录上出现，普通记录不占位置
            if (entry.isMerged) {
                MergeHintBar(entry.mergedCount, canUnmerge, onUnmerge)
            }

            // 气体与算法同栏：各报一个值，各自带自己的按钮。
            // 气体不再写「这条记录没有气体信息」之类的解释 —— CR-4 老格式本来就不带 FO₂，
            // 每条都提示一遍等于噪音，要标的时候自然会点「标注」。
            GasDecoCard(
                gasText = entry.gasLabel.substringBefore(" · "),
                nitrox = entry.isNitrox,
                gfLow = deco.gfLow,
                gfHigh = deco.gfHigh,
                onGas = { showGas = true },
                onDeco = { showGf = true },
            )

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
                // 单位放进 label：值写成 "11.2 m/min" 会在这张三等分卡片里折行
                MetricCard("上升速度", "${Fmt.one(analysis.ascentMax)} m/min", Modifier.weight(1f), dense = true)
                MetricCard("NDL 最低", ndlText(ndlMin), Modifier.weight(1f))
                MetricCard("NDL 出水", ndlText(ndlEnd), Modifier.weight(1f))
            }
            Text(
                "最小安全深度 = 当前算法下的天花板，不能比它更浅；" +
                    "NDL = 继续停留到出现减压义务的时间，「已减压」表示该时刻已背着义务；" +
                    "GF99 = 此刻直接升到水面的过饱和度，>100% 即越过原始 M 值线。" +
                    "整条潜水按单一气体（${entry.gasLabel.substringBefore(" · ")}）计算，中途换气暂未计入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 用户补充的简要信息：手表和 .bin 里都读不到，只能自己记。
            // 放在剖面图**上面**——先看"这是哪次潜水"，再往下才是曲线。
            AnnotationCard(
                annotation = entry.annotation,
                onEdit = { showInfo = true },
            )

            if (pending) {
                // 摘要已经够画指标卡，但剖面还在后台补；不要拿空剖面画一张假图
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "正在载入这条的剖面…",
                            Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
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
            }

            // 「其他信息」卡已拆掉：开始时间进第一栏，结束时间由开始 + 时长算出，
            // 水下时长跟第一栏的「时长」重复，采样点与原始大小属于排查用的内部量，
            // 合并组的关系改由上方那条提示条表达。
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showGas) {
        GasDialog(
            current = entry.gas,
            fromRecord = entry.gasSource == GasSource.RECORD,
            onPick = { o2, he ->
                onGas(o2, he)
                showGas = false
            },
            onDismiss = { showGas = false },
        )
    }

    if (showGf) {
        GfDialog(
            deco = deco,
            onChange = onDeco,
            onDismiss = { showGf = false },
        )
    }

    if (showInfo) {
        AnnotationDialog(
            current = entry.annotation,
            onSave = {
                onAnnotation(it)
                showInfo = false
            },
            onDismiss = { showInfo = false },
        )
    }
}

/**
 * 「潜水信息」卡：只读展示已经填过的项，没填就提示一句。
 *
 * 为什么不直接放输入框：详情页本身是个长滚动列表，常驻四个输入框既占地方，
 * 又会在每次重组时把焦点抢回去；点「填写/编辑」进对话框一次性提交更稳。
 */
@Composable
private fun AnnotationCard(annotation: DiveAnnotation, onEdit: () -> Unit) {
    val site = annotation.siteName?.trim()?.takeIf { it.isNotEmpty() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "潜水信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!annotation.isNotEmpty) {
                    Text(
                        "潜点、能见度、穿着和类型都可以自己补，手表里没有这些",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (site != null) InfoLine("潜点", site)
                    annotation.visibilityM?.let { InfoLine("能见度", "${Fmt.one(it)} m") }
                    annotation.suit?.let { InfoLine("穿着", it.label) }
                    if (annotation.tags.isNotEmpty()) {
                        InfoLine("类型", annotation.tags.sortedBy { it.ordinal }.joinToString(" · ") { it.label })
                    }
                }
            }
            TextButton(onClick = onEdit) {
                Text(if (annotation.isNotEmpty) "编辑" else "填写")
            }
        }
    }
}

/**
 * 填信息：潜点、能见度、湿衣/干衣、类型标签（多选）。
 *
 * 一次提交整块内容 —— 取消就什么都不写，避免"改了一半"的半成品记录。
 * 能见度允许留空（很多人记不住），填了就必须是 0~100 米之间的数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationDialog(
    current: DiveAnnotation,
    onSave: (DiveAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var site by remember { mutableStateOf(current.siteName ?: "") }
    var visText by remember { mutableStateOf(current.visibilityM?.let { Fmt.one(it) } ?: "") }
    var suit by remember { mutableStateOf(current.suit) }
    var tags by remember { mutableStateOf(current.tags) }

    val vis = if (visText.isBlank()) null else visText.toDoubleOrNull()
    val visBad = visText.isNotBlank() && (vis == null || vis < 0 || vis > 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("潜水信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = site,
                    onValueChange = { if (it.length <= 40) site = it },
                    label = { Text("潜点") },
                    placeholder = { Text("比如：三亚 蜈支洲岛") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = visText,
                    onValueChange = { v ->
                        if (v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) visText = v
                    },
                    label = { Text("能见度") },
                    suffix = { Text("m") },
                    singleLine = true,
                    isError = visBad,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visBad) {
                    Text(
                        "填 0~100 之间的数字，或者留空",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text("穿着", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SuitKind.entries.forEach { k ->
                        FilterChip(
                            selected = suit == k,
                            onClick = { suit = if (suit == k) null else k },
                            label = { Text(k.label) },
                        )
                    }
                }

                Text("类型（可多选）", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DiveTag.entries.forEach { t ->
                        FilterChip(
                            // 海水/淡水互斥由 DiveTag.toggle 处理，这里只管渲染
                            selected = t in tags,
                            onClick = { tags = DiveTag.toggle(tags, t) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Text(
                    "海水和淡水只能选一个；沙地、珊瑚礁、峭壁可以同时成立。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !visBad,
                onClick = {
                    onSave(
                        DiveAnnotation(
                            siteName = site.trim().takeIf { it.isNotEmpty() },
                            visibilityM = vis,
                            suit = suit,
                            tags = tags,
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        // 清空 = 回到"没填过"，不动原始记录
                        site = ""
                        visText = ""
                        suit = null
                        tags = emptySet()
                        onSave(DiveAnnotation())
                    },
                ) { Text("清空") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 气体标注：直接填 O₂ / He 百分比。
 *
 * 常见的几档做成快捷入口（空气、EAN32、EAN36、Tx 21/35），
 * 但主体是自由输入 —— 高氧比例本来就可能是 31.5% 这种非整数。
 */
@Composable
private fun GasDialog(
    current: Deco.Gas,
    fromRecord: Boolean,
    onPick: (Double?, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var o2Text by remember { mutableStateOf(GasSettings.pctText(current.o2)) }
    var heText by remember { mutableStateOf(GasSettings.pctText(current.he)) }
    val o2 = GasSettings.pctFromInput(o2Text)
    val he = GasSettings.pctFromInput(heText) ?: 0.0
    val valid = o2 != null && GasSettings.valid(o2, he)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标注这次潜水的气体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromRecord) {
                    Text(
                        "当前值来自原始记录；手动改会覆盖它，让这条按你填的算。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = o2Text,
                        onValueChange = { if (it.all { c -> c.isDigit() || c == '.' } && it.count { c -> c == '.' } <= 1) o2Text = it },
                        label = { Text("氧气 O₂") },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = o2 == null || o2 !in 0.07..1.0,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heText,
                        onValueChange = { if (it.all { c -> c.isDigit() || c == '.' } && it.count { c -> c == '.' } <= 1) heText = it },
                        label = { Text("氦气 He") },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = he !in 0.0..0.95,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "空气" to (0.21 to 0.0),
                        "EAN32" to (0.32 to 0.0),
                        "EAN36" to (0.36 to 0.0),
                        "Tx 21/35" to (0.21 to 0.35),
                    ).forEach { (label, pair) ->
                        FilterChip(
                            selected = o2 != null && abs((o2 ?: 0.0) - pair.first) < 1e-6 && abs(he - pair.second) < 1e-6,
                            onClick = {
                                o2Text = GasSettings.pctText(pair.first)
                                heText = GasSettings.pctText(pair.second)
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    if (valid) "按 ${gasName(Deco.Gas(o2!!, he))} 重算 NDL 与安全深度"
                    else "氧气 7~100%，氦气 0~95%，两者之和不能超过 100%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPick(o2, he) }) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null, null) }) { Text("跟随记录") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** GF 就地调整：两个数字，合法才写回（跟设置页共享同一份持久化值）。 */
@Composable
private fun GfDialog(
    deco: DecoSettings,
    onChange: (DecoSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var lowText by remember { mutableStateOf(DecoSettings.fmt(deco.gfLow)) }
    var highText by remember { mutableStateOf(DecoSettings.fmt(deco.gfHigh)) }
    val low = lowText.toDoubleOrNull()
    val high = highText.toDoubleOrNull()
    val valid = low != null && high != null && DecoSettings.valid(low, high)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("梯度余量 GF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lowText,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) lowText = it },
                        label = { Text("GF Low") },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = low == null || low !in DecoSettings.MIN..DecoSettings.MAX,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = highText,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) highText = it },
                        label = { Text("GF High") },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = high == null || high !in DecoSettings.MIN..DecoSettings.MAX,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (valid) DecoSettings(low!!, high).describe()
                    else "两个值都要在 10~100 之间，且 Low ≤ High",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "默认 ${DecoSettings.fmt(DecoSettings.DEFAULT.gfLow)}/" +
                        "${DecoSettings.fmt(DecoSettings.DEFAULT.gfHigh)}；" +
                        "GF 越小越保守，NDL 越短、天花板越深。改完所有页面一起变。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onChange(DecoSettings(low!!, high!!))
                    onDismiss()
                },
            ) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onChange(DecoSettings.DEFAULT); onDismiss() }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** 带单位的长值（如 "11.2 m/min"）在三分之一宽的卡里会折行，用小一号字。 */
    dense: Boolean = false,
    /** 秒级时间（"18:23:43"）在四等分卡片里用 16sp 放不下，降到 13sp。 */
    compact: Boolean = false,
    /** 单位单独用小一号字，免得 "22.11 m" 把四等分卡片撑爆。 */
    unit: String? = null,
) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = when {
                        compact -> MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp)
                        dense -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (unit != null) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 1.dp, bottom = 1.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 气体 + 减压算法同栏：左边气体、右边算法，各自带自己的入口。
 *
 * 两列不等宽：气体那半只放「空气 / EAN32」和一个小按钮，106dp 足够；
 * 让出来的宽度给算法那半（ZHL-16C + 30/85 + 配置），360dp 屏上才不挤。
 */
@Composable
private fun GasDecoCard(
    gasText: String,
    nitrox: Boolean,
    gfLow: Double,
    gfHigh: Double,
    onGas: () -> Unit,
    onDeco: () -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(Modifier.width(106.dp)) {
                Text(
                    "气体",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        gasText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        // 高氧只染这个值，卡底保持白色，免得把右边算法也一起染色
                        color = if (nitrox) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    MiniAction("标注", onGas, Modifier.padding(start = 6.dp))
                }
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = 1.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(
                    "减压算法",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ZHL-16C",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        "${DecoSettings.fmt(gfLow)}/${DecoSettings.fmt(gfHigh)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                        maxLines = 1,
                    )
                    MiniAction("配置", onDeco, Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** 合并记录的提示条：普通记录不显示，不占位置。 */
@Composable
private fun MergeHintBar(count: Int, canUnmerge: Boolean, onUnmerge: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "由 $count 条记录合并 · 时长含水面间隔",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (canUnmerge) {
                MiniAction("拆开", onUnmerge, arrow = false)
            } else {
                Text(
                    "自动合并",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 卡片内的小号文字按钮。
 *
 * 不用 TextButton：它自带 48dp 最小高度，会把整张卡顶高约 10dp。
 * 这里视觉高度约 22dp，靠外围 padding 让点击区域不至于太小。
 */
@Composable
private fun MiniAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            if (arrow) "$text ›" else text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
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
