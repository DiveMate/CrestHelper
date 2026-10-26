package com.divelog.app.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CR-4 的 BLE 传输层：把 GATT 的 notify/write 封装成「一问一答的字节流」。
 *
 * 四个必须踩对的点（都是实测/踩坑得来的，别随手改）：
 *  1. **必须用 LE transport 连接**。CR-4 是随机私有地址，走 BR/EDR 会直接 Unreachable。
 *  2. **MTU 要自己抬**。协议单包最大 260 字节，默认 MTU 23 装不下；
 *     先 `requestMtu(517)`，抬不动就按 `mtu-3` 分包写。
 *  3. **收包要重组**。一次 notify 只有 `mtu-3` 字节，260 字节的包会被拆成十几片，
 *     必须按包头里的 len 字段拼回完整包（[WatchProtocol.extractPacket]）。
 *  4. **写要串行**。BLE 同一时刻只允许一个 outstanding write，必须等
 *     `onCharacteristicWrite` 再发下一包，否则会被协议栈直接拒绝。
 *
 * 所有 GATT 回调都是异步的，这里统一收敛成 suspend 函数，上层只管 `transfer()`。
 */
class BleWatchLink private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    initialMtu: Int,
    override val name: String,
) : WatchLink {

    private val mutex = Mutex()
    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    private val mtu = AtomicInteger(initialMtu)

    @Volatile
    private var closed = false

    private var writeSignal: CompletableDeferred<Unit>? = null

    internal fun onNotify(value: ByteArray) {
        if (!closed) rx.trySend(value)
    }

    internal fun onWriteDone() {
        writeSignal?.complete(Unit)
    }

    internal fun onMtuChanged(newMtu: Int) {
        if (newMtu > 23) mtu.set(newMtu)
    }

    internal fun onDisconnected() {
        if (closed) return
        closed = true
        rx.close()
        writeSignal?.completeExceptionally(IOException("蓝牙已断开"))
    }

    override suspend fun transfer(
        grp: Int,
        cmd: Int,
        dir: Int,
        payload: ByteArray,
        timeoutMs: Long,
    ): ByteArray = mutex.withLock {
        if (closed) throw IOException("蓝牙已断开")

        // 丢掉上一轮命令残留的分片，避免串包
        while (rx.tryReceive().isSuccess) { /* drain */ }

        writePacket(WatchProtocol.buildPacket(grp, cmd, dir, payload))

        val deadline = System.currentTimeMillis() + timeoutMs
        var buf = ByteArray(0)
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) throw IOException("等待 0x%02x/0x%02x 响应超时".format(grp, cmd))
            val chunk = withTimeoutOrNull(remain) { rx.receive() }
                ?: throw IOException("等待 0x%02x/0x%02x 响应超时".format(grp, cmd))
            buf = if (buf.isEmpty()) chunk else buf + chunk

            val parsed = WatchProtocol.extractPacket(buf) ?: continue
            buf = parsed.rest
            // 夹在中间的其它包（例如上一条命令的迟到响应）直接丢
            val body = runCatching { WatchProtocol.parsePacket(parsed.packet, grp, cmd, dir) }.getOrNull()
            if (body != null) return body
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        rx.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private suspend fun writePacket(packet: ByteArray) {
        val chunkSize = (mtu.get() - 3).coerceIn(20, 512)
        var offset = 0
        while (offset < packet.size) {
            val n = minOf(chunkSize, packet.size - offset)
            writeChunk(packet.copyOfRange(offset, offset + n))
            offset += n
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(bytes: ByteArray) {
        val signal = CompletableDeferred<Unit>()
        writeSignal = signal
        val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                writeChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeChar.value = bytes
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!submitted) throw IOException("BLE 写入提交失败")
        // 等 onCharacteristicWrite；个别设备会吞回调，超时后继续（下一条命令会重新同步）
        withTimeoutOrNull(WRITE_TIMEOUT_MS) { signal.await() }
        writeSignal = null
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MTU_TARGET = 517

        /** 实测的 GATT 参数（见 docs/01-调研 §8）。 */
        val SERVICE_UUID: UUID = UUID.fromString("f000ffe0-ab12-45ec-84c8-46483f4626e9")
        val CHAR_UUID: UUID = UUID.fromString("f000ffe1-ab12-45ec-84c8-46483f4626e9")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 同硬件的贴牌名，扫描时一并认。 */
        val BROADCAST_NAMES = listOf("Crest-CR4", "CR-4", "Deep Six", "Excursion", "Centauri", "Alpha")

        /**
         * 连接 + 服务发现 + 订阅 notify。
         * 任何一步失败都抛异常（带可读原因），由界面展示。
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(ctx: Context, device: BluetoothDevice): BleWatchLink {
            val link = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectInternal(ctx, device) }
                ?: throw IOException("连接超时（${CONNECT_TIMEOUT_MS / 1000} 秒）")
            // notify 订阅生效需要一点时间，直接发第一条命令容易丢响应
            delay(120)
            return link
        }

        @SuppressLint("MissingPermission")
        private suspend fun connectInternal(ctx: Context, device: BluetoothDevice): BleWatchLink =
            suspendCancellableCoroutine { cont ->
                val settled = AtomicBoolean(false)
                val mtuRef = AtomicInteger(23)
                var pendingLink: BleWatchLink? = null
                val main = Handler(Looper.getMainLooper())

                fun succeed(link: BleWatchLink) {
                    if (settled.compareAndSet(false, true)) cont.resume(link)
                }

                fun fail(e: Throwable) {
                    if (settled.compareAndSet(false, true)) cont.resumeWithException(e)
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when {
                            newState == BluetoothProfile.STATE_CONNECTED &&
                                status == BluetoothGatt.GATT_SUCCESS -> {
                                g.requestMtu(MTU_TARGET)
                                g.discoverServices()
                            }

                            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                                pendingLink?.onDisconnected()
                                fail(IOException("连接断开（status=$status）"))
                            }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            mtuRef.set(mtu)
                            pendingLink?.onMtuChanged(mtu)
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(IOException("服务发现失败（status=$status）"))
                            return
                        }
                        val service: BluetoothGattService? =
                            g.getService(SERVICE_UUID) ?: pickSerialLikeService(g)
                        if (service == null) {
                            fail(IOException("没找到数据服务（期望 $SERVICE_UUID）"))
                            return
                        }
                        val ch = service.getCharacteristic(CHAR_UUID)
                            ?: service.characteristics.firstOrNull { it.isWritable() }
                        if (ch == null) {
                            fail(IOException("没找到可写特征（service=${service.uuid}）"))
                            return
                        }
                        val notifyChar = service.characteristics.firstOrNull { it.isNotifiable() } ?: ch

                        g.setCharacteristicNotification(notifyChar, true)
                        val cccd = notifyChar.getDescriptor(CCCD_UUID)

                        val link = BleWatchLink(
                            gatt = g,
                            writeChar = ch,
                            initialMtu = mtuRef.get(),
                            name = "BLE: ${runCatching { device.name }.getOrNull() ?: device.address}",
                        )
                        pendingLink = link

                        if (cccd == null) {
                            succeed(link)
                            return
                        }
                        val enable =
                            if (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            } else {
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, enable)
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                cccd.value = enable
                                g.writeDescriptor(cccd)
                            }
                        }
                        // 兜底：个别 ROM 不回 onDescriptorWrite，1.5s 后直接放行
                        main.postDelayed({
                            if (!settled.get()) succeed(link)
                        }, 1_500)
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                        pendingLink?.let { succeed(it) }
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        ch: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        pendingLink?.onWriteDone()
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        ch: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        pendingLink?.onNotify(value)
                    }

                    @Deprecated("API < 33 的回调")
                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        pendingLink?.onNotify(ch.value ?: return)
                    }
                }

                // 必须显式指定 TRANSPORT_LE：CR-4 是随机地址，双模连接会 Unreachable
                val gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    fail(IOException("connectGatt 返回 null"))
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                }
            }

        /** 没有命中已知 UUID 时，挑一个同时具备 write 与 notify 的服务（和桌面端探测逻辑一致）。 */
        @SuppressLint("MissingPermission")
        private fun pickSerialLikeService(g: BluetoothGatt): BluetoothGattService? =
            g.services.firstOrNull { s -> s.characteristics.any { it.isWritable() } && s.characteristics.any { it.isNotifiable() } }

        private fun BluetoothGattCharacteristic.isWritable(): Boolean =
            properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
            properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }
}

/**
 * 扫描 CR-4。
 *
 * 用服务 UUID `0000ffc0` 做 ScanFilter（广播里带的就是它），
 * 这样即使广播名被改过也能扫到；名字只作为兜底识别与展示。
 */
class Cr4Scanner(private val ctx: Context) {

    private val manager get() = ctx.getSystemService(BluetoothManager::class.java)

    val isSupported: Boolean get() = manager?.adapter != null
    val isEnabled: Boolean get() = manager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    suspend fun scan(timeoutMs: Long = 8_000, onFound: (ScanResult) -> Unit = {}): List<ScanResult> {
        val adapter = manager?.adapter ?: throw IOException("设备不支持蓝牙")
        if (!adapter.isEnabled) throw IOException("蓝牙未开启")
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("BLE 扫描不可用")

        val found = LinkedHashMap<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device?.name }.getOrNull()
                val byService = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().lowercase().startsWith("0000ffc0")
                } == true
                if (!byService && !looksLikeCr4(name)) return
                synchronized(found) {
                    val prev = found[result.device.address]
                    if (prev == null || result.rssi > prev.rssi) found[result.device.address] = result
                }
                onFound(result)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString("0000ffc0-0000-1000-8000-00805f9b34fb")))
                .build()
        )
        scanner.startScan(filters, settings, callback)
        try {
            delay(timeoutMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return synchronized(found) { found.values.toList() }
    }

    private fun looksLikeCr4(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return BleWatchLink.BROADCAST_NAMES.any { name.contains(it, ignoreCase = true) }
    }
}

/** 连接状态，供设备页显示。 */
sealed interface LinkState {
    data object Idle : LinkState
    data object Scanning : LinkState
    data object Connecting : LinkState
    data class Ready(val name: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

/** 设备页用的连接持有者（把生命周期与 link 解耦，退出页面时统一断开）。 */
class WatchSession {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    var link: BleWatchLink? = null
        private set

    suspend fun connect(ctx: Context, device: BluetoothDevice): Result<BleWatchLink> {
        _state.value = LinkState.Connecting
        return runCatching { BleWatchLink.connect(ctx, device) }
            .onSuccess {
                link = it
                _state.value = LinkState.Ready(it.name)
            }
            .onFailure { _state.value = LinkState.Failed(it.message ?: "连接失败") }
    }

    suspend fun disconnect() {
        runCatching { link?.close() }
        link = null
        _state.value = LinkState.Idle
    }
}
