package com.divelog.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.divelog.app.data.SyncRunRecord
import com.divelog.app.sync.LinkState
import com.divelog.app.sync.SyncMode
import com.divelog.app.sync.WatchHub
import com.divelog.app.sync.WatchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备页：**运行环境 + 数据概况 + 同步入口 + 文件导入**。
 *
 * 真正的同步操作搬到了独立的 [SyncScreen]（点「打开同步页」进入）。
 * 这里只留一张状态卡，让人一眼看到「有没有配对 / 现在连没连 / 上次什么时候同步的」，
 * 不用再在一堆卡片里往下滚找同步按钮。
 */
@Composable
fun DeviceScreen(
    data: DiveDataSet,
    entries: List<DiveEntry>,
    deletedCount: Int,
    importedCount: Int = 0,
    importing: Boolean = false,
    repo: DiveRepository,
    session: WatchSession,
    /** 设置页里选的默认同步模式，只在入口卡上展示。 */
    defaultSyncMode: SyncMode = SyncMode.AUTO,
    pairedAddress: String? = null,
    pairedName: String? = null,
    onOpenSync: () -> Unit = {},
    onImport: () -> Unit = {},
    onClearImported: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val perms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permGranted = remember(refreshTick) {
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    val btEnabled = adapter?.isEnabled == true
    val soLoaded = DiveParser.isAvailable
    val years = remember(entries) {
        val ys = entries.map { it.year }.filter { it > 0 }
        if (ys.isEmpty()) "-" else "${ys.min()} ~ ${ys.max()}"
    }

    // 连接状态跨页保持，这里只是读它来显示
    val linkState by session.state.collectAsState()
    // 同步跑在进程级引擎里，即使人已经退出同步页，这张卡也照样能看到进度
    val eng by WatchHub.engine.state.collectAsState()

    // 上次同步：每次回到本页（或刷新）重读一次
    var lastRun by remember { mutableStateOf<SyncRunRecord?>(null) }
    // 清空是真删库，点之前必须再问一次
    var showClearConfirm by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(refreshTick, importedCount) {
        lastRun = withContext(Dispatchers.IO) {
            runCatching { repo.lastSyncAny() }.getOrNull()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---------------------------------------------------------------- 同步入口
        item {
            InfoCard("手表同步", "配对、增量/全量、手表设置都在这一页里") {
                val linked = linkState is LinkState.Ready
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (val st = linkState) {
                                is LinkState.Ready -> "已连接 ${st.name}"
                                is LinkState.Connecting -> "正在连接…"
                                is LinkState.Failed -> "上次连接失败"
                                else -> if (pairedAddress != null) "已配对：${pairedName ?: "CR-4"}" else "还没配对"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            pairedAddress ?: "扫描一次就能配上，以后直接连",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            lastRun?.let { "上次同步 ${stampOf(it)} · ${runBrief(it)}" } ?: "还没同步过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onOpenSync) {
                        Text(if (eng.running) "看进度" else if (linked || pairedAddress != null) "打开" else "配对手表")
                    }
                }
                if (eng.running) {
                    LinearProgressIndicator(
                        progress = { eng.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        "${eng.line}（后台同步中，可以离开这一页）",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Line("默认模式", modeLabel(defaultSyncMode))
                Line("本地手表记录", "${data.watchCount} 条")
                if (!permGranted || !btEnabled) {
                    Text(
                        when {
                            !permGranted -> "还没给蓝牙权限，进同步页时会提示申请"
                            else -> "蓝牙未开启，先在系统设置里打开"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 数据
        item {
            InfoCard("数据", "APK 内置的真实设备日志 + 手表同步 + 文件导入") {
                Line("原始记录", "${data.dives.size} 条")
                Line("其中手表同步", if (data.watchCount > 0) "${data.watchCount} 条" else "—")
                Line("其中文件导入", if (importedCount > data.watchCount) "${importedCount - data.watchCount} 条" else "—")
                Line("当前展示", "${entries.size} 组" + if (deletedCount > 0) "（已删除 $deletedCount 条）" else "")
                Line("被过滤标记", "${data.filteredCount} 条（垃圾/重复，仍在库里）")
                Line("被合并标记", "${data.mergedCount} 条")
                Line("采样点", "${data.totalSamples}")
                Line("时间跨度", years)
                Line("累计时长", "${Fmt.one(data.totalSeconds / 3600.0)} 小时")
            }
        }

        // ---------------------------------------------------------------- 导入
        item {
            InfoCard("导入日志", "选 CR-4 导出的 .bin 文件，可多选；自动解析并入库，重复文件会跳过") {
                Button(
                    onClick = onImport,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(if (importing) "正在导入…" else "选择文件导入")
                }
            }
        }

        // ---------------------------------------------------------------- 数据维护
        // 这张卡跟蓝牙无关：不配对、不开蓝牙也要能用，所以不放在同步卡里，
        // 也不跟着 importedCount 变化而整卡消失（0 条时按钮置灰即可）。
        item {
            InfoCard("数据维护", "清空会真删库里的行（内置演示数据保留），删了就找不回") {
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = importedCount > 0 && !importing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        if (importedCount > 0) "清空导入的数据（$importedCount 条）"
                        else "没有可清空的数据"
                    )
                }
                Text(
                    "只清手表同步和文件导入的记录（内置数据保留）。这是真删库，" +
                        "回收站里也找不回来，要留的请先导出原始 bin。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // ---------------------------------------------------------------- 环境 + 构建
        // 这两块是排查用的，日常没人看，合并成一张**默认折叠**的卡：
        // 收起时只留一行摘要（系统版本 + 机型 + 原生库是否加载），点开才是明细。
        item {
            var expanded by remember { mutableStateOf(false) }
            // 版本号只在 .so 加载状态变化时取一次，不要放在 if 里（否则每次展开都会重新 JNI 调用）
            val nativeVer = remember(soLoaded) {
                if (soLoaded) runCatching { DiveParser.nativeVersion() }.getOrElse { "调用失败" } else "—"
            }
            InfoCard("运行环境与构建", if (expanded) null else envSummary(soLoaded)) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "收起明细" else "展开明细")
                }
                if (expanded) {
                    Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    Line("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    Line("ABI", Build.SUPPORTED_ABIS.joinToString(", ") { it.substringAfter('-') })
                    Line("蓝牙", when {
                        adapter == null -> "不支持"
                        btEnabled -> "已开启"
                        else -> "已关闭"
                    })
                    Line("权限", if (permGranted) "已授予" else "未授予")
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Line("Kotlin/Compose", "已加载")
                    Line("libdc (.so)", if (soLoaded) "已加载" else "未加载")
                    Line("libdc 版本", nativeVer)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        refreshTick++
                        if (!permGranted) launcher.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (permGranted) "刷新状态" else "申请蓝牙权限")
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空导入的数据？") },
            text = {
                Text(
                    "将从数据库里删除 $importedCount 条手表同步 / 文件导入记录及其采样点，" +
                        "这一步不可撤销，回收站也找不回来。内置数据会保留。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearImported()
                    },
                ) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 折叠时显示的一行摘要：系统版本 + 机型 + 原生库状态。 */
private fun envSummary(soLoaded: Boolean): String =
    "Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · " +
        if (soLoaded) "解析库已加载" else "解析库未加载"

/** 时间戳 -> "MM-dd HH:mm"。 */
private fun stampOf(r: SyncRunRecord): String {
    if (r.startedAt <= 0) return "—"
    val t = java.time.Instant.ofEpochMilli(r.startedAt)
        .atZone(java.time.ZoneId.systemDefault())
    return "%02d-%02d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)
}

/** 一次同步的结果摘要。 */
private fun runBrief(r: SyncRunRecord): String {
    val mode = when (r.mode.uppercase()) {
        "FULL" -> "全量"
        "INCREMENTAL" -> "增量"
        else -> "自动"
    }
    val parts = buildString {
        if (r.added > 0) append("新增 ${r.added}")
        if (r.updated > 0) append(if (isEmpty()) "更新 ${r.updated}" else "，更新 ${r.updated}")
        if (r.skipped > 0) append(if (isEmpty()) "跳过 ${r.skipped}" else "，跳过 ${r.skipped}")
        if (r.failed > 0) append(if (isEmpty()) "失败 ${r.failed}" else "，失败 ${r.failed}")
    }
    return "$mode · " + if (parts.isEmpty()) "无变化" else parts
}

@Composable
private fun InfoCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
