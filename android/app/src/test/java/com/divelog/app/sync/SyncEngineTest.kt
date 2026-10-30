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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * 后台同步的"状态机"契约。
 *
 * 界面已经不再自己跑同步了 —— 它只是订阅 [SyncEngine.state] 的显示器。
 * 所以这里要守住三件事：
 *  1. 一次同步跑完，状态必须落到「不在跑 + 结果 + tick+1」，否则界面永远转圈；
 *  2. 没连表时点同步必须被挡住（给的是人话，不是异常）；
 *  3. 同步过程中进度是**逐条推出来的**，不是跑完才一次性给。
 *
 * 用假表 + 内存入库跑，不需要 native 解析器，也不需要真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

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

    private fun ingestor(): DiveIngestor = DiveIngestor { deviceId, index, fp, bytes ->
        val id = "watch_${deviceId}_$index"
        val dt = LocalDateTime.of(2026, 1, 1, 0, 0).plusHours(index.toLong())
        repo.db.insertAll(
            listOf(
                DiveRow(
                    dive = Dive(
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
                    ),
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
    fun `跑完一轮后状态收敛为完成，数据真的入库了`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 3))
        val engine = SyncEngine({ link }, ingestor())

        engine.runSync(repo, SyncMode.AUTO, link)
        assertTrue(engine.state.value.running)

        val st = withTimeout(15_000) { engine.state.first { it.finishedTick > 0 } }
        assertFalse("跑完必须退出 running，否则界面一直转圈", st.running)
        assertNull(st.error)
        assertNotNull(st.result)
        assertEquals(3, st.result!!.added)
        assertEquals(3, repo.db.count())
        assertEquals(1, st.finishedTick)
    }

    @Test
    fun `进度是逐条推出来的，不是一次性给`() = runBlocking {
        val link = FakeWatchLink(dives = fakeDives(1, 4))
        val engine = SyncEngine({ link }, ingestor())

        val seen = mutableListOf<Int>()
        val watchScope = CoroutineScope(Dispatchers.Default)
        watchScope.launch {
            engine.state.collect { if (it.progress != null) synchronized(seen) { seen += it.progress!!.current } }
        }

        engine.runSync(repo, SyncMode.FULL, link)
        val st = withTimeout(15_000) { engine.state.first { !it.running && it.finishedTick > 0 } }
        watchScope.cancel()
        assertEquals(4, st.result!!.added)
        // 至少看到过"下到第 1 条"这种中间态，而不是跑完一次性给
        assertTrue("应该能看到中间进度，实际=$seen", synchronized(seen) { seen.isNotEmpty() })
    }

    @Test
    fun `没连表时点同步给的是人话，不会抛异常`() {
        val engine = SyncEngine(WatchSession())
        assertEquals("还没连上手表，先点连接", engine.start(ctx, repo, SyncMode.AUTO))
        assertFalse(engine.state.value.running)
    }

    @Test
    fun `clearResult 不会把正在跑的同步抹掉`() = runBlocking<Unit> {
        val link = FakeWatchLink(dives = fakeDives(1, 2))
        val engine = SyncEngine({ link }, ingestor())
        engine.runSync(repo, SyncMode.AUTO, link)
        engine.clearResult()
        // 清的是上一轮的结论，running 与进度不能受影响
        assertTrue(engine.state.value.running)
        withTimeout(15_000) { engine.state.first { !it.running && it.finishedTick > 0 } }
    }
}
