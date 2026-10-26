package com.divelog.app.sync

/**
 * 「一块手表」的抽象：只要能按包收发，就能跑完整的同步与设置流程。
 *
 * 有两个实现：
 *  - [BleWatchLink]（Android 真机，GATT notify/write）
 *  - `FakeWatchLink`（测试用，内存里模拟一台表，见 test 源集）
 *
 * 把 IO 抽掉之后，同步计划、协议、入库全都能在 JVM 里跑单测，
 * 不必为了验证「增量只下两条」去连真表。
 */
interface WatchLink {

    /** 传输层名字，用于界面展示（例如 "BLE: Crest-CR4"）。 */
    val name: String

    /**
     * 发一个命令并等它的响应 payload。
     * 实现负责：清空残留、按 BLE 分包重组、超时重试。
     * 失败抛 [WatchProtocolException]（协议错）或 [java.io.IOException]（链路错）。
     */
    suspend fun transfer(
        grp: Int,
        cmd: Int,
        dir: Int,
        payload: ByteArray = EMPTY,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ByteArray

    suspend fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        val EMPTY = ByteArray(0)
    }
}
