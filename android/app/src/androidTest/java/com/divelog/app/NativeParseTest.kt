package com.divelog.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream

/**
 * L3 测试：真机/模拟器仪器测试 —— 验证 JNI + libdivecomputer 解析链。
 *
 * 这一层是唯一能真正跑通「原始字节 -> 结构化 dive」的地方：
 * JVM 测试只能覆盖 JSON 解析层，.so 必须上真机。
 *
 * 黄金样本 golden_dive1.bin = CR-4 的 cr4_dive00001，
 * 已知（来自 dive/data/dives.csv 的官方解析结果）：
 *   divetime=3673s, maxdepth=7.5m, tempMin=32.0C, nsamples=377, bytes=2446
 *   datetime=2020-11-22 12:05:04
 * 数值一旦漂移就说明 libdivecomputer 解析或 JNI 桥接出问题了。
 */
@RunWith(AndroidJUnit4::class)
class NativeParseTest {

    private fun loadGolden(name: String): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val stream: InputStream = ctx.assets.open(name)
        return stream.use { it.readBytes() }
    }

    @Test(timeout = 60_000)
    fun `so_loads_successfully`() {
        assertTrue(
            "libdcbridge.so 加载失败: ${DiveParser.loadError}",
            DiveParser.isAvailable,
        )
    }

    @Test(timeout = 60_000)
    fun `nativeVersion_returns_non_blank`() {
        if (!DiveParser.isAvailable) return   // .so 不可用时跳过，避免整类测试红
        val v = DiveParser.nativeVersion()
        assertNotNull("nativeVersion 不应返回 null", v)
        assertTrue("nativeVersion 不应为空", v.isNotBlank())
    }

    @Test(timeout = 60_000)
    fun `golden_sample_matches_vendor_parse`() {
        assertTrue("libdcbridge.so 未加载，跳过", DiveParser.isAvailable)
        val bytes = loadGolden("golden_dive1.bin")

        val dive = DiveParser.parse(bytes)
        assertNotNull("黄金样本解析失败", dive)
        requireNotNull(dive)

        // 与 dive/data/dives.csv 记录的官方解析结果对拍
        assertEquals("字节数", 2446, dive.bytes)
        assertEquals("潜水时长(秒)", 3673, dive.divetime)
        assertEquals("最大深度(米)", 7.5, dive.maxdepth, 0.1)
        assertEquals("最低水温(摄氏度)", 32.0, dive.tempMin, 0.2)
        assertEquals("采样点数", 377, dive.sampleCount)
        assertEquals("时间", "2020-11-22 12:05:04", dive.dateText)
    }

    @Test(timeout = 60_000)
    fun `sample_count_matches_array_length`() {
        assertTrue("libdcbridge.so 未加载，跳过", DiveParser.isAvailable)
        val dive = DiveParser.parse(loadGolden("golden_dive1.bin"))
        requireNotNull(dive)
        assertEquals("samples 数组长度应等于 sampleCount", dive.sampleCount, dive.samples.size)
        assertTrue("应解析出实际采样点", dive.samples.isNotEmpty())
    }

    @Test(timeout = 60_000)
    fun `samples_time_increasing_and_depth_non_negative`() {
        assertTrue("libdcbridge.so 未加载，跳过", DiveParser.isAvailable)
        val dive = DiveParser.parse(loadGolden("golden_dive1.bin"))
        requireNotNull(dive)

        var prevT = Int.MIN_VALUE
        for (s in dive.samples) {
            assertTrue("采样时间应递增，${s.t} < $prevT", s.t >= prevT)
            assertTrue("深度不应为负: ${s.depth}", s.depth >= 0.0)
            prevT = s.t
        }
        val maxInSamples = dive.samples.maxOf { it.depth }
        assertTrue("采样最大深度应接近汇总值 ${dive.maxdepth}, 实际 $maxInSamples",
            kotlin.math.abs(maxInSamples - dive.maxdepth) < 1.0)
    }

    @Test(timeout = 60_000)
    fun `empty_and_garbage_input_does_not_crash`() {
        if (!DiveParser.isAvailable) return
        // 这几个是最容易在真机上触发 native 崩溃的输入
        assertEquals(null, DiveParser.parse(ByteArray(0)))
        assertEquals(null, DiveParser.parse(byteArrayOf(0x00)))
        assertEquals(null, DiveParser.parse(ByteArray(512)))       // 全零
        val garbage = ByteArray(1024) { (it % 256).toByte() }
        assertEquals(null, DiveParser.parse(garbage))
    }

    @Test(timeout = 60_000)
    fun `truncated_golden_sample_does_not_crash`() {
        assertTrue("libdcbridge.so 未加载，跳过", DiveParser.isAvailable)
        val full = loadGolden("golden_dive1.bin")
        // 只给前 200 字节（数据不完整），native 必须优雅失败而不是段错误
        val truncated = full.copyOfRange(0, 200)
        val r = DiveParser.parse(truncated)
        // 结果可以是 null 或不完整，但不能抛异常崩溃
        assertTrue("截断输入应被安全处理", r == null || r.bytes > 0)
    }
}