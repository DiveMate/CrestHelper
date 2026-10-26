package com.divelog.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
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
) {
    fun samplesOf(d: Dive): List<Sample> = samples[d.file] ?: emptyList()
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
 * 数据仓库：内置 assets（253 条真实日志）+ 用户导入的日志，首次加载后缓存在内存。
 *
 * 导入的日志落在应用私有目录（见 [ImportedStore]），格式与 assets 的 CSV 同构，
 * 所以两者可以直接拼起来当成一份数据集用。
 */
class DiveRepository(private val ctx: Context) {

    val imported: ImportedStore = ImportedStore(java.io.File(ctx.filesDir, "imported"))

    @Volatile
    private var cached: DiveDataSet? = null

    suspend fun load(force: Boolean = false): DiveDataSet = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }
        val builtinDives = ctx.assets.open("dives.csv").bufferedReader().use { DiveCsv.parseDives(it) }
        val builtinSamples = ctx.assets.open("samples.csv").bufferedReader().use { DiveCsv.parseSamples(it) }
        val extra = imported.load()
        val dives = builtinDives + extra.dives
        val samples = if (extra.samples.isEmpty()) builtinSamples else builtinSamples + extra.samples
        // 默认按时间倒序（最近的在最上面）
        val ds = DiveDataSet(dives.sortedByDescending { it.datetime }, samples)
        cached = ds
        ds
    }

    /**
     * 导入原始字节（CR-4 的 `.bin`，以后也可以是蓝牙下载到的字节）。
     *
     * 去重按 [DiveIngest.signature]：同一条潜水改个文件名再导一次也不会重复。
     * 返回汇总，调用方据此提示用户；实际数据要再 `load(force = true)` 才刷新。
     */
    suspend fun importBytes(files: List<ByteArray>): ImportSummary = withContext(Dispatchers.Default) {
        val base = load()
        val ids = base.dives.map { it.file }.toMutableSet()
        val sigs = base.dives.map { DiveIngest.signature(it) }.toMutableSet()
        var nextNo = (base.dives.maxOfOrNull { it.no } ?: 0) + 1
        var added = 0
        var duplicate = 0
        var failed = 0

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
            imported.append(DiveIngest.toDive(parsed, id, nextNo++), DiveIngest.toSamples(parsed))
            ids += id
            sigs += sig
            added++
        }
        ImportSummary(added, duplicate, failed)
    }

    /** 清空所有导入的日志（内置数据不受影响）。 */
    suspend fun clearImported() = withContext(Dispatchers.IO) {
        imported.clear()
        cached = null
    }
}

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
