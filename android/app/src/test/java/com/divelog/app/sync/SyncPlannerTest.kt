package com.divelog.app.sync

import com.divelog.app.data.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步策略回归：这里每一个分支都对应一种真机上会遇到的状态，
 * 判断错了的后果是「重复下载几百条」或者「漏掉新日志」，都是用户能直接感受到的。
 */
class SyncPlannerTest {

    private fun watch(ndives: Int, last: Int, fw6: Boolean = true) = WatchInfo(
        hardware = "H01.1",
        software = if (fw6) "D01.6.2" else "D01.4.0",
        serial = "SN9F0001",
        fw6 = fw6,
        ndives = ndives,
        lastIndex = last,
    )

    @Test
    fun `首次同步走全量`() {
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), null, null)
        assertEquals(PlanKind.FULL, p.kind)
        assertEquals(20, p.steps)
        assertEquals(1, p.fromIndex)
        assertEquals(20, p.toIndex)
        assertTrue(!p.fallback)
    }

    @Test
    fun `锚点一致时只补缺口`() {
        val local = WatchProgress(maxIndex = 18, fingerprint = "aabbccddeeff", count = 18)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), local, "aabbccddeeff")
        assertEquals(PlanKind.INCREMENTAL, p.kind)
        assertEquals(2, p.steps)
        assertEquals(19, p.fromIndex)
        assertEquals(20, p.toIndex)
        assertTrue(!p.fallback)
    }

    @Test
    fun `没有新日志就是已最新`() {
        val local = WatchProgress(maxIndex = 20, fingerprint = "aabbccddeeff", count = 20)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), local, "aabbccddeeff")
        assertEquals(PlanKind.UP_TO_DATE, p.kind)
        assertEquals(0, p.steps)
        assertTrue(!p.hasWork)
    }

    @Test
    fun `手表条数比本地少说明被覆盖过，降级全量`() {
        val local = WatchProgress(maxIndex = 30, fingerprint = "aabbccddeeff", count = 30)
        // 环形缓冲覆盖：只剩 20 条，但编号仍在涨
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 55), local, "deadbeef0001")
        assertEquals(PlanKind.FULL, p.kind)
        assertEquals(20, p.steps)
        assertTrue(p.fallback)
    }

    @Test
    fun `锚点指纹不一致说明编号重排，降级全量`() {
        val local = WatchProgress(maxIndex = 18, fingerprint = "aabbccddeeff", count = 18)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), local, "001122334455")
        assertEquals(PlanKind.FULL, p.kind)
        assertTrue(p.fallback)
    }

    @Test
    fun `读不到锚点也降级全量`() {
        val local = WatchProgress(maxIndex = 18, fingerprint = "aabbccddeeff", count = 18)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), local, null)
        assertEquals(PlanKind.FULL, p.kind)
        assertTrue(p.fallback)
    }

    @Test
    fun `本地缺指纹时不冒险，降级全量`() {
        val local = WatchProgress(maxIndex = 18, fingerprint = null, count = 18)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(20, 20), local, "aabbccddeeff")
        assertEquals(PlanKind.FULL, p.kind)
        assertTrue(p.fallback)
    }

    @Test
    fun `缺口大于存量不可信，降级全量`() {
        // 本地停在第 2 条，手表却有 10 条且编号跳到 999
        val local = WatchProgress(maxIndex = 2, fingerprint = "aabbccddeeff", count = 2)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(10, 999), local, "aabbccddeeff")
        assertEquals(PlanKind.FULL, p.kind)
        assertEquals(10, p.steps)
        assertTrue(p.fallback)
    }

    @Test
    fun `编号回退说明手表被清空后重新计数`() {
        val local = WatchProgress(maxIndex = 30, fingerprint = "aabbccddeeff", count = 30)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(5, 5), local, "aabbccddeeff")
        assertEquals(PlanKind.FULL, p.kind)
        assertEquals(5, p.steps)
        assertTrue(p.fallback)
    }

    @Test
    fun `编号相同但条数减少也要重扫`() {
        val local = WatchProgress(maxIndex = 30, fingerprint = "aabbccddeeff", count = 30)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(12, 30), local, "aabbccddeeff")
        assertEquals(PlanKind.FULL, p.kind)
        assertTrue(p.fallback)
    }

    @Test
    fun `强制全量不参与增量判断`() {
        val local = WatchProgress(maxIndex = 19, fingerprint = "aabbccddeeff", count = 19)
        val p = SyncPlanner.plan(SyncMode.FULL, watch(20, 20), local, "aabbccddeeff")
        assertEquals(PlanKind.FULL, p.kind)
        assertEquals(20, p.steps)
        assertTrue(!p.fallback)
    }

    @Test
    fun `空手表没有活要干`() {
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(0, 0), null, null)
        assertEquals(PlanKind.UP_TO_DATE, p.kind)
    }

    @Test
    fun `老固件也能增量`() {
        val local = WatchProgress(maxIndex = 8, fingerprint = "aabbccddeeff", count = 8)
        val p = SyncPlanner.plan(SyncMode.AUTO, watch(10, 10, fw6 = false), local, "aabbccddeeff")
        assertEquals(PlanKind.INCREMENTAL, p.kind)
        assertEquals(2, p.steps)
    }
}
