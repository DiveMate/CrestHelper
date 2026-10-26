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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 潜水日志 App 主入口。
 *
 * 数据来自 APK 内置的真实 CR-4 日志（data 目录的 CSV 打包进 assets），不依赖蓝牙；
 * 连接手表下载留到 Phase 3。
 *
 * 状态模型：
 *   原始 dives.csv (253 条)
 *     ├─ 用户清理掉的记录 (deleted，软删除，可恢复)
 *     ├─ 手动合并组 (manualGroups)
 *     └─ 自动合并 (settings.autoMerge / siThresholdS)
 *   → DiveEntries.build() → 展示用的 List<DiveEntry>（列表/统计/详情共用）
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
    Triple("日志", Icons.Default.Home, 0),
    Triple("分析", Icons.Default.Search, 1),
    Triple("统计", Icons.Default.Star, 2),
    Triple("设备", Icons.Default.Settings, 3),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveApp() {
    val ctx = LocalContext.current
    val prefs = remember { DivePrefs(ctx) }
    val repo = remember { DiveRepository(ctx) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var data by remember { mutableStateOf<DiveDataSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    var settings by remember { mutableStateOf(prefs.settings) }
    var junkRule by remember { mutableStateOf(prefs.junkRule) }
    var deleted by remember { mutableStateOf(prefs.deleted) }
    var manualGroups by remember { mutableStateOf(prefs.manualGroups) }

    var openId by remember { mutableStateOf<String?>(null) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { repo.load() }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: it.toString() }
    }

    // 导入 .bin：读字节 -> 解析 -> 落盘 -> 重载数据集（与 Phase 3 蓝牙下载共用一条路径）
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
            data = repo.load(force = true)
            importedCount = repo.imported.count()
            importing = false
            snackbarHost.showSnackbar(summary.text)
        }
    }

    LaunchedEffect(data) {
        importedCount = withContext(Dispatchers.IO) { repo.imported.count() }
    }

    val entries = remember(data, settings, deleted, manualGroups) {
        val ds = data
        if (ds == null) emptyList()
        else DiveEntries.build(ds.dives, settings, manualGroups, deleted) { ds.samplesOf(it) }
    }

    val openEntry = openId?.let { id -> entries.firstOrNull { it.id == id } }
    BackHandler(enabled = openEntry != null) { openId = null }

    fun applySettings(s: DiveSettings) {
        settings = s
        prefs.autoMerge = s.autoMerge
        prefs.siThresholdS = s.siThresholdS
    }

    fun mergeSelected(selected: List<DiveEntry>) {
        if (selected.size < 2) return
        val files = selected.flatMap { e -> e.records.map { it.file } }
        manualGroups = manualGroups + listOf(files)
        prefs.manualGroups = manualGroups
    }

    fun unmerge(entry: DiveEntry) {
        val files = entry.records.map { it.file }.toSet()
        manualGroups = manualGroups.filterNot { it.toSet() == files }
        prefs.manualGroups = manualGroups
    }

    fun deleteEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        // 条目可能是合并组：删就把组内所有原始记录都标记删除
        val files = ids.flatMap { id ->
            entries.firstOrNull { it.id == id }?.records?.map { it.file } ?: listOf(id)
        }.toSet()
        deleted = deleted + files
        prefs.deleted = deleted
        showCleanup = false
        scope.launch {
            val res = snackbarHost.showSnackbar(
                "已删除 ${ids.size} 组日志",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) {
                deleted = deleted - files
                prefs.deleted = deleted
            }
        }
    }

    // 详情页独占屏幕（避免和底部导航叠在一起）
    if (openEntry != null) {
        DiveDetailScreen(
            entry = openEntry,
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
                    TABS.forEachIndexed { i, (label, icon, _) ->
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
                        settings = settings,
                        onOpen = { openId = it.id },
                        onOpenMergeSettings = { showMergeDialog = true },
                        onOpenCleanup = { showCleanup = true },
                        onMergeSelected = { mergeSelected(it) },
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

                    else -> DeviceScreen(
                        data = ds,
                        entries = entries,
                        deletedCount = deleted.size,
                        importedCount = importedCount,
                        importing = importing,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onClearImported = {
                            scope.launch {
                                repo.clearImported()
                                data = repo.load(force = true)
                                importedCount = 0
                                snackbarHost.showSnackbar("已清空导入的日志")
                            }
                        },
                    )
                }
            }
        }

        if (showMergeDialog) {
            MergeSettingsDialog(
                entries = entries,
                rawCount = data?.dives?.size ?: 0,
                settings = settings,
                onApply = { applySettings(it) },
                onDismiss = { showMergeDialog = false },
            )
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
                onDelete = { deleteEntries(it) },
                onRestoreAll = {
                    deleted = emptySet()
                    prefs.deleted = emptySet()
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
