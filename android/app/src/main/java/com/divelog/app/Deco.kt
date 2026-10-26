package com.divelog.app

import kotlin.math.exp
import kotlin.math.ln

/**
 * ZHL-16C + Gradient Factor 减压内核（纯 Kotlin，无设备可测）。
 *
 * 与 Python 版 `dive/tools/deco.py` 逐行对应，数值已对拍一致。
 * deco.py 自检的黄金值（30m/20min: ceiling ZHL=1.68, GF30/85=15.24, GF50/70=9.73）
 * 在 [DecoTest] 里作为回归基准锁死。
 *
 * 关键坑（勿改）：
 *  1. 组织压力是**惰性气体分压**，而 M 值线 M = A + P_amb/B 用的是**总环境压**。
 *     两者量纲不同，GF99 计算必须用 P_inert = f * P_amb（f = fN2 + fHe）。
 *     漏掉这步：水面平衡会算出 GF99 = -10.7 而不是 0。
 *  2. GF ceiling 的锚点必须用 gf_low（第一减压站）：
 *     先求 c_low；若当前深度还深于 c_low 则 ceiling = c_low；
 *     否则按 depth/c_low 插值 gf。早先用 gf_high 做锚点会导致
 *     gf_low 完全失效（GF30/85 义务反少于 GF50/70）。
 */
object Deco {

    /** 1 bar 对应的海水深度（10 m/bar 简化，与 libdivecomputer 内部一致，误差 <2%） */
    const val M_PER_BAR = 10.0
    const val SURFACE_PRESSURE = 1.0    // bar，水面绝对压

    /** 上升速率告警阈值（m/min）。休闲潜水通用 18，保守 10。 */
    const val ASCENT_LIMIT = 18.0

    const val NCOMP = 16

    // ZHL-16C：氮气半时（分钟）
    private val N2_HALF_TIMES = doubleArrayOf(
        4.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
        109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0,
    )

    // ZHL-16C：氮气 A 系数（bar）
    private val N2_A = doubleArrayOf(
        1.1696, 1.0000, 0.8618, 0.7562, 0.6667, 0.5600,
        0.4947, 0.4500, 0.4187, 0.3798, 0.3497, 0.3223,
        0.2850, 0.2737, 0.2523, 0.2327,
    )

    // ZHL-16C：氮气 B 系数（无量纲）
    private val N2_B = doubleArrayOf(
        0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434,
        0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403,
        0.9477, 0.9544, 0.9602, 0.9653,
    )

    // ZHL-16C：氦气半时（分钟）
    private val HE_HALF_TIMES = doubleArrayOf(
        1.51, 3.02, 4.52, 6.04, 9.05, 12.07, 18.11, 24.14,
        36.20, 48.26, 60.37, 72.45, 90.57, 120.76, 150.94, 181.02,
    )

    private val HE_A = doubleArrayOf(
        1.6189, 1.3830, 1.1919, 1.0458, 0.9220, 0.8205,
        0.7305, 0.6502, 0.5950, 0.5545, 0.5333, 0.5189,
        0.5181, 0.5176, 0.5172, 0.5119,
    )

    private val HE_B = doubleArrayOf(
        0.4770, 0.5747, 0.6527, 0.7223, 0.7582, 0.7957,
        0.8279, 0.8553, 0.8757, 0.8903, 0.8997, 0.9073,
        0.9122, 0.9171, 0.9217, 0.9267,
    )

    /** 呼吸气体。默认空气。 */
    data class Gas(val o2: Double = 0.21, val he: Double = 0.0) {
        val n2: Double get() = 1.0 - o2 - he
        /** 总惰性气体份额 f = fN2 + fHe（GF 公式的关键，见类注释坑1）。 */
        val inertTotal: Double get() = n2 + he
    }

    /** 深度（m） -> 绝对压（bar）。 */
    fun depthToPressure(depthM: Double): Double = SURFACE_PRESSURE + depthM / M_PER_BAR

    /** 绝对压（bar） -> 深度（m），负值钳到 0。 */
    fun pressureToDepth(pressureBar: Double): Double =
        ((pressureBar - SURFACE_PRESSURE) * M_PER_BAR).coerceAtLeast(0.0)

    /**
     * ZHL-16C 组织模型。tissues[i] = (pn2, phe)，单位 bar。
     * 可变状态，仅用于单线程分析；测试可 [copy]。
     */
    class DecoModel(val gas: Gas = AIR) {
        var pn2 = DoubleArray(NCOMP) { SURFACE_PRESSURE * gas.n2 }
            private set
        var phe = DoubleArray(NCOMP) { SURFACE_PRESSURE * gas.he }
            private set

        fun tissuePressure(i: Int): Double = pn2[i] + phe[i]

        /**
         * 一段线性深度变化的 Schreiner 更新。
         *   P = Palv + (P0 - Palv) * e^(-kt) + R * (t - (1 - e^(-kt))/k)
         * R = f_gas * (dP/dt) 是惰性气体分压变化率。
         */
        fun addSegment(depth0: Double, depth1: Double, timeMin: Double, g: Gas = gas) {
            if (timeMin <= 0) return   // 瞬时深度跳变：不更新
            val p0 = depthToPressure(depth0)
            val p1 = depthToPressure(depth1)
            val rate = (p1 - p0) / timeMin      // bar/min

            val fn2 = g.n2
            val fhe = g.he
            for (i in 0 until NCOMP) {
                // 氮气（用段末环境压作为肺泡分压基准）
                val palvN2 = p1 * fn2
                val kN2 = ln(2.0) / N2_HALF_TIMES[i]
                val rN2 = fn2 * rate
                pn2[i] = palvN2 + (pn2[i] - palvN2) * exp(-kN2 * timeMin) +
                    rN2 * (timeMin - (1.0 - exp(-kN2 * timeMin)) / kN2)

                // 氦气
                val palvHe = p1 * fhe
                val kHe = ln(2.0) / HE_HALF_TIMES[i]
                val rHe = fhe * rate
                phe[i] = palvHe + (phe[i] - palvHe) * exp(-kHe * timeMin) +
                    rHe * (timeMin - (1.0 - exp(-kHe * timeMin)) / kHe)
            }
        }

        /** 水面间隔：在 1 bar 呼吸空气排气。 */
        fun addSurfaceInterval(minutes: Double) = addSegment(0.0, 0.0, minutes)

        /** A/B 系数，按组织内 N2/He 比例插值。 */
        private fun ab(i: Int): Pair<Double, Double> {
            val tot = pn2[i] + phe[i]
            if (tot <= 0) return N2_A[i] to N2_B[i]
            val wHe = phe[i] / tot
            return (N2_A[i] * (1 - wHe) + HE_A[i] * wHe) to (N2_B[i] * (1 - wHe) + HE_B[i] * wHe)
        }

        /** Bühlmann M 值线：允许的最大组织惰性气体分压。 */
        fun mValue(i: Int, pAmb: Double): Double {
            val (a, b) = ab(i)
            return a + pAmb / b
        }

        /**
         * GF99：当前组织过饱和梯度相对 M 值线的百分比（取所有组织最大值）。
         *   GF99 = (P_tissue - f*P_amb) / (M - f*P_amb) * 100
         */
        fun gf99(pAmb: Double): Double {
            val f = gas.inertTotal
            val pInert = f * pAmb
            var worst = -1e9
            for (i in 0 until NCOMP) {
                val pt = tissuePressure(i)
                val m = mValue(i, pAmb)
                val denom = m - pInert
                if (denom <= 1e-9) continue
                val g = (pt - pInert) / denom * 100.0
                if (g > worst) worst = g
            }
            return worst
        }

        /**
         * 给定生效 gf，反解天花板深度（m）。
         * 由 P_tissue = f*P_amb + gf*(A + P_amb/B - f*P_amb)
         * 解出 P_amb = (P_tissue - gf*A) / (f*(1-gf) + gf/B)
         */
        private fun ceilingAtGf(gf: Double, f: Double): Double {
            var worst = -1e9
            for (i in 0 until NCOMP) {
                val (a, b) = ab(i)
                val denom = f * (1.0 - gf) + gf / b
                if (denom <= 1e-9) continue
                val pAllowed = (tissuePressure(i) - gf * a) / denom
                val d = (pAllowed - SURFACE_PRESSURE) * M_PER_BAR
                if (d > worst) worst = d
            }
            return worst
        }

        /**
         * 带 Gradient Factor 的天花板（m，>=0）。锚点用 gf_low，见类注释坑2。
         */
        fun ceilingGf(gfLow: Double, gfHigh: Double, depth: Double): Double {
            val f = gas.inertTotal
            val cLow = ceilingAtGf(gfLow, f)
            if (cLow <= 0) return 0.0
            if (depth >= cLow) return cLow
            val frac = (depth / cLow).coerceIn(0.0, 1.0)
            val gf = gfHigh + (gfLow - gfHigh) * frac
            return ceilingAtGf(gf, f).coerceAtLeast(0.0)
        }

        /** 纯 Bühlmann（= GF 100/100）天花板（m）。 */
        fun ceiling(depth: Double = 0.0): Double = ceilingGf(1.0, 1.0, depth)

        /**
         * 直接升到水面的天花板（m）：>0 表示不能直接出水，必须做减压停留。
         *
         * NDL 必须用这个判据而不是 [ceilingGf]：
         *  - [ceilingGf] 是"我该在哪停"的停留深度，锚点是 gf_low（偏保守）；
         *  - NDL 问的是"我还能不能一口气出水"，对应水面的 gf_high 约束。
         * 用前者算 18m/GF50-70 会得到 9 分钟（明显偏离任何减压表），
         * 用后者是 30 分钟，与主流电脑表一致。
         */
        fun surfaceCeiling(gfHigh: Double): Double = ceilingAtGf(gfHigh, gas.inertTotal)

        fun copy(): DecoModel {
            val m = DecoModel(gas)
            m.pn2 = pn2.copyOf()
            m.phe = phe.copyOf()
            return m
        }
    }

    /** 默认呼吸气体：空气。 */
    val AIR = Gas(0.21, 0.0)

    /** 预置算法档位，UI 展示与切换用。 */
    data class Algorithm(
        val id: String,
        val name: String,
        val gfLow: Double,
        val gfHigh: Double,
        val desc: String,
    )

    val ALGORITHMS = listOf(
        Algorithm("zhl16c", "ZHL-16C 原始", 1.00, 1.00, "Bühlmann 原始 M 值线，不施加任何梯度余量。最激进。"),
        Algorithm("gf80", "GF 80/80", 0.80, 0.80, "轻度保守，接近多数电脑表的默认策略。"),
        Algorithm("gf50_70", "GF 50/70", 0.50, 0.70, "常见休闲潜水保守设置。"),
        Algorithm("gf30_85", "GF 30/85", 0.30, 0.85, "技术潜水常用，深处留大余量、浅处放宽。"),
        Algorithm("gf20_60", "GF 20/60", 0.20, 0.60, "极保守，用于压力测试：看看最坏情况下有多少潜次会冒出减压义务。"),
    )

    /** 一条潜水的分析结果。 */
    data class ProfileAnalysis(
        val gf99Max: Double,
        val ceilingMax: Double,
        val decoSec: Int,
        val decoDuty: Boolean,
        val ascentMax: Double,
        val ascentViolations: Int,
    )

    /**
     * 对一条潜水剖面跑完整分析。samples: [(tSec, depthM)]，时间必须递增。
     * 空输入返回全零结果（不抛异常）。
     */
    fun analyzeProfile(
        samples: List<Pair<Int, Double>>,
        algorithm: Algorithm,
        gas: Gas = AIR,
    ): ProfileAnalysis {
        if (samples.isEmpty()) return ProfileAnalysis(0.0, 0.0, 0, false, 0.0, 0)

        val model = DecoModel(gas)
        var gf99Max = -1e9
        var ceilingMax = 0.0
        var decoSec = 0
        var ascentMax = 0.0
        var ascentViolations = 0

        var prevT = samples[0].first
        var prevD = samples[0].second

        for (idx in 1 until samples.size) {
            val (t, d) = samples[idx]
            val dtMin = (t - prevT) / 60.0
            if (dtMin <= 0) { prevT = t; prevD = d; continue }
            model.addSegment(prevD, d, dtMin, gas)

            val pAmb = depthToPressure(d)
            val g99 = model.gf99(pAmb)
            if (g99 > gf99Max) gf99Max = g99

            val c = model.ceilingGf(algorithm.gfLow, algorithm.gfHigh, d)
            if (c > ceilingMax) ceilingMax = c
            if (c > 0.0) decoSec += (t - prevT)

            if (d < prevD) {
                val rate = (prevD - d) / dtMin
                if (rate > ascentMax) ascentMax = rate
                if (rate > ASCENT_LIMIT) ascentViolations++
            }

            prevT = t
            prevD = d
        }

        // 出水后补 30 秒，模拟到达水面的最终状态
        if (prevD > 0) model.addSegment(prevD, 0.0, 0.5, gas)

        return ProfileAnalysis(
            gf99Max = gf99Max.coerceAtLeast(0.0),
            ceilingMax = ceilingMax,
            decoSec = decoSec,
            decoDuty = ceilingMax > 0.0,
            ascentMax = ascentMax,
            ascentViolations = ascentViolations,
        )
    }

    /** NDL 计算上限（秒）：99 分钟以上统一按"99+"展示，够用了。 */
    const val NDL_CAP = 99 * 60

    /** NDL 仿真步长（秒）。1 分钟精度对曲线展示足够，且把开销压到毫秒级。 */
    const val NDL_STEP = 60

    /**
     * 剩余免减压时间（NDL）。
     *
     * 定义：保持当前深度不变，还能待多久而**仍可直接升到水面**。
     *  - 返回 0            => 此刻已经不能直接出水（曲线里会画成红色区）
     *  - 返回 [NDL_CAP]    => 至少还能免减压 99 分钟以上
     *
     * 判据用 [DecoModel.surfaceCeiling]（gf_high 在水面的约束），见其注释。
     * 实现是"原地继续呼吸"的仿真，不改动传入模型（内部 copy）。
     */
    fun ndlSec(
        model: DecoModel,
        algorithm: Algorithm,
        depth: Double,
        capS: Int = NDL_CAP,
        stepS: Int = NDL_STEP,
    ): Int {
        // 已经不能直接出水了就直接返回 0，省掉一次完整仿真
        if (model.surfaceCeiling(algorithm.gfHigh) > 0.01) return 0
        val m = model.copy()
        var t = 0
        while (t < capS) {
            m.addSegment(depth, depth, stepS / 60.0)
            t += stepS
            if (m.surfaceCeiling(algorithm.gfHigh) > 0.01) return t
        }
        return capS
    }

    /** 剖面时间序列上的一个点（画图用）。 */
    data class SeriesPoint(
        val t: Int,
        val depth: Double,
        val temp: Double?,
        val gf99: Double,
        val ceiling: Double,
        /** 剩余免减压时间（秒），见 [ndlSec]。 */
        val ndl: Int = 0,
    )

    /**
     * 逐采样计算 GF99 / 天花板 / NDL，供剖面图叠加。
     * 语义与 [analyzeProfile] 一致：第 0 点组织仍按水面平衡处理，之后逐段积分。
     *
     * NDL 是"从现在起再仿真一段"的昂贵计算，所以只在一组等距抽样点上算
     * （最多约 [ndlPoints] 个），其余点线性插值 —— 曲线形状完全够用，
     * 但把 277 个采样点 × 99 分钟的重复仿真压到毫秒级。
     */
    fun series(
        samples: List<Sample>,
        algorithm: Algorithm,
        gas: Gas = AIR,
        computeNdl: Boolean = true,
        ndlPoints: Int = 60,
    ): List<SeriesPoint> {
        if (samples.isEmpty()) return emptyList()
        val model = DecoModel(gas)
        val out = ArrayList<SeriesPoint>(samples.size)
        val stride = if (!computeNdl) 0 else (samples.size / ndlPoints).coerceAtLeast(1)
        val anchorIdx = ArrayList<Int>()
        val anchorNdl = ArrayList<Int>()

        fun point(s: Sample): SeriesPoint {
            val pAmb = depthToPressure(s.depth)
            return SeriesPoint(
                t = s.t,
                depth = s.depth,
                temp = s.temp,
                gf99 = model.gf99(pAmb).coerceAtLeast(0.0),
                ceiling = model.ceilingGf(algorithm.gfLow, algorithm.gfHigh, s.depth),
            )
        }

        out.add(point(samples[0]))
        if (stride > 0) {
            anchorIdx.add(0)
            anchorNdl.add(ndlSec(model, algorithm, samples[0].depth))
        }
        var prevT = samples[0].t
        var prevD = samples[0].depth
        for (i in 1 until samples.size) {
            val s = samples[i]
            val dt = (s.t - prevT) / 60.0
            if (dt > 0) model.addSegment(prevD, s.depth, dt, gas)
            out.add(point(s))
            if (stride > 0 && (i % stride == 0 || i == samples.size - 1)) {
                anchorIdx.add(i)
                anchorNdl.add(ndlSec(model, algorithm, s.depth))
            }
            prevT = s.t
            prevD = s.depth
        }

        if (stride == 0) return out

        // 锚点之间线性插值（NDL 是阶梯函数，插值只是为了视觉连贯）
        val ndlArr = IntArray(out.size)
        for (k in 0 until anchorIdx.size - 1) {
            val i0 = anchorIdx[k]
            val i1 = anchorIdx[k + 1]
            val v0 = anchorNdl[k]
            val v1 = anchorNdl[k + 1]
            for (i in i0 until i1) {
                val f = if (i1 == i0) 0.0 else (i - i0).toDouble() / (i1 - i0)
                ndlArr[i] = (v0 + (v1 - v0) * f).toInt()
            }
        }
        ndlArr[out.lastIndex] = anchorNdl.last()
        return out.mapIndexed { i, p -> p.copy(ndl = ndlArr[i]) }
    }
}