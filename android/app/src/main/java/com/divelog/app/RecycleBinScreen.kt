package com.divelog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveSource

/**
 * 回收站：所有被删过的**原始记录**都在这儿。
 *
 * 删除从来不删行，只写 `user_deleted = 1`：原始字节还在 raw/ 里、采样点还在 sample 表里，
 * 所以这里恢复只是把标记清掉，不涉及任何重新解析。
 *
 * 之前是"删完弹个 snackbar 让你撤销"，问题在于撤销入口几秒后就消失、
 * 而且一次删多条时只能撤销最近那一批。改成回收站后，用户可以自己回来慢慢找。
 *
 * 刻意**不提供「彻底删除」**：原始日志是唯一的再解析来源（raw 目录里的 bin 文件），
 * 真删了就再也回不来了。要腾空间请用设备页的「清空导入的数据」，那里有二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    dives: List<Dive>,
    meta: Map<String, DiveMeta>,
    onRestore: (ids: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    // 按时间倒序：刚删的排前面，符合"我刚刚误删了"的实际查找顺序
    val gone = remember(dives, meta) {
        dives.filter { meta[it.file]?.userDeleted == true }
            .sortedByDescending { it.datetime }
    }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val allSelected = gone.isNotEmpty() && gone.all { it.file in selected }

    fun restore(ids: List<String>) {
        if (ids.isEmpty()) return
        onRestore(ids)
        selected = emptySet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站" + if (gone.isNotEmpty()) "（${gone.size}）" else "") },
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
        Column(Modifier.padding(pad).fillMaxSize()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "删除只是打标，原始日志与采样点都还在库里",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "恢复 = 清掉删除标记，回到日志列表。这里不提供彻底删除，" +
                            "因为原始字节是唯一能重新解析的来源；要腾空间请去设备页「清空导入的数据」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (gone.isEmpty()) {
                Text(
                    "回收站是空的",
                    Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "共 ${gone.size} 条" else "已选 ${selected.size} 条",
                        Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(
                        onClick = {
                            selected = if (allSelected) emptySet() else gone.mapTo(HashSet()) { it.file }
                        },
                    ) { Text(if (allSelected) "取消全选" else "全选") }
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { restore(selected.toList()) },
                    ) { Text("恢复选中") }
                    TextButton(
                        enabled = selected.isEmpty(),
                        onClick = { restore(gone.map { it.file }) },
                    ) { Text("全部恢复") }
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(gone, key = { it.file }) { d ->
                        val checked = d.file in selected
                        val m = meta[d.file]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) selected - d.file else selected + d.file
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        d.datetime,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${Fmt.duration(d.divetimeS)} · 最深 ${Fmt.one(d.maxdepthM)} m" +
                                            sourceTag(m),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { restore(listOf(d.file)) }) { Text("恢复") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 括号里的来源标记：#手表编号 / 文件导入 / 内置数据。 */
private fun sourceTag(m: DiveMeta?): String {
    if (m == null) return ""
    return when (m.source) {
        DiveSource.WATCH -> m.watchIndex?.let { " · 手表 #$it" } ?: " · 手表同步"
        DiveSource.FILE -> " · 文件导入"
        DiveSource.BUILTIN -> " · 内置"
    }
}
