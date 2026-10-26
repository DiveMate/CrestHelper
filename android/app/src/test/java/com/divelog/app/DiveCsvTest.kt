package com.divelog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * CSV 解析回归：真实 dives.csv / samples.csv 的字段格式与脏数据容错。
 */
class DiveCsvTest {

    private fun reader(s: String): BufferedReader = BufferedReader(StringReader(s))

    @Test
    fun parses_dive_header_and_row() {
        val csv = """
            file,dive_no,datetime,divetime_s,maxdepth_m,temp_min_c,gasmixes,nsamples,nevents,bytes,path
            a.bin,7,2025-06-01 10:20:30,3673,7.5,32.0,0,377,0,2446,C:\raw\a.bin
        """.trimIndent()

        val list = DiveCsv.parseDives(reader(csv))
        assertEquals(1, list.size)
        val d = list[0]
        assertEquals("a.bin", d.file)
        assertEquals(7, d.no)
        assertEquals(3673, d.divetimeS)
        assertEquals(7.5, d.maxdepthM, 1e-9)
        assertEquals(377, d.nsamples)
        assertEquals(2025, d.year)
        assertEquals(6, d.month)
        assertEquals("2025-06-01", d.dateText)
        assertEquals("10:20:30", d.timeText)
        assertEquals("61:13", d.durationText)
    }

    @Test
    fun skips_blank_and_malformed_lines() {
        val csv = """
            file,dive_no,datetime,divetime_s,maxdepth_m,temp_min_c,gasmixes,nsamples,nevents,bytes,path

            broken,1
            ok.bin,2,2025-01-01 00:00:00,100,5.0,20.0,0,10,0,100,C:\x
        """.trimIndent()

        val list = DiveCsv.parseDives(reader(csv))
        assertEquals(1, list.size)
        assertEquals("ok.bin", list[0].file)
    }

    @Test
    fun parses_samples_and_keeps_missing_temp_null() {
        val csv = """
            file,t_sec,depth_m,temp_c
            a.bin,10,1.64,
            a.bin,20,3.25,32.4
            a.bin,bad,3.0,1.0
        """.trimIndent()

        val map = DiveCsv.parseSamples(reader(csv))
        val s = map.getValue("a.bin")
        assertEquals(2, s.size)                   // bad 行被跳过
        assertNull(s[0].temp)                     // 空温度 => null，不能变 0.0
        assertEquals(3.25, s[1].depth, 1e-9)
        assertEquals(32.4, s[1].temp!!, 1e-9)
    }

    @Test
    fun samples_are_sorted_by_time() {
        val csv = """
            file,t_sec,depth_m,temp_c
            b.bin,20,2.0,
            b.bin,10,1.0,
        """.trimIndent()

        val s = DiveCsv.parseSamples(reader(csv)).getValue("b.bin")
        assertEquals(10, s[0].t)
        assertEquals(20, s[1].t)
        assertTrue(s[0].depth < s[1].depth)
    }
}
