package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 测试：合并间隔的"自由输入"换算。
 *
 * 用户的诉求是"时间可以自己配置而不是选项"，所以这段逻辑必须可靠：
 *  - 输入 10（分钟）=> 600 秒
 *  - 输入 45（秒）  => 45 秒
 *  - 输入 2.5（分钟）=> 150 秒
 *  - 非法输入不能悄悄改坏设置（返回 null，UI 显示错误，不落到 prefs）
 */
class SiInputTest {

    @Test
    fun minutes_input_is_converted_to_seconds() {
        assertEquals(600, DiveSettings.siFromInput("10", minutes = true))
        assertEquals(90, DiveSettings.siFromInput("1.5", minutes = true))
        assertEquals(150, DiveSettings.siFromInput("2.5", minutes = true))
    }

    @Test
    fun seconds_input_is_used_as_is() {
        assertEquals(45, DiveSettings.siFromInput("45", minutes = false))
        assertEquals(7, DiveSettings.siFromInput("7", minutes = false))
        assertEquals(601, DiveSettings.siFromInput("601", minutes = false))
    }

    @Test
    fun invalid_input_returns_null() {
        assertNull(DiveSettings.siFromInput("", minutes = true))
        assertNull(DiveSettings.siFromInput(".", minutes = true))
        assertNull(DiveSettings.siFromInput("0", minutes = true))
        assertNull(DiveSettings.siFromInput("-5", minutes = false))
        assertNull(DiveSettings.siFromInput("abc", minutes = false))
    }

    @Test
    fun text_has_no_trailing_zeros() {
        assertEquals("10", DiveSettings.siInputText(600, minutes = true))
        assertEquals("2.5", DiveSettings.siInputText(150, minutes = true))
        assertEquals("45", DiveSettings.siInputText(45, minutes = false))
    }

    @Test
    fun round_trip_keeps_the_exact_seconds() {
        val values = listOf(1, 30, 45, 60, 119, 300, 600, 601, 901, 1800, 3600, 86_400)
        for (sec in values) {
            for (minutes in listOf(true, false)) {
                val text = DiveSettings.siInputText(sec, minutes)
                assertEquals(
                    "秒数 $sec 在单位 minutes=$minutes 下往返后变了（文本=$text）",
                    sec,
                    DiveSettings.siFromInput(text, minutes),
                )
            }
        }
    }

    @Test
    fun unit_is_minutes_only_for_whole_minutes() {
        assertTrue(DiveSettings.siUseMinutes(600))
        assertTrue(DiveSettings.siUseMinutes(60))
        assertFalse("45 秒不是整分钟", DiveSettings.siUseMinutes(45))
        assertFalse("90 秒不是整分钟", DiveSettings.siUseMinutes(90))
    }

    @Test
    fun valid_range_is_one_second_to_one_day() {
        assertTrue(DiveSettings.siValid(1))
        assertTrue(DiveSettings.siValid(86_400))
        assertFalse(DiveSettings.siValid(0))
        assertFalse(DiveSettings.siValid(86_401))
    }

    @Test
    fun human_text_prefers_minutes_when_whole() {
        assertEquals("10 分钟", DiveSettings.siText(600))
        assertEquals("45 秒", DiveSettings.siText(45))
        assertEquals("90 秒", DiveSettings.siText(90))
    }
}
