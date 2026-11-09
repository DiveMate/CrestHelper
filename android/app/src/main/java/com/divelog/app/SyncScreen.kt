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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.divelog.app.data.SyncRunRecord
import com.divelog.app.sync.ClockResult
import com.divelog.app.sync.Cr4Scanner
import com.divelog.app.sync.LinkState
import com.divelog.app.sync.PlanBundle
import com.divelog.app.sync.PlanKind
import com.divelog.app.sync.SyncMode
import com.divelog.app.sync.SyncPlan
import com.divelog.app.sync.SyncProgress
import com.divelog.app.sync.SyncResult
import com.divelog.app.sync.WatchInfo
import com.divelog.app.sync.WatchSession
import com.divelog.app.sync.WatchSetting
import com.divelog.app.sync.WatchHub
import com.divelog.app.sync.WatchSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同步页：**独立一页**，从设备页点进去。
 *
 * 之前同步的所有控件都堆在设备页的卡片流里，跟"数据概况 / 运行环境"混在一起，
 * 打开时得往下滚才看得见，蓝牙没开时更是一整块灰按钮。
 * 拆出来的好处是：这一页只干一件事 —— 连表、看计划、下数据、写时间。
 *
 * 交互刻意做成「先看计划、再点执行」：连上后先读设备信息与同步计划
 * （全量还是增量、为什么），确认无误再真正下载。手表日志几百条时，
 * 这一步能省下几分钟的无效下载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    data: DiveDataSet,
    repo: DiveRepository,
    session: WatchSession,
    /** 设置页里选的默认模式；这里可以就地改，改完写回设置。 */
    syncMode: SyncMode,
    onSyncMode: (SyncMode) -> Unit,
    pairedAddress: String?,
    pairedName: String?,
    onPaired: (address: String, name: String) -> Unit,
    onForgetPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val perms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    // 通知权限只影响"后台同步时能不能看到进度通知"，不拦同步本身，所以单独要
    val notifPerm = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 拒了也能同步，只是锁屏时看不到进度 */ }
    var permTick by remember { mutableStateOf(0) }
    val permGranted = remember(permTick) {
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permTick++ }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val btEnabled = adapter?.isEnabled == true

    val scanner = remember { Cr4Scanner(ctx) }
    val linkState by session.state.collectAsState()

    var devices by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var watchInfo by remember { mutableStateOf<WatchInfo?>(null) }
    var bundle by remember { mutableStateOf<PlanBundle?>(null) }
    // 同步本身跑在进程级的 SyncEngine 里：这一页只是"显示器"，
    // 中途退出/锁屏/切后台都不影响后台继续下载（前台服务 + 唤醒锁）。
    val eng by WatchHub.engine.state.collectAsState()
    val syncing = eng.running
    val progress = eng.progress
    val result = eng.result
    var settings by remember { mutableStateOf<List<WatchSetting>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var clockMessage by remember { mutableStateOf<String?>(null) }
    // 配对成功后弹「要不要现在同步」——不让它默认就跑，几百条表能跑几分钟
    var showSyncPrompt by remember { mutableStateOf(false) }
    // 上次同步（任意手表），入场读一次、同步完再读一次
    var lastRun by remember { mutableStateOf<SyncRunRecord?>(null) }
    var seenTick by remember { mutableIntStateOf(eng.finishedTick) }

    suspend fun refreshLastRun() {
        lastRun = withContext(Dispatchers.IO) { runCatching { repo.lastSyncAny() }.getOrNull() }
    }
    LaunchedEffect(Unit) { refreshLastRun() }

    /**
     * 同步在别的线程/别的页面里跑完时，这里负责把"读出来的一次性信息"刷新一遍：
     * 上次同步记录、设备档案、本地进度、设置矩阵。
     * 用 finishedTick 当信号，重复 enter/leave 也不会重复触发。
     */
    LaunchedEffect(eng.finishedTick) {
        if (eng.finishedTick == seenTick) return@LaunchedEffect
        seenTick = eng.finishedTick
        refreshLastRun()
        val link = session.link ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                val sync = WatchSync(link, repo)
                val info = sync.info()
                Triple(info, sync.makePlan(syncMode), sync.settingsMatrix(info))
            }
        }.onSuccess { (info, plan, matrix) ->
            watchInfo = info
            bundle = plan
            settings = matrix
        }
    }

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
            WatchHub.engine.clearResult()
            settings = emptyList()
            watchInfo = null
            bundle = null
            val res = session.connect(ctx, device)
            val link = res.getOrNull() ?: run {
                message = res.exceptionOrNull()?.message ?: "连接失败"
                return@launch
            }
            // 配对信息落库（地址用于下次直接连，不用再扫 8 秒）
            val name = runCatching { device.name }.getOrNull() ?: pairedName ?: "CR-4"
            onPaired(device.address, name)
            runCatching {
                withContext(Dispatchers.IO) {
                    val sync = WatchSync(link, repo)
                    val info = sync.info()
                    Triple(info, sync.makePlan(syncMode), sync.settingsMatrix(info))
                }
            }.onSuccess { (info, plan, matrix) ->
                watchInfo = info
                bundle = plan
                settings = matrix
                showSyncPrompt = true
            }.onFailure {
                message = "读取设备信息失败：${it.message ?: it}"
                // 设备信息读不出来也允许直接同步，避免卡在"不能同步也不能断开"
                showSyncPrompt = true
            }
        }
    }

    /** 连已配对手表：地址存在 prefs 里，省掉每次扫描。 */
    fun connectPaired() {
        val addr = pairedAddress ?: return
        val dev = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull()
        if (dev == null) {
            message = "配对的地址无效（$addr），请重新扫描一次"
            return
        }
        connectAndInspect(dev)
    }

    fun runSync(mode: SyncMode) {
        if (syncing) return
        // 现场查权限，不信 remember 的缓存：用户可能在系统设置里刚撤销蓝牙权限。
        // 不查就进 sync.start()，SyncService 里 startForeground 会 SecurityException 崩溃。
        val missing = perms.any {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            message = "需要蓝牙权限才能同步，请先授予"
            permLauncher.launch(perms.toTypedArray())
            return
        }
        showSyncPrompt = false
        message = null
        WatchHub.engine.clearResult()
        // 没给通知权限也照样同步，只是锁屏后在通知栏看不到进度
        if (notifPerm.isNotEmpty() &&
            notifPerm.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        ) {
            notifLauncher.launch(notifPerm.toTypedArray())
        }
        WatchHub.engine.start(ctx, repo, mode)?.let { message = it }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手表同步") },
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
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            // ------------------------------------------------------------ 连接
            item {
                SyncCard(
                    "连接手表",
                    if (linkState is LinkState.Ready) "已连接，可以看计划并同步"
                    else "广播名 Crest-CR4；配对一次以后不用再扫",
                ) {
                    when (val st = linkState) {
                        is LinkState.Ready -> {
                            SyncLine("连接", st.name)
                            watchInfo?.let { SyncLine("设备档案", deviceLine(it)) }
                        }

                        is LinkState.Connecting -> {
                            Text("正在连接…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }

                        is LinkState.Failed -> {
                            Text(
                                "连接失败：${st.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { if (permGranted) scan() else permLauncher.launch(perms.toTypedArray()) },
                                enabled = !scanning,
                                modifier = Modifier.padding(top = 6.dp),
                            ) { Text(if (scanning) "扫描中…" else "重新扫描") }
                        }

                        else -> {
                            // 已配对手表：直接连，不用每次扫 8 秒
                            if (pairedAddress != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "已配对：${pairedName ?: "CR-4"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            pairedAddress,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            lastRun?.let { "上次同步：${stampOf(it)} · ${runBrief(it)}" }
                                                ?: "还没同步过",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            if (permGranted) connectPaired()
                                            else permLauncher.launch(perms.toTypedArray())
                                        },
                                        enabled = btEnabled,
                                    ) { Text("连接") }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = { if (permGranted) scan() else permLauncher.launch(perms.toTypedArray()) },
                                        enabled = !scanning,
                                    ) { Text(if (scanning) "扫描中…" else "换一块表") }
                                    TextButton(onClick = onForgetPaired) { Text("解除配对") }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            } else {
                                Text(
                                    if (!permGranted) "需要蓝牙权限才能扫描/连接"
                                    else "点下面扫描附近的手表",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    onClick = { if (permGranted) scan() else permLauncher.launch(perms.toTypedArray()) },
                                    enabled = !scanning && btEnabled,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) { Text(if (scanning) "扫描中…（8 秒）" else "扫描手表") }
                            }

                            if (devices.isNotEmpty()) {
                                devices.forEach { d ->
                                    val name = runCatching { d.device.name }.getOrNull() ?: "未知设备"
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
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

                            if (lastRun != null && pairedAddress == null) {
                                Text(
                                    "上次同步：${stampOf(lastRun!!)} · ${runBrief(lastRun!!)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ------------------------------------------------------------ 同步
            if (linkState is LinkState.Ready) {
                item {
                    SyncCard("同步", "本地已有 ${data.watchCount} 条手表记录 · ${data.totalSamples} 个采样点") {
                        // 模式就地可切，跟设置页同一份值（下次进来还是这个）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyncModeChip("自动", SyncMode.AUTO, syncMode, onSyncMode)
                            SyncModeChip("增量", SyncMode.INCREMENTAL, syncMode, onSyncMode)
                            SyncModeChip("全量", SyncMode.FULL, syncMode, onSyncMode)
                        }
                        Text(
                            when (syncMode) {
                                SyncMode.AUTO -> "自动：能增量就增量（手表最新编号 − 本地最大编号），锚点对不上才退回全量。"
                                SyncMode.INCREMENTAL -> "增量：只补新记录，最快；锚点校验不过会自动转全量并说明原因。"
                                SyncMode.FULL -> "全量：逐条核对指纹，最慢但最稳；手表日志被清空重排后必须用它。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        HorizontalDivider(Modifier.padding(vertical = 6.dp))

                        bundle?.let { b -> PlanBlock(b.plan) }

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
                            Text(
                                "后台同步中：可以返回、切 App、锁屏，下载不会断（通知栏有进度，也能在那里取消）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            eng.warning?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(
                                onClick = { WatchHub.engine.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("取消同步", color = MaterialTheme.colorScheme.error) }
                        } else {
                            Button(
                                onClick = { runSync(syncMode) },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) { Text("开始同步（${modeLabel(syncMode)}）") }
                        }

                        eng.error?.let {
                            Text(
                                "同步未完成：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        result?.let {
                            Text(
                                it.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
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
                                    WatchHub.engine.clearResult()
                                    clockMessage = null
                                }
                            },
                            enabled = !syncing,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        ) { Text(if (syncing) "同步中，先别断" else "断开连接") }
                    }
                }

                // -------------------------------------------------------- 手表设置
                if (settings.isNotEmpty()) {
                    item {
                        SyncCard(
                            "手表设置",
                            "协议层能写的只有时间；其余项固件没开放写命令，如实标注",
                        ) {
                            settings.forEach { SettingRow(it) }
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
            }

            // ------------------------------------------------------------ 说明
            item {
                SyncCard("同步是怎么判断的", null) {
                    Text(
                        "增量：读手表最新编号，减去本地已存的最大编号，差的这些直接下，只再校验一条锚点指纹。" +
                            "锚点对不上说明手表那边编号重排过（环形缓冲写满覆盖），自动降级全量。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "入库按 (设备, 编号) 幂等，重复同步不会产生重复记录；" +
                            "新记录会覆盖摘要但保留你标的收藏 / 删除 / 气体。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 配对成功后的选择：现在同步（按当前模式）还是稍后
    if (showSyncPrompt) {
        AlertDialog(
            onDismissRequest = { showSyncPrompt = false },
            title = { Text("手表已配对") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        watchInfo?.let { deviceLine(it) } ?: (pairedName ?: "CR-4"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    bundle?.let { b ->
                        Text(
                            "建议：${if (b.plan.kind == PlanKind.UP_TO_DATE) "已是最新，不用同步" else "${modeLabel(syncMode)}，${b.plan.steps} 条"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            b.plan.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(enabled = !syncing, onClick = { runSync(syncMode) }) {
                    Text("${modeLabel(syncMode)}同步")
                }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !syncing, onClick = { runSync(SyncMode.FULL) }) { Text("全量") }
                    TextButton(onClick = { showSyncPrompt = false }) { Text("稍后") }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 小部件
// ---------------------------------------------------------------------------

/** 同步模式的中文短名。 */
internal fun modeLabel(m: SyncMode): String = when (m) {
    SyncMode.AUTO -> "自动"
    SyncMode.INCREMENTAL -> "增量"
    SyncMode.FULL -> "全量"
}

/** 一行展示设备档案：条数 / 最新编号 / 序列号。 */
internal fun deviceLine(info: WatchInfo): String =
    "${info.ndives} 条 · 最新 #${info.lastIndex} · ${info.deviceId}"

/** 同步计划：为什么走全量/增量，要下多少条。 */
@Composable
private fun PlanBlock(plan: SyncPlan) {
    val (title, color) = when {
        plan.kind == PlanKind.UP_TO_DATE ->
            "已是最新" to MaterialTheme.colorScheme.onSurfaceVariant
        plan.kind == PlanKind.INCREMENTAL ->
            "增量：${plan.steps} 条" to MaterialTheme.colorScheme.primary
        plan.fallback -> "降级全量：${plan.steps} 条" to MaterialTheme.colorScheme.error
        else -> "全量：${plan.steps} 条" to MaterialTheme.colorScheme.primary
    }
    Text(title, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    Text(
        plan.reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

@Composable
private fun SyncCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
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
private fun SyncLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

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
