package com.divelog.app

/**
 * 日志合并与无效日志过滤（纯 Kotlin，无设备可测）。
 *
 * 与 Python 版 `dive/tools/analyze.py` 的 group/merge/filter 规则一致。
 *
 * 背景（真实数据里发现的三类问题）：
 *  1. 中途升水再下水：电脑表会把一次潜水分段记录（dive#130 持续 1 秒 0.29m，
 *     145 秒后又有 #131 持续 2882 秒），必须合并成一次。
 *  2. 浅水误触：<2m 的记录（0.29m/1 秒）多为入水前表没稳住被触发。
 *  3. 短时误触：<60s 的记录多为试机。
 *
 * 合并的关键价值：中间的水面段必须作为**真实水面间隔**参与排气积分，
 * 否则会低估组织余氮、算出过于宽松的减压义务。
 */
object DiveMerger {

    /** 合并判据：两条记录的水面间隔 <= 此值（秒）视为同一次潜水。 */
    const val DEFAULT_SI_THRESHOLD = 600

    /** 有效潜水的最小时长（秒）。 */
    const val MIN_DURATION_S = 60

    /** 有效潜水的最小深度（m）。 */
    const val DEFAULT_MIN_DEPTH = 2.0

    /** 无效记录的标记。 */
    const val FLAG_SHALLOW = "shallow"
    const val FLAG_SHORT = "short"

    /** 一条潜水记录。 */
    data class DiveRecord(
        val id: Int,
        /** 起始时间（epoch 毫秒），用于算真实间隔 */
        val startMillis: Long,
        val durationSec: Int,
        val maxDepth: Double,
        val samples: List<Triple<Int, Double, Double?>> = emptyList(),
    ) {
        val endMillis: Long get() = startMillis + durationSec * 1000L

        /** surface interval：与上一条的间隔（秒）。调用方需保证按时间升序。 */
        fun surfaceIntervalAfter(prev: DiveRecord): Double =
            (startMillis - prev.endMillis) / 1000.0
    }

    /**
     * 按水面间隔把连续记录分组（同组 = 应合并为一次潜水）。
     * 输入必须按 startMillis 升序；返回的分组保持原顺序。
     */
    fun group(dives: List<DiveRecord>, siThreshold: Int = DEFAULT_SI_THRESHOLD): List<List<DiveRecord>> {
        if (dives.isEmpty()) return emptyList()
        val sorted = dives.sortedBy { it.startMillis }
        val groups = ArrayList<List<DiveRecord>>()
        var cur = ArrayList<DiveRecord>()
        var prev: DiveRecord? = null
        for (d in sorted) {
            val p = prev
            if (p != null) {
                val si = d.surfaceIntervalAfter(p)
                // 间隔 > 阈值 或 时间倒退 => 视为新的一次潜水
                if (si > siThreshold || si < 0) {
                    groups.add(cur)
                    cur = ArrayList()
                }
            }
            cur.add(d)
            prev = d
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    /**
     * 把一组记录拼成一条连续剖面。
     *
     * 中间的水面段用头尾各一个 0m 采样表达"停留"，时间跨度等于真实
     * surface interval —— 这样排气积分才正确。
     */
    fun mergeProfile(parts: List<DiveRecord>): List<Triple<Int, Double, Double?>> {
        val out = ArrayList<Triple<Int, Double, Double?>>()
        var baseline = 0                       // 累计时间偏移（秒）
        var prev: DiveRecord? = null

        for ((k, part) in parts.withIndex()) {
            val p = prev
            if (k > 0 && p != null) {
                val si = part.surfaceIntervalAfter(p)
                if (si > 0) {
                    out.add(Triple(baseline, 0.0, null))
                    baseline += si.toInt()
                    out.add(Triple(baseline, 0.0, null))
                }
            }
            val s = part.samples
            if (s.isNotEmpty()) {
                val t0 = s.first().first
                for ((t, d, tp) in s) {
                    out.add(Triple(baseline + (t - t0), d, tp))
                }
                baseline = out.last().first
            }
            prev = part
        }
        return out
    }

    /** 合并后的汇总结果。 */
    data class Merged(
        val id: Int,
        val startMillis: Long,
        val durationSec: Int,
        val maxDepth: Double,
        val sampleCount: Int,
        val mergedFrom: List<Int>,
        val profile: List<Triple<Int, Double, Double?>>,
    )

    /** 把一组记录合并为一条 [Merged]（时长累加、最大深度取最大）。 */
    fun mergeGroup(group: List<DiveRecord>, mergedId: Int = group.firstOrNull()?.id ?: 0): Merged? {
        if (group.isEmpty()) return null
        val first = group.first()
        val last = group.last()
        return Merged(
            id = mergedId,
            startMillis = first.startMillis,
            durationSec = ((last.endMillis - first.startMillis) / 1000L).toInt(),
            maxDepth = group.maxOf { it.maxDepth },
            sampleCount = group.sumOf { it.samples.size },
            mergedFrom = group.map { it.id },
            profile = mergeProfile(group),
        )
    }

    /** 无效记录的判定结果。 */
    data class Validity(val valid: Boolean, val flags: List<String>)

    /**
     * 判定记录是否有效：太浅(<minDepth) 或 太短(<minDuration) 视为误触。
     * 用 > 而非 >= 属于故意：阈值本身就是排除线。
     */
    fun validate(d: DiveRecord, minDepth: Double = DEFAULT_MIN_DEPTH, minDuration: Int = MIN_DURATION_S): Validity {
        val flags = ArrayList<String>(2)
        if (d.maxDepth < minDepth) flags.add(FLAG_SHALLOW)
        if (d.durationSec < minDuration) flags.add(FLAG_SHORT)
        return Validity(flags.isEmpty(), flags)
    }

    /** 合并后无效的记录应从分析中剔除（真实数据：198 条合并结果里 13 条无效）。 */
    fun filterValid(merged: List<Merged>, minDepth: Double = DEFAULT_MIN_DEPTH): List<Merged> =
        merged.filter { it.maxDepth >= minDepth }

    /**
     * 一步到位：原始记录 -> 合并后有效列表。
     * 这是 analyze.py 主流程的等价物。
     */
    fun mergeAndFilter(
        dives: List<DiveRecord>,
        siThreshold: Int = DEFAULT_SI_THRESHOLD,
        minDepth: Double = DEFAULT_MIN_DEPTH,
    ): List<Merged> =
        filterValid(group(dives, siThreshold).mapNotNull { mergeGroup(it) }, minDepth)
}