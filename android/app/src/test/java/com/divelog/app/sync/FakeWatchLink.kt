package com.divelog.app.sync

/**
 * 内存里的「一台假手表」：按 CR-4 的线协议应答，用来在 JVM 里跑通
 * 同步计划 → 走编号链 → 下 header/profile → 入库 的完整流程。
 *
 * 它只实现协议层需要的命令，不依赖蓝牙、不依赖 native 解析器，
 * 所以「增量只下 2 条」这种断言可以秒级验证，不必连真表。
 */
class FakeWatchLink(
    private val serial: String = "SN9F0001",
    private val hardware: String = "H01.1",
    private val software: String = "D01.6.2",
    private val fw6: Boolean = true,
    dives: List<FakeDive> = emptyList(),
) : WatchLink {

    override val name: String = "fake-watch"

    var dives: List<FakeDive> = dives.sortedBy { it.index }

    /** 收到的命令流水，测试用来断言「没有多读不需要的记录」。 */
    val commands = mutableListOf<String>()

    /** 让指定编号在下载时抛错，验证单条失败不拖垮整轮。 */
    var failIndex: Int? = null

    var clockDate: ByteArray? = null
        private set
    var clockTime: ByteArray? = null
        private set
    var clockStored: Boolean = false
        private set

    data class FakeDive(
        val index: Int,
        val fingerprint: String,
        val profileSize: Int = 512,
        val headerSize: Int = 156,
    )

    override suspend fun transfer(
        grp: Int,
        cmd: Int,
        dir: Int,
        payload: ByteArray,
        timeoutMs: Long,
    ): ByteArray {
        val key = "%02x/%02x".format(grp, cmd)
        commands += key
        return when {
            grp == WatchProtocol.GRP_SETTINGS && cmd == WatchProtocol.CMD_SETTINGS_LOAD -> ByteArray(0)
            grp == WatchProtocol.GRP_SETTINGS && cmd == WatchProtocol.CMD_SETTINGS_DATE -> {
                clockDate = payload.copyOf()
                payload.copyOf()
            }

            grp == WatchProtocol.GRP_SETTINGS && cmd == WatchProtocol.CMD_SETTINGS_TIME -> {
                clockTime = payload.copyOf()
                payload.copyOf()
            }

            grp == WatchProtocol.GRP_SETTINGS && cmd == WatchProtocol.CMD_SETTINGS_STORE -> {
                clockStored = true
                ByteArray(0)
            }

            grp == WatchProtocol.GRP_INFO && cmd == WatchProtocol.CMD_INFO_HARDWARE -> pad(hardware)
            grp == WatchProtocol.GRP_INFO && cmd == WatchProtocol.CMD_INFO_SOFTWARE -> pad(software)
            grp == WatchProtocol.GRP_INFO && cmd == WatchProtocol.CMD_INFO_SERIAL -> pad(serial)

            grp == WatchProtocol.GRP_INFO && cmd == WatchProtocol.CMD_INFO_LASTDIVE ->
                WatchProtocol.leU16(lastIndex)

            grp == WatchProtocol.GRP_DIVE && cmd == WatchProtocol.CMD_DIVE_COUNT ->
                WatchProtocol.leU16(dives.size)

            grp == WatchProtocol.GRP_DIVE && cmd == WatchProtocol.CMD_DIVE_INDEX_LAST ->
                WatchProtocol.leU16(lastIndex)

            grp == WatchProtocol.GRP_DIVE && cmd == WatchProtocol.CMD_DIVE_INDEX_PREVIOUS -> {
                val cur = WatchProtocol.leU32At(payload, 0).toInt()
                WatchProtocol.leU32(previousOf(cur).toLong())
            }

            grp == WatchProtocol.GRP_DIVE && cmd == WatchProtocol.CMD_DIVE_HEADER -> {
                val idx = WatchProtocol.leU16At(payload, 0)
                if (failIndex == idx) throw java.io.IOException("模拟读取失败 #$idx")
                val d = dives.firstOrNull { it.index == idx }
                    ?: throw WatchProtocolException("没有编号 #$idx")
                headerBytes(d)
            }

            grp == WatchProtocol.GRP_DIVE && cmd == WatchProtocol.CMD_DIVE_PROFILE -> {
                val idx = WatchProtocol.leU16At(payload, 0)
                val off = WatchProtocol.leU32At(payload, 2).toInt()
                val d = dives.firstOrNull { it.index == idx }
                    ?: throw WatchProtocolException("没有编号 #$idx")
                val profile = profileBytes(d)
                val n = minOf(200, profile.size - off)
                if (n <= 0) ByteArray(0) else profile.copyOfRange(off, off + n)
            }

            else -> throw WatchProtocolException("未实现的命令 $key")
        }
    }

    override suspend fun close() = Unit

    fun add(vararg more: FakeDive) {
        dives = (dives + more).sortedBy { it.index }
    }

    fun removeIndex(index: Int) {
        dives = dives.filterNot { it.index == index }
    }

    val lastIndex: Int get() = dives.maxOfOrNull { it.index } ?: 0

    private fun previousOf(index: Int): Int =
        dives.filter { it.index < index }.maxOfOrNull { it.index } ?: 0

    private fun headerBytes(d: FakeDive): ByteArray {
        val h = ByteArray(if (fw6) d.headerSize else 156)
        if (fw6) h[2] = d.headerSize.toByte()
        WatchProtocol.leU32(d.profileSize.toLong()).copyInto(h, 8)
        hexToBytes(d.fingerprint).copyInto(h, WatchProtocol.FP_OFFSET)
        return h
    }

    private fun profileBytes(d: FakeDive): ByteArray =
        ByteArray(d.profileSize) { ((d.index * 7 + it) % 251).toByte() }

    private fun pad(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(maxOf(raw.size, 6))
        raw.copyInto(out)
        return out
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
