package com.divelog.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.divelog.app.Dive
import com.divelog.app.DiveRepository
import com.divelog.app.WatchIngest
import com.divelog.app.data.DiveDb
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveRow
import com.divelog.app.data.DiveSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * 同步端到端（协议 → 计划 → 下载 → 入库），用内存假表跑。
 *
 * 这里验证的是**行为契约**，不是真机兼容性：
 *  - 第一次必须全量
 *  - 第二次只补新增（这是用户最在意的「快」）
 *  - 全量重跑不重复入库
 *  - 单条坏数据不拖垮整轮
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchSyncTest {

    private lateinit var ctx: Context
    private lateinit var repo: DiveRepository

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(DiveDb.DB_NAME)
        repo = DiveRepository(ctx)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(DiveDb.DB_NAME)
    }

    /** 内存入库：绕过 native 解析器（JVM 里没有 .so），但写入真实 SQLite，锚点逻辑才是真的。 */
    private fun memoryIngestor(): DiveIngestor = DiveIngestor { deviceId, index, fp, bytes ->
        val id = "watch_${deviceId}_$index"
        val dt = LocalDateTime.of(2026, 1, 1, 0, 0).plusHours(index.toLong())
        val dive = Dive(
            file = id,
            no = index,
            datetime = "%04d-%02d-%02d %02d:%02d:%02d".format(
                dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute, dt.second,
            ),
            divetimeS = 1_800,
            maxdepthM = 12.0 + index,
            tempMinC = 20.0,
            nsamples = 10,
            bytes = bytes.size.toLong(),
        )
        repo.db.insertAll(
            listOf(
                DiveRow(
                    dive = dive,
                    samples = emptyList(),
                    meta = DiveMeta(
                        id = id,
                        source = DiveSource.WATCH,
                        deviceId = deviceId,
                        watchIndex = index,
                        fingerprint = fp,
                    ),
                )
            )
        )
        WatchIngest.ADDED
    }

    private fun fakeDives(from: Int, to: Int) = (from..to).map {
        FakeWatchLink.FakeDive(index = it, fingerprint = "%012x".format(it * 7L + 1))
    }

    @Test
    fun `首次全量，之后增量只补新增`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 3))
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())

        val first = sync.makePlan(SyncMode.AUTO)
        assertEquals(PlanKind.FULL, first.plan.kind)
        val r1 = sync.run(first)
        assertEquals(3, r1.added)
        assertEquals(3, repo.db.count())

        // 手表又潜了两次
        link.dives = fakeDives(1, 5)

        val second = sync.makePlan(SyncMode.AUTO)
        assertEquals(PlanKind.INCREMENTAL, second.plan.kind)
        assertEquals(2, second.plan.steps)
        assertTrue(second.plan.reason.contains("锚点"))

        val headersBefore = link.commands.count { it == "c0/02" }
        val r2 = sync.run(second)
        assertEquals(2, r2.added)
        assertEquals(5, repo.db.count())
        // 增量只多读了 2 条 header，没有把 5 条全扫一遍
        assertEquals(headersBefore + 2, link.commands.count { it == "c0/02" })
    }

    @Test
    fun `全量重跑不会重复入库`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 4))
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())

        assertEquals(4, sync.run(sync.makePlan(SyncMode.AUTO)).added)
        val again = sync.run(sync.makePlan(SyncMode.FULL))
        assertEquals(0, again.added)
        assertEquals(4, again.skipped)
        assertEquals(4, repo.db.count())
    }

    @Test
    fun `单条坏数据不拖垮整轮`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 5))
        link.failIndex = 2
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())

        val r = sync.run(sync.makePlan(SyncMode.FULL))
        assertEquals(1, r.failed)
        assertEquals(4, r.added)
        assertEquals(4, repo.db.count())
        assertTrue(r.errors.any { it.contains("#2") })
    }

    @Test
    fun `手表清空重排后退回全量且不产生重复`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 6))
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())
        sync.run(sync.makePlan(SyncMode.AUTO))
        assertEquals(6, repo.db.count())

        // 模拟环形缓冲覆盖：旧的 1-4 条没了，只剩 3-8（其中 3-6 内容不变但编号仍在）
        link.dives = fakeDives(3, 8)
        val plan = sync.makePlan(SyncMode.AUTO)
        // 本地 maxIndex=6 的指纹还在手表上，会走增量补 7、8
        assertEquals(PlanKind.INCREMENTAL, plan.plan.kind)
        assertEquals(2, plan.plan.steps)
        sync.run(plan)
        assertEquals(8, repo.db.count())

        // 再模拟一次彻底重排：编号回到 1..3 且内容全新
        link.dives = listOf(
            FakeWatchLink.FakeDive(1, "aaaabbbbcccc"),
            FakeWatchLink.FakeDive(2, "ddddeeeeffff"),
        )
        val replan = sync.makePlan(SyncMode.AUTO)
        assertEquals(PlanKind.FULL, replan.plan.kind)
        assertTrue(replan.plan.fallback)
    }

    @Test
    fun `时间校准按 日期-时间-存储 三步下发并回读校验`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 1))
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())
        val at = LocalDateTime.of(2026, 10, 26, 12, 30, 45)

        val r = sync.setClock(at)
        assertTrue(r is ClockResult.Ok)
        assertArrayEquals(byteArrayOf(26, 10, 26), link.clockDate)
        assertArrayEquals(byteArrayOf(12, 30, 45), link.clockTime)
        assertTrue(link.clockStored)
    }

    @Test
    fun `设备信息解析出新指令集与最新编号`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(10, 21))
        val sync = WatchSync(link, repo, ingestor = memoryIngestor())
        val info = sync.info()
        assertEquals("SN9F0001", info.serial)
        assertTrue(info.fw6)
        assertEquals(12, info.ndives)
        assertEquals(21, info.lastIndex)
    }
}
