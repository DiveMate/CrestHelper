package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.divelog.app.sync.SyncMode

/**
 * 设置页：把「合并 / 清理 / 减压 / 气体 / 同步」这些**参数**集中到这里。
 *
 * 之前这些散在列表页的两个对话框里，改完就忘、也找不到，
 * 而且清理规则本身是"长期参数"而不是一次性操作，放在列表操作行上不合适。
 *
 * 所有数值项都是**自由输入**（不是档位选择）：间隔、垃圾阈值、GF 两个值、
 * 默认气体比例都能填任意合法值，填错的当场标红且不写入。
 */
@Composable
fun SettingsScreen(
    data: DiveDataSet,
    entries: List<DiveEntry>,
    settings: DiveSettings,
    onSettings: (DiveSettings) -> Unit,
    junkRule: JunkRule,
    onJunkRule: (JunkRule) -> Unit,
    deco: DecoSettings,
    onDeco: (DecoSettings) -> Unit,
    gas: GasSettings,
    onGas: (GasSettings) -> Unit,
    syncMode: SyncMode,
    onSyncMode: (SyncMode) -> Unit,
    pairedName: String?,
    pairedAddress: String?,
    onForgetPaired: () -> Unit,
    deletedCount: Int,
    onOpenCleanup: () -> Unit,
    /** 打开回收站（所有被软删的记录都在里面，用户自己找）。 */
    onOpenRecycle: () -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ------------------------------------------------------------ 合并
        item {
            SettingCard("日志合并", "水面间隔在阈值内的相邻记录视为同一次潜水") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动合并", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (settings.autoMerge) "已开启" else "已关闭：每条原始记录单独显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoMerge,
                        onCheckedChange = { onSettings(settings.copy(autoMerge = it)) },
                    )
                }
                if (settings.autoMerge) {
                    SiField(settings.siThresholdS) { onSettings(settings.copy(siThresholdS = it)) }
                }
                Text(
                    "${data.dives.size} 条原始记录 → ${entries.size} 组" +
                        if (entries.any { it.isMerged }) "（其中 ${entries.count { it.isMerged }} 组是合并的）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 垃圾清理
        item {
            SettingCard("垃圾清理", "误触/试机记录按「时长 + 深度」自动判定；删除只是打标，随时能恢复") {
                var durText by remember(junkRule.maxDurationS) {
                    mutableStateOf(junkRule.maxDurationS.toString())
                }
                var depText by remember(junkRule.maxDepthM) {
                    mutableStateOf(Fmt.input(junkRule.maxDepthM))
                }
                NumField(
                    label = "时长不超过（秒）",
                    text = durText,
                    suffix = "s",
                    valid = durText.toIntOrNull()?.let { it in 0..86400 } == true,
                    hint = "0 ~ 86400",
                    onText = { raw ->
                        durText = raw
                        raw.toIntOrNull()?.takeIf { it in 0..86400 }?.let {
                            onJunkRule(junkRule.copy(maxDurationS = it))
                        }
                    },
                )
                NumField(
                    label = "最深不足（米）",
                    text = depText,
                    suffix = "m",
                    valid = depText.toDoubleOrNull()?.let { it in 0.0..60.0 } == true,
                    hint = "0 ~ 60",
                    onText = { raw ->
                        depText = raw
                        raw.toDoubleOrNull()?.takeIf { it in 0.0..60.0 }?.let {
                            onJunkRule(junkRule.copy(maxDepthM = it))
                        }
                    },
                )
                val hit = entries.count { junkRule.matches(it) }
                Text(
                    "当前规则会命中 $hit 组（配置改完不会自动删，需要手动执行一次清理）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenCleanup) { Text("预览并清理…") }
            }
        }

        // ------------------------------------------------------------ 减压算法
        item {
            SettingCard(
                "减压算法",
                "ZHL-16C + 梯度余量，默认 " +
                    "${DecoSettings.fmt(DecoSettings.DEFAULT.gfLow)}/" +
                    "${DecoSettings.fmt(DecoSettings.DEFAULT.gfHigh)}；要别的值直接填数字",
            ) {
                var lowText by remember(deco.gfLow) { mutableStateOf(DecoSettings.fmt(deco.gfLow)) }
                var highText by remember(deco.gfHigh) { mutableStateOf(DecoSettings.fmt(deco.gfHigh)) }
                val low = lowText.toDoubleOrNull()
                val high = highText.toDoubleOrNull()
                val ok = low != null && high != null && DecoSettings.valid(low, high)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField(
                        label = "GF Low",
                        text = lowText,
                        suffix = "%",
                        valid = low != null && low in DecoSettings.MIN..DecoSettings.MAX,
                        hint = "10 ~ 100",
                        modifier = Modifier.weight(1f),
                        onText = {
                            lowText = it
                            val l = it.toDoubleOrNull()
                            val h = highText.toDoubleOrNull()
                            if (l != null && h != null && DecoSettings.valid(l, h)) {
                                onDeco(deco.copy(gfLow = l, gfHigh = h))
                            }
                        },
                    )
                    NumField(
                        label = "GF High",
                        text = highText,
                        suffix = "%",
                        valid = high != null && high in DecoSettings.MIN..DecoSettings.MAX,
                        hint = "10 ~ 100",
                        modifier = Modifier.weight(1f),
                        onText = {
                            highText = it
                            val l = lowText.toDoubleOrNull()
                            val h = it.toDoubleOrNull()
                            if (l != null && h != null && DecoSettings.valid(l, h)) {
                                onDeco(deco.copy(gfLow = l, gfHigh = h))
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ok) "${deco.algorithm().name} · ${deco.describe()}"
                        else "GF 必须在 10~100 之间，且 Low ≤ High",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onDeco(DecoSettings.DEFAULT) }) { Text("恢复默认") }
                }
            }
        }

        // ------------------------------------------------------------ 气体
        item {
            SettingCard(
                "默认呼吸气体",
                "记录里读不到气体时按它算减压（CR-4 的老记录格式不带气体信息）",
            ) {
                var o2Text by remember(gas.o2) { mutableStateOf(GasSettings.pctText(gas.o2)) }
                var heText by remember(gas.he) { mutableStateOf(GasSettings.pctText(gas.he)) }
                val o2 = GasSettings.pctFromInput(o2Text)
                val he = GasSettings.pctFromInput(heText) ?: 0.0
                val ok = o2 != null && GasSettings.valid(o2, he)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField(
                        label = "氧气 O₂",
                        text = o2Text,
                        suffix = "%",
                        valid = o2 != null && o2 in 0.07..1.0,
                        hint = "7 ~ 100",
                        modifier = Modifier.weight(1f),
                        onText = {
                            o2Text = it
                            val v = GasSettings.pctFromInput(it)
                            val h = GasSettings.pctFromInput(heText) ?: 0.0
                            if (v != null && GasSettings.valid(v, h)) onGas(gas.copy(o2 = v, he = h))
                        },
                    )
                    NumField(
                        label = "氦气 He",
                        text = heText,
                        suffix = "%",
                        valid = he in 0.0..0.95,
                        hint = "休闲潜水填 0",
                        modifier = Modifier.weight(1f),
                        onText = {
                            heText = it
                            val v = GasSettings.pctFromInput(it) ?: 0.0
                            val o = GasSettings.pctFromInput(o2Text)
                            if (o != null && GasSettings.valid(o, v)) onGas(gas.copy(o2 = o, he = v))
                        },
                    )
                }
                Text(
                    if (ok) "当前默认：${gas.label}" +
                        "（高氧会让 NDL 变长，空气会让 NDL 变短，别填错）"
                    else "氧气 7~100%，氦气 0~95%，两者之和 ≤ 100%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    "想让某一次潜水用别气体，在详情页点「气体」直接标注，那条优先于这里的默认值。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 同步
        item {
            SettingCard("手表同步", "默认用哪种模式；同步页里也能就地改（同一份值）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncModeChip("自动", SyncMode.AUTO, syncMode, onSyncMode)
                    SyncModeChip("增量", SyncMode.INCREMENTAL, syncMode, onSyncMode)
                    SyncModeChip("全量", SyncMode.FULL, syncMode, onSyncMode)
                }
                Text(
                    when (syncMode) {
                        SyncMode.AUTO -> "自动：能增量就增量（用手表最新编号 - 本地最大编号），锚点对不上才退回全量。"
                        SyncMode.INCREMENTAL -> "增量：只补新的几条，最快；锚点校验不过会自动转全量并提示原因。"
                        SyncMode.FULL -> "全量：逐条核对指纹再决定下载，最慢但最稳，日志被清空重排后必须用。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已配对手表", style = MaterialTheme.typography.bodySmall)
                        Text(
                            pairedAddress?.let { "${pairedName ?: "CR-4"} · $it" }
                                ?: "还没配对（在「设备」页扫描）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pairedAddress != null) {
                        TextButton(onClick = onForgetPaired) { Text("解除") }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 数据完整性
        item {
            SettingCard(
                "数据完整性",
                "所有清理、删除、合并都只写标记位；原始字节和原始记录永远留在库里",
            ) {
                KvLine("原始记录", "${data.dives.size} 条")
                KvLine("有效展示", "${entries.size} 组")
                KvLine("已删除（软）", "${data.deletedCount} 条")
                KvLine("被过滤标记", "${data.filteredCount} 条")
                KvLine("被合并标记", "${data.mergedCount} 条")
                KvLine("已收藏", "${data.favoriteCount} 条")
                KvLine("采样点", "${data.totalSamples} 个")
                Text(
                    "删掉的都在「回收站」里，恢复只是清标记，不重新解析；" +
                        "只有设备页的「清空导入的数据」会真正删库，那里有二次确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 回收站
        // 删除是静默的（不弹"已删除/撤销"），所以入口必须显眼且**永远在**——
        // 不按有没有内容来隐藏，否则删完再想找回就找不到入口了。
        item {
            SettingCard(
                "回收站",
                "删掉的记录不会真的消失，都在这里；恢复只是把标记清掉",
            ) {
                Text(
                    if (deletedCount > 0) "里面有 $deletedCount 条等你处理" else "现在是空的",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onOpenRecycle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { Text(if (deletedCount > 0) "打开回收站（$deletedCount）" else "打开回收站") }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SyncModeChip(
    label: String,
    mode: SyncMode,
    current: SyncMode,
    onPick: (SyncMode) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { onPick(mode) },
        label = { Text(label) },
    )
}

/** 合并间隔：自由输入 + 单位切换，复用 [DiveSettings] 的换算与校验。 */
@Composable
private fun SiField(sec: Int, onSec: (Int) -> Unit) {
    var useMinutes by remember { mutableStateOf(DiveSettings.siUseMinutes(sec)) }
    var text by remember(sec, useMinutes) {
        mutableStateOf(DiveSettings.siInputText(sec, useMinutes))
    }
    val parsed = DiveSettings.siFromInput(text, useMinutes)
    val valid = parsed != null && DiveSettings.siValid(parsed)

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NumField(
                label = "水面间隔阈值",
                text = text,
                suffix = if (useMinutes) "分钟" else "秒",
                valid = valid,
                hint = "1 秒 ~ 24 小时",
                modifier = Modifier.weight(1f),
                onText = { raw ->
                    text = raw
                    DiveSettings.siFromInput(raw, useMinutes)
                        ?.takeIf { DiveSettings.siValid(it) }
                        ?.let(onSec)
                },
            )
            Column {
                Text("单位", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !useMinutes,
                        onClick = {
                            if (useMinutes) {
                                DiveSettings.siFromInput(text, true)?.let { s ->
                                    useMinutes = false
                                    text = DiveSettings.siInputText(s, false)
                                }
                            }
                        },
                        label = { Text("秒") },
                    )
                    FilterChip(
                        selected = useMinutes,
                        onClick = {
                            if (!useMinutes) {
                                DiveSettings.siFromInput(text, false)?.let { s ->
                                    useMinutes = true
                                    text = DiveSettings.siInputText(s, true)
                                }
                            }
                        },
                        label = { Text("分钟") },
                    )
                }
            }
        }
        Text(
            if (valid) "当前 = ${DiveSettings.siText(parsed!!)}，阈值越大合并越激进"
            else "请输入 1 秒 ~ 24 小时之间的数值",
            style = MaterialTheme.typography.labelSmall,
            color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}

/** 只接受数字和一个小数点的输入框；非法时标红但不写入。 */
@Composable
private fun NumField(
    label: String,
    text: String,
    suffix: String,
    valid: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    onText: (String) -> Unit,
) {
    key(label) {
        Column(modifier) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                        onText(raw)
                    }
                },
                singleLine = true,
                isError = !valid,
                label = { Text(label) },
                suffix = { Text(suffix) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!valid) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun KvLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
