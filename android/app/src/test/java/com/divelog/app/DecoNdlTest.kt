package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NDL（剩余免减压时间）与水面约束的回归测试。
 *
 * 判据说明见 Deco.DecoModel.surfaceCeiling：
 * NDL 问的是"还能不能一口气出水"，所以用 gf_high 在水面的约束，
 * 这与主流电脑表/减压表量级一致（18m GF50/70 实测 ≈ 23 分钟），
 * 而用天花板（gf_low 锚点）会算出 9 分钟，明显不对。
 */
class DecoNdlTest {

    private fun model(vararg segs: Triple<Double, Double, Double>): Deco.DecoModel {
        val m = Deco.DecoModel()
        for ((d0, d1, min) in segs) m.addSegment(d0, d1, min)
        return m
    }

    private fun algo(id: String) = Deco.ALGORITHMS.first { it.id == id }

    @Test
    fun `ndl_at_18m_gf50_70_matches_dive_table_magnitude`() {
        val sec = Deco.ndlSec(Deco.DecoModel(), algo("gf50_70"), 18.0)
        val min = sec / 60.0
        // 实测 23 分钟；给一个足够宽的区间，锁住"量级正确"而不是逐个数字
        assertTrue("18m GF50/70 的 NDL 应落在 20~45 分钟，实测 $min", min in 20.0..45.0)
    }

    @Test
    fun `ndl_shrinks_as_depth_increases`() {
        val a = algo("gf80")
        val ndl18 = Deco.ndlSec(Deco.DecoModel(), a, 18.0)
        val ndl30 = Deco.ndlSec(Deco.DecoModel(), a, 30.0)
        val ndl40 = Deco.ndlSec(Deco.DecoModel(), a, 40.0)
        assertTrue("18m(${ndl18}s) 应长于 30m(${ndl30}s)", ndl18 > ndl30)
        assertTrue("30m(${ndl30}s) 应长于 40m(${ndl40}s)", ndl30 > ndl40)
    }

    @Test
    fun `ndl_is_zero_once_surface_ceiling_appears`() {
        // 30m 待 40 分钟：GF50/70 下已经不能直接出水
        val m = model(
            Triple(0.0, 30.0, 1.0),
            Triple(30.0, 30.0, 40.0),
        )
        assertTrue("水面天花板应 > 0", m.surfaceCeiling(0.70) > 0.0)
        assertEquals(0, Deco.ndlSec(m, algo("gf50_70"), 30.0))
    }

    @Test
    fun `ndl_is_capped_for_shallow_dives`() {
        val sec = Deco.ndlSec(Deco.DecoModel(), algo("zhl16c"), 5.0)
        assertEquals(Deco.NDL_CAP, sec)
    }

    @Test
    fun `surface_ceiling_is_zero_after_full_desaturation`() {
        val m = Deco.DecoModel()
        m.addSurfaceInterval(1000.0)
        // 完全平衡时没有天花板：原始值可能是负数（允许的深度在水面之上），只需 <= 0
        assertTrue("脱饱和后水面天花板应 <= 0，实测 ${m.surfaceCeiling(0.70)}", m.surfaceCeiling(0.70) <= 0.01)
    }

    @Test
    fun `series_fills_ndl_for_every_point_and_decreases_on_bottom`() {
        val samples = buildList {
            add(0 to 0.0)
            add(60 to 18.0)
            for (t in 120..900 step 60) add(t to 18.0)
            add(960 to 0.0)
        }.map { Sample(it.first, it.second, 24.0) }

        val series = Deco.series(samples, algo("gf80"))

        assertEquals(samples.size, series.size)
        assertTrue("NDL 不应为负", series.all { it.ndl >= 0 })
        assertTrue("NDL 不应超过上限", series.all { it.ndl <= Deco.NDL_CAP })
        assertTrue(
            "底停期间 NDL 必须递减（首 ${series[2].ndl}s -> 末 ${series[series.size - 2].ndl}s）",
            series[series.size - 2].ndl < series[2].ndl,
        )
    }

    @Test
    fun `series_can_skip_ndl_for_speed`() {
        val samples = listOf(0 to 0.0, 60 to 20.0, 120 to 0.0)
            .map { Sample(it.first, it.second, null) }
        val series = Deco.series(samples, algo("gf30_85"), computeNdl = false)
        assertTrue("跳过 NDL 时全部应为 0", series.all { it.ndl == 0 })
        assertEquals(samples.size, series.size)
    }

    @Test
    fun `more_conservative_algorithm_gives_shorter_ndl_at_same_depth`() {
        val m1 = model(Triple(0.0, 18.0, 2.0), Triple(18.0, 18.0, 10.0))
        val m2 = m1.copy()
        val loose = Deco.ndlSec(m1, algo("zhl16c"), 18.0)
        val strict = Deco.ndlSec(m2, algo("gf20_60"), 18.0)
        assertTrue("ZHL(${loose}s) 应比 GF20/60(${strict}s) 更宽松", loose > strict)
    }
}
