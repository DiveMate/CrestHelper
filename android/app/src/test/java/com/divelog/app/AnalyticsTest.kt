package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计聚合回归：合并分组、无效判定、年度/直方图/月度。
 * 这些数字直接决定分析页与统计页的展示，必须锁住。
 */
class AnalyticsTest {

    private fun dive(
        file: String,
        dt: String,
        dur: Int,
        depth: Double,
        temp: Double = 20.0,
    ) = Dive(
        file = file,
        no = file.hashCode() and 0xffff,
        datetime = dt,
        divetimeS = dur,
        maxdepthM = depth,
        tempMinC = temp,
        nsamples = 100,
        bytes = 1000,
    )

    @Test
    fun merge_short_surface_interval_into_one_group() {
        // 真实案例：dive#130 只有 1 秒，145 秒后又下水
        val a = dive("a", "2025-06-01 10:00:00", 1, 0.29)
        val b = dive("b", "2025-06-01 10:02:25", 2882, 7.37)
        val groups = Analytics.mergeGroups(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun merge_splits_when_surface_interval_exceeds_threshold() {
        // 水面间隔 601 秒 > 600 => 两次独立潜水
        val a = dive("a", "2025-06-01 10:00:00", 100, 10.0)
        val b = dive("b", "2025-06-01 10:11:41", 100, 10.0)   // 10:01:40 结束 + 601s
        val groups = Analytics.mergeGroups(listOf(a, b))
        assertEquals(2, groups.size)
    }

    @Test
    fun merge_keeps_exactly_600s_together() {
        val a = dive("a", "2025-06-01 10:00:00", 100, 10.0)
        val b = dive("b", "2025-06-01 10:11:40", 100, 10.0)   // 恰好 600s
        assertEquals(1, Analytics.mergeGroups(listOf(a, b)).size)
    }

    @Test
    fun invalid_flags_detect_shallow_and_short() {
        assertTrue(Analytics.invalidFlags(dive("a", "2025-01-01 00:00:00", 100, 1.9)).contains("浅水"))
        assertTrue(Analytics.invalidFlags(dive("b", "2025-01-01 00:00:00", 59, 5.0)).contains("短时"))
        assertTrue(Analytics.invalidFlags(dive("c", "2025-01-01 00:00:00", 100, 5.0)).isEmpty())
    }

    @Test
    fun by_year_is_sorted_and_counted() {
        val list = listOf(
            dive("a", "2026-01-01 10:00:00", 100, 5.0),
            dive("b", "2025-01-01 10:00:00", 100, 5.0),
            dive("c", "2025-02-01 10:00:00", 100, 5.0),
        )
        assertEquals(listOf(2025 to 2, 2026 to 1), Analytics.byYear(list))
    }

    @Test
    fun depth_histogram_buckets() {
        val list = listOf(
            dive("a", "2025-01-01 10:00:00", 100, 3.0),
            dive("b", "2025-01-01 10:00:00", 100, 7.5),
            dive("c", "2025-01-01 10:00:00", 100, 12.0),
            dive("d", "2025-01-01 10:00:00", 100, 31.0),
        )
        val h = Analytics.depthHistogram(list).toMap()
        assertEquals(1, h["0-5m"])
        assertEquals(1, h["5-10m"])
        assertEquals(1, h["10-15m"])
        assertEquals(1, h["30+m"])
    }

    @Test
    fun monthly_counts_for_one_year() {
        val list = listOf(
            dive("a", "2025-01-05 10:00:00", 100, 5.0),
            dive("b", "2025-01-20 10:00:00", 100, 5.0),
            dive("c", "2025-03-01 10:00:00", 100, 5.0),
            dive("d", "2024-03-01 10:00:00", 100, 5.0),
        )
        val m = Analytics.monthly(list, 2025)
        assertEquals(2, m[0])
        assertEquals(1, m[2])
        assertEquals(0, m[11])
    }

    @Test
    fun duplicate_sorting_is_stable_for_same_timestamp() {
        // 同一时间的脏数据不应把分组搞崩
        val a = dive("a", "2025-06-01 10:00:00", 100, 5.0)
        val b = dive("b", "2025-06-01 10:00:00", 100, 6.0)
        val groups = Analytics.mergeGroups(listOf(a, b))
        assertEquals(1, groups.size)
    }
}
