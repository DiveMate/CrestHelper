package com.divelog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.divelog.app.data.DiveAnnotation
import com.divelog.app.sync.SyncMode
import com.divelog.app.sync.WatchHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 潜水日志 App 主入口。
 *
 * 数据、标记、偏好全部落在 SQLite + SharedPreferences，界面只是视图：
 *   原始记录（dive 表）
 *     ├─ user_deleted  软删除（可恢复，永不物理删除）
 *     ├─ favorite      收藏
 *     ├─ gas_o2/gas_he 气体（记录自带或用户标注）
 *     ├─ group_id      合并归属
 *     └─ filter_reason 过滤原因
 *   → DiveEntries.build(参数来自设置页) → 列表/统计/详情共用
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 之前写死 MaterialTheme()（等价于浅色）：系统开深色模式时整个界面还是白的。
            // 图表用的是 colorScheme 里的 primary/outlineVariant/onSurfaceVariant，跟着一起变。
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DiveApp()
                }
            }
        }
    }
}

/**
 * 底部导航。
 *
 * 「分析」暂时不上了（[AnalysisScreen] 代码留着，等确定要什么之后再说）：
 * 它需要等整条剖面到齐才能算，几千条记录时会长时间显示"0 减压"这种假结论，
 * 与其给个半成品，不如先藏起来。统计页已经覆盖"看总量"的需求。
 */
private val TABS = listOf(
    "日志" to Icons.Default.Home,
    "统计" to Icons.Default.Star,
    "设备" to Icons.Default.Settings,
    "设置" to Icons.Default.Build,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveApp() {
    val ctx = LocalContext.current
    val prefs = remember { DivePrefs(ctx) }
    val repo = remember { DiveRepository(ctx) }
    // BLE 连接与同步都挂在进程级单例上：切 tab 不断线，退出同步页/锁屏也不中断下载
    val watchSession = WatchHub.session
    val syncState by WatchHub.engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var data by remember { mutableStateOf<DiveDataSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 这几个是"用户看得到的当前位置"：旋转/进程重建后必须原样恢复，
    // 否则会跳回日志列表、或者把已经看过的同步结果再弹一遍。
    var tab by rememberSaveable { mutableIntStateOf(0) }

    var settings by remember { mutableStateOf(prefs.settings) }
    var junkRule by remember { mutableStateOf(prefs.junkRule) }
    var decoSettings by remember { mutableStateOf(prefs.decoSettings) }
    var gasSettings by remember { mutableStateOf(prefs.gasSettings) }
    var syncMode by remember { mutableStateOf(prefs.syncMode) }
    var pairedAddress by remember { mutableStateOf(prefs.pairedAddress) }
    var pairedName by remember { mutableStateOf(prefs.pairedName) }
    var manualGroups by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSync by rememberSaveable { mutableStateOf(false) }
    var showRecycle by rememberSaveable { mutableStateOf(false) }
    var showCleanup by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importedCount by remember { mutableIntStateOf(0) }

    // 剖面（六万个采样点）走独立通道：摘要先出列表，剖面在后台分块载入。
    val sampleState by repo.samples.state.collectAsState()

    suspend fun reload() {
        data = repo.load(force = true)
        manualGroups = withContext(Dispatchers.IO) { repo.manualGroups() }
        importedCount = withContext(Dispatchers.IO) {
            repo.db.count("source <> ?", arrayOf("builtin"))
        }
        // 摘要换了一轮，剖面也跟着补（invalidate 过的会重读；没变过的直接命中）
        runCatching { repo.samples.loadAll() }
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure { error = it.message ?: it.toString() }
    }

    /**
     * 同步在进程级引擎里跑，界面只监听"完成信号"。
     *
     * 这样即使同步进行到一半退出了同步页、锁屏、甚至 Activity 重建，
     * 完成之后数据照样会自动刷新一遍，不用用户回来手动下拉。
     */
    // 必须用 rememberSaveable：finishedTick 挂在进程级引擎上，旋转后仍然 >0，
    // 而这个值如果归零，就会把「同步完成」的 Snackbar 重放一遍并重复 reload()。
    var seenSyncTick by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(syncState.finishedTick) {
        if (syncState.finishedTick == seenSyncTick) return@LaunchedEffect
        seenSyncTick = syncState.finishedTick
        runCatching { reload() }
        val r = syncState.result
        val msg = when {
            syncState.error != null -> "同步未完成：${syncState.error}"
            r != null -> "同步完成：新增 ${r.added} · 更新 ${r.updated} · 跳过 ${r.skipped}"
            else -> "同步结束"
        }
        snackbarHost.showSnackbar(msg)
    }

    // 导入 .bin：读字节 -> 解析 -> 落盘 -> 重载数据集（与蓝牙下载共用一条路径）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val payloads = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
            }
            val summary = runCatching { repo.importBytes(payloads) }
                .getOrElse {
                    importing = false
                    snackbarHost.showSnackbar("导入出错：${it.message ?: it}")
                    return@launch
                }
            reload()
            importing = false
            snackbarHost.showSnackbar(summary.text)
        }
    }

    // 软删除标记直接来自元数据表（原始记录仍在库里，只是被标了 user_deleted）
    val deleted = remember(data) {
        data?.meta?.values?.filter { it.userDeleted }?.mapTo(HashSet()) { it.id } ?: emptySet<String>()
    }
    val favorites = remember(data) {
        data?.meta?.values?.filter { it.favorite }?.mapTo(HashSet()) { it.id } ?: emptySet<String>()
    }

    val entries = remember(data, settings, deleted, manualGroups, gasSettings) {
        val ds = data
        if (ds == null) emptyList()
        else DiveEntries.build(
            dives = ds.dives,
            settings = settings,
            manualGroups = manualGroups,
            deleted = deleted,
            // 刻意不传 samplesOf：列表只要摘要（平均深度/水温/气体数已在 dive 表里算好），
            // 剖面对列表有价值的地方只有详情页，那里单独从 SampleCache 取。
            metaOf = { ds.metaOf(it) },
            defaultGas = gasSettings.gas,
        )
    }

    // 把「当前视图的合并归属 / 垃圾判定」物化回标记位，保证后台可查
    LaunchedEffect(entries, junkRule) {
        if (entries.isNotEmpty()) runCatching { repo.materialize(entries, junkRule) }
    }

    val openEntry = openId?.let { id -> entries.firstOrNull { it.id == id } }
    BackHandler(enabled = openEntry != null) { openId = null }

    fun applySettings(s: DiveSettings) {
        settings = s
        prefs.autoMerge = s.autoMerge
        prefs.siThresholdS = s.siThresholdS
    }

    fun applyDeco(d: DecoSettings) {
        decoSettings = d
        prefs.decoSettings = d
    }

    fun applyGas(g: GasSettings) {
        gasSettings = g
        prefs.gasSettings = g
    }

    fun mergeSelected(selected: List<DiveEntry>) {
        if (selected.size < 2) return
        val files = selected.flatMap { e -> e.records.map { it.file } }
        scope.launch {
            repo.saveManualGroup(files, settings.siThresholdS)
            reload()
        }
    }

    fun unmerge(entry: DiveEntry) {
        scope.launch {
            val members = entry.records.map { it.file }
            val gid = withContext(Dispatchers.IO) { repo.db.groupIdOf(members) }
            if (gid != null) repo.removeManualGroup(gid)
            reload()
        }
    }

    /** 收藏/取消收藏：作用于这条记录（合并组则整组）。 */
    fun toggleFavorite(e: DiveEntry) {
        val ids = e.records.map { it.file }
        val already = ids.any { it in favorites }
        scope.launch {
            repo.setFavorite(ids, !already)
            reload()
        }
    }

    /** 软删除：只写 user_deleted。删完不弹提示，要找回就去回收站。 */
    fun deleteEntries(targets: List<DiveEntry>) {
        if (targets.isEmpty()) return
        val files = targets.flatMap { e -> e.records.map { it.file } }.toSet()
        showCleanup = false
        scope.launch {
            repo.markDeleted(files, true)
            reload()
        }
    }

    fun setEntryGas(e: DiveEntry, o2: Double?, he: Double?) {
        scope.launch {
            // 合并组：整组标同一个气体（记录里本来就分不出是哪一段换的气）
            for (r in e.records) repo.setGas(r.file, o2, he)
            reload()
        }
    }

    /**
     * 补充的简要信息（潜点 / 能见度 / 穿着 / 类型）。
     *
     * 合并组写整组：以后拆开合并，每一段仍然知道自己属于哪次潜水；
     * 读的时候只认组内第一条（[DiveEntries.entry] 就是这么取的）。
     */
    fun setEntryAnnotation(e: DiveEntry, a: DiveAnnotation) {
        scope.launch {
            repo.setAnnotation(e.records.map { it.file }, a)
            reload()
        }
    }

    // 详情页独占屏幕（避免和底部导航叠在一起）
    if (openEntry != null) {
        // 直接跳到某条记录：只补这一条的剖面（几百行、几毫秒），不必等全量载入
        LaunchedEffect(openEntry.id) {
            runCatching { repo.samples.ensure(openEntry.records.map { it.file }) }
        }
        DiveDetailScreen(
            entry = openEntry,
            deco = decoSettings,
            onDeco = { applyDeco(it) },
            onGas = { o2, he -> setEntryGas(openEntry, o2, he) },
            onAnnotation = { setEntryAnnotation(openEntry, it) },
            onBack = { openId = null },
            canUnmerge = manualGroups.any { it.toSet() == openEntry.records.map { r -> r.file }.toSet() },
            onUnmerge = { unmerge(openEntry) },
            samples = sampleState.map,
            loading = sampleState.loading,
        )
        return
    }

    // 同步页同样独占屏幕（从设备页点进来），返回键回设备页
    val dsForSync = data
    if (showSync && dsForSync != null) {
        BackHandler(enabled = true) { showSync = false }
        SyncScreen(
            data = dsForSync,
            repo = repo,
            session = watchSession,
            syncMode = syncMode,
            onSyncMode = { mode ->
                syncMode = mode
                prefs.syncMode = mode
            },
            pairedAddress = pairedAddress,
            pairedName = pairedName,
            onPaired = { addr, name ->
                pairedAddress = addr
                pairedName = name
                prefs.pairedAddress = addr
                prefs.pairedName = name
            },
            onForgetPaired = {
                prefs.forgetPaired()
                pairedAddress = null
                pairedName = null
            },
            onBack = { showSync = false },
        )
        return
    }

    // 回收站：所有被软删的原始记录，恢复只是清标记
    if (showRecycle) {
        BackHandler(enabled = true) { showRecycle = false }
        RecycleBinScreen(
            dives = data?.dives ?: emptyList(),
            meta = data?.meta ?: emptyMap(),
            onRestore = { ids ->
                scope.launch {
                    repo.markDeleted(ids, false)
                    reload()
                }
            },
            onBack = { showRecycle = false },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == 0) "潜水日志" else TABS[tab].first) },
                    actions = {
                        if (tab == 0) {
                            IconButton(
                                enabled = !importing,
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "导入日志")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                val ds = data
                when {
                    error != null -> CenterState("数据加载失败：$error")
                    ds == null -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("正在加载潜水数据…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }

                    tab == 0 -> DiveListScreen(
                        entries = entries,
                        rawCount = ds.dives.size,
                        favorites = favorites,
                        onOpen = { openId = it.id },
                        onMergeSelected = { mergeSelected(it) },
                        onToggleFavorite = { toggleFavorite(it) },
                        onDelete = { deleteEntries(listOf(it)) },
                        onDeleteSelected = { deleteEntries(it) },
                        profileProgress = sampleState.progress.takeIf { sampleState.loading },
                        profileLoaded = sampleState.loadedDives,
                        profileTotal = sampleState.totalDives,
                    )

                    tab == 1 -> StatsScreen(entries)

                    tab == 2 -> DeviceScreen(
                        data = ds,
                        entries = entries,
                        deletedCount = deleted.size,
                        importedCount = importedCount,
                        importing = importing,
                        repo = repo,
                        session = watchSession,
                        defaultSyncMode = syncMode,
                        pairedAddress = pairedAddress,
                        pairedName = pairedName,
                        onOpenSync = { showSync = true },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onClearImported = {
                            scope.launch {
                                repo.clearImported()
                                reload()
                                snackbarHost.showSnackbar("已清空导入的日志")
                            }
                        },
                    )

                    else -> SettingsScreen(
                        data = ds,
                        entries = entries,
                        settings = settings,
                        onSettings = { applySettings(it) },
                        junkRule = junkRule,
                        onJunkRule = {
                            junkRule = it
                            prefs.junkRule = it
                        },
                        deco = decoSettings,
                        onDeco = { applyDeco(it) },
                        gas = gasSettings,
                        onGas = { applyGas(it) },
                        syncMode = syncMode,
                        onSyncMode = {
                            syncMode = it
                            prefs.syncMode = it
                        },
                        pairedName = pairedName,
                        pairedAddress = pairedAddress,
                        onForgetPaired = {
                            prefs.forgetPaired()
                            pairedAddress = null
                            pairedName = null
                        },
                        deletedCount = deleted.size,
                        onOpenCleanup = { showCleanup = true },
                        onOpenRecycle = { showRecycle = true },
                    )
                }
            }
        }

        if (showCleanup) {
            CleanupDialog(
                entries = entries,
                rule = junkRule,
                deletedCount = deleted.size,
                onRuleChange = {
                    junkRule = it
                    prefs.junkRule = it
                },
                onDelete = { ids ->
                    deleteEntries(entries.filter { it.id in ids })
                },
                onRestoreAll = {
                    scope.launch {
                        repo.markDeleted(deleted, false)
                        reload()
                    }
                },
                onDismiss = { showCleanup = false },
            )
        }
    }
}

@Composable
fun CenterState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
