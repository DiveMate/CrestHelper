package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 测试：日志合并与无效记录过滤。
 *
 * 用例直接取自 CR-4 真实数据里的三个坑：
 *  - 中途升水：dive#130（1 秒 / 0.29m）与 dive#131（2882 秒）间隔 145 秒，应合并
 *  - 浅水误触：0.29m、1 秒
 *  - 短时误触：<60s
 */
class DiveMergerTest {

    private fun dive(
        id: Int,
        startMin: Long,       // 用分钟表示起始时间，够用且可读
        dur: Int,
        maxDepth: Double = 18.0,
        sampleCount: Int = 10,
    ) = DiveMerger.DiveRecord(
        id = id,
        startMillis = startMin * 60_000L,
        durationSec = dur,
        maxDepth = maxDepth,
        // 采样点铺满整个潜水时长（与真实 dive 剖面一致，间隔 = 时长/点数）
        samples = List(sampleCount) { i ->
            val t = (dur.toDouble() * i / sampleCount).toInt()
            Triple(t, maxDepth * (i + 1) / (sampleCount + 1), 30.0)
        },
    )

    @Test
    fun `short_surface_interval_merges_dives`() {
        // #130 在 t=0，#131 在 145 秒后开始 —— 应合并
        val a = dive(130, startMin = 0, dur = 1, maxDepth = 0.29, sampleCount = 2)
        val b = dive(131, startMin = 2, dur = 2882, maxDepth = 18.2)
        // a 结束于 1s，b 开始于 120s -> si = 119s < 600s
        val groups = DiveMerger.group(listOf(a, b))
        assertEquals("应合并为 1 组", 1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `long_surface_interval_splits_dives`() {
        val a = dive(1, startMin = 0, dur = 1800)      // 0s 开始，1800s 结束
        val b = dive(2, startMin = 45, dur = 1800)     // 2700s 开始 -> si=900s > 600s 阈值
        val groups = DiveMerger.group(listOf(a, b))
        assertEquals("水面间隔 900s 应拆成 2 组", 2, groups.size)
    }

    @Test
    fun `interval_exactly_at_threshold_still_merges`() {
        // 阈值 600s：si == 600 不应拆开（判据是 > 阈值才拆）
        val a = dive(1, startMin = 0, dur = 600)
        val b = dive(2, startMin = 20, dur = 600)   // a 结束于 600s，b 开始于 1200s -> si=600
        val groups = DiveMerger.group(listOf(a, b), siThreshold = 600)
        assertEquals(1, groups.size)
    }

    @Test
    fun `time_going_backwards_starts_new_group`() {
        val a = dive(1, startMin = 100, dur = 600)
        val b = dive(2, startMin = 50, dur = 600)   // 比 a 更早
        val groups = DiveMerger.group(listOf(a, b))
        assertTrue("应能处理乱序数据", groups.isNotEmpty())
        // 排序后 a/b 顺序会调整，但不该抛异常
    }

    @Test
    fun `merged_takes_max_depth_and_cumulative_duration`() {
        val a = dive(130, startMin = 0, dur = 1, maxDepth = 0.29, sampleCount = 2)
        val b = dive(131, startMin = 2, dur = 2882, maxDepth = 18.2)
        val merged = DiveMerger.mergeGroup(listOf(a, b), mergedId = 130)
        requireNotNull(merged)
        assertEquals("应取最大深度", 18.2, merged.maxDepth, 1e-9)
        assertEquals("应记录被合并的源 id", listOf(130, 131), merged.mergedFrom)
        assertTrue("时长应覆盖真实跨度", merged.durationSec >= 2882)
    }

    @Test
    fun `merged_profile_includes_surface_segment`() {
        val a = dive(1, startMin = 0, dur = 120, maxDepth = 20.0)    // 0-120s
        val b = dive(2, startMin = 10, dur = 600, maxDepth = 25.0)   // 600-1200s -> si=480s
        val merged = DiveMerger.mergeGroup(listOf(a, b))
        requireNotNull(merged)

        val profile = merged.profile
        assertTrue("剖面应有数据点", profile.isNotEmpty())
        // 剖面应横跨 a + 水面间隔 + b，即覆盖到 b 的结束（1200s 左右）
        val span = profile.maxOf { it.first.toLong() }
        assertTrue("剖面时间跨度应覆盖两段潜水，实际 $span", span >= 1100L)
        // 中间应有 0m 水面采样点参与排气积分
        assertTrue("剖面应包含 0m 水面段", profile.any { it.second == 0.0 })
    }

    @Test
    fun `shallower_than_2m_flagged_shallow_invalid`() {
        val v = DiveMerger.validate(dive(130, startMin = 0, dur = 1, maxDepth = 0.29))
        assertFalse("0.29m 浅潜应无效", v.valid)
        assertTrue(DiveMerger.FLAG_SHALLOW in v.flags)
    }

    @Test
    fun `shorter_than_60s_flagged_short_invalid`() {
        val v = DiveMerger.validate(dive(1, startMin = 0, dur = 45, maxDepth = 12.0))
        assertFalse("45 秒应无效", v.valid)
        assertTrue(DiveMerger.FLAG_SHORT in v.flags)
    }

    @Test
    fun `shallow_and_short_both_flagged`() {
        val v = DiveMerger.validate(dive(130, startMin = 0, dur = 1, maxDepth = 0.29))
        assertEquals(2, v.flags.size)
        assertTrue(DiveMerger.FLAG_SHALLOW in v.flags)
        assertTrue(DiveMerger.FLAG_SHORT in v.flags)
    }

    @Test
    fun `normal_dive_is_valid`() {
        val v = DiveMerger.validate(dive(1, startMin = 0, dur = 3673, maxDepth = 7.5))
        assertTrue("3673s/7.5m 应有效", v.valid)
        assertTrue(v.flags.isEmpty())
    }

    @Test
    fun `empty_input_returns_empty_without_crash`() {
        assertTrue(DiveMerger.group(emptyList()).isEmpty())
        assertTrue(DiveMerger.mergeAndFilter(emptyList()).isEmpty())
        assertEquals(null, DiveMerger.mergeGroup(emptyList()))
    }

    @Test
    fun `single_dive_forms_own_group_and_merges`() {
        val only = dive(1, startMin = 0, dur = 3673, maxDepth = 7.5, sampleCount = 377)
        val groups = DiveMerger.group(listOf(only))
        assertEquals(1, groups.size)
        val merged = DiveMerger.mergeGroup(groups[0])
        requireNotNull(merged)
        assertEquals(377, merged.sampleCount)
    }

    @Test
    fun `mergeAndFilter_drops_shallow_dives`() {
        val good = dive(1, startMin = 0, dur = 1800, maxDepth = 20.0)
        val shallow = dive(2, startMin = 40, dur = 1800, maxDepth = 0.5)
        val result = DiveMerger.mergeAndFilter(listOf(good, shallow))
        assertEquals("浅潜应被剔除", 1, result.size)
        assertEquals(1, result[0].mergedFrom[0])
    }

    @Test
    fun `profile_timestamps_non_decreasing`() {
        val a = dive(1, startMin = 0, dur = 300, maxDepth = 18.0)
        val b = dive(2, startMin = 12, dur = 600, maxDepth = 22.0)
        val merged = DiveMerger.mergeGroup(listOf(a, b))
        requireNotNull(merged)
        var prev = Int.MIN_VALUE
        for ((t, _, _) in merged.profile) {
            assertTrue("时间戳应单调不减，$t < $prev", t >= prev)
            prev = t
        }
    }
}