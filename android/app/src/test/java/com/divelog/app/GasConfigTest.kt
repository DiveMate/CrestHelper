package com.divelog.app

import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveSource
import com.divelog.app.data.GasSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 气体标注与减压参数的可配置性。
 *
 * 背景（用数据说话）：CR-4 的 v0 记录格式**根本不存 FO2**，
 * 把手上的 294 份原始日志全跑一遍，gasmixCount 全是 0、也没有气体切换事件，
 * 所以「当时是不是高氧」程序没法从记录里读出来 —— 只能：
 *   1) 记录真带了气体时（新固件/别的表）用记录的；
 *   2) 否则让用户标一条，或退到全局默认气体；
 *   3) 并且必须把气体真正喂进减压模型，否则标了也只是个装饰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // org.json 只有在 Robolectric 下才是真实现，纯 JVM 会抛 Stub!
class GasConfigTest {

    private fun dive(id: String, datetime: String = "2026-10-01 10:00:00") = Dive(
        file = id,
        no = 1,
        datetime = datetime,
        divetimeS = 1_800,
        maxdepthM = 18.0,
        tempMinC = 22.0,
        nsamples = 3,
        bytes = 1_024,
    )

    private fun meta(
        id: String,
        o2: Double? = null,
        he: Double? = null,
        src: GasSource? = null,
    ) = DiveMeta(id = id, source = DiveSource.FILE, gasO2 = o2, gasHe = he, gasSource = src)

    // ------------------------------------------------------------ 解析层

    @Test
    fun `记录里带气体时能解析出 FO2 与气体切换`() {
        val json = """
            {"ok":true,"bytes":900,"divetime":1200,"maxdepth":20.0,"tempMin":18.0,
             "gasmixCount":2,"sampleCount":2,
             "datetime":{"year":2026,"month":10,"day":1,"hour":10,"minute":0,"second":0},
             "gas":[{"o2":0.2100,"he":0.0000},{"o2":0.3200,"he":0.0000}],
             "samples":[{"t":0,"depth":0.0,"g":0},{"t":60,"depth":18.0,"temp":19.5,"g":1}]}
        """.trimIndent()
        val d = DiveParser.fromJson(json)!!
        assertEquals(2, d.gases.size)
        assertEquals(0.21, d.gases[0].o2, 1e-9)
        assertEquals(0.32, d.gases[1].o2, 1e-9)
        assertEquals("EAN32", d.gases[1].label)
        assertEquals(0, d.samples[0].gas)
        assertEquals(1, d.samples[1].gas)
    }

    @Test
    fun `记录没带气体时 gases 为空样本也没有气体下标`() {
        val json = """
            {"ok":true,"bytes":900,"divetime":600,"maxdepth":7.5,"tempMin":32.0,
             "gasmixCount":0,"sampleCount":2,
             "datetime":{"year":2020,"month":11,"day":22,"hour":12,"minute":5,"second":4},
             "gas":[],
             "samples":[{"t":0,"depth":0.0},{"t":10,"depth":7.5,"temp":32.0}]}
        """.trimIndent()
        val d = DiveParser.fromJson(json)!!
        assertTrue("v0 记录解析不出气体", d.gases.isEmpty())
        assertNull(d.primaryGas)
        assertNull(d.samples[1].gas)
    }

    @Test
    fun `气体名字覆盖空气 高氧 三混 纯氧`() {
        assertEquals("空气", gasName(Deco.Gas(0.21, 0.0)))
        assertEquals("EAN32", gasName(Deco.Gas(0.32, 0.0)))
        assertEquals("Tx 21/35", gasName(Deco.Gas(0.21, 0.35)))
        assertEquals("纯氧", gasName(Deco.Gas(1.0, 0.0)))
    }

    // ------------------------------------------------------------ 优先级

    @Test
    fun `气体优先级：用户标注 高于 记录 高于 默认`() {
        val rec = meta("a", 0.32, 0.0, GasSource.RECORD)
        val usr = meta("a", 0.36, 0.0, GasSource.USER)
        val def = Deco.Gas(0.21, 0.0)

        val (g1, s1) = resolveGas(listOf(usr, rec), def)
        assertEquals(0.36, g1.o2, 1e-9)
        assertEquals(GasSource.USER, s1)

        val (g2, s2) = resolveGas(listOf(rec), def)
        assertEquals(0.32, g2.o2, 1e-9)
        assertEquals(GasSource.RECORD, s2)

        val (g3, s3) = resolveGas(listOf(meta("a")), def)
        assertEquals(0.21, g3.o2, 1e-9)
        assertNull("没标注也没记录，就是默认值", s3)
    }

    @Test
    fun `DiveEntry 的气体来自元数据，且标注后 NDL 会变`() {
        val d = dive("a")
        val samples = listOf(Sample(0, 0.0, 22.0), Sample(600, 18.0, 20.0), Sample(1200, 18.0, 20.0))
        val air = DiveEntries.build(
            listOf(d),
            samplesOf = { samples },
            defaultGas = Deco.Gas(0.21, 0.0),
        ).single()
        assertEquals("空气 · 默认", air.gasLabel)
        assertFalse(air.isNitrox)

        val nitrox = DiveEntries.build(
            listOf(d),
            samplesOf = { samples },
            metaOf = { meta("a", 0.32, 0.0, GasSource.USER) },
        ).single()
        assertEquals("EAN32 · 手动标注", nitrox.gasLabel)
        assertTrue(nitrox.isNitrox)

        // 同样的深度与保守度，高氧的 NDL 必须更长（否则说明气体没进模型）
        val algo = DecoSettings(50.0, 70.0).algorithm()
        val ndlAir = Deco.ndlSec(Deco.DecoModel(air.gas), algo, 30.0, capS = 7200)
        val ndlEan = Deco.ndlSec(Deco.DecoModel(nitrox.gas), algo, 30.0, capS = 7200)
        assertTrue("EAN32 的 NDL($ndlEan s) 应长于空气($ndlAir s)", ndlEan > ndlAir)
    }

    @Test
    fun `气体输入校验：氧气范围 氦气范围 两者之和`() {
        assertTrue(GasSettings.valid(0.21, 0.0))
        assertTrue(GasSettings.valid(1.0, 0.0))
        assertFalse("氧气不能低于 7%", GasSettings.valid(0.05, 0.0))
        assertFalse("氧气不能超过 100%", GasSettings.valid(1.2, 0.0))
        assertFalse("氧+氦不能超过 100%", GasSettings.valid(0.5, 0.6))
        assertEquals(0.32, GasSettings.pctFromInput("32")!!, 1e-9)
        assertEquals(0.315, GasSettings.pctFromInput("31.5")!!, 1e-9)
        assertNull(GasSettings.pctFromInput("abc"))
        assertNull(GasSettings.pctFromInput("0"))
        assertEquals("32", GasSettings.pctText(0.32))
        assertEquals("31.5", GasSettings.pctText(0.315))
    }

    // ------------------------------------------------------------ GF 可配置

    @Test
    fun `GF 直接配两个值，不再受档位限制`() {
        val d = DecoSettings(55.0, 75.0)
        val a = d.algorithm()
        assertEquals("GF 55/75", a.name)
        assertEquals(0.55, a.gfLow, 1e-9)
        assertEquals(0.75, a.gfHigh, 1e-9)
        assertTrue(a.desc.isNotBlank())

        assertTrue(DecoSettings.valid(10.0, 10.0))
        assertTrue(DecoSettings.valid(100.0, 100.0))
        assertFalse("low 不能大于 high", DecoSettings.valid(90.0, 60.0))
        assertFalse("低于下限", DecoSettings.valid(5.0, 60.0))
        assertFalse("高于上限", DecoSettings.valid(50.0, 120.0))
    }

    @Test
    fun `GF 越保守 NDL 越短`() {
        val aggressive = Deco.algorithmFor(0.80, 0.80)
        val conservative = Deco.algorithmFor(0.30, 0.60)
        val model = Deco.DecoModel(Deco.Gas(0.21, 0.0))
        val a = Deco.ndlSec(model, aggressive, 30.0, capS = 7200)
        val c = Deco.ndlSec(model, conservative, 30.0, capS = 7200)
        assertTrue("GF30-60 的 NDL($c s) 应短于 GF80-80($a s)", c < a)
    }
}
