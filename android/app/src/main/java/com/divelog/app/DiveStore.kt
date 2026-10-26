package com.divelog.app

import android.content.Context
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
) {
    val primary: Dive get() = records.first()
    val mergedCount: Int get() = records.size
    val isMerged: Boolean get() = records.size > 1

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
            .map { recs -> entry(recs, samplesOf) }
            .sortedByDescending { it.startMillis }
    }

    /** 单组记录 -> [DiveEntry]。 */
    fun entry(records: List<Dive>, samplesOf: (Dive) -> List<Sample>): DiveEntry {
        val ordered = records.sortedBy { Analytics.startMillis(it) }
        val first = ordered.first()
        val t0 = Analytics.startMillis(first)
        val endMillis = ordered.maxOf { Analytics.startMillis(it) + it.divetimeS * 1000L }

        val isSingle = ordered.size == 1
        val samples = if (isSingle) samplesOf(first) else mergeSamples(ordered, t0, samplesOf)

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
        )
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
            for (p in s) out.add(Sample(offset + (p.t - base), p.depth, p.temp))
            prevEndT = out.last().t
        }
        return out
    }

    /** 时间加权平均深度（梯形积分；只算水下段，不含水面间隔）。 */
    fun avgDepth(records: List<Dive>, samplesOf: (Dive) -> List<Sample>): Double {
        var area = 0.0
        var time = 0.0
        for (r in records) {
            val s = samplesOf(r)
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
        return v
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

    private companion object {
        const val KEY_AUTO_MERGE = "auto_merge"
        const val KEY_SI = "si_threshold_s"
        const val KEY_JUNK_SEC = "junk_max_sec"
        const val KEY_JUNK_M = "junk_max_depth_m"
        const val KEY_DELETED = "deleted_files"
        const val KEY_GROUPS = "manual_groups"
    }
}
