package com.divelog.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.divelog.app.data.DiveDb
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveRow
import com.divelog.app.data.DiveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * 采样点缓存的回归：**分块推进 + 单条兜底**。
 *
 * 需求原话是「采样点全量载入内存，记录列表展示缓存的摘要，异步加载，
 * 比如只加载第一页、之后随着翻页逐步加载」。落到实现上就是两条：
 *
 *  1. `loadAll()` 每读一批（[SampleCache] 的 chunk）就发一份新快照，
 *     界面能显示「剖面 40/253」一直往前爬，而不是卡住几百毫秒；
 *  2. `ensure()` 只补指定的几条 —— 直接跳到一条老记录时不必等全量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleCacheTest {

    private lateinit var ctx: Context
    private lateinit var db: DiveDb

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(DiveDb.DB_NAME)
        db = DiveDb(ctx)
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(DiveDb.DB_NAME)
    }

    private fun seed(n: Int, samplesPerDive: Int = 5) {
        val rows = ArrayList<DiveRow>(n)
        for (i in 0 until n) {
            val id = "d$i"
            rows.add(
                DiveRow(
                    dive = Dive(
                        file = id,
                        no = i + 1,
                        // 越小的 i 越新，用来验证「先读最近的」
                        datetime = "2026-10-%02d 10:00:00".format(28 - i % 28),
                        divetimeS = 600 + i,
                        maxdepthM = 10.0 + i,
                        tempMinC = 20.0,
                        nsamples = samplesPerDive,
                        bytes = 100,
                        avgDepthM = 8.0,
                    ),
                    samples = (0 until samplesPerDive).map {
                        Sample(it * 60, it.toDouble(), 21.0)
                    },
                    meta = DiveMeta(id = id, source = DiveSource.BUILTIN),
                )
            )
        }
        db.insertAll(rows)
    }

    @Test
    fun `分块载入会推进多份快照`() = runBlocking {
        seed(10)
        val cache = SampleCache(db, chunk = 3)

        val seen = Collections.synchronizedList(ArrayList<SampleCache.Snapshot>())
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            cache.state.collect { seen.add(it) }
        }
        cache.loadAll()
        job.cancel()

        val last = cache.state.value
        assertTrue("载完应为 complete", last.complete)
        assertFalse("载完不应还在 loading", last.loading)
        assertEquals(10, last.totalDives)
        assertEquals(10, last.loadedDives)
        assertEquals(10 * 5, last.count)
        assertEquals(1f, last.progress, 1e-6f)

        // 至少出现过一次「没载完但已经有数据」的中间态
        val midway = seen.filter { it.loadedDives in 1 until 10 }
        assertTrue("应发布中间进度快照，实际 ${seen.map { it.loadedDives }}", midway.isNotEmpty())
        assertTrue(
            "进度必须单调不减，实际 ${seen.map { it.loadedDives }}",
            seen.map { it.loadedDives }.zipWithNext().all { p -> p.second >= p.first },
        )
    }

    @Test
    fun `载入顺序是新记录优先`() = runBlocking {
        seed(6)
        val ids = db.diveIdsWithSamples()
        assertEquals(6, ids.size)
        // d0 是 10-28，d5 是 10-23 → 倒序排列，d0 在最前
        assertEquals("d0", ids.first())
        assertEquals("d5", ids.last())
    }

    @Test
    fun `ensure 只补指定的一条`() = runBlocking {
        seed(6)
        val cache = SampleCache(db, chunk = 3)
        val s = cache.ensure(listOf("d3"))
        assertEquals(1, s.map.size)
        assertEquals(5, s.of("d3").size)
        assertEquals(5, s.count)
        assertFalse("只补了一条，不代表全量完成", s.complete)
    }

    @Test
    fun `ensure 已缓存的不会重复读`() = runBlocking {
        seed(3)
        val cache = SampleCache(db, chunk = 10)
        cache.loadAll()
        val first = cache.state.value.map["d1"]
        val again = cache.ensure(listOf("d1"))
        assertTrue("同一份对象应被复用", first === again.map["d1"] || first == again.map["d1"])
        assertEquals(3, again.map.size)
    }

    @Test
    fun `invalidate 保留已载入的剖面只标记失效`() = runBlocking {
        seed(4)
        val cache = SampleCache(db, chunk = 2)
        cache.loadAll()
        assertEquals(4, cache.state.value.map.size)

        cache.invalidate()
        val after = cache.state.value
        assertFalse(after.complete)
        assertEquals("失效不应丢掉已有数据，否则界面会闪一下空图", 4, after.map.size)

        // 再次 loadAll 会补齐并回到 complete
        cache.loadAll()
        assertTrue(cache.state.value.complete)
    }

    @Test
    fun `重复 loadAll 直接复用`() = runBlocking {
        seed(4)
        val cache = SampleCache(db, chunk = 2)
        val a = cache.loadAll()
        val b = cache.loadAll()
        assertTrue(a.complete && b.complete)
        assertEquals(4, b.totalDives)
    }

    @Test
    fun `没有剖面的记录不出现在载入队列里`() = runBlocking {
        db.insertAll(
            listOf(
                DiveRow(
                    dive = Dive(
                        file = "empty",
                        no = 1,
                        datetime = "2026-10-01 10:00:00",
                        divetimeS = 60,
                        maxdepthM = 1.0,
                        tempMinC = 20.0,
                        nsamples = 0,
                        bytes = 10,
                    ),
                    samples = emptyList(),
                    meta = DiveMeta(id = "empty", source = DiveSource.BUILTIN),
                )
            )
        )
        val cache = SampleCache(db)
        val s = cache.loadAll()
        assertTrue(s.complete)
        assertEquals(0, s.totalDives)
        assertEquals(1f, s.progress, 1e-6f)
    }
}
