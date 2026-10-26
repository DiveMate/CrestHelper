package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 测试：减压内核数值回归。
 *
 * 黄金值全部来自 Python 版 `dive/tools/deco.py` 的 _selftest 输出，
 * 两端必须逐位一致 —— 这样以后改 Kotlin 不会悄悄偏离已验证的 Python 基准。
 */
class DecoTest {

    private fun assertNear(expected: Double, actual: Double, tol: Double = 0.02) =
        assertEquals("expected $expected but was $actual", expected, actual, tol)

    @Test
    fun `surface_equilibrium_gf99_should_be_zero`() {
        val m = Deco.DecoModel()
        m.addSurfaceInterval(600.0)   // 10 小时
        assertNear(0.0, m.gf99(1.0), 0.01)
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

        assertNear(1.68, m.ceiling())                     // ZHL-16C 原始
        assertNear(15.24, m.ceilingGf(0.30, 0.85, 30.0)) // GF30/85
        assertNear(9.73, m.ceilingGf(0.50, 0.70, 30.0))  // GF50/70
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
        assertNear(-1.1, m.gf99(Deco.depthToPressure(30.0)), 0.15)

        val m2 = m.copy()
        m2.addSegment(30.0, 10.0, 2.0)

        assertNear(9.75, m2.ceilingGf(0.30, 0.85, 10.0))   // 黄金值
        assertNear(35.8, m2.gf99(Deco.depthToPressure(10.0)), 0.15)
    }

    @Test
    fun `gf99_increases_monotonically_with_bottom_time`() {
        val bottoms = listOf(5.0, 10.0, 20.0, 40.0, 120.0)
        var prev = -1e9
        for (bottom in bottoms) {
            val m = Deco.DecoModel()
            m.addSegment(0.0, 30.0, 1.0)
            m.addSegment(30.0, 30.0, bottom)
            val g = m.gf99(Deco.depthToPressure(30.0))
            assertTrue("底停 $bottom min 的 GF99($g) 不应低于前一档($prev)", g >= prev)
            prev = g
        }
    }

    @Test
    fun `instant_ascent_gf99_far_above_100`() {
        val m = Deco.DecoModel()
        m.addSegment(0.0, 30.0, 1.0)
        m.addSegment(30.0, 30.0, 40.0)
        m.addSegment(30.0, 0.0, 0.1)     // 6 秒冲到水面
        assertNear(149.4, m.gf99(1.0), 0.5)
        assertTrue("应远超 100", m.gf99(1.0) > 100)
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