package com.divelog.app

import android.content.Context
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.GasSource
import com.divelog.app.sync.SyncMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 展示层数据模型与用户设置。
 *
 * 为什么要这一层：
 *  - 原始 dives.csv 有 253 条记录，其中不少是"一次潜水被分段记录"，
 *    直接列出来既难看也会让统计失真，所以列表按 [DiveSettings] 合并后再展示；
 *  - 合并/删除是用户操作，必须持久化（assets 只读，不能改原始数据），
 *    因此删除用"墓碑"记录（[DivePrefs.deleted]），合并用文件组（[DivePrefs.manualGroups]）。
 *
 * 这里的计算全部是纯 Kotlin，可被 JVM 单元测试直接覆盖（见 EntriesTest）。
 */

/** 自动合并设置。si = surface interval（水面间隔）。 */
data class DiveSettings(
    val autoMerge: Boolean = true,
    val siThresholdS: Int = 600,
) {
    companion object {
        /** 阈值的合法范围（秒）：1 秒 ~ 24 小时。 */
        const val SI_MIN_S = 1
        const val SI_MAX_S = 86_400

        fun siValid(sec: Int): Boolean = sec in SI_MIN_S..SI_MAX_S

        /** 阈值文案：整分钟显示"10 分钟"，否则"45 秒"。 */
        fun siText(sec: Int): String =
            if (sec >= 60 && sec % 60 == 0) "${sec / 60} 分钟" else "$sec 秒"

        /** 打开输入框时默认用哪个单位：整分钟用"分钟"，其余用"秒"。 */
        fun siUseMinutes(sec: Int): Boolean = sec >= 60 && sec % 60 == 0

        /**
         * 用户输入 -> 秒。非法（空、非数字、<=0、NaN）返回 null。
         * 支持小数：分钟单位下 2.5 => 150 秒。
         */
        fun siFromInput(raw: String, minutes: Boolean): Int? {
            val v = raw.toDoubleOrNull() ?: return null
            if (!v.isFinite() || v <= 0.0) return null
            return (if (minutes) v * 60 else v).roundToInt()
        }

        /** 秒 -> 输入框文本（整数不带小数点）。 */
        fun siInputText(sec: Int, minutes: Boolean): String {
            if (!minutes) return sec.toString()
            val m = sec / 60.0
            val r = m.roundToInt()
            return if (abs(m - r) < 1e-9) r.toString()
            else String.format(Locale.US, "%.2f", m).trimEnd('0').trimEnd('.')
        }
    }
}

/** 垃圾日志判定规则：时长 <= [maxDurationS] 或 深度 < [maxDepthM]。 */
data class JunkRule(
    val maxDurationS: Int = 60,
    val maxDepthM: Double = 2.0,
) {
    fun matches(e: DiveEntry): Boolean =
        e.bottomTimeS <= maxDurationS || e.maxDepthM < maxDepthM

    val text: String get() = "时长 ≤ ${maxDurationS}s 或 最深 < ${Fmt.one(maxDepthM)}m"
}

/**
 * 默认呼吸气体：**记录里没带气体信息时**（CR-4 的 v0 格式就是这种情况）用它算减压。
 *
 * 为什么要有它：同一份剖面，按空气算和按 EAN32 算，18m 的 NDL 差了将近一倍。
 * 记录没说，只能退到「用户平时用什么气」这个全局假设，并明确标成「默认」。
 */
data class GasSettings(
    val o2: Double = 0.21,
    val he: Double = 0.0,
) {
    val gas: Deco.Gas get() = Deco.Gas(o2, he)
    val label: String get() = gasName(gas)

    companion object {
        const val O2_MIN = 0.07
        const val O2_MAX = 1.0
        const val HE_MAX = 0.95

        /** 氧气 + 氦气不能超过 100%（剩下的是氮）。 */
        fun valid(o2: Double, he: Double): Boolean =
            o2 in O2_MIN..O2_MAX && he in 0.0..HE_MAX && o2 + he <= 1.001

        /** 用户输入的百分数（如 "32"、"32.5"）-> 分数；非法返回 null。 */
        fun pctFromInput(raw: String): Double? {
            val v = raw.toDoubleOrNull() ?: return null
            if (!v.isFinite() || v <= 0) return null
            return v / 100.0
        }

        /** 分数 -> 输入框里的百分数文本。 */
        fun pctText(v: Double): String {
            val p = v * 100.0
            val r = Math.round(p).toDouble()
            return if (abs(p - r) < 1e-6) r.toInt().toString()
            else String.format(Locale.US, "%.1f", p)
        }
    }
}

/**
 * 减压算法设置：**直接配 GF 两个值**，不再让人从档位里挑。
 *
 * 档位是把连续的 GF 参数离散化，实际用起来总有一个"差一点"的尴尬：
 * 想按 GF 55/75 潜水就只能选 50/70 或 80/80。这里改成自由输入 + 合法性校验。
 *
 * 默认 30/85：深处留大余量、浅处放宽，技术潜水里最常见的一组；
 * 比 50/70 保守，宁可多算一点减压也不低估。
 */
data class DecoSettings(
    val gfLow: Double = 30.0,
    val gfHigh: Double = 85.0,
) {
    fun algorithm(): Deco.Algorithm =
        Deco.algorithmFor(gfLow / 100.0, gfHigh / 100.0).copy(desc = describe())

    /** 描述按"保守程度"给一句人话，取代原来档位上的固定文案。 */
    fun describe(): String = when {
        gfHigh >= 95 && gfLow >= 90 -> "接近纯 Bühlmann，最激进；只适合对照检查。"
        gfHigh >= 85 && gfLow <= 35 -> "技术潜水常用：深处留大余量、浅处放宽。"
        gfHigh >= 80 -> "轻度保守，接近多数电脑表的默认策略。"
        gfHigh >= 65 -> "常见休闲潜水保守设置。"
        else -> "很保守：NDL 会明显缩短，适合冷水/连续多潜。"
    }

    companion object {
        const val MIN = 10.0
        const val MAX = 100.0

        /** 出厂参数（30/85）。详情页拿它判断「现在是不是默认值」。 */
        val DEFAULT = DecoSettings()

        /** GF 合法：都在 10~100，且 low ≤ high（否则 M 值线插值会反向）。 */
        fun valid(low: Double, high: Double): Boolean =
            low in MIN..MAX && high in MIN..MAX && low <= high

        fun fmt(v: Double): String {
            val r = Math.round(v).toDouble()
            return if (abs(v - r) < 1e-6) r.toInt().toString()
            else String.format(Locale.US, "%.0f", v)
        }
    }
}

/** 气体 -> 人话名字。 */
fun gasName(g: Deco.Gas): String = when {
    g.he > 0.005 -> "Tx ${pctOf(g.o2)}/${pctOf(g.he)}"
    g.o2 > 0.995 -> "纯氧"
    g.o2 > 0.215 -> "EAN${pctOf(g.o2)}"
    else -> "空气"
}

private fun pctOf(v: Double): String = Math.round(v * 100).toString()

/** 气体优先级：用户标注 > 记录自带 > 全局默认。 */
fun resolveGas(metas: List<DiveMeta?>, defaultGas: Deco.Gas): Pair<Deco.Gas, GasSource?> {
    metas.firstOrNull { it?.gasSource == GasSource.USER && it.gasO2 != null }?.let {
        return Deco.Gas(it.gasO2!!, it.gasHe ?: 0.0) to GasSource.USER
    }
    metas.firstOrNull { it?.gasSource == GasSource.RECORD && it.gasO2 != null }?.let {
        return Deco.Gas(it.gasO2!!, it.gasHe ?: 0.0) to GasSource.RECORD
    }
    return defaultGas to null
}

/**
 * 一条可展示的潜水：可能是 1 条原始记录，也可能是多条合并而成。
 *
 * [samples] 是**按真实时间轴拼好的剖面**（合并时中间插入水面段），
 * 直接喂给 Deco 就能得到正确的排气结果。
 */
data class DiveEntry(
    val id: String,
    val records: List<Dive>,
    val startMillis: Long,
    /** 从第一条开始到最后一条结束（含水面间隔）。 */
    val durationS: Int,
    /** 各段实际水下时长之和（不含水面间隔）。 */
    val bottomTimeS: Int,
    val maxDepthM: Double,
    /** 时间加权平均深度。 */
    val avgDepthM: Double,
    val tempMinC: Double?,
    val tempMaxC: Double?,
    val samples: List<Sample>,
    /** 实际用于减压计算的气体。 */
    val gas: Deco.Gas = Deco.AIR,
    /** 气体值的来源；null = 按「全局默认气体」算的。 */
    val gasSource: GasSource? = null,
    /** 原始记录里出现过几种气体（>1 表示多气体潜水）。 */
    val gasCount: Int = 0,
) {
    val primary: Dive get() = records.first()
    val mergedCount: Int get() = records.size
    val isMerged: Boolean get() = records.size > 1

    /** 气体标记文案，例如 "EAN32 · 手动标注"、"空气 · 默认"。 */
    val gasLabel: String get() = gasName(gas) + " · " + when (gasSource) {
        GasSource.RECORD -> "记录"
        GasSource.USER -> "手动标注"
        null -> "默认"
    }

    /** 是否高氧（含三混）。 */
    val isNitrox: Boolean get() = gas.o2 > 0.215 || gas.he > 0.005

    val year: Int get() = primary.year
    val month: Int get() = primary.month
    val dateText: String get() = primary.dateText
    val timeText: String get() = primary.timeText
    val durationText: String get() = Fmt.duration(durationS)
    val depthText: String get() = "${Fmt.one(maxDepthM)} m"
    val avgDepthText: String get() = "${Fmt.one(avgDepthM)} m"

    val tempText: String
        get() {
            val lo = tempMinC
            val hi = tempMaxC
            if (lo == null && hi == null) return "—"
            if (lo == null) return "${Fmt.one(hi!!)} °C"
            if (hi == null || abs(hi - lo) < 0.05) return "${Fmt.one(lo)} °C"
            return "${Fmt.one(lo)}–${Fmt.one(hi)} °C"
        }
}

/** 把原始记录 + 用户设置/删除/手动合并，装配成展示用的 [DiveEntry] 列表。 */
object DiveEntries {

    fun build(
        dives: List<Dive>,
        settings: DiveSettings = DiveSettings(),
        manualGroups: List<List<String>> = emptyList(),
        deleted: Set<String> = emptySet(),
        samplesOf: (Dive) -> List<Sample> = { emptyList() },
        metaOf: (Dive) -> DiveMeta? = { null },
        defaultGas: Deco.Gas = Deco.AIR,
    ): List<DiveEntry> {
        val alive = dives.filter { it.file !in deleted }
        if (alive.isEmpty()) return emptyList()
        val byFile = alive.associateBy { it.file }

        // 1) 手动合并组先变成"原子种子"，避免被自动合并规则拆散
        val inManual = HashSet<String>()
        val seeds = ArrayList<MutableList<Dive>>()
        for (g in manualGroups) {
            val recs = g.mapNotNull { byFile[it] }.sortedBy { Analytics.startMillis(it) }
            if (recs.isEmpty()) continue
            recs.forEach { inManual.add(it.file) }
            seeds.add(recs.toMutableList())
        }
        for (d in alive) if (d.file !in inManual) seeds.add(mutableListOf(d))
        seeds.sortBy { Analytics.startMillis(it.first()) }

        // 2) 自动合并：相邻种子间隔 <= 阈值 => 同一次潜水（负间隔/重叠也合并，见 analyze.py）
        val merged = ArrayList<MutableList<Dive>>(seeds.size)
        for (s in seeds) {
            val prev = merged.lastOrNull()
            if (prev != null && settings.autoMerge) {
                val prevEnd = prev.maxOf { Analytics.startMillis(it) + it.divetimeS * 1000L }
                val gapS = (Analytics.startMillis(s.first()) - prevEnd) / 1000.0
                if (gapS <= settings.siThresholdS) {
                    prev.addAll(s)
                    continue
                }
            }
            merged.add(s)
        }

        // 3) 聚合 + 按时间倒序
        return merged
            .map { recs -> entry(recs, samplesOf, metaOf, defaultGas) }
            .sortedByDescending { it.startMillis }
    }

    /** 单组记录 -> [DiveEntry]。 */
    fun entry(
        records: List<Dive>,
        samplesOf: (Dive) -> List<Sample>,
        metaOf: (Dive) -> DiveMeta? = { null },
        defaultGas: Deco.Gas = Deco.AIR,
    ): DiveEntry {
        val ordered = records.sortedBy { Analytics.startMillis(it) }
        val first = ordered.first()
        val t0 = Analytics.startMillis(first)
        val endMillis = ordered.maxOf { Analytics.startMillis(it) + it.divetimeS * 1000L }

        val isSingle = ordered.size == 1
        val samples = if (isSingle) samplesOf(first) else mergeSamples(ordered, t0, samplesOf)
        val (gas, gasSource) = resolveGas(ordered.map { metaOf(it) }, defaultGas)

        return DiveEntry(
            id = first.file,
            records = ordered,
            startMillis = t0,
            durationS = ((endMillis - t0) / 1000L).toInt(),
            bottomTimeS = ordered.sumOf { it.divetimeS },
            maxDepthM = ordered.maxOf { it.maxdepthM },
            avgDepthM = avgDepth(ordered, samplesOf),
            tempMinC = minTemp(ordered, samplesOf),
            tempMaxC = maxTemp(ordered, samplesOf),
            samples = samples,
            gas = gas,
            gasSource = gasSource,
            gasCount = distinctGasCount(ordered, metaOf, samplesOf),
        )
    }

    /** 这条（或这组）记录里出现过几种不同气体；0 表示记录没写气体。 */
    fun distinctGasCount(
        records: List<Dive>,
        metaOf: (Dive) -> DiveMeta?,
        samplesOf: (Dive) -> List<Sample>,
    ): Int {
        val idx = HashSet<Int>()
        for (r in records) for (s in samplesOf(r)) s.gas?.let { idx.add(it) }
        if (idx.isNotEmpty()) return idx.size
        // 剖面还没进内存时用入库时算好的摘要（列表页走的就是这条路）
        val fromSummary = records.maxOfOrNull { it.gasCount } ?: 0
        if (fromSummary > 0) return fromSummary
        // 记录里没写的话，就看元数据里有没有值（手动标注也算一种）
        return if (records.any { metaOf(it)?.gasO2 != null }) 1 else 0
    }

    /**
     * 拼接多条记录的剖面：中间插入水面段（0m 两点），
     * 让"升水停留再下水"这段真实水面间隔参与排气积分。
     */
    fun mergeSamples(records: List<Dive>, t0: Long, samplesOf: (Dive) -> List<Sample>): List<Sample> {
        val out = ArrayList<Sample>()
        var prevEndT = -1
        for (r in records) {
            val s = samplesOf(r)
            if (s.isEmpty()) continue
            val offset = ((Analytics.startMillis(r) - t0) / 1000L).toInt()
            if (prevEndT >= 0 && offset > prevEndT) {
                out.add(Sample(prevEndT, 0.0, null))
                out.add(Sample(offset, 0.0, null))
            }
            val base = s.first().t
            for (p in s) out.add(Sample(offset + (p.t - base), p.depth, p.temp, p.gas))
            prevEndT = out.last().t
        }
        return out
    }

    /**
     * 时间加权平均深度（梯形积分；只算水下段，不含水面间隔）。
     *
     * 剖面没进内存时回落到入库时算好的 `avg_depth_m`，按水下时长加权 ——
     * 这样列表页不必等六万个采样点读完就能显示正确的平均深度，
     * 而且两种口径走的是同一套梯形积分（见 [SampleStats.avgDepth]），结果一致。
     */
    fun avgDepth(records: List<Dive>, samplesOf: (Dive) -> List<Sample>): Double {
        var area = 0.0
        var time = 0.0
        for (r in records) {
            val s = samplesOf(r)
            if (s.size < 2) {
                if (r.avgDepthM > 0 && r.divetimeS > 0) {
                    area += r.avgDepthM * r.divetimeS
                    time += r.divetimeS
                }
                continue
            }
            for (i in 1 until s.size) {
                val dt = (s[i].t - s[i - 1].t).toDouble()
                if (dt <= 0) continue
                area += (s[i].depth + s[i - 1].depth) / 2.0 * dt
                time += dt
            }
        }
        return if (time > 0) area / time else records.maxOf { it.maxdepthM }
    }

    fun minTemp(records: List<Dive>, samplesOf: (Dive) -> List<Sample>): Double? {
        var v: Double? = null
        for (r in records) for (s in samplesOf(r)) {
            val t = s.temp ?: continue
            if (v == null || t < v!!) v = t
        }
        return v ?: records.minOfOrNull { it.tempMinC }
    }

    fun maxTemp(records: List<Dive>, samplesOf: (Dive) -> List<Sample>): Double? {
        var v: Double? = null
        for (r in records) for (s in samplesOf(r)) {
            val t = s.temp ?: continue
            if (v == null || t > v!!) v = t
        }
        // 摘要里的最高水温（入库时算的）兜底，列表页不依赖剖面
        return v ?: records.mapNotNull { it.tempMaxC }.maxOrNull()
    }
}

/** 设置/删除/手动合并的持久化（SharedPreferences）。 */
class DivePrefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("dive_log_prefs", Context.MODE_PRIVATE)

    var autoMerge: Boolean
        get() = sp.getBoolean(KEY_AUTO_MERGE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_MERGE, v).apply()

    var siThresholdS: Int
        get() = sp.getInt(KEY_SI, 600)
        set(v) = sp.edit().putInt(KEY_SI, v).apply()

    var junkRule: JunkRule
        get() = JunkRule(sp.getInt(KEY_JUNK_SEC, 60), sp.getFloat(KEY_JUNK_M, 2.0f).toDouble())
        set(v) = sp.edit().putInt(KEY_JUNK_SEC, v.maxDurationS).putFloat(KEY_JUNK_M, v.maxDepthM.toFloat()).apply()

    var deleted: Set<String>
        get() = sp.getStringSet(KEY_DELETED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_DELETED, v).apply()

    var manualGroups: List<List<String>>
        get() = (sp.getString(KEY_GROUPS, "") ?: "")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { seg -> seg.split('|').filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        set(v) = sp.edit()
            .putString(KEY_GROUPS, v.joinToString(";") { it.joinToString("|") })
            .apply()

    val settings: DiveSettings get() = DiveSettings(autoMerge, siThresholdS)

    /** 减压算法：GF 两个值直接可配。默认 30/85（跟 [DecoSettings] 的默认值保持一致）。 */
    var decoSettings: DecoSettings
        get() = DecoSettings(
            sp.getFloat(KEY_GF_LOW, 30f).toDouble(),
            sp.getFloat(KEY_GF_HIGH, 85f).toDouble(),
        )
        set(v) = sp.edit()
            .putFloat(KEY_GF_LOW, v.gfLow.toFloat())
            .putFloat(KEY_GF_HIGH, v.gfHigh.toFloat())
            .apply()

    /** 默认呼吸气体：记录没带气体时用它算减压。 */
    var gasSettings: GasSettings
        get() = GasSettings(
            sp.getFloat(KEY_GAS_O2, 0.21f).toDouble(),
            sp.getFloat(KEY_GAS_HE, 0f).toDouble(),
        )
        set(v) = sp.edit()
            .putFloat(KEY_GAS_O2, v.o2.toFloat())
            .putFloat(KEY_GAS_HE, v.he.toFloat())
            .apply()

    /** 同步默认模式（AUTO / INCREMENTAL / FULL）。 */
    var syncMode: SyncMode
        get() = runCatching { SyncMode.valueOf(sp.getString(KEY_SYNC_MODE, null) ?: "") }
            .getOrDefault(SyncMode.AUTO)
        set(v) = sp.edit().putString(KEY_SYNC_MODE, v.name).apply()

    /** 已配对手表：地址 + 名字。下次进设备页直接连，不用重新扫。 */
    var pairedAddress: String?
        get() = sp.getString(KEY_PAIRED_ADDR, null)
        set(v) = sp.edit().putString(KEY_PAIRED_ADDR, v).apply()

    var pairedName: String?
        get() = sp.getString(KEY_PAIRED_NAME, null)
        set(v) = sp.edit().putString(KEY_PAIRED_NAME, v).apply()

    fun forgetPaired() {
        sp.edit().remove(KEY_PAIRED_ADDR).remove(KEY_PAIRED_NAME).apply()
    }

    private companion object {
        const val KEY_AUTO_MERGE = "auto_merge"
        const val KEY_SI = "si_threshold_s"
        const val KEY_JUNK_SEC = "junk_max_sec"
        const val KEY_JUNK_M = "junk_max_depth_m"
        const val KEY_DELETED = "deleted_files"
        const val KEY_GROUPS = "manual_groups"
        const val KEY_GF_LOW = "gf_low"
        const val KEY_GF_HIGH = "gf_high"
        const val KEY_GAS_O2 = "default_gas_o2"
        const val KEY_GAS_HE = "default_gas_he"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_PAIRED_ADDR = "paired_address"
        const val KEY_PAIRED_NAME = "paired_name"
    }
}
