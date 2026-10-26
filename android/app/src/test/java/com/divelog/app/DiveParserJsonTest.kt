package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L1 测试：JSON -> Dive 的纯逻辑转换（无需设备、不加载 .so）。
 *
 * 关键点：DiveParser.parse() 会走 native，只有 fromJson() 是纯 Kotlin。
 * 这里把 fromJson 拆出来单独测，保证解析模型层的回归能力。
 *
 * @Config(sdk = [34])：Robolectric 4.13 最高只支持 SDK 34，而 App 的
 * targetSdk 是 35（为修 Android 16 上的启动 ANR）。不指定会在类初始化时报
 * "Package targetSdkVersion=35 > maxSdkVersion=34"，整类用例一条都跑不了。
 * 这里只影响 JVM 测试的运行环境，不影响真机行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiveParserJsonTest {

    @Test
    fun `valid_json_parses_all_fields`() {
        val json = """
            {
              "ok": true,
              "bytes": 2446,
              "divetime": 3673,
              "maxdepth": 7.5,
              "tempMin": 32.0,
              "gasmixCount": 0,
              "sampleCount": 377,
              "datetime": {"year":2020,"month":11,"day":22,"hour":12,"minute":5,"second":4},
              "samples": [
                {"t":0,"depth":0.0,"temp":32.0},
                {"t":4,"depth":1.2},
                {"t":8,"depth":7.5,"temp":31.5}
              ]
            }
        """.trimIndent()

        val d = DiveParser.fromJson(json)
        assertTrue("应解析成功", d != null)
        requireNotNull(d)
        assertEquals(2446, d.bytes)
        assertEquals(3673, d.divetime)
        assertEquals(7.5, d.maxdepth, 1e-9)
        assertEquals(32.0, d.tempMin, 1e-9)
        assertEquals(377, d.sampleCount)
        assertEquals("2020-11-22 12:05:04", d.dateText)
        assertEquals("61:13", d.durationText)   // 3673s = 61分13秒
        assertEquals(3, d.samples.size)
    }

    @Test
    fun `sample_without_temp_yields_null_not_crash`() {
        val json = """{"ok":true,"divetime":60,"samples":[{"t":1,"depth":2.0}]}"""
        val d = DiveParser.fromJson(json)
        requireNotNull(d)
        assertEquals(1, d.samples.size)
        assertNull(d.samples[0].temp)
    }

    @Test
    fun `json_null_temp_converts_safely`() {
        val json = """{"ok":true,"samples":[{"t":1,"depth":2.0,"temp":null}]}"""
        val d = DiveParser.fromJson(json)
        requireNotNull(d)
        assertNull("JSON null 不应触发异常", d.samples[0].temp)
    }

    @Test
    fun `ok_false_returns_null`() {
        assertNull(DiveParser.fromJson("""{"ok":false,"error":"bad magic"}"""))
    }

    @Test
    fun `malformed_input_returns_null_without_throwing`() {
        // 这些是最容易在真机上崩掉的输入：null / 空串 / 截断 JSON / 非 JSON
        assertNull(DiveParser.fromJson(null))
        assertNull(DiveParser.fromJson(""))
        assertNull(DiveParser.fromJson("   "))
        assertNull(DiveParser.fromJson("""{"ok":true,"samples":[)"""))  // 截断
        assertNull(DiveParser.fromJson("not json at all"))
        assertNull(DiveParser.fromJson("[]"))
    }

    @Test
    fun `missing_datetime_yields_zero_fields`() {
        val d = DiveParser.fromJson("""{"ok":true,"divetime":100,"samples":[]}""")
        requireNotNull(d)
        assertEquals(0, d.year)
        assertEquals("0000-00-00 00:00:00", d.dateText)
    }

    @Test
    fun `durationText_carries_whole_hours`() {
        val json = """{"ok":true,"divetime":3600,"samples":[]}"""
        val d = DiveParser.fromJson(json)
        requireNotNull(d)
        assertEquals("60:00", d.durationText)
    }
}