package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 测试：减压内核数值回归。
 *
 * 黄金值来自 Python 版 `tools/deco.py` 的 _selftest 输出（2026-11 修正 Schreiner
 * 肺泡压基准后重算），两端必须一致 —— 这样以后改 Kotlin 不会悄悄偏离已验证的基准。
 *
 * 注意：黄金值里有升降段的对拍用例 [schreiner_segment_matches_numeric_integration]，
 * 因为「恒底停」恰好是 Schreiner p0/p1 写错也看不出来的情形。
 */
class DecoTest {

    private fun assertNear(expected: Double, actual: Double, tol: Double = 0.02) =
        assertEquals("expected $expected but was $actual", expected, actual, tol)

    @Test
    fun `surface_equilibrium_gf99_should_be_zero`() {
        val m = Deco.DecoModel()
        m.addSurfaceInterval(600.0)   // 10 小时
        assertNear(0.0, m.gf99Surface(), 0.01)
    }

    @Test
    fun `surface_equilibrium_ceiling_should_be_zero`() {
        val m = Deco.DecoModel()
        m.addSurfaceInterval(600.0)
        assertNear(0.0, m.ceiling(), 0.01)
    }

    @Test
    fun `30m20min_ceilings_match_deco_py_golden_values`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 20.0)

        assertNear(1.46, m.ceiling())                     // ZHL-16C 原始
        assertNear(15.13, m.ceilingGf(0.30, 0.85, 30.0)) // GF30/85
        assertNear(9.43, m.ceilingGf(0.50, 0.70, 30.0))  // GF50/70
        // 水面口径的 GF99（标准定义）：已经越过 M 值线
        assertNear(112.8, m.gf99Surface(), 0.2)
    }

    /**
     * Schreiner 段积分 vs 细分数值积分。
     *
     * 2026-11 审查发现 addSegment 把 p1 当肺泡压基准（叠加 R 项等于把深度变化算两遍）：
     * 下降段高估负荷、上升段过度排气。旧的黄金值全是恒底停，恰好测不出来。
     */
    @Test
    fun `schreiner_segment_matches_numeric_integration`() {
        // 第一组织（N2 半时 4 min）从水面平衡 0.79 bar 出发
        val cases = listOf(
            Triple(0.0, 30.0, 1.0) to 0.983980,
            Triple(0.0, 30.0, 100.0) to 3.023233,
            Triple(30.0, 10.0, 2.0) to 1.239433,
        )
        for ((seg, expect) in cases) {
            val (d0, d1, min) = seg
            val m = Deco.DecoModel()
            m.addSegment(d0, d1, min)
            assertEquals(
                "段 $d0→$d1 m / $min min 与数值积分不符",
                expect, m.tissuePressure(0), 1e-4,
            )
        }
    }

    @Test
    fun `more_conservative_GF_gives_deeper_ceiling_monotonic`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 20.0)

        val zhl = m.ceiling()
        val gf80 = m.ceilingGf(0.80, 0.80, 30.0)
        val gf50 = m.ceilingGf(0.50, 0.70, 30.0)
        val gf30 = m.ceilingGf(0.30, 0.85, 30.0)

        // 原始最激进(浅) -> GF30/85 最保守(深)
        assertTrue("zhl($zhl) <= gf80($gf80)", zhl <= gf80)
        assertTrue("gf80($gf80) <= gf50($gf50)", gf80 <= gf50)
        assertTrue("gf50($gf50) <= gf30($gf30)", gf50 <= gf30)
    }

    @Test
    fun `at_10m_GF30_85_ceiling_shallower_and_gf99_positive`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 20.0)
        // 水面口径：30m/20min 已经越过 M 值线（旧测试用当前深度压，恒为负，测不出风险）
        assertNear(112.8, m.gf99Surface(), 0.3)

        val m2 = m.copy()
        m2.addSegment(30.0, 10.0, 2.0)

        assertNear(10.02, m2.ceilingGf(0.30, 0.85, 10.0))   // 黄金值
        assertNear(110.2, m2.gf99Surface(), 0.3)
    }

    @Test
    fun `gf99_increases_monotonically_with_bottom_time`() {
        val bottoms = listOf(5.0, 10.0, 20.0, 40.0, 120.0)
        var prev = -1e9
        for (bottom in bottoms) {
            val m = Deco.DecoModel()
            m.addSegment(0.0, 30.0, 1.0)
            m.addSegment(30.0, 30.0, bottom)
            val g = m.gf99Surface()
            assertTrue("底停 $bottom min 的 GF99($g) 不应低于前一档($prev)", g >= prev)
            prev = g
        }
        // 30m 底停 20 min 的绝对值也锁一下，防止整体比例漂移
        assertNear(112.8, gf99At30m(20.0), 0.3)
    }

    private fun gf99At30m(bottom: Double): Double {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, bottom)
        return m.gf99Surface()
    }

    @Test
    fun `analyze_profile_gf99_uses_surface_definition`() {
        // 30m 底停 25 分钟：按当前深度口径会算成负值/0（旧实现的问题），
        // 直冲水面口径必须明显越过 100%。
        val samples = listOf(0 to 0.0, 60 to 30.0, 1560 to 30.0)
        val res = Deco.analyzeProfile(samples, Deco.ALGORITHMS[1])
        assertTrue("水面口径 GF99 应 >100，实际 ${res.gf99Max}", res.gf99Max > 100)
    }

    @Test
    fun `instant_ascent_gf99_far_above_100`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 40.0)
        m.addSegment(30.0, 0.0, 0.1)     // 6 秒冲到水面
        assertNear(148.5, m.gf99Surface(), 0.5)
        assertTrue("应远超 100", m.gf99Surface() > 100)
    }

    @Test
    fun `no_deco_duty_within_no_deco_limit`() {
        // 18m/15min：休闲潜水的典型免减压潜次
        val samples = buildList {
            add(0 to 0.0)
            add(60 to 18.0)
            add(900 to 18.0)      // 底停 14 分钟
            add(1020 to 6.0)
            add(1080 to 0.0)
        }
        val res = Deco.analyzeProfile(samples, Deco.ALGORITHMS[1])  // GF80/80
        assertFalse("18m/15min 不应有减压义务，实测 ceiling=${res.ceilingMax}", res.decoDuty)
    }

    @Test
    fun `deep_long_dive_should_incur_deco_duty`() {
        // 40m/25min：明确超出免减压极限
        val samples = buildList {
            add(0 to 0.0)
            add(90 to 40.0)
            add(1590 to 40.0)     // 底停 25 分钟
            add(1710 to 10.0)
            add(1770 to 0.0)
        }
        val res = Deco.analyzeProfile(samples, Deco.ALGORITHMS[2])  // GF50/70
        assertTrue("40m/25min 应有减压义务", res.decoDuty)
        assertTrue("减压义务时长的秒数应为正", res.decoSec > 0)
    }

    @Test
    fun `excessive_ascent_rate_counted_as_violation`() {
        // 从 30m 用 1 分钟冲到 0m = 30 m/min，远超 18 m/min
        val samples = listOf(0 to 0.0, 60 to 30.0, 120 to 0.0)
        val res = Deco.analyzeProfile(samples, Deco.ALGORITHMS[1])
        assertTrue("上升速率违规次数应 >0", res.ascentViolations > 0)
        assertNear(30.0, res.ascentMax, 0.5)
    }

    @Test
    fun `empty_profile_returns_zeros_without_crash`() {
        val res = Deco.analyzeProfile(emptyList(), Deco.ALGORITHMS[0])
        assertEquals(0.0, res.gf99Max, 0.0)
        assertEquals(0, res.decoSec)
        assertFalse(res.decoDuty)
    }

    @Test
    fun `non_monotonic_time_data_skipped_without_crash`() {
        // 真机数据偶尔有重复时间戳，解析器必须容错
        val samples = listOf(0 to 0.0, 60 to 10.0, 60 to 10.0, 30 to 5.0, 120 to 0.0)
        val res = Deco.analyzeProfile(samples, Deco.ALGORITHMS[1])
        assertTrue("应正常返回结果", res.ascentMax >= 0.0)
    }

    @Test
    fun `depth_and_pressure_conversion_is_reversible`() {
        for (d in listOf(0.0, 6.0, 30.0, 40.0, 100.0)) {
            val p = Deco.depthToPressure(d)
            assertNear(d, Deco.pressureToDepth(p), 1e-6)
        }
    }

    @Test
    fun `negative_pressure_clamps_to_zero_depth`() {
        assertEquals(0.0, Deco.pressureToDepth(0.5), 0.0)
    }

    @Test
    fun `model_copy_is_independent`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 20.0)
        val before = m.ceiling()

        val c = m.copy()
        c.addSegment(30.0, 30.0, 20.0)   // 只改副本

        assertEquals("副本修改不应影响原模型", before, m.ceiling(), 1e-9)
        assertTrue("副本应变得更保守", c.ceiling() > before)
    }

    @Test
    fun `algorithm_gf_params_strictly_decreasing`() {
        val zhl = Deco.ALGORITHMS[0]
        val gf30 = Deco.ALGORITHMS[3]
        assertEquals(1.00, zhl.gfLow, 0.0)
        assertEquals(0.30, gf30.gfLow, 0.0)
        assertTrue("gf30_85 应比 zhl 保守", gf30.gfLow < zhl.gfLow)
    }
}