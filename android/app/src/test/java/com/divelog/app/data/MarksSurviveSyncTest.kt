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
 * 用户标记的持久化与「重同步不抹掉标记」。
 *
 * 这条路径最容易出事：手表每次同步都会用解析结果覆盖同一行，
 * 如果覆盖时把收藏/气体/软删除一起重置，用户标了几十条收藏，
 * 下次连表同步一遍就全没了 —— 而且很难被发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarksSurviveSyncTest {

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

    private fun row(
        id: String,
        watchIndex: Int? = null,
        fingerprint: String? = null,
        favorite: Boolean = false,
        userDeleted: Boolean = false,
        o2: Double? = null,
        gasSource: GasSource? = null,
    ) = DiveRow(
        dive = Dive(
            file = id,
            no = 1,
            datetime = "2026-10-01 10:00:00",
            divetimeS = 1_800,
            maxdepthM = 18.0,
            tempMinC = 22.0,
            nsamples = 3,
            bytes = 1_024,
        ),
        samples = listOf(Sample(0, 0.0, 22.0), Sample(60, 18.0, 21.0), Sample(120, 18.0, 21.0)),
        meta = DiveMeta(
            id = id,
            source = if (watchIndex != null) DiveSource.WATCH else DiveSource.BUILTIN,
            deviceId = if (watchIndex != null) "SN1" else null,
            watchIndex = watchIndex,
            fingerprint = fingerprint,
            favorite = favorite,
            userDeleted = userDeleted,
            gasO2 = o2,
            gasSource = gasSource,
        ),
    )

    @Test
    fun `收藏可持久化也可取消`() {
        db.insertAll(listOf(row("a"), row("b")))
        db.setFavorite(listOf("a"), true)
        assertTrue(db.metaMap()["a"]!!.favorite)
        assertFalse(db.metaMap()["b"]!!.favorite)
        assertEquals(setOf("a"), db.favoriteIds())

        db.setFavorite(listOf("a"), false)
        assertFalse(db.metaMap()["a"]!!.favorite)
        assertTrue(db.favoriteIds().isEmpty())
    }

    @Test
    fun `重新同步同一条记录时收藏与软删除不被抹掉`() {
        db.insertAll(listOf(row("w1", watchIndex = 1, fingerprint = "aa01")))
        db.setFavorite(listOf("w1"), true)
        db.setUserDeleted(listOf("w1"), true)

        // 手表重下同一条（指纹变了，属于"设备重写日志"），元数据里没有任何用户标记
        db.insertAll(listOf(row("w1", watchIndex = 1, fingerprint = "bb02")))

        val m = db.metaMap()["w1"]!!
        assertTrue("收藏必须保留", m.favorite)
        assertTrue("软删除必须保留", m.userDeleted)
        assertEquals("指纹要更新成新的", "bb02", m.fingerprint)
        assertEquals(1, db.count())
    }

    @Test
    fun `重新同步时手动合并组不被抹掉`() {
        db.insertAll(listOf(row("w1", watchIndex = 1), row("w2", watchIndex = 2)))
        db.saveManualGroup(listOf("w1", "w2"))

        db.insertAll(listOf(row("w1", watchIndex = 1), row("w2", watchIndex = 2)))

        assertEquals(MergeKind.MANUAL, db.metaMap()["w1"]!!.mergeKind)
        assertEquals(1, db.manualGroups().size)
    }

    @Test
    fun `用户标注的气体不会被记录自带的气体覆盖`() {
        db.insertAll(listOf(row("w1", watchIndex = 1)))

        // 记录里解析出 EAN32
        db.setRecordGas("w1", 0.32, 0.0)
        val m1 = db.metaMap()["w1"]!!
        assertEquals(0.32, m1.gasO2!!, 1e-9)
        assertEquals(GasSource.RECORD, m1.gasSource)

        // 用户改成 EAN36
        db.setGas("w1", 0.36, 0.0)
        assertEquals(GasSource.USER, db.metaMap()["w1"]!!.gasSource)

        // 再同步一次，记录又说 0.32，但不能盖掉用户的 0.36
        db.setRecordGas("w1", 0.32, 0.0)
        val m2 = db.metaMap()["w1"]!!
        assertEquals(0.36, m2.gasO2!!, 1e-9)
        assertEquals(GasSource.USER, m2.gasSource)

        // 重新解析落库（insertAll 带 RECORD 气体）同样不能盖掉
        db.insertAll(listOf(row("w1", watchIndex = 1, o2 = 0.32, gasSource = GasSource.RECORD)))
        assertEquals(0.36, db.metaMap()["w1"]!!.gasO2!!, 1e-9)
        assertEquals(GasSource.USER, db.metaMap()["w1"]!!.gasSource)
    }

    @Test
    fun `清除气体标注后回到未知状态`() {
        db.insertAll(listOf(row("a")))
        db.setGas("a", 0.32, 0.0)
        assertEquals(GasSource.USER, db.metaMap()["a"]!!.gasSource)

        db.setGas("a", null)
        val m = db.metaMap()["a"]!!
        assertNull(m.gasO2)
        assertNull(m.gasSource)
    }

    @Test
    fun `删除后反复同步同一条不会复活，恢复后也不会被重新删掉`() {
        db.insertAll(listOf(row("w1", watchIndex = 7, fingerprint = "aa01")))
        db.setUserDeleted(listOf("w1"), true)
        assertEquals(setOf("w1"), db.deletedIds())

        // 场景：用户删了一条，之后手表又同步了几轮。
        // 上层对同指纹是直接 SKIPPED，这里假设最坏情况——真的又写了几遍。
        repeat(3) {
            db.insertAll(listOf(row("w1", watchIndex = 7, fingerprint = "aa01")))
            assertTrue("同步必须不能让删掉的记录回到列表", db.metaMap()["w1"]!!.userDeleted)
        }
        assertEquals(setOf("w1"), db.deletedIds())
        assertEquals("反复同步也不能多出记录", 1, db.count())

        // 从回收站恢复
        db.setUserDeleted(listOf("w1"), false)
        assertTrue(db.deletedIds().isEmpty())

        // 恢复之后再同步一次，不能被旧标记重新标成已删除
        db.insertAll(listOf(row("w1", watchIndex = 7, fingerprint = "aa01")))
        assertFalse("恢复后同步不能又把它删掉", db.metaMap()["w1"]!!.userDeleted)
    }

    @Test
    fun `回收站按标记列出，恢复后回到正常列表`() {
        db.insertAll(listOf(row("a"), row("b"), row("c")))
        db.setUserDeleted(listOf("a", "b"), true)

        assertEquals(setOf("a", "b"), db.deletedIds())
        assertEquals("三条记录都还在库里", 3, db.count())

        db.setUserDeleted(listOf("a", "b"), false)
        assertTrue(db.deletedIds().isEmpty())
        assertEquals(3, db.count())
    }

    @Test
    fun `物化过滤标记不会动收藏与气体`() {
        db.insertAll(listOf(row("a", favorite = true, o2 = 0.32, gasSource = GasSource.USER)))
        db.materialize(listOf(AutoGroup("auto_a", listOf("a"))), mapOf("a" to FilterReason.JUNK))

        val m = db.metaMap()["a"]!!
        assertEquals(FilterReason.JUNK, m.filterReason)
        assertTrue("收藏不该被物化过程清掉", m.favorite)
        assertEquals(0.32, m.gasO2!!, 1e-9)
        assertEquals(GasSource.USER, m.gasSource)
    }

    @Test
    fun `库版本升级到 4 且新列可读写`() {
        assertEquals(4, DiveDb.DB_VERSION)
        db.insertAll(listOf(row("a", favorite = true)))
        // 能读到新列就说明建表语句与查询语句是同一套字段
        assertTrue(db.metaMap()["a"]!!.favorite)
        // v3 的摘要列也要能读回来（列表懒加载就靠它）
        assertEquals(0.0, db.allDives().single().avgDepthM, 1e-9)
        // v4 的标注列：写进去要能原样读出来
        db.setAnnotation(
            listOf("a"),
            DiveAnnotation(
                siteName = "青龙湾",
                visibilityM = 12.0,
                suit = SuitKind.WET,
                tags = setOf(DiveTag.REEF, DiveTag.SALT),
            ),
        )
        val m = db.metaMap()["a"]!!
        assertEquals("青龙湾", m.siteName)
        assertEquals(12.0, m.visibilityM!!, 1e-9)
        assertEquals(SuitKind.WET, m.suit)
        assertEquals(setOf(DiveTag.REEF, DiveTag.SALT), m.tags)
    }

    @Test
    fun `标注不会被重同步抹掉`() {
        db.insertAll(listOf(row("a")))
        db.setAnnotation(listOf("a"), DiveAnnotation(siteName = "大堡礁", suit = SuitKind.DRY))
        // 手表又同步了一遍同一条
        db.insertAll(listOf(row("a")))
        val m = db.metaMap()["a"]!!
        assertEquals("重同步后潜点名不能丢", "大堡礁", m.siteName)
        assertEquals(SuitKind.DRY, m.suit)
    }
}
