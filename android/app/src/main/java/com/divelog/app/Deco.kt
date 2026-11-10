package com.divelog.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import java.util.Locale

/**
 * ZHL-16C + Gradient Factor 减压内核（纯 Kotlin，无设备可测）。
 *
 * 与 Python 版 `tools/deco.py` 逐行对应，数值已对拍一致；黄金值在 [DecoTest] 里锁死。
 *
 * v0.1.4 算法口径校准（2026-11-10，对齐 Subsurface core/deco.cpp 与自写的
 * deco-planner server/app/deco_engine/buhlmann.py）：
 *  1. 参数表换成**标准 ZHL-16C**。旧版 N2 首半时 4.0（应 5.0）、N2 a 系数是
 *     ZHL-16B 那套、He 半时是「N2 半时 ÷ 2.65」推出来的 —— 标着 16C 其实不是。
 *  2. 吸入惰性气体分压扣肺泡水蒸气 0.0627 bar，组织初值同样按
 *     (p_surface - p_wv) * f 平衡；水面压 1.01325 bar、海水密度 1.03 kg/L。
 *  3. GF 天花板改用「冻结第一减压站锚点 + 压力空间割线解析解」，不再逐点
 *     重算锚点、也不再按当前深度线性插值 gf（旧解法系统性过度保守）。
 *
 * 关键坑（勿改）：
 *  1. 组织压力是**惰性气体分压**，而 M 值线 M = A + P_amb/B 用的是**总环境压**。
 *     GF/GF99 的基线必须用 P_inert = f * (P_amb - p_wv)。漏掉这步：水面平衡
 *     会算出 GF99 ≠ 0，100% 阈值线也不再对应 M 值线。
 *  2. a/b 按组织内 N2/He 负荷**线性插值**（Subsurface 同），不是对 1/b 插值。
 *     水蒸气只作用于吸入分压与初始饱和，不进 M 值线公式。
 *  3. GF ceiling 由组织状态决定，与当前深度无关；第一减压站锚点必须按整条
 *     潜水取历史最大值并冻结，否则会随排气漂移。
 *  4. NDL 判据用 [DecoModel.surfaceCeiling]（水面 gf_high 约束），不是 ceilingGf
 *     （第一减压站 gf_low 约束）—— 后者问「该在哪停」，前者问「能否直接出水」。
 */
object Deco {

    /** 平均海平面大气压（bar）。 */
    const val SURFACE_PRESSURE = 1.01325

    /** 37°C 肺泡水蒸气压（bar，Bühlmann Rq=1.0）。 */
    const val WATER_VAPOR_PRESSURE = 0.0627

    /** 海水密度 kg/L，重力加速度 m/s²。 */
    const val WATER_DENSITY = 1.03
    const val GRAVITY = 9.80665
    const val BAR_PER_METER = WATER_DENSITY * GRAVITY / 100.0

    /** 1 bar 对应的海水深度（m），≈ 9.8997；旧版用 10 m/bar 简化。 */
    const val M_PER_BAR = 1.0 / BAR_PER_METER

    /** 上升速率告警阈值（m/min）。休闲潜水通用 18，保守 10。 */
    const val ASCENT_LIMIT = 18.0

    const val NCOMP = 16

    // ZHL-16C：氮气半时（分钟）
    private val N2_HALF_TIMES = doubleArrayOf(
        5.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
        109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0,
    )

    // ZHL-16C：氮气 A 系数（bar）
    private val N2_A = doubleArrayOf(
        1.1696, 1.0000, 0.8618, 0.7562, 0.6200, 0.5043,
        0.4410, 0.4000, 0.3750, 0.3500, 0.3295, 0.3065,
        0.2835, 0.2610, 0.2480, 0.2327,
    )

    // ZHL-16C：氮气 B 系数（无量纲，M = a + p_amb / b）
    private val N2_B = doubleArrayOf(
        0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434,
        0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403,
        0.9477, 0.9544, 0.9602, 0.9653,
    )

    // ZHL-16C：氦气半时（分钟）
    private val HE_HALF_TIMES = doubleArrayOf(
        1.88, 3.02, 4.72, 6.99, 10.21, 14.48, 20.53, 29.11,
        41.20, 55.19, 70.69, 90.34, 115.29, 147.42, 188.24, 240.03,
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
     * ZHL-16C 组织模型。tissues[i] = (pn2, phe)，单位 bar，均为惰性气体分压。
     * 可变状态，仅用于单线程分析；测试可 [copy]。
     */
    class DecoModel(val gas: Gas = AIR) {
        var pn2 = DoubleArray(NCOMP) { (SURFACE_PRESSURE - WATER_VAPOR_PRESSURE) * gas.n2 }
            private set
        var phe = DoubleArray(NCOMP) { (SURFACE_PRESSURE - WATER_VAPOR_PRESSURE) * gas.he }
            private set

        fun tissuePressure(i: Int): Double = pn2[i] + phe[i]

        /**
         * 一段线性深度变化的 Schreiner 更新。
         *   P = Palv + (P0 - Palv) * e^(-kt) + R * (t - (1 - e^(-kt))/k)
         * 其中 P_alv = f_gas * (p_amb0 - p_wv) 是**段起始**的吸入惰性气体分压，
         * R = f_gas * (dp_amb/dt) 是惰性气体分压变化率（水蒸气为常数，不参与微分）。
         *
         * 坑（v0.1.3 审查确认）：Palv 必须用 p0，不能用 p1。用 p1 再叠加 R 项
         * 等于把深度变化算了两遍 —— 下降段负荷虚高、上升段过度排气。恒深度段
         * p0==p1 看不出来，所以以前的黄金值（全是恒底停）一直没暴露。
         */
        fun addSegment(depth0: Double, depth1: Double, timeMin: Double, g: Gas = gas) {
            if (timeMin <= 0) return   // 瞬时深度跳变：不更新
            val p0 = depthToPressure(depth0)
            val p1 = depthToPressure(depth1)
            val rate = (p1 - p0) / timeMin      // bar/min
            val pAlvBase = p0 - WATER_VAPOR_PRESSURE

            val fn2 = g.n2
            val fhe = g.he
            for (i in 0 until NCOMP) {
                // 氮气（肺泡分压基准 = 段起始吸入分压）
                val palvN2 = pAlvBase * fn2
                val kN2 = ln(2.0) / N2_HALF_TIMES[i]
                val eN2 = exp(-kN2 * timeMin)
                pn2[i] = palvN2 + (pn2[i] - palvN2) * eN2 +
                    fn2 * rate * (timeMin - (1.0 - eN2) / kN2)

                // 氦气
                val palvHe = pAlvBase * fhe
                val kHe = ln(2.0) / HE_HALF_TIMES[i]
                val eHe = exp(-kHe * timeMin)
                phe[i] = palvHe + (phe[i] - palvHe) * eHe +
                    fhe * rate * (timeMin - (1.0 - eHe) / kHe)
            }
        }

        /** 水面间隔：在水面呼吸当前气体排气。 */
        fun addSurfaceInterval(minutes: Double) = addSegment(0.0, 0.0, minutes)

        /** A/B 系数，按组织内 N2/He 负荷比例线性插值（见类注释坑2）。 */
        private fun ab(i: Int): Pair<Double, Double> {
            val tot = pn2[i] + phe[i]
            if (tot <= 0) return N2_A[i] to N2_B[i]
            val wHe = phe[i] / tot
            return (N2_A[i] * (1 - wHe) + HE_A[i] * wHe) to (N2_B[i] * (1 - wHe) + HE_B[i] * wHe)
        }

        /** Bühlmann M 值线：允许的最大组织惰性气体分压（用总环境压，不扣水蒸气）。 */
        fun mValue(i: Int, pAmb: Double): Double {
            val (a, b) = ab(i)
            return a + pAmb / b
        }

        /** 环境压对应的吸入惰性气体分压：f * (p_amb - p_wv)。 */
        fun inspiredInertPressure(pAmb: Double): Double =
            gas.inertTotal * (pAmb - WATER_VAPOR_PRESSURE)

        /**
         * GF99 通用式：给定环境压 [pAmb] 下，组织过饱和梯度相对 M 值线的百分比
         * （取所有组织最大值）。
         *   GF99 = (P_tissue - f*(P_amb - p_wv)) / (M - f*(P_amb - p_wv)) * 100
         *
         * 注意这个函数的语义完全取决于 [pAmb]：
         *  - 传 [SURFACE_PRESSURE] = 标准定义的「直冲水面 GF99」，见 [gf99Surface]；
         *  - 传当前深度压 = 「当前深度下的过饱和度」，深水段会恒为 0/负值。
         * 界面、图表、100% 阈值线一律用前者，别在这里传深度压。
         */
        fun gf99(pAmb: Double): Double {
            val pInert = inspiredInertPressure(pAmb)
            var worst = -1e9
            for (i in 0 until NCOMP) {
                val pt = tissuePressure(i)
                val denom = mValue(i, pAmb) - pInert
                if (denom <= 1e-9) continue
                val g = (pt - pInert) / denom * 100.0
                if (g > worst) worst = g
            }
            return worst
        }

        /**
         * 直冲水面 GF99（%）：假设此刻直接升到水面，组织过饱和占 M 值线的百分比。
         *
         * 这才是 GF99 的标准语义（`docs/03` 定义、100% 阈值线、免责文案都按它写），
         * 也是「这次潜水当时是不是其实已经越线了」唯一能回答的指标。
         *
         * 历史教训：2026-11 审查发现调用点传的是当前深度压，导致深水段恒显示 0，
         * 两条真实潜水（00183 / 00185）已经越过 M 值线却被显示成 85%。别再改回去。
         */
        fun gf99Surface(): Double = gf99(SURFACE_PRESSURE)

        /**
         * 常数 gf 下允许的最小环境压（bar），取所有组织最严格的那个。
         * 由 P_tissue = f*(p - p_wv) + gf*(a + p/b - f*(p - p_wv)) 解出：
         *   p = (P_tissue - gf*a + f*p_wv*(1-gf)) / (f*(1-gf) + gf/b)
         */
        private fun ceilingPressureAtGf(gf: Double): Double {
            val f = gas.inertTotal
            var worst = -1e9
            for (i in 0 until NCOMP) {
                val (a, b) = ab(i)
                val denom = f * (1.0 - gf) + gf / b
                if (denom <= 1e-9) continue
                val num = tissuePressure(i) - gf * a +
                    f * WATER_VAPOR_PRESSURE * (1.0 - gf)
                val pAllowed = num / denom
                if (pAllowed > worst) worst = pAllowed
            }
            return worst
        }

        /**
         * 当前时刻的 GF-low 第一减压站锚点压力（bar），不低于水面压。
         *
         * Subsurface 的 `gf_low_pressure_this_dive` 取的是整次潜水里这个值的
         * **历史最大值**：锚点一旦定下就冻结，不随后续排气漂移。
         * [analyzeProfile] 与 [series] 负责按剖面推进这个状态。
         */
        fun gfLowAnchorPressure(gfLow: Double): Double =
            ceilingPressureAtGf(gfLow).coerceAtLeast(SURFACE_PRESSURE)

        /**
         * 带 Gradient Factor 的天花板（m，>=0）。
         *
         * Subsurface / deco-planner 的标准解法：在 (环境压 p, 允许组织张力 Y)
         * 平面上，以水面 `(S, gf_high)` 与冻结的第一减压站 `(L, gf_low)` 连成
         * 直线，解析求 Y = P_tissue 与直线的交点：
         *   Y(p)   = f*(p-p_wv) + gf*(a + p/b - f*(p-p_wv))
         *   Y_S    = Y(S) with gf_high
         *   Y_L    = Y(L) with gf_low
         *   p_ceil = S + (P_tissue - Y_S) * (L - S) / (Y_L - Y_S)
         * 取所有组织中最大的 p_ceil 换算成深度。
         *
         * 常数 gf（gf_low == gf_high）时 Y 本身线性，此解与闭式解精确一致
         * （DecoTest 有对应用例）。Y_L <= Y_S 时水面 gf_high 已允许直接出水，
         * 该组织不存在天花板，按 Subsurface/decoplanner 的做法过滤。
         *
         * [depth] 仅为兼容旧签名保留：GF ceiling 由组织状态决定，与当前深度无关。
         * [anchorPressure] 传整条潜水冻结下来的锚点；< 0 时用当前时刻值。
         */
        @Suppress("UNUSED_PARAMETER")
        fun ceilingGf(
            gfLow: Double,
            gfHigh: Double,
            depth: Double,
            anchorPressure: Double = -1.0,
        ): Double {
            val s = SURFACE_PRESSURE
            var anchor = gfLowAnchorPressure(gfLow)
            if (anchorPressure >= 0.0 && anchorPressure > anchor) anchor = anchorPressure
            if (anchor <= s + 1e-9) return 0.0
            val l = anchor

            val f = gas.inertTotal
            val pwv = WATER_VAPOR_PRESSURE
            var worst = s
            for (i in 0 until NCOMP) {
                val (a, b) = ab(i)

                fun tolerated(p: Double, gf: Double): Double {
                    val inert = f * (p - pwv)
                    return inert + gf * (a + p / b - inert)
                }

                val yS = tolerated(s, gfHigh)
                val yL = tolerated(l, gfLow)
                val slope = (yL - yS) / (l - s)
                if (slope <= 1e-12) continue
                val pAllowed = s + (tissuePressure(i) - yS) / slope
                if (pAllowed > worst) worst = pAllowed
            }
            return pressureToDepth(worst)
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
         * 用后者是 30 分钟量级，与主流电脑表一致。
         */
        fun surfaceCeiling(gfHigh: Double): Double = pressureToDepth(ceilingPressureAtGf(gfHigh))

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

    /**
     * 按 GF 两个值现造一个算法档。
     *
     * 界面已经不再让人从 [ALGORITHMS] 里挑档位（挑不到 55/75 这种值），
     * 但「多档位对拍」那一页仍然需要离散的样本来对比，所以两套并存。
     */
    fun algorithmFor(gfLow: Double, gfHigh: Double): Algorithm {
        // id 和 name 用同一份格式化结果：以前 id 用截断、name 用四舍五入，
        // GF 30.5 会显示成「GF 31/85」，id 却是 gf_30_85 —— 显示与实际参数对不上。
        val lo = pct(gfLow)
        val hi = pct(gfHigh)
        return Algorithm(
            id = "gf_${lo}_$hi",
            name = "GF $lo/$hi",
            gfLow = gfLow,
            gfHigh = gfHigh,
            desc = "",
        )
    }

    /** 百分数文本：整数不带小数点，否则保留一位（30.5 必须显示成 30.5）。 */
    private fun pct(v: Double): String {
        val p = v * 100.0
        val r = Math.round(p).toDouble()
        return if (abs(p - r) < 1e-6) r.toInt().toString() else String.format(Locale.US, "%.1f", p)
    }

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
        var anchor = SURFACE_PRESSURE   // 冻结的第一减压站锚点，只增不减

        var prevT = samples[0].first
        var prevD = samples[0].second

        for (idx in 1 until samples.size) {
            val (t, d) = samples[idx]
            val dtMin = (t - prevT) / 60.0
            // 时间戳重复/倒退：直接跳过这一段，**不要**把 prev 挪到坏点上 ——
            // 挪了之后下一段会从坏点开始，等于把时间轴拉长、多积分一截负荷。
            if (dtMin <= 0) continue
            model.addSegment(prevD, d, dtMin, gas)

            // GF99 用「直冲水面」口径（水面压），不是当前深度压，见 gf99Surface()
            val g99 = model.gf99Surface()
            if (g99 > gf99Max) gf99Max = g99

            anchor = model.gfLowAnchorPressure(algorithm.gfLow).coerceAtLeast(anchor)
            val c = model.ceilingGf(algorithm.gfLow, algorithm.gfHigh, d, anchor)
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

        // 出水后补 30 秒到水面：日志经常在水面截断，最后一段上升没记全。
        // 这一步的 GF99 要计入峰值，否则「出水前那一刻」的风险会被系统性低估。
        if (prevD > 0) {
            model.addSegment(prevD, 0.0, 0.5, gas)
            val g = model.gf99Surface()
            if (g > gf99Max) gf99Max = g
        }

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

    /** NDL 结果量化步长（秒）。二分求出连续值后向下取整到这个粒度。 */
    const val NDL_STEP = 60

    /**
     * 剩余免减压时间（NDL）。
     *
     * 定义：保持当前深度不变，还能待多久而**仍可直接升到水面**。
     *  - 返回 0            => 此刻已经不能直接出水（曲线里会画成红色区）
     *  - 返回 [NDL_CAP]    => 至少还能免减压 99 分钟以上
     *
     * 判据用 [DecoModel.surfaceCeiling]（gf_high 在水面的约束），见其注释。
     * 恒深度段的 Schreiner 是闭式的，任意分钟一步就能算到，因此用二分搜索
     * 代替 99 次逐分钟仿真（deco-planner planner.py 的做法），40 次迭代即可。
     * 不改动传入模型（内部 copy）。
     */
    fun ndlSec(
        model: DecoModel,
        algorithm: Algorithm,
        depth: Double,
        capS: Int = NDL_CAP,
        stepS: Int = NDL_STEP,
    ): Int {
        if (model.surfaceCeiling(algorithm.gfHigh) > 0.01) return 0
        val capMin = capS / 60.0

        fun violates(minutes: Double): Boolean {
            val m = model.copy()
            m.addSegment(depth, depth, minutes)
            return m.surfaceCeiling(algorithm.gfHigh) > 0.01
        }

        if (!violates(capMin)) return capS
        var lo = 0.0
        var hi = capMin
        repeat(40) {
            val mid = (lo + hi) / 2.0
            if (violates(mid)) hi = mid else lo = mid
        }
        val sec = (lo * 60.0).toInt()
        return (sec / stepS) * stepS
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
        var anchor = SURFACE_PRESSURE

        fun point(s: Sample, gfAnchor: Double): SeriesPoint {
            return SeriesPoint(
                t = s.t,
                depth = s.depth,
                temp = s.temp,
                gf99 = model.gf99Surface().coerceAtLeast(0.0),
                ceiling = model.ceilingGf(algorithm.gfLow, algorithm.gfHigh, s.depth, gfAnchor),
            )
        }

        anchor = model.gfLowAnchorPressure(algorithm.gfLow).coerceAtLeast(anchor)
        out.add(point(samples[0], anchor))
        if (stride > 0) {
            anchorIdx.add(0)
            anchorNdl.add(ndlSec(model, algorithm, samples[0].depth))
        }
        var prevT = samples[0].t
        var prevD = samples[0].depth
        for (i in 1 until samples.size) {
            val s = samples[i]
            val dt = (s.t - prevT) / 60.0
            // 时间戳重复/倒退：不积分，也不推进 prev（否则下一段会把时间轴拉长）
            if (dt > 0) {
                model.addSegment(prevD, s.depth, dt, gas)
                prevT = s.t
                prevD = s.depth
            }
            anchor = model.gfLowAnchorPressure(algorithm.gfLow).coerceAtLeast(anchor)
            out.add(point(s, anchor))
            if (stride > 0 && (i % stride == 0 || i == samples.size - 1)) {
                anchorIdx.add(i)
                anchorNdl.add(ndlSec(model, algorithm, s.depth))
            }
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
