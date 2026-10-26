package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「列表只吃摘要」的回归。
 *
 * 拆分后的契约：
 *  - 列表/统计/筛选**不需要**剖面。平均深度、水温极值、气体种类数都在 `dive` 表里，
 *    入库时按采样点算一次即可。
 *  - 所以 [DiveEntries.build] 不传 `samplesOf` 时，这几项必须仍然正确，
 *    否则一懒加载就会变成「平均深度 0 m、水温 —」，用户看到的就是数据丢失。
 */
class SummaryOnlyTest {

    private fun dive(
        id: String,
        datetime: String,
        divetime: Int,
        maxDepth: Double,
        avgDepth: Double,
        tempMin: Double = 20.0,
        tempMax: Double? = 24.0,
        gasCount: Int = 0,
        nsamples: Int = 100,
    ) = Dive(
        file = id,
        no = 0,
        datetime = datetime,
        divetimeS = divetime,
        maxdepthM = maxDepth,
        tempMinC = tempMin,
        nsamples = nsamples,
        bytes = 1024,
        avgDepthM = avgDepth,
        tempMaxC = tempMax,
        gasCount = gasCount,
    )

    @Test
    fun `没有剖面时平均深度来自摘要`() {
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, avgDepth = 11.5)
        val e = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single()
        assertEquals(11.5, e.avgDepthM, 1e-6)
        assertEquals("11.5 m", e.avgDepthText)
    }

    @Test
    fun `没有剖面时水温极值来自摘要`() {
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, 11.5, tempMin = 17.4, tempMax = 23.6)
        val e = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single()
        assertEquals(17.4, e.tempMinC!!, 1e-6)
        assertEquals(23.6, e.tempMaxC!!, 1e-6)
    }

    @Test
    fun `摘要里没有最高水温时为 null 而不是 0`() {
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, 11.5, tempMax = null)
        val e = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single()
        assertNull(e.tempMaxC)
    }

    @Test
    fun `气体种类数来自摘要`() {
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, 11.5, gasCount = 2)
        val e = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single()
        assertEquals(2, e.gasCount)
    }

    @Test
    fun `合并组的平均深度按水下时长加权`() {
        // A：10 分钟平均 10m；B：30 分钟平均 20m  → 总体 (10*600 + 20*1800) / 2400 = 17.5
        val a = dive("a", "2026-10-01 10:00:00", 600, 12.0, avgDepth = 10.0)
        val b = dive("b", "2026-10-01 10:05:00", 1800, 22.0, avgDepth = 20.0)
        // 两条开始时间重叠 => 一定合并（负间隔也算同一次，见 analyze.py 口径）
        val e = DiveEntries.build(
            listOf(a, b),
            DiveSettings(autoMerge = true, siThresholdS = 600),
        ).single()
        assertEquals(2, e.mergedCount)
        assertEquals(17.5, e.avgDepthM, 1e-6)
        assertEquals(22.0, e.maxDepthM, 1e-6)
        assertEquals(2400, e.bottomTimeS)
    }

    @Test
    fun `剖面在内存时优先用剖面算`() {
        // 摘要故意写错：有剖面时应该以剖面为准（详情页走这条路）
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, avgDepth = 99.0)
        val samples = listOf(
            Sample(0, 0.0, 22.0),
            Sample(600, 20.0, 21.0),
            Sample(1200, 0.0, 22.0),
        )
        val e = DiveEntries.build(
            listOf(d),
            DiveSettings(autoMerge = false),
            samplesOf = { samples },
        ).single()
        // 梯形积分：(0+20)/2*600 + (20+0)/2*600 = 12000，除以 1200 秒 => 10
        assertEquals(10.0, e.avgDepthM, 1e-6)
    }

    @Test
    fun `全部记录都没有摘要时退回最大深度`() {
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, avgDepth = 0.0)
        val e = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single()
        assertEquals(18.0, e.avgDepthM, 1e-6)
    }

    @Test
    fun `摘要口径与剖面口径一致`() {
        // SampleStats（入库时算）与 DiveEntries.avgDepth（视图算）必须是同一套梯形积分，
        // 否则同一条记录在列表和详情里会给出两个平均深度。
        val samples = listOf(
            Sample(0, 0.0, null),
            Sample(120, 8.0, null),
            Sample(300, 18.0, null),
            Sample(900, 6.0, null),
            Sample(1200, 0.0, null),
        )
        val summary = SampleStats.avgDepth(samples)
        val d = dive("a", "2026-10-01 10:00:00", 1200, 18.0, avgDepth = summary)
        val fromSummary = DiveEntries.build(listOf(d), DiveSettings(autoMerge = false)).single().avgDepthM
        val fromProfile = DiveEntries.build(
            listOf(d),
            DiveSettings(autoMerge = false),
            samplesOf = { samples },
        ).single().avgDepthM
        assertEquals(fromProfile, fromSummary, 1e-9)
        assertTrue("平均深度应落在最大深度以内", fromSummary <= 18.0)
    }
}
