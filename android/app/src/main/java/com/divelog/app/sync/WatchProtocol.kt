package com.divelog.app.sync

/**
 * CR-4 / DeepSix Excursion 的 BLE 线协议（与 libdivecomputer `deepsix_excursion.c` 逐字节一致）。
 *
 * 包格式：
 * ```
 * [grp:1][cmd:1][dir:1][len:1][payload:len][csum:1]
 * csum = (sum(前 4+len 字节) & 0xFF) XOR 0xFF
 * ```
 * 响应包：`grp = 请求 grp + 1`，`cmd` 与 `dir` 原样返回。
 *
 * 本对象只做「字节 ↔ 结构」，不发任何 IO，所以能被 JVM 单元测试完整覆盖
 *（协议错一位整条链路都是垃圾数据，这一层必须锁死）。
 */
object WatchProtocol {

    // ---- 方向 ----
    const val DIR_WRITE = 0x00
    const val DIR_READ = 0x01

    // ---- 0xA0 设备信息 ----
    const val GRP_INFO = 0xA0
    const val CMD_INFO_HARDWARE = 0x01
    const val CMD_INFO_SOFTWARE = 0x02
    const val CMD_INFO_SERIAL = 0x03
    const val CMD_INFO_LASTDIVE = 0x04

    // ---- 0xB0 设置 ----
    const val GRP_SETTINGS = 0xB0
    const val CMD_SETTINGS_DATE = 0x01
    const val CMD_SETTINGS_TIME = 0x03
    const val CMD_SETTINGS_STORE = 0x27
    const val CMD_SETTINGS_LOAD = 0x28

    // ---- 0xC0 日志 ----
    const val GRP_DIVE = 0xC0
    const val CMD_DIVE_HEADER = 0x02
    const val CMD_DIVE_PROFILE = 0x03
    const val CMD_DIVE_COUNT = 0x05
    const val CMD_DIVE_INDEX_LAST = 0x06
    const val CMD_DIVE_INDEX_FIRST = 0x07
    const val CMD_DIVE_INDEX_PREVIOUS = 0x08
    const val CMD_DIVE_INDEX_NEXT = 0x09

    /** header 里指纹的偏移与长度（增量同步的锚点）。 */
    const val FP_OFFSET = 12
    const val FP_SIZE = 6

    /** 老固件的 header 固定长度；新固件（fw6）由 header[2] 给出。 */
    const val HEADERSIZE_MIN = 128
    const val HEADERSIZE_V0 = 156

    private val EMPTY = ByteArray(0)

    /** 组一个请求包。payload 最长 255（协议 len 字段只有 1 字节）。 */
    fun buildPacket(grp: Int, cmd: Int, dir: Int, payload: ByteArray = EMPTY): ByteArray {
        require(payload.size <= 255) { "payload 超过 255 字节: ${payload.size}" }
        val out = ByteArray(4 + payload.size + 1)
        out[0] = grp.toByte()
        out[1] = cmd.toByte()
        out[2] = dir.toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        var sum = 0
        for (i in 0 until 4 + payload.size) sum += out[i].toInt() and 0xFF
        out[4 + payload.size] = ((sum and 0xFF) xor 0xFF).toByte()
        return out
    }

    /**
     * 校验并取出一个响应包的 payload。
     * 头不符/校验和错一律抛 [WatchProtocolException]，由调用方决定重试还是放弃。
     */
    fun parsePacket(raw: ByteArray, grp: Int, cmd: Int, dir: Int): ByteArray {
        if (raw.size < 5) throw WatchProtocolException("包太短（${raw.size} 字节）")
        val rGrp = raw[0].toInt() and 0xFF
        val rCmd = raw[1].toInt() and 0xFF
        val rDir = raw[2].toInt() and 0xFF
        val len = raw[3].toInt() and 0xFF
        if (rGrp != (grp + 1) and 0xFF) {
            throw WatchProtocolException(
                "响应组不符：期望 0x%02x，实际 0x%02x".format((grp + 1) and 0xFF, rGrp)
            )
        }
        if (rCmd != cmd) throw WatchProtocolException("响应命令不符：期望 0x%02x，实际 0x%02x".format(cmd, rCmd))
        if (rDir != dir) throw WatchProtocolException("响应方向不符：期望 0x%02x，实际 0x%02x".format(dir, rDir))
        if (raw.size < 4 + len + 1) {
            throw WatchProtocolException("数据不完整：需要 ${4 + len + 1} 字节，实际 ${raw.size}")
        }
        var sum = 0
        for (i in 0 until 4 + len) sum += raw[i].toInt() and 0xFF
        val expect = ((sum and 0xFF) xor 0xFF).toByte()
        if (raw[4 + len] != expect) {
            throw WatchProtocolException(
                "校验和错误：期望 0x%02x，实际 0x%02x".format(expect.toInt() and 0xFF, raw[4 + len].toInt() and 0xFF)
            )
        }
        return raw.copyOfRange(4, 4 + len)
    }

    /**
     * 从字节流里切出**一个完整包**；不够一个包时返回 null。
     * BLE 一次 notify 只有 MTU-3 字节，260 字节的包必然会被拆开，必须在这里重组。
     */
    fun extractPacket(buf: ByteArray): WatchPacket? {
        if (buf.size < 4) return null
        val len = buf[3].toInt() and 0xFF
        val total = 4 + len + 1
        if (buf.size < total) return null
        return WatchPacket(buf.copyOfRange(0, total), buf.copyOfRange(total, buf.size))
    }

    /** 解析 dive header：编号、header 长度、profile 长度、指纹。 */
    fun parseHeader(raw: ByteArray, index: Int, fw6: Boolean): DiveHeader {
        if (raw.size < HEADERSIZE_MIN) throw WatchProtocolException("header 太短（${raw.size} 字节）")
        val headerSize = if (fw6) raw[2].toInt() and 0xFF else HEADERSIZE_V0
        if (headerSize < FP_OFFSET + FP_SIZE || headerSize > raw.size) {
            throw WatchProtocolException("header 长度非法：$headerSize（实际收到 ${raw.size}）")
        }
        val profileSize = leU32(raw, 8)
        val fp = raw.copyOfRange(FP_OFFSET, FP_OFFSET + FP_SIZE).toHex()
        return DiveHeader(index = index, headerSize = headerSize, profileSize = profileSize, fingerprint = fp)
    }

    // ---- 小端编码工具 ----

    fun leU16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    fun leU32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    /**
     * 小端读 16 位。响应可能被设备截短（空包 / 1 字节），越界直接抛协议异常，
     * 不要等 Kotlin 抛 ArrayIndexOutOfBounds —— 那个信息看不出是哪条命令出的问题。
     */
    fun leU16At(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) {
            throw WatchProtocolException("响应太短：offset=$off 需要 2 字节，实际 ${b.size}")
        }
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    fun leU32At(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) {
            throw WatchProtocolException("响应太短：offset=$off 需要 4 字节，实际 ${b.size}")
        }
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun leU32(b: ByteArray, off: Int): Int = leU32At(b, off).toInt()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

class WatchProtocolException(message: String) : Exception(message)

/** 一个完整包 + 剩余未消费字节。 */
data class WatchPacket(val packet: ByteArray, val rest: ByteArray)

/** dive header 的结构化表示。 */
data class DiveHeader(
    /** 手表上的日志编号。 */
    val index: Int,
    /** header 字节数（老固件 156，新固件由 header[2] 给出）。 */
    val headerSize: Int,
    /** 剖面字节数。 */
    val profileSize: Int,
    /** 6 字节指纹（hex），用来判断「这条是不是已经下过」。 */
    val fingerprint: String,
) {
    val totalSize: Int get() = headerSize + profileSize
}

/** 手表档案。 */
data class WatchInfo(
    val hardware: String,
    val software: String,
    val serial: String,
    val fw6: Boolean,
    /** 手表上现有日志条数。 */
    val ndives: Int,
    /** 最新一条的编号（增量同步的起点）。 */
    val lastIndex: Int,
) {
    /** 设备档案 id：序列号优先，拿不到就用固件+硬件兜底（保证同一块表稳定）。 */
    val deviceId: String get() = serial.ifBlank { "unknown_${hardware}_$software" }
    val displayName: String get() = "Crest CR-4"
    val firmwareText: String get() = software.trim().ifBlank { "—" }
}
