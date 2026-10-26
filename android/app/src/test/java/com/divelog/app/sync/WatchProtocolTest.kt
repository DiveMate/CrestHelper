package com.divelog.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 协议层回归：这一层错一个字节，整条链路都是垃圾数据，
 * 而且真机调试成本极高，所以必须用固定向量锁死。
 */
class WatchProtocolTest {

    @Test
    fun `组包：校验和 = 前4+len字节之和取反`() {
        // grp=A0 cmd=01 dir=01 len=00 -> 0xA0+0x01+0x01+0x00 = 0xA2 -> ^0xFF = 0x5D
        val pkt = WatchProtocol.buildPacket(0xA0, 0x01, 0x01)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x01, 0x01, 0x00, 0x5D), pkt)
    }

    @Test
    fun `解析响应：组号自动加一`() {
        // 响应 [A1][01][01][02][12][34][14]
        val raw = byteArrayOf(0xA1.toByte(), 0x01, 0x01, 0x02, 0x12, 0x34, 0x14)
        val payload = WatchProtocol.parsePacket(raw, 0xA0, 0x01, 0x01)
        assertArrayEquals(byteArrayOf(0x12, 0x34), payload)
    }

    @Test
    fun `组号不符要报错`() {
        val raw = byteArrayOf(0xA2.toByte(), 0x01, 0x01, 0x00, 0x5C)
        assertThrows(WatchProtocolException::class.java) {
            WatchProtocol.parsePacket(raw, 0xA0, 0x01, 0x01)
        }
    }

    @Test
    fun `校验和错误要报错`() {
        val raw = byteArrayOf(0xA1.toByte(), 0x01, 0x01, 0x02, 0x12, 0x34, 0x00)
        assertThrows(WatchProtocolException::class.java) {
            WatchProtocol.parsePacket(raw, 0xA0, 0x01, 0x01)
        }
    }

    @Test
    fun `BLE 分包重组：不到一个包返回 null`() {
        val full = WatchProtocol.buildPacket(0xC0, 0x02, 0x01, byteArrayOf(1, 2, 3))
        assertNull(WatchProtocol.extractPacket(full.copyOfRange(0, 3)))

        val partial = WatchProtocol.extractPacket(full.copyOfRange(0, full.size - 1))
        assertNull(partial)

        val one = WatchProtocol.extractPacket(full)
        assertNotNull(one)
        assertArrayEquals(full, one!!.packet)
        assertEquals(0, one.rest.size)
    }

    @Test
    fun `BLE 分包重组：粘包时留下剩余字节`() {
        val a = WatchProtocol.buildPacket(0xC0, 0x02, 0x01, byteArrayOf(9))
        val b = WatchProtocol.buildPacket(0xC0, 0x03, 0x01, byteArrayOf(7, 8))
        val merged = a + b
        val first = WatchProtocol.extractPacket(merged)!!
        assertArrayEquals(a, first.packet)
        assertArrayEquals(b, first.rest)
    }

    @Test
    fun `header 解析：新固件读 header2 作为长度`() {
        val h = ByteArray(156)
        h[2] = 156.toByte()
        WatchProtocol.leU32(4096).copyInto(h, 8)
        "0a0b0c0d0e0f".hexToBytes().copyInto(h, WatchProtocol.FP_OFFSET)

        val parsed = WatchProtocol.parseHeader(h, 42, fw6 = true)
        assertEquals(42, parsed.index)
        assertEquals(156, parsed.headerSize)
        assertEquals(4096, parsed.profileSize)
        assertEquals("0a0b0c0d0e0f", parsed.fingerprint)
        assertEquals(4252, parsed.totalSize)
    }

    @Test
    fun `header 太短要报错`() {
        assertThrows(WatchProtocolException::class.java) {
            WatchProtocol.parseHeader(ByteArray(64), 1, fw6 = false)
        }
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
