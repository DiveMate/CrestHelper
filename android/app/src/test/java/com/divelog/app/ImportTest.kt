package com.divelog.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 导入链路的单元测试（无需设备、不加载 .so）。
 *
 * 覆盖三件容易出错、且出错后很难发现的事：
 *  1. 解析结果 -> 入库模型 的字段映射（时间/时长/深度/采样点数）；
 *  2. 去重签名：同一次潜水改文件名后仍能识别为重复；
 *  3. 落盘的 CSV 能被同一个解析器读回来（写读同构），否则重启后数据会"消失"。
 */
class ImportTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("imported-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 构造一个解析结果（模拟 JNI 输出，不依赖 native）。 */
    private fun parsed(
        datetime: String = "2026-10-11 01:44:46",
        divetime: Int = 3673,
        maxdepth: Double = 7.5,
        bytes: Long = 2446,
        samples: Int = 3,
    ): DiveParser.Dive = DiveParser.Dive(
        bytes = bytes.toInt(),
        divetime = divetime,
        maxdepth = maxdepth,
        tempMin = 32.0,
        gasmixCount = 0,
        sampleCount = samples,
        year = datetime.substring(0, 4).toInt(),
        month = datetime.substring(5, 7).toInt(),
        day = datetime.substring(8, 10).toInt(),
        hour = datetime.substring(11, 13).toInt(),
        minute = datetime.substring(14, 16).toInt(),
        second = datetime.substring(17, 19).toInt(),
        samples = (0 until samples).map { i ->
            DiveParser.Sample(t = i * 10, depth = i * 0.5, temp = if (i == 1) null else 30.0 + i)
        },
    )

    @Test
    fun to_dive_maps_fields() {
        val p = parsed()
        val d = DiveIngest.toDive(p, "imp_x", no = 254)
        assertEquals("imp_x", d.file)
        assertEquals(254, d.no)
        assertEquals("2026-10-11 01:44:46", d.datetime)
        assertEquals(3673, d.divetimeS)
        assertEquals(7.5, d.maxdepthM, 1e-9)
        assertEquals(3, d.nsamples)
        assertEquals(2446L, d.bytes)
        // 派生字段也要对（列表按年份/月份筛选依赖它们）
        assertEquals(2026, d.year)
        assertEquals(10, d.month)
    }

    @Test
    fun to_samples_keeps_missing_temperature_as_null() {
        val s = DiveIngest.toSamples(parsed(samples = 3))
        assertEquals(3, s.size)
        assertEquals(10, s[1].t)
        assertNull("缺失温度必须是 null，不能是 0.0", s[1].temp)
        assertEquals(32.0, s[2].temp!!, 1e-9)
    }

    @Test
    fun id_is_stable_and_unique() {
        val p = parsed()
        val first = DiveIngest.idFor(p, emptySet())
        assertTrue("id 应带 imp_ 前缀", first.startsWith("imp_"))
        // 已存在同名 id 时不能复用，否则会覆盖别人的数据
        val second = DiveIngest.idFor(p, setOf(first))
        assertNotEquals(first, second)
        assertFalse(second in setOf(first))
    }

    @Test
    fun signature_ignores_file_name() {
        val a = DiveIngest.toDive(parsed(), "imp_a", 1)
        val b = DiveIngest.toDive(parsed(), "imp_b", 2)
        // 改名/换 id 不影响去重判定
        assertEquals(DiveIngest.signature(a), DiveIngest.signature(b))
    }

    @Test
    fun signature_differs_on_content() {
        val base = DiveIngest.toDive(parsed(), "a", 1)
        val otherTime = DiveIngest.toDive(parsed(datetime = "2026-10-11 09:00:00"), "b", 2)
        val otherBytes = DiveIngest.toDive(parsed(bytes = 2500), "c", 3)
        assertNotEquals(DiveIngest.signature(base), DiveIngest.signature(otherTime))
        assertNotEquals(DiveIngest.signature(base), DiveIngest.signature(otherBytes))
    }

    @Test
    fun store_roundtrip_survives_reload() {
        val store = ImportedStore(dir)
        assertEquals(0, store.count())

        val p = parsed()
        val dive = DiveIngest.toDive(p, DiveIngest.idFor(p, emptySet()), 254)
        store.append(dive, DiveIngest.toSamples(p))

        // 模拟"重启 App"：新建实例重新读盘
        val reloaded = ImportedStore(dir).load()
        assertEquals(1, reloaded.dives.size)
        val got = reloaded.dives[0]
        assertEquals(dive.file, got.file)
        assertEquals(dive.datetime, got.datetime)
        assertEquals(dive.divetimeS, got.divetimeS)
        assertEquals(dive.maxdepthM, got.maxdepthM, 1e-9)
        assertEquals(3, got.nsamples)

        val samples = reloaded.samplesOf(got)
        assertEquals(3, samples.size)
        assertEquals(10, samples[1].t)
        assertNull("空温度列要读回 null", samples[1].temp)
    }

    @Test
    fun store_appends_multiple_without_losing_header() {
        val store = ImportedStore(dir)
        repeat(3) { i ->
            val p = parsed(datetime = "2026-10-1${i + 1} 10:00:00", bytes = (2446 + i).toLong())
            store.append(DiveIngest.toDive(p, "imp_$i", i + 1), DiveIngest.toSamples(p))
        }
        val ds = store.load()
        assertEquals(3, ds.dives.size)
        assertEquals(9, ds.totalSamples)
        assertEquals(setOf("imp_0", "imp_1", "imp_2"), store.ids())
    }

    @Test
    fun store_clear_empties_everything() {
        val store = ImportedStore(dir)
        val p = parsed()
        store.append(DiveIngest.toDive(p, "imp_a", 1), DiveIngest.toSamples(p))
        assertEquals(1, store.count())
        store.clear()
        assertEquals(0, store.count())
        assertTrue(store.ids().isEmpty())
    }

    @Test
    fun imported_entries_go_through_normal_merge_pipeline() {
        // 导入的数据要和内置数据走同一套合并/统计逻辑
        val store = ImportedStore(dir)
        val p1 = parsed(datetime = "2026-10-11 01:00:00", divetime = 600, bytes = 100)
        val p2 = parsed(datetime = "2026-10-11 01:20:00", divetime = 600, bytes = 200)
        store.append(DiveIngest.toDive(p1, "imp_1", 1), DiveIngest.toSamples(p1))
        store.append(DiveIngest.toDive(p2, "imp_2", 2), DiveIngest.toSamples(p2))

        val ds = store.load()
        // 间隔 10 分钟（600s）<= 阈值 => 应合并成 1 组
        val entries = DiveEntries.build(
            ds.dives,
            DiveSettings(autoMerge = true, siThresholdS = 600),
            samplesOf = { ds.samplesOf(it) },
        )
        assertEquals(1, entries.size)
        assertEquals(2, entries[0].mergedCount)
        assertEquals(1200, entries[0].bottomTimeS)
    }

    @Test
    fun summary_text_reads_naturally() {
        assertEquals("成功导入 2 条", ImportSummary(added = 2).text)
        assertEquals(
            "成功导入 2 条，跳过重复 1 条，失败 3 条",
            ImportSummary(added = 2, duplicate = 1, failed = 3).text,
        )
        assertEquals("没有选择文件", ImportSummary().text)
        assertEquals(
            "导入失败 2 条（不是有效的 CR-4 日志）",
            ImportSummary(failed = 2).text,
        )
    }
}
