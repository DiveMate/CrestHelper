package com.divelog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.divelog.app.sync.SyncMode
import com.divelog.app.sync.WatchSession
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
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DiveApp()
                }
            }
        }
    }
}

private val TABS = listOf(
    "日志" to Icons.Default.Home,
    "分析" to Icons.Default.Search,
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
    // BLE 连接要跨页保持（切 tab 不应该断线），所以会话放在这里而不是设备页内部
    val watchSession = remember { WatchSession() }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var data by remember { mutableStateOf<DiveDataSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    var settings by remember { mutableStateOf(prefs.settings) }
    var junkRule by remember { mutableStateOf(prefs.junkRule) }
    var decoSettings by remember { mutableStateOf(prefs.decoSettings) }
    var gasSettings by remember { mutableStateOf(prefs.gasSettings) }
    var syncMode by remember { mutableStateOf(prefs.syncMode) }
    var pairedAddress by remember { mutableStateOf(prefs.pairedAddress) }
    var pairedName by remember { mutableStateOf(prefs.pairedName) }
    var manualGroups by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    var openId by remember { mutableStateOf<String?>(null) }
    var showCleanup by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importedCount by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        data = repo.load(force = true)
        manualGroups = withContext(Dispatchers.IO) { repo.manualGroups() }
        importedCount = withContext(Dispatchers.IO) {
            repo.db.count("source <> ?", arrayOf("builtin"))
        }
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure { error = it.message ?: it.toString() }
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
            samplesOf = { ds.samplesOf(it) },
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

    /** 软删除：只写 user_deleted，原始记录与原始字节都还在。 */
    fun deleteEntries(targets: List<DiveEntry>) {
        if (targets.isEmpty()) return
        val files = targets.flatMap { e -> e.records.map { it.file } }.toSet()
        showCleanup = false
        scope.launch {
            repo.markDeleted(files, true)
            reload()
            val res = snackbarHost.showSnackbar(
                "已删除 ${targets.size} 组（数据仍在库里，可恢复）",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) {
                repo.markDeleted(files, false)
                reload()
            }
        }
    }

    fun setEntryGas(e: DiveEntry, o2: Double?, he: Double?) {
        scope.launch {
            // 合并组：整组标同一个气体（记录里本来就分不出是哪一段换的气）
            for (r in e.records) repo.setGas(r.file, o2, he)
            reload()
        }
    }

    // 详情页独占屏幕（避免和底部导航叠在一起）
    if (openEntry != null) {
        DiveDetailScreen(
            entry = openEntry,
            deco = decoSettings,
            onDeco = { applyDeco(it) },
            onGas = { o2, he -> setEntryGas(openEntry, o2, he) },
            onBack = { openId = null },
            canUnmerge = manualGroups.any { it.toSet() == openEntry.records.map { r -> r.file }.toSet() },
            onUnmerge = { unmerge(openEntry) },
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
                    )

                    tab == 1 -> AnalysisScreen(
                        data = ds,
                        settings = settings,
                        excluded = deleted,
                        onOpen = { dive ->
                            openId = entries.firstOrNull { e -> e.records.any { it.file == dive.file } }?.id
                        },
                    )

                    tab == 2 -> StatsScreen(entries)

                    tab == 3 -> DeviceScreen(
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
                        onPaired = { addr, name ->
                            pairedAddress = addr
                            pairedName = name
                            prefs.pairedAddress = addr
                            prefs.pairedName = name
                        },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onClearImported = {
                            scope.launch {
                                repo.clearImported()
                                reload()
                                snackbarHost.showSnackbar("已清空导入的日志")
                            }
                        },
                        onSyncFinished = { scope.launch { reload() } },
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
                        onRestoreAll = {
                            scope.launch {
                                repo.markDeleted(deleted, false)
                                reload()
                                snackbarHost.showSnackbar("已恢复 ${deleted.size} 条记录")
                            }
                        },
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
