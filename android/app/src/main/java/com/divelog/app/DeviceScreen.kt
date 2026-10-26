package com.divelog.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.divelog.app.sync.Cr4Scanner
import com.divelog.app.sync.LinkState
import com.divelog.app.sync.PlanKind
import com.divelog.app.sync.PlanBundle
import com.divelog.app.sync.SyncMode
import com.divelog.app.sync.SyncPlan
import com.divelog.app.sync.SyncProgress
import com.divelog.app.sync.SyncResult
import com.divelog.app.sync.WatchInfo
import com.divelog.app.sync.WatchSession
import com.divelog.app.sync.WatchSetting
import com.divelog.app.sync.WatchSync
import com.divelog.app.sync.ClockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备页：运行环境 + 数据概况 + **手表同步/设置** + 文件导入。
 *
 * 同步的交互刻意做成「先看计划、再点执行」：
 * 用户点连接后先读到设备信息与同步计划（全量还是增量、为什么），
 * 确认无误再真正下载。手表日志少的时候差别不大，几百条时这一步能省下几分钟。
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
    onImport: () -> Unit = {},
    onClearImported: () -> Unit = {},
    onSyncFinished: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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

    val btManager = ctx.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    val btEnabled = adapter?.isEnabled == true
    val soLoaded = DiveParser.isAvailable
    val years = remember(entries) {
        val ys = entries.map { it.year }.filter { it > 0 }
        if (ys.isEmpty()) "-" else "${ys.min()} ~ ${ys.max()}"
    }

    // ---- 手表同步状态 ----
    val scanner = remember { Cr4Scanner(ctx) }
    val linkState by session.state.collectAsState()
    var devices by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var watchInfo by remember { mutableStateOf<WatchInfo?>(null) }
    var bundle by remember { mutableStateOf<PlanBundle?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SyncProgress?>(null) }
    var result by remember { mutableStateOf<SyncResult?>(null) }
    var settings by remember { mutableStateOf<List<WatchSetting>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var clockMessage by remember { mutableStateOf<String?>(null) }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true
            message = null
            runCatching { scanner.scan(8_000) }
                .onSuccess {
                    devices = it
                    message = if (it.isEmpty()) "没扫到手表。确认表已开机、离手机近一点、蓝牙已开。" else null
                }
                .onFailure { message = it.message }
            scanning = false
        }
    }

    fun connectAndInspect(device: android.bluetooth.BluetoothDevice) {
        scope.launch {
            message = null
            result = null
            settings = emptyList()
            watchInfo = null
            bundle = null
            val res = session.connect(ctx, device)
            val link = res.getOrNull() ?: run {
                message = res.exceptionOrNull()?.message ?: "连接失败"
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val sync = WatchSync(link, repo)
                    val info = sync.info()
                    val plan = sync.makePlan(SyncMode.AUTO)
                    Triple(info, plan, sync.settingsMatrix(info))
                }
            }.onSuccess { (info, plan, matrix) ->
                watchInfo = info
                bundle = plan
                settings = matrix
            }.onFailure {
                message = "读取设备信息失败：${it.message ?: it}"
            }
        }
    }

    fun runSync(mode: SyncMode) {
        val link = session.link ?: return
        if (syncing) return
        scope.launch {
            syncing = true
            progress = null
            result = null
            message = null
            val sync = WatchSync(link, repo)
            runCatching {
                val b = withContext(Dispatchers.IO) { sync.makePlan(mode) }
                bundle = b
                withContext(Dispatchers.IO) { sync.run(b) { progress = it } }
            }.onSuccess {
                result = it
                onSyncFinished()
                // 同步后重新读设备信息，刷新「本地进度」
                runCatching {
                    withContext(Dispatchers.IO) {
                        val info = sync.info()
                        val plan = sync.makePlan(SyncMode.AUTO)
                        Triple(info, plan, sync.settingsMatrix(info))
                    }
                }.onSuccess { (info, plan, matrix) ->
                    watchInfo = info
                    bundle = plan
                    settings = matrix
                }
            }.onFailure {
                message = "同步失败：${it.message ?: it}"
            }
            syncing = false
            progress = null
        }
    }

    fun syncClock() {
        val link = session.link ?: return
        scope.launch {
            clockMessage = null
            val r = withContext(Dispatchers.IO) { WatchSync(link, repo).setClock() }
            clockMessage = when (r) {
                is ClockResult.Ok -> "已下发 ${"%04d-%02d-%02d %02d:%02d:%02d".format(
                    r.at.year, r.at.monthValue, r.at.dayOfMonth, r.at.hour, r.at.minute, r.at.second,
                )}"
                is ClockResult.Failed -> "失败：${r.reason}"
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
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

        // ---------------------------------------------------------------- 同步
        item {
            InfoCard("手表同步", "增量：用手表最新编号与本地最大编号对差；全量：逐条核对指纹") {
                val st = linkState
                when (st) {
                    is LinkState.Ready -> {
                        Line("连接", st.name)
                        watchInfo?.let { Line("手表档案", deviceLine(it)) }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        bundle?.let { b -> PlanCard(b.plan) }

                        if (syncing) {
                            val p = progress
                            LinearProgressIndicator(
                                progress = { p?.fraction ?: 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                            Text(
                                p?.let { "第 ${it.current}/${it.total} 条 · #${it.index} · ${it.stage}" }
                                    ?: "准备中…",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { runSync(SyncMode.AUTO) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("同步（自动）") }
                                OutlinedButton(
                                    onClick = { runSync(SyncMode.FULL) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("全量") }
                            }
                        }

                        result?.let {
                            Text(
                                it.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (it.errors.isNotEmpty()) {
                                Text(
                                    it.errors.joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                scope.launch {
                                    session.disconnect()
                                    watchInfo = null
                                    bundle = null
                                    settings = emptyList()
                                    result = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("断开连接") }
                    }

                    is LinkState.Connecting -> {
                        Text("正在连接…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }

                    is LinkState.Failed -> {
                        Text("连接失败：${st.reason}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Button(onClick = { scan() }, enabled = !scanning) {
                            Text(if (scanning) "扫描中…" else "重新扫描")
                        }
                    }

                    else -> {
                        Text(
                            if (permGranted) "点下面扫描附近的手表（广播名 Crest-CR4）"
                            else "需要蓝牙权限才能扫描",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { if (permGranted) scan() else launcher.launch(perms.toTypedArray()) },
                            enabled = !scanning && btEnabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) { Text(if (scanning) "扫描中…（8 秒）" else "扫描手表") }

                        if (devices.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            devices.forEach { d ->
                                val name = runCatching { d.device.name }.getOrNull() ?: "未知设备"
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${d.device.address} · ${d.rssi} dBm",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { connectAndInspect(d.device) }) { Text("连接") }
                                }
                            }
                        }
                    }
                }

                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!btEnabled) {
                    Text(
                        "蓝牙未开启，先在系统设置里打开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 设置
        if (settings.isNotEmpty()) {
            item {
                InfoCard("手表设置", "协议层能写的只有时间；其余项固件没开放写命令，如实标注") {
                    settings.forEach { s ->
                        SettingRow(s)
                    }
                    OutlinedButton(
                        onClick = { syncClock() },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("把本机时间写到手表") }
                    clockMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
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
                if (importedCount > 0) {
                    TextButton(onClick = onClearImported, modifier = Modifier.fillMaxWidth()) {
                        Text("清空导入的 $importedCount 条（含手表同步）")
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 环境
        item {
            InfoCard("运行环境", null) {
                Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                Line("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                Line("ABI", Build.SUPPORTED_ABIS.joinToString(", ") { it.substringAfter('-') })
                Line("蓝牙", when {
                    adapter == null -> "不支持"
                    btEnabled -> "已开启"
                    else -> "已关闭"
                })
                Line("权限", if (permGranted) "已授予" else "未授予")
            }
        }
        item {
            InfoCard("构建状态", null) {
                Line("Kotlin/Compose", "已加载")
                Line("libdc (.so)", if (soLoaded) "已加载" else "未加载")
                Line(
                    "libdc 版本",
                    if (soLoaded) {
                        remember { runCatching { DiveParser.nativeVersion() }.getOrElse { "调用失败" } }
                    } else "—"
                )
            }
        }
        item {
            Button(
                onClick = {
                    refreshTick++
                    if (!permGranted) launcher.launch(perms.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (permGranted) "刷新状态" else "请求 BLE 权限")
            }
        }
    }
}

/** 同步计划的展示：为什么走全量/增量，要下多少条。 */
@Composable
private fun PlanCard(plan: SyncPlan) {
    val (title, color) = when {
        plan.kind == PlanKind.UP_TO_DATE ->
            "已是最新" to MaterialTheme.colorScheme.onSurfaceVariant
        plan.kind == PlanKind.INCREMENTAL ->
            "增量：${plan.steps} 条" to MaterialTheme.colorScheme.primary
        plan.fallback -> "降级全量：${plan.steps} 条" to MaterialTheme.colorScheme.error
        else -> "全量：${plan.steps} 条" to MaterialTheme.colorScheme.primary
    }
    Text(title, style = MaterialTheme.typography.titleSmall, color = color)
    Text(
        plan.reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(s: WatchSetting) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                s.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (s.supported) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    !s.supported -> "不可写"
                    s.value != null -> s.value
                    else -> "—"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.supported) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        s.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行展示设备档案：条数 / 最新编号 / 序列号。 */
private fun deviceLine(info: WatchInfo): String =
    "${info.ndives} 条 · 最新 #${info.lastIndex} · ${info.deviceId}"

@Composable
private fun InfoCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
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
