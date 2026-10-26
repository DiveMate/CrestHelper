package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并 / 清理 / 聚合的回归测试。
 *
 * 这些数字直接决定列表页、顶部统计和分析页的展示，必须锁住：
 *  - 自动合并按"水面间隔 <= 阈值"（含负间隔/重叠）
 *  - 手动合并跨大间隔也必须合并
 *  - 删除是软删除（能恢复），删掉的记录不进任何统计
 *  - 合并后的时长/平均深度/温度极值要按真实时间轴算
 */
class EntriesTest {

    private fun dive(
        file: String,
        dt: String,
        dur: Int,
        depth: Double,
        temp: Double = 20.0,
        no: Int = 1,
    ) = Dive(
        file = file,
        no = no,
        datetime = dt,
        divetimeS = dur,
        maxdepthM = depth,
        tempMinC = temp,
        nsamples = 10,
        bytes = 1000,
    )

    private fun prof(vararg pairs: Pair<Int, Double>, temp: Double? = 25.0): List<Sample> =
        pairs.map { Sample(it.first, it.second, temp) }

    @Test
    fun auto_merge_joins_records_within_threshold() {
        // 真实案例：dive#130 只有 1 秒 0.29m，145 秒后又有 #131 持续 2882 秒
        val a = dive("a", "2025-06-01 10:00:00", 1, 0.29, no = 130)
        val b = dive("b", "2025-06-01 10:02:25", 2882, 7.37, no = 131)

        val entries = DiveEntries.build(listOf(a, b))

        assertEquals(1, entries.size)
        assertEquals(2, entries[0].records.size)
        assertEquals(1 + 2882, entries[0].bottomTimeS)
        // 跨度含中间 145 秒水面间隔
        assertEquals(145 + 2882, entries[0].durationS)
        assertEquals(7.37, entries[0].maxDepthM, 1e-9)
    }

    @Test
    fun auto_merge_splits_beyond_threshold() {
        // 水面间隔 601 秒 > 默认 600 => 两次独立潜水
        val a = dive("a", "2025-06-01 10:00:00", 100, 10.0)
        val b = dive("b", "2025-06-01 10:11:41", 100, 10.0)
        assertEquals(2, DiveEntries.build(listOf(a, b)).size)
    }

    @Test
    fun auto_merge_threshold_is_configurable() {
        // 间隔 300 秒：默认 600 合并成 1 组，调到 180 就分成 2 组
        val a = dive("a", "2025-06-01 10:00:00", 100, 10.0)
        val b = dive("b", "2025-06-01 10:06:40", 100, 10.0)   // 10:01:40 结束 + 300s
        assertEquals(1, DiveEntries.build(listOf(a, b), DiveSettings(siThresholdS = 600)).size)
        assertEquals(2, DiveEntries.build(listOf(a, b), DiveSettings(siThresholdS = 180)).size)
    }

    @Test
    fun auto_merge_can_be_switched_off() {
        val a = dive("a", "2025-06-01 10:00:00", 100, 10.0)
        val b = dive("b", "2025-06-01 10:02:00", 100, 10.0)
        val off = DiveEntries.build(listOf(a, b), DiveSettings(autoMerge = false))
        assertEquals(2, off.size)
        assertFalse(off.any { it.isMerged })
    }

    @Test
    fun manual_group_merges_across_large_gap_even_when_auto_off() {
        val a = dive("a", "2025-06-01 10:00:00", 600, 12.0, no = 1)
        val b = dive("b", "2025-06-01 14:00:00", 600, 15.0, no = 2)   // 4 小时以后
        val entries = DiveEntries.build(
            listOf(a, b),
            DiveSettings(autoMerge = false),
            manualGroups = listOf(listOf("a", "b")),
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].isMerged)
        assertEquals(listOf(1, 2), entries[0].records.map { it.no })
    }

    @Test
    fun deleted_records_disappear_from_entries() {
        val a = dive("a", "2025-06-01 10:00:00", 600, 12.0)
        val b = dive("b", "2025-06-02 10:00:00", 600, 15.0)
        val entries = DiveEntries.build(listOf(a, b), deleted = setOf("a"))
        assertEquals(1, entries.size)
        assertEquals("b", entries[0].id)
    }

    @Test
    fun deleting_every_record_returns_empty_list() {
        val a = dive("a", "2025-06-01 10:00:00", 600, 12.0)
        assertTrue(DiveEntries.build(listOf(a), deleted = setOf("a")).isEmpty())
    }

    @Test
    fun merged_samples_insert_surface_gap_at_zero_depth() {
        val a = dive("a", "2025-06-01 10:00:00", 60, 10.0)
        val b = dive("b", "2025-06-01 10:02:00", 60, 12.0)     // 间隔 60 秒
        val map = mapOf(
            "a" to prof(0 to 0.0, 30 to 10.0, 60 to 0.0),
            "b" to prof(0 to 0.0, 30 to 12.0, 60 to 0.0),
        )
        val e = DiveEntries.build(listOf(a, b), samplesOf = { map.getValue(it.file) })[0]

        // 合并后时间轴：0..60（第一段）→ 120..180（第二段）
        assertEquals(0, e.samples.first().t)
        assertEquals(180, e.samples.last().t)
        // 中间水面段必须是 0m，否则排气积分会低估余氮
        val gap = e.samples.filter { it.t in 60..120 }
        assertTrue("应插入水面段采样点", gap.size >= 2)
        assertTrue("水面段深度必须为 0", gap.all { it.depth == 0.0 })
    }

    @Test
    fun merged_entry_aggregates_avg_depth_and_temperature_range() {
        val a = dive("a", "2025-06-01 10:00:00", 60, 10.0, temp = 20.0)
        val b = dive("b", "2025-06-01 10:02:00", 60, 10.0, temp = 28.0)
        val map = mapOf(
            "a" to prof(0 to 0.0, 60 to 10.0, temp = 20.0),      // 平均 5m
            "b" to prof(0 to 0.0, 60 to 10.0, temp = 28.0),
        )
        val e = DiveEntries.build(listOf(a, b), samplesOf = { map.getValue(it.file) })[0]

        assertEquals(5.0, e.avgDepthM, 1e-6)
        assertEquals(20.0, e.tempMinC!!, 1e-6)
        assertEquals(28.0, e.tempMaxC!!, 1e-6)
        assertEquals("20–28 °C", e.tempText)
    }

    @Test
    fun avg_depth_is_time_weighted_not_sample_average() {
        // 0..30s 停在 0m、30..60s 从 0m 匀速下到 20m：时间加权 5m；
        // 若按采样点算术平均会得到 6.67m。
        val a = dive("a", "2025-06-01 10:00:00", 60, 20.0)
        val map = mapOf("a" to prof(0 to 0.0, 30 to 0.0, 60 to 20.0))
        val e = DiveEntries.build(listOf(a), samplesOf = { map.getValue(it.file) })[0]
        assertEquals(5.0, e.avgDepthM, 1e-6)
    }

    @Test
    fun entries_sorted_newest_first() {
        val a = dive("a", "2024-06-01 10:00:00", 600, 12.0)
        val b = dive("b", "2026-06-01 10:00:00", 600, 15.0)
        val c = dive("c", "2025-06-01 10:00:00", 600, 18.0)
        val entries = DiveEntries.build(listOf(a, b, c), DiveSettings(autoMerge = false))
        assertEquals(listOf(2026, 2025, 2024), entries.map { it.year })
    }

    @Test
    fun junk_rule_matches_short_or_shallow() {
        val rule = JunkRule(maxDurationS = 60, maxDepthM = 2.0)
        val short = DiveEntries.build(
            listOf(dive("s", "2025-06-01 10:00:00", 45, 12.0)),
            samplesOf = { emptyList() },
        )[0]
        val shallow = DiveEntries.build(
            listOf(dive("d", "2025-06-01 10:00:00", 900, 1.4)),
            samplesOf = { emptyList() },
        )[0]
        val good = DiveEntries.build(
            listOf(dive("g", "2025-06-01 10:00:00", 1800, 18.0)),
            samplesOf = { emptyList() },
        )[0]

        assertTrue(rule.matches(short))
        assertTrue(rule.matches(shallow))
        assertFalse(rule.matches(good))
    }

    @Test
    fun merging_is_idempotent_when_no_group_matches() {
        val a = dive("a", "2025-06-01 10:00:00", 600, 12.0)
        val once = DiveEntries.build(listOf(a), manualGroups = listOf(listOf("a", "不存在")))
        assertEquals(1, once.size)
        assertEquals(1, once[0].records.size)
    }
}
