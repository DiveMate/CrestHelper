package com.divelog.app

import android.content.Context
import com.divelog.app.data.AutoGroup
import com.divelog.app.data.DiveDb
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveRow
import com.divelog.app.data.DiveSource
import com.divelog.app.data.FilterReason
import com.divelog.app.data.SyncRunRecord
import com.divelog.app.data.WatchProgress
import com.divelog.app.data.WatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 数据层：把桌面端解析好的真实潜水数据（data/dives.csv + samples.csv）
 * 打包进 APK assets，启动时一次性读进内存。
 *
 * 规模：253 条潜水 / 约 6 万个采样点，内存占用约 2~3 MB，无需数据库。
 * 解析逻辑是纯 Kotlin（只依赖 BufferedReader），可以在 JVM 单元测试里直接跑。
 */

/** 一条潜水的汇总（对应 dives.csv 一行）。 */
data class Dive(
    val file: String,
    val no: Int,
    val datetime: String,      // "2020-11-22 12:05:04"
    val divetimeS: Int,
    val maxdepthM: Double,
    val tempMinC: Double,
    val nsamples: Int,
    val bytes: Long,
) {
    val year: Int get() = datetime.take(4).toIntOrNull() ?: 0
    val month: Int get() = datetime.drop(5).take(2).toIntOrNull() ?: 0
    val dateText: String get() = datetime.take(10)
    val timeText: String get() = datetime.drop(11)
    val durationText: String get() = Fmt.duration(divetimeS)
    val depthText: String get() = Fmt.one(maxdepthM) + " m"
}

/** 一个采样点。temperature 可能缺失（原始设备未记录）。 */
data class Sample(val t: Int, val depth: Double, val temp: Double?)

/** 全量数据集。 */
data class DiveDataSet(
    val dives: List<Dive>,
    val samples: Map<String, List<Sample>>,
    /** 每条原始记录的元数据：来源 / 手表编号 / 合并归属 / 过滤标记。 */
    val meta: Map<String, DiveMeta> = emptyMap(),
) {
    fun samplesOf(d: Dive): List<Sample> = samples[d.file] ?: emptyList()
    fun metaOf(d: Dive): DiveMeta? = meta[d.file]

    /** 被标为无效（垃圾/重复）的记录数；只统计，不影响展示。 */
    val filteredCount: Int get() = meta.values.count { it.filterReason != null }
    val mergedCount: Int get() = meta.values.count { it.isMerged }
    val watchCount: Int get() = meta.values.count { it.source == DiveSource.WATCH }
    val deletedCount: Int get() = meta.values.count { it.userDeleted }

    val totalSamples: Int get() = samples.values.sumOf { it.size }
    val totalSeconds: Long get() = dives.sumOf { it.divetimeS.toLong() }
    val maxDepth: Double get() = dives.maxOfOrNull { it.maxdepthM } ?: 0.0
}

/** CSV 解析（dives.csv / samples.csv）。 */
object DiveCsv {

    fun parseDives(reader: BufferedReader): List<Dive> {
        val out = ArrayList<Dive>(300)
        reader.useLines { lines ->
            for ((i, raw) in lines.withIndex()) {
                if (i == 0 || raw.isBlank()) continue          // 跳过表头
                val p = raw.split(',')
                if (p.size < 10) continue
                out.add(
                    Dive(
                        file = p[0],
                        no = p[1].toIntOrNull() ?: 0,
                        datetime = p[2],
                        divetimeS = p[3].toIntOrNull() ?: 0,
                        maxdepthM = p[4].toDoubleOrNull() ?: 0.0,
                        tempMinC = p[5].toDoubleOrNull() ?: 0.0,
                        nsamples = p[7].toIntOrNull() ?: 0,
                        bytes = p[9].toLongOrNull() ?: 0L,
                    )
                )
            }
        }
        return out
    }

    /**
     * samples.csv -> file -> 采样点列表。
     * 注意 temp_c 可能为空（真实数据里有），必须解析成 null 而不是 0.0。
     */
    fun parseSamples(reader: BufferedReader): Map<String, List<Sample>> {
        val map = HashMap<String, MutableList<Sample>>(300)
        reader.useLines { lines ->
            for ((i, raw) in lines.withIndex()) {
                if (i == 0 || raw.isBlank()) continue
                val p = raw.split(',')
                if (p.size < 3) continue
                val t = p[1].toIntOrNull() ?: continue
                val d = p[2].toDoubleOrNull() ?: continue
                val tp = if (p.size > 3 && p[3].isNotBlank()) p[3].toDoubleOrNull() else null
                map.getOrPut(p[0]) { ArrayList() }.add(Sample(t, d, tp))
            }
        }
        return map.mapValues { (_, v) -> v.sortedBy { it.t } }
    }
}

/**
 * 数据仓库。
 *
 * 唯一的真源是 SQLite（[DiveDb]）：
 *   - 首次启动时把 assets 内置数据 + 旧版 CSV 导入一次性 **seed** 进库；
 *   - 之后无论文件导入还是手表下载，都只往库里写，CSV 不再参与运行时读写；
 *   - 原始字节（.bin）仍然单独落盘到 `files/raw/`，库里只存路径——这样随时能重放解析，
 *     而合并/过滤只改标记位，绝不动原始数据。
 */
class DiveRepository(private val ctx: Context) {

    val db: DiveDb = DiveDb(ctx.applicationContext)

    /** 旧版 CSV 导入目录：只在一次性迁移时读。 */
    private val legacyStore = ImportedStore(File(ctx.filesDir, "imported"))
    private val rawRoot = File(ctx.filesDir, "raw")

    @Volatile
    private var cached: DiveDataSet? = null

    suspend fun load(force: Boolean = false): DiveDataSet = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }
        seedIfNeeded()
        val ds = DiveDataSet(
            dives = db.allDives(),
            samples = db.allSamples(),
            meta = db.metaMap(),
        )
        cached = ds
        ds
    }

    /** 库为空时的一次性初始化：内置数据 + 旧 CSV + 旧设置里的删除/合并标记。 */
    private fun seedIfNeeded() {
        if (!db.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = ArrayList<DiveRow>(300)

        val builtinSamples = ctx.assets.open("samples.csv").bufferedReader().use { DiveCsv.parseSamples(it) }
        ctx.assets.open("dives.csv").bufferedReader().use { reader ->
            for (d in DiveCsv.parseDives(reader)) {
                rows.add(
                    DiveRow(
                        dive = d,
                        samples = builtinSamples[d.file] ?: emptyList(),
                        meta = DiveMeta(id = d.file, source = DiveSource.BUILTIN, importedAt = now),
                    )
                )
            }
        }

        // 旧版本用 CSV 存导入记录，这里搬进库（文件保留不删，出问题能回退）
        val legacy = legacyStore.load()
        for (d in legacy.dives) {
            rows.add(
                DiveRow(
                    dive = d,
                    samples = legacy.samples[d.file] ?: emptyList(),
                    meta = DiveMeta(id = d.file, source = DiveSource.FILE, importedAt = now),
                )
            )
        }
        db.insertAll(rows)

        // 旧设置里的软删除/手动合并，迁成标记位
        val prefs = DivePrefs(ctx)
        if (prefs.deleted.isNotEmpty()) db.setUserDeleted(prefs.deleted, true)
        for (g in prefs.manualGroups) runCatching { db.saveManualGroup(g) }
    }

    /**
     * 导入原始字节（CR-4 的 `.bin`，或任何符合该格式的字节流）。
     * 去重按 [DiveIngest.signature]：同一条潜水改名再导一次不会重复。
     */
    suspend fun importBytes(files: List<ByteArray>): ImportSummary = withContext(Dispatchers.Default) {
        seedIfNeeded()
        val ids = db.ids().toMutableSet()
        val sigs = db.signatures()
        var nextNo = db.maxDiveNo() + 1
        var added = 0
        var duplicate = 0
        var failed = 0
        val rows = ArrayList<DiveRow>(files.size)

        for (bytes in files) {
            val parsed = DiveParser.parse(bytes)
            if (parsed == null || parsed.divetime <= 0 || parsed.samples.isEmpty()) {
                failed++
                continue
            }
            val sig = DiveIngest.signature(parsed)
            if (sig in sigs) {
                duplicate++
                continue
            }
            val id = DiveIngest.idFor(parsed, ids)
            val dive = DiveIngest.toDive(parsed, id, nextNo++)
            val rawPath = saveRaw(id, bytes)
            rows.add(
                DiveRow(
                    dive = dive,
                    samples = DiveIngest.toSamples(parsed),
                    meta = DiveMeta(
                        id = id,
                        source = DiveSource.FILE,
                        rawPath = rawPath,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
            )
            ids += id
            sigs += sig
            added++
        }
        db.insertAll(rows)
        if (rows.isNotEmpty()) cached = null
        ImportSummary(added, duplicate, failed)
    }

    /**
     * 手表下载的一条记录入库（幂等）。
     *
     * 幂等键是 `(deviceId, watchIndex)`：
     *  - 同一编号同一指纹 → [WatchIngest.SKIPPED]（增量同步重跑不会产生重复）
     *  - 同一编号不同指纹 → 覆盖（设备重算/重写过的日志）
     *  - 编号新但内容与已有记录一致 → [WatchIngest.DUPLICATE]（手表日志被清空重排过）
     */
    suspend fun ingestWatch(
        deviceId: String,
        watchIndex: Int,
        fingerprint: String,
        bytes: ByteArray,
    ): WatchIngest = withContext(Dispatchers.Default) {
        seedIfNeeded()
        val parsed = DiveParser.parse(bytes)
        if (parsed == null || parsed.divetime <= 0 || parsed.samples.isEmpty()) {
            return@withContext WatchIngest.FAILED
        }
        val id = "watch_${deviceId}_$watchIndex"
        val existing = db.metaMap()[id]
        if (existing != null && existing.fingerprint == fingerprint) {
            return@withContext WatchIngest.SKIPPED
        }
        val isUpdate = existing != null
        if (!isUpdate && db.countBySignature(parsed.dateText, parsed.divetime, parsed.bytes.toLong(), id) > 0) {
            return@withContext WatchIngest.DUPLICATE
        }

        val no = if (isUpdate) (db.allDives().firstOrNull { it.file == id }?.no ?: 0) else db.maxDiveNo() + 1
        val dive = DiveIngest.toDive(parsed, id, no)
        val rawPath = saveRaw(id, bytes)
        db.insertAll(
            listOf(
                DiveRow(
                    dive = dive,
                    samples = DiveIngest.toSamples(parsed),
                    meta = DiveMeta(
                        id = id,
                        source = DiveSource.WATCH,
                        deviceId = deviceId,
                        watchIndex = watchIndex,
                        fingerprint = fingerprint,
                        rawPath = rawPath,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
            )
        )
        cached = null
        if (isUpdate) WatchIngest.UPDATED else WatchIngest.ADDED
    }

    suspend fun markDeleted(ids: Collection<String>, deleted: Boolean) = withContext(Dispatchers.IO) {
        db.setUserDeleted(ids, deleted)
        cached = null
    }

    suspend fun saveManualGroup(memberIds: List<String>, siS: Int? = null): String? =
        withContext(Dispatchers.IO) {
            if (memberIds.size < 2) null
            else {
                cached = null
                db.saveManualGroup(memberIds, siS)
            }
        }

    suspend fun removeManualGroup(groupId: String) = withContext(Dispatchers.IO) {
        db.removeManualGroup(groupId)
        cached = null
    }

    fun manualGroups(): List<List<String>> = db.manualGroups()

    /**
     * 把当前视图的合并/过滤结果写回标记位。
     * 幂等，可在数据或阈值变化后反复调用。
     */
    suspend fun materialize(entries: List<DiveEntry>, junkRule: JunkRule) = withContext(Dispatchers.IO) {
        val manualKeys = manualGroups().map { it.toSet() }.toSet()
        val groups = ArrayList<AutoGroup>()
        val filters = HashMap<String, FilterReason>()
        for (e in entries) {
            val members = e.records.map { it.file }
            if (members.size > 1 && members.toSet() !in manualKeys) {
                groups.add(AutoGroup("auto_${members.first()}", members))
            }
            if (junkRule.matches(e)) {
                for (m in members) filters[m] = FilterReason.JUNK
            }
        }
        db.materialize(groups, filters)
    }

    /** 清空非内置数据（文件导入 + 手表下载）。 */
    suspend fun clearImported() = withContext(Dispatchers.IO) {
        db.clearSource(DiveSource.FILE)
        db.clearSource(DiveSource.WATCH)
        cached = null
    }

    /** 清空某一来源（同步页的「清空本机手表数据」用）。 */
    suspend fun clearSource(source: DiveSource) = withContext(Dispatchers.IO) {
        db.clearSource(source)
        cached = null
    }

    fun watchProgress(deviceId: String): WatchProgress? = db.watchProgress(deviceId)

    fun upsertWatch(w: WatchRecord) = db.upsertWatch(w)

    fun watch(id: String): WatchRecord? = db.watch(id)

    fun logSync(run: SyncRunRecord) = db.logSync(run)

    fun lastSync(watchId: String): SyncRunRecord? = db.lastSync(watchId)

    /** 原始字节落盘，返回相对 filesDir 的路径（库里只存这个）。 */
    private fun saveRaw(id: String, bytes: ByteArray): String? = runCatching {
        if (!rawRoot.exists()) rawRoot.mkdirs()
        val f = File(rawRoot, "$id.bin")
        f.writeBytes(bytes)
        f.relativeTo(ctx.filesDir).path
    }.getOrNull()

    fun rawFile(id: String, rawPath: String?): File? {
        val p = rawPath ?: "raw/$id.bin"
        val f = File(ctx.filesDir, p)
        return if (f.exists()) f else null
    }
}

/** 单条手表记录的入库结果。 */
enum class WatchIngest { ADDED, UPDATED, SKIPPED, DUPLICATE, FAILED }

/** 统计聚合（纯函数，可单测）。 */
object Analytics {

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun startMillis(d: Dive): Long = runCatching {
        LocalDateTime.parse(d.datetime, FMT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** 无效标记：浅于 2m 或 短于 60s。 */
    fun invalidFlags(d: Dive): List<String> {
        val flags = ArrayList<String>(2)
        if (d.maxdepthM < DiveMerger.DEFAULT_MIN_DEPTH) flags.add("浅水")
        if (d.divetimeS < DiveMerger.MIN_DURATION_S) flags.add("短时")
        return flags
    }

    /**
     * 按水面间隔分组：SI <= 600s 视为同一次潜水（中途升水再下水）。
     * 与 analyze.py 的 group 规则一致；返回的分组按时间升序。
     */
    fun mergeGroups(dives: List<Dive>, siThreshold: Int = DiveMerger.DEFAULT_SI_THRESHOLD): List<List<Dive>> {
        if (dives.isEmpty()) return emptyList()
        val sorted = dives.sortedBy { startMillis(it) }
        val out = ArrayList<List<Dive>>()
        var cur = ArrayList<Dive>()
        var prevEnd = Long.MIN_VALUE
        for (d in sorted) {
            val st = startMillis(d)
            val end = st + d.divetimeS * 1000L
            if (cur.isNotEmpty()) {
                val si = (st - prevEnd) / 1000.0
                // 与 analyze.py 一致：si <= 600 都算同一次（含时间重叠的脏数据）
                if (si > siThreshold) {
                    out.add(cur)
                    cur = ArrayList()
                    prevEnd = Long.MIN_VALUE
                }
            }
            cur.add(d)
            if (end > prevEnd) prevEnd = end
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** 年度分布（升序）。 */
    fun byYear(dives: List<Dive>): List<Pair<Int, Int>> = byYearOf(dives.map { it.year })

    fun byYearOf(years: List<Int>): List<Pair<Int, Int>> =
        years.filter { it > 0 }.groupingBy { it }.eachCount().toSortedMap().map { it.key to it.value }

    /** 深度直方图。 */
    fun depthHistogram(dives: List<Dive>): List<Pair<String, Int>> = depthHistogramOf(dives.map { it.maxdepthM })

    fun depthHistogramOf(values: List<Double>): List<Pair<String, Int>> {
        val buckets = listOf(0, 5, 10, 15, 20, 25, 30, Int.MAX_VALUE)
        val counts = IntArray(buckets.size - 1)
        for (v in values) {
            for (i in 0 until buckets.size - 1) {
                if (v >= buckets[i] && v < buckets[i + 1]) { counts[i]++; break }
            }
        }
        return buckets.dropLast(1).mapIndexed { i, lo ->
            val hi = buckets[i + 1]
            val label = if (hi == Int.MAX_VALUE) "$lo+m" else "$lo-${hi}m"
            label to counts[i]
        }
    }

    /** 时长直方图（分钟）。 */
    fun durationHistogram(dives: List<Dive>): List<Pair<String, Int>> =
        durationHistogramOf(dives.map { it.divetimeS })

    fun durationHistogramOf(seconds: List<Int>): List<Pair<String, Int>> {
        val edges = listOf(0, 10, 20, 30, 45, 60, 90, Int.MAX_VALUE)   // 分钟
        val counts = IntArray(edges.size - 1)
        for (s in seconds) {
            val m = s / 60
            for (i in 0 until edges.size - 1) {
                if (m >= edges[i] && m < edges[i + 1]) { counts[i]++; break }
            }
        }
        return edges.dropLast(1).mapIndexed { i, lo ->
            val hi = edges[i + 1]
            if (hi == Int.MAX_VALUE) "$lo+m" else "$lo-${hi}m"
        }.mapIndexed { i, label -> label to counts[i] }
    }

    /** 指定年份的每月潜次（12 个）。 */
    fun monthly(dives: List<Dive>, year: Int): IntArray =
        monthlyOf(dives.map { it.year to it.month }, year)

    fun monthlyOf(pairs: List<Pair<Int, Int>>, year: Int): IntArray {
        val arr = IntArray(12)
        for ((y, m) in pairs) if (y == year && m in 1..12) arr[m - 1]++
        return arr
    }
}

/** 数值/时间格式化（避免 Locale 差异，手工拼）。 */
object Fmt {
    fun one(v: Double): String {
        val r = Math.round(v * 10.0) / 10.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    /** 3673 -> "61:13"（总分钟数，潜水日志惯例） */
    fun duration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    /** 坐标轴时间标签：秒 -> "12'" */
    fun axisMinutes(sec: Int): String = "${sec / 60}'"
}
