package com.divelog.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.divelog.app.Dive
import com.divelog.app.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 存储层回归：这里保证「原始数据永不丢、标记位可反复物化」这两条底线。
 *
 * 用户要的是「后台保留所有元数据：是否合并、是否被过滤都打个标」，
 * 对应到代码就是 [DiveDb.materialize] 必须幂等、且不能碰手动合并组。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiveDbTest {

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

    private fun dive(
        id: String,
        no: Int,
        datetime: String = "2026-10-01 10:00:00",
        divetime: Int = 1_800,
        depth: Double = 12.0,
        bytes: Long = 1_024,
    ) = Dive(
        file = id,
        no = no,
        datetime = datetime,
        divetimeS = divetime,
        maxdepthM = depth,
        tempMinC = 21.5,
        nsamples = 3,
        bytes = bytes,
    )

    private fun samples() = listOf(
        Sample(0, 0.0, 22.0),
        Sample(1, 5.0, 21.0),
        Sample(2, 12.0, null),
    )

    private fun row(
        id: String,
        no: Int,
        source: DiveSource = DiveSource.BUILTIN,
        datetime: String = "2026-10-01 10:00:00",
        deviceId: String? = null,
        watchIndex: Int? = null,
        fingerprint: String? = null,
    ) = DiveRow(
        dive = dive(id, no, datetime),
        samples = samples(),
        meta = DiveMeta(
            id = id,
            source = source,
            deviceId = deviceId,
            watchIndex = watchIndex,
            fingerprint = fingerprint,
        ),
    )

    @Test
    fun `插入后能按原样读回：汇总 采样点 元数据`() {
        db.insertAll(listOf(row("a", 1)))
        assertEquals(1, db.count())
        val d = db.allDives().single()
        assertEquals("a", d.file)
        assertEquals(12.0, d.maxdepthM, 1e-9)

        val s = db.allSamples()["a"]!!
        assertEquals(3, s.size)
        assertEquals(5.0, s[1].depth, 1e-9)
        assertNull("温度缺失必须是 null 而不是 0.0", s[2].temp)

        val m = db.metaMap()["a"]!!
        assertEquals(DiveSource.BUILTIN, m.source)
        assertFalse(m.userDeleted)
        assertNull(m.filterReason)
    }

    @Test
    fun `同 id 重复插入是覆盖而不是新增，采样点不会翻倍`() {
        db.insertAll(listOf(row("a", 1)))
        db.insertAll(listOf(row("a", 1)))
        assertEquals(1, db.count())
        assertEquals(3, db.allSamples()["a"]!!.size)
    }

    @Test
    fun `软删除只改标记，记录仍在库里`() {
        db.insertAll(listOf(row("a", 1), row("b", 2)))
        db.setUserDeleted(listOf("a"), true)

        assertEquals(2, db.count())
        assertTrue(db.metaMap()["a"]!!.userDeleted)
        assertEquals("已删除", db.metaMap()["a"]!!.badge)

        db.setUserDeleted(listOf("a"), false)
        assertFalse(db.metaMap()["a"]!!.userDeleted)
    }

    @Test
    fun `手动合并组可持久化也可拆开`() {
        db.insertAll(listOf(row("a", 1), row("b", 2), row("c", 3)))
        val gid = db.saveManualGroup(listOf("a", "b"), siS = 420)

        val groups = db.manualGroups()
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.single().toSet())
        assertEquals(MergeKind.MANUAL, db.metaMap()["a"]!!.mergeKind)

        db.removeManualGroup(gid)
        assertTrue(db.manualGroups().isEmpty())
        assertEquals(MergeKind.NONE, db.metaMap()["a"]!!.mergeKind)
    }

    @Test
    fun `物化自动合并与过滤标记，且不覆盖手动组`() {
        db.insertAll(listOf(row("a", 1), row("b", 2), row("c", 3)))
        db.saveManualGroup(listOf("a", "b"))

        db.materialize(
            groups = listOf(AutoGroup("auto_c", listOf("c"))),
            filters = mapOf("c" to FilterReason.JUNK),
        )
        val m = db.metaMap()
        // 手动组保留
        assertEquals(MergeKind.MANUAL, m["a"]!!.mergeKind)
        assertNull(m["a"]!!.filterReason)
        // 自动组与过滤标记写回
        assertEquals(MergeKind.AUTO, m["c"]!!.mergeKind)
        assertEquals("auto_c", m["c"]!!.groupId)
        assertEquals(FilterReason.JUNK, m["c"]!!.filterReason)
    }

    @Test
    fun `物化是幂等的，反复跑不会叠加`() {
        db.insertAll(listOf(row("a", 1), row("b", 2)))
        repeat(3) {
            db.materialize(
                groups = listOf(AutoGroup("auto_a", listOf("a", "b"))),
                filters = emptyMap(),
            )
        }
        val m = db.metaMap()
        assertEquals(2, m.values.count { it.groupId == "auto_a" })
        assertEquals(2, m.values.count { it.mergeKind == MergeKind.AUTO })
    }

    @Test
    fun `清掉自动组后手动组仍在`() {
        db.insertAll(listOf(row("a", 1), row("b", 2), row("c", 3)))
        db.saveManualGroup(listOf("a", "b"))
        db.materialize(listOf(AutoGroup("auto_c", listOf("c"))), emptyMap())

        db.clearAutoGroups()
        val m = db.metaMap()
        assertEquals(MergeKind.MANUAL, m["a"]!!.mergeKind)
        assertEquals(MergeKind.NONE, m["c"]!!.mergeKind)
    }

    @Test
    fun `手表进度取最大编号与对应指纹`() {
        db.insertAll(
            listOf(
                row("w1", 1, DiveSource.WATCH, "2026-10-01 10:00:00", "SN1", 1, "aa0000000001"),
                row("w5", 5, DiveSource.WATCH, "2026-10-02 10:00:00", "SN1", 5, "aa0000000005"),
                row("w3", 3, DiveSource.WATCH, "2026-10-03 10:00:00", "SN1", 3, "aa0000000003"),
            )
        )
        val p = db.watchProgress("SN1")!!
        assertEquals(5, p.maxIndex)
        assertEquals("aa0000000005", p.fingerprint)
        assertEquals(3, p.count)
        assertEquals(3, db.fingerprintsOf("SN1").size)
        assertNull(db.watchProgress("OTHER"))
    }

    @Test
    fun `同内容不同编号可被识别为重复`() {
        db.insertAll(listOf(row("a", 1)))
        val d = db.allDives().single()
        assertEquals(1, db.countBySignature(d.datetime, d.divetimeS, d.bytes, "b"))
        assertEquals(0, db.countBySignature(d.datetime, d.divetimeS, d.bytes, "a"))
    }

    @Test
    fun `按来源清理不影响其它来源`() {
        db.insertAll(
            listOf(
                row("a", 1, DiveSource.BUILTIN),
                row("b", 2, DiveSource.FILE),
                row("c", 3, DiveSource.WATCH, deviceId = "SN1", watchIndex = 1),
            )
        )
        db.clearSource(DiveSource.FILE)
        val ids = db.ids()
        assertEquals(setOf("a", "c"), ids)
        assertEquals(2, db.allSamples().size)
    }

    @Test
    fun `dive_no 最大值用于续编号`() {
        db.insertAll(listOf(row("a", 7), row("b", 3)))
        assertEquals(7, db.maxDiveNo())
    }
}
