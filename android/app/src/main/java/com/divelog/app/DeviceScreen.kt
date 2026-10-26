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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 设备页：运行环境自检 + 数据概况 + 日志导入入口。
 *
 * 导入走系统文件选择器（SAF），拿到的是 CR-4 的原始 `.bin`；
 * 解析/入库逻辑与 Phase 3 的蓝牙下载完全相同，换的只是字节来源。
 */
@Composable
fun DeviceScreen(
    data: DiveDataSet,
    entries: List<DiveEntry>,
    deletedCount: Int,
    importedCount: Int = 0,
    importing: Boolean = false,
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

    val btManager = ctx.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    val btEnabled = adapter?.isEnabled == true
    val soLoaded = DiveParser.isAvailable
    val years = remember(entries) {
        val ys = entries.map { it.year }.filter { it > 0 }
        if (ys.isEmpty()) "-" else "${ys.min()} ~ ${ys.max()}"
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            InfoCard("数据", "APK 内置的真实设备日志") {
                Line("原始记录", "${data.dives.size} 条")
                Line("其中导入", if (importedCount > 0) "$importedCount 条" else "—")
                Line("当前展示", "${entries.size} 组" + if (deletedCount > 0) "（已清理 $deletedCount 条）" else "")
                Line("采样点", "${entries.sumOf { it.samples.size }}")
                Line("时间跨度", years)
                Line("累计时长", "${Fmt.one(entries.sumOf { it.durationS.toLong() } / 3600.0)} 小时")
            }
        }
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
                        Text("清空已导入的 $importedCount 条")
                    }
                }
            }
        }
        item {
            InfoCard("运行环境", null) {
                Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                Line("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                Line("ABI", Build.SUPPORTED_ABIS.joinToString(", ") { it.substringAfter('-') })
            }
        }
        item {
            InfoCard("蓝牙 BLE", null) {
                Line("适配器", when {
                    adapter == null -> "不支持"
                    btEnabled -> "已开启"
                    else -> "已关闭"
                })
                Line("权限", if (permGranted) "已授予" else "未授予")
                Line("扫描/下载", "Phase 3 接入（文件导入已可用）")
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
