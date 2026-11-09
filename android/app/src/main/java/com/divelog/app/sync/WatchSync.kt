package com.divelog.app.sync

import com.divelog.app.DiveRepository
import com.divelog.app.WatchIngest
import com.divelog.app.data.WatchProgress
import com.divelog.app.data.WatchRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime

/**
 * 同步执行器：把 [SyncPlanner] 的决定落到「真的发命令、真的入库」。
 *
 * 一次完整同步：
 *  1. [info]         读设备信息 + 条数 + 最新编号
 *  2. [makePlan]     读本地进度，必要时读锚点 header，交给 [SyncPlanner] 决策
 *  3. [run]          按编号链逐条下 header/profile，落库（幂等）
 *
 * 所有失败都**不中断整轮**（单条坏了继续下一条），最后汇总裁决，
 * 这样一块表里有一条脏数据也不会把整次同步废掉。
 */
class WatchSync(
    private val link: WatchLink,
    private val repo: DiveRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 入库入口。默认写库；单测里换成内存实现，就能在没有 native 解析器的 JVM 里跑通全流程。 */
    private val ingestor: DiveIngestor = DiveIngestor { d, i, f, b -> repo.ingestWatch(d, i, f, b) },
) {

    // ------------------------------------------------------------------ 设备信息

    suspend fun info(): WatchInfo {
        // 协议要求：先 LOAD 设置，设备才进入「可读」状态
        link.transfer(WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_LOAD, WatchProtocol.DIR_WRITE)

        val hw = link.transfer(WatchProtocol.GRP_INFO, WatchProtocol.CMD_INFO_HARDWARE, WatchProtocol.DIR_READ)
        val sw = link.transfer(WatchProtocol.GRP_INFO, WatchProtocol.CMD_INFO_SOFTWARE, WatchProtocol.DIR_READ)
        val ser = link.transfer(WatchProtocol.GRP_INFO, WatchProtocol.CMD_INFO_SERIAL, WatchProtocol.DIR_READ)

        // 固件 "D01" + 主版本 >= '6' 使用新指令集（fw6）
        val fw6 = sw.size >= 5 && sw[0] == 'D'.code.toByte() && sw[1] == '0'.code.toByte() &&
            sw[2] == '1'.code.toByte() && sw[4] >= '6'.code.toByte()

        val (ndives, last) = if (fw6) {
            val cnt = link.transfer(WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_COUNT, WatchProtocol.DIR_READ)
            val lst = link.transfer(WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_INDEX_LAST, WatchProtocol.DIR_READ)
            WatchProtocol.leU16At(cnt, 0) to WatchProtocol.leU16At(lst, 0)
        } else {
            val lst = link.transfer(
                WatchProtocol.GRP_INFO, WatchProtocol.CMD_INFO_LASTDIVE, WatchProtocol.DIR_READ,
                WatchProtocol.leU16(0),
            )
            val n = WatchProtocol.leU16At(lst, 0)
            n to n
        }

        return WatchInfo(
            hardware = asciiOf(hw),
            software = asciiOf(sw),
            serial = asciiOf(ser).trim().trim('\u0000'),
            fw6 = fw6,
            ndives = ndives,
            lastIndex = last,
        )
    }

    /** 本地进度（这块表已经下到哪一条）。 */
    fun localProgress(deviceId: String): WatchProgress? = repo.watchProgress(deviceId)

    /**
     * 手表上指定编号的指纹。增量同步的锚点校验要它。
     * 读不到（编号不存在/设备不回）返回 null，由 [SyncPlanner] 降级成全量。
     */
    suspend fun anchorFingerprint(index: Int, fw6: Boolean): String? {
        // 不能用 runCatching：它会把 CancellationException 一起吃下去，
        // 取消同步时这里会假装"读不到锚点"，然后继续跑完整轮。
        return try {
            val raw = link.transfer(
                WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_HEADER, WatchProtocol.DIR_READ,
                WatchProtocol.leU16(index),
            )
            WatchProtocol.parseHeader(raw, index, fw6).fingerprint
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            null
        }
    }

    /** 组合：读设备信息 → 读本地进度 → 需要时读锚点 → 出计划。 */
    suspend fun makePlan(mode: SyncMode): PlanBundle {
        val watch = info()
        val local = localProgress(watch.deviceId)
        val anchor = if (mode == SyncMode.FULL || local == null || local.maxIndex <= 0) {
            null
        } else {
            anchorFingerprint(local.maxIndex, watch.fw6)
        }
        val plan = SyncPlanner.plan(mode, watch, local, anchor)
        return PlanBundle(watch, local, plan)
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 按计划下载。会先把编号链走一遍（fw6 用 PREVIOUS 命令，老固件直接递减），
     * 再从旧到新逐条下载——顺序下载能让进度条时间轴正确，也便于中途取消。
     */
    suspend fun run(
        bundle: PlanBundle,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val started = clock()
        val info = bundle.watch
        val plan = bundle.plan
        if (!plan.hasWork) {
            return SyncResult(plan = plan, watch = info, elapsedMs = clock() - started)
        }

        val indices = collectIndices(info, plan.steps)
        val deviceId = info.deviceId
        val known = repo.db.fingerprintsOf(deviceId)
        var added = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        var consecutiveFailures = 0
        val errors = ArrayList<String>(4)

        for ((n, idx) in indices.withIndex()) {
            // 取消检查放在循环顶部：用户点「取消」后不该再把剩下几百条跑完
            currentCoroutineContext().ensureActive()
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                errors.add("连续 $consecutiveFailures 条失败，中止本轮")
                break
            }
            onProgress(SyncProgress(n, indices.size, idx, "读取 #$idx"))
            try {
                val headerRaw = link.transfer(
                    WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_HEADER, WatchProtocol.DIR_READ,
                    WatchProtocol.leU16(idx),
                )
                val header = WatchProtocol.parseHeader(headerRaw, idx, info.fw6)

                // 全量模式下已存在且指纹一致 → 跳过（省带宽，也避免重复覆盖）
                val localFp = known[idx]
                if (plan.kind == PlanKind.FULL && localFp != null &&
                    localFp.equals(header.fingerprint, ignoreCase = true)
                ) {
                    skipped++
                    consecutiveFailures = 0
                    onProgress(SyncProgress(n + 1, indices.size, idx, "已存在，跳过"))
                    continue
                }

                onProgress(SyncProgress(n, indices.size, idx, "下载剖面 ${header.profileSize}B"))
                val bytes = downloadProfile(idx, header, headerRaw)

                when (ingestor.ingest(deviceId, idx, header.fingerprint, bytes)) {
                    WatchIngest.ADDED -> added++
                    WatchIngest.UPDATED -> updated++
                    WatchIngest.SKIPPED -> skipped++
                    WatchIngest.DUPLICATE -> skipped++
                    WatchIngest.FAILED -> {
                        failed++
                        errors.add("#$idx 解析失败（字节 ${bytes.size}）")
                    }
                }
                consecutiveFailures = 0
            } catch (c: CancellationException) {
                // 取消不是"这一条失败"：必须向上抛，否则会继续下一条、
                // 凑满 3 次失败后照常写库并记一条「同步完成」。
                throw c
            } catch (e: Exception) {
                failed++
                consecutiveFailures++
                if (errors.size < MAX_ERRORS) errors.add("#$idx ${e.message ?: e::class.java.simpleName}")
            }
            onProgress(SyncProgress(n + 1, indices.size, idx, "完成"))
        }

        // 写库前再确认一次：取消时不该留下"同步完成"的假记录
        currentCoroutineContext().ensureActive()
        val elapsed = clock() - started
        repo.upsertWatch(
            WatchRecord(
                id = deviceId,
                name = info.displayName,
                serial = info.serial,
                firmware = info.software,
                hardware = info.hardware,
                lastIndex = info.lastIndex,
                ndives = info.ndives,
                lastSyncAt = clock(),
            )
        )
        repo.logSync(
            com.divelog.app.data.SyncRunRecord(
                watchId = deviceId,
                mode = plan.kind.name,
                startedAt = started,
                finishedAt = clock(),
                added = added,
                updated = updated,
                skipped = skipped,
                failed = failed,
                note = plan.reason,
            )
        )
        return SyncResult(
            plan = plan,
            watch = info,
            added = added,
            updated = updated,
            skipped = skipped,
            failed = failed,
            elapsedMs = elapsed,
            errors = errors,
        )
    }

    /**
     * 走编号链，返回「这次要处理的编号」（从旧到新）。
     *
     * fw6 的编号不一定连续（删除/重排过），所以只能靠 INDEX_PREVIOUS 一条条问；
     * 老固件是递减序号，直接算。走不动了（返回 0 或原地打转）立即停下，
     * 避免坏设备把这里变成死循环。
     */
    private suspend fun collectIndices(info: WatchInfo, steps: Int): List<Int> {
        val out = ArrayList<Int>(steps)
        var cur = info.lastIndex
        var last = -1
        for (i in 0 until steps) {
            if (cur <= 0 || cur == last) break
            out.add(cur)
            last = cur
            if (i == steps - 1) break
            cur = if (info.fw6) {
                val rsp = link.transfer(
                    WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_INDEX_PREVIOUS, WatchProtocol.DIR_READ,
                    WatchProtocol.leU32(cur.toLong()),
                )
                if (rsp.size < 4) 0 else WatchProtocol.leU32At(rsp, 0).toInt()
            } else {
                cur - 1
            }
        }
        out.reverse()
        return out
    }

    /** 下载剖面并拼成完整 `.bin`（header + profile），与桌面端 raw 目录里的样本文件同格式。 */
    private suspend fun downloadProfile(index: Int, header: DiveHeader, headerRaw: ByteArray): ByteArray {
        val profile = ByteArray(header.profileSize)
        var offset = 0
        var guard = 0
        while (offset < header.profileSize) {
            if (++guard > MAX_PROFILE_CHUNKS) {
                throw WatchProtocolException("剖面分包数异常（> $MAX_PROFILE_CHUNKS）")
            }
            val req = WatchProtocol.leU16(index) + WatchProtocol.leU32(offset.toLong())
            val chunk = link.transfer(
                WatchProtocol.GRP_DIVE, WatchProtocol.CMD_DIVE_PROFILE, WatchProtocol.DIR_READ, req,
            )
            if (chunk.isEmpty()) throw WatchProtocolException("剖面空包（offset=$offset）")
            val n = minOf(chunk.size, header.profileSize - offset)
            chunk.copyInto(profile, offset, 0, n)
            offset += n
        }
        val full = ByteArray(headerRaw.size + profile.size)
        headerRaw.copyInto(full)
        profile.copyInto(full, headerRaw.size)
        return full
    }

    // ------------------------------------------------------------------ 设置

    /**
     * 下发时间校准。
     *
     * 这是协议里**唯一确定可写**的设置：`CMD_SETTINGS_DATE` / `CMD_SETTINGS_TIME`
     * 写完后设备会回读同样的字节，必须逐字节校验一致，最后 `CMD_SETTINGS_STORE` 落盘。
     * 固件没有 PUT 校验和这一步就断电，时间会停在写入前——所以顺序不能改。
     */
    suspend fun setClock(now: LocalDateTime = LocalDateTime.now()): ClockResult {
        if (now.year !in 2000..2255) return ClockResult.Failed("年份超出设备范围（2000-2255）")
        // 已经写进易失寄存器、还没 STORE 的状态。中途失败必须 LOAD 丢弃，
        // 否则设备停在一个"日期改了、时间没改"的半写入态，用户下次看表会以为时间是对的。
        var pending = false
        return try {
            link.transfer(WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_LOAD, WatchProtocol.DIR_WRITE)

            val date = byteArrayOf(
                (now.year - 2000).toByte(),
                now.monthValue.toByte(),
                now.dayOfMonth.toByte(),
            )
            val dateRsp = link.transfer(
                WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_DATE, WatchProtocol.DIR_WRITE, date,
            )
            if (!dateRsp.contentEquals(date)) {
                discardPending()
                return ClockResult.Failed("日期回读不一致：写入 ${date.toHexString()}，回读 ${dateRsp.toHexString()}")
            }
            pending = true

            val time = byteArrayOf(now.hour.toByte(), now.minute.toByte(), now.second.toByte())
            val timeRsp = link.transfer(
                WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_TIME, WatchProtocol.DIR_WRITE, time,
            )
            if (!timeRsp.contentEquals(time)) {
                discardPending()
                return ClockResult.Failed("时间回读不一致：写入 ${time.toHexString()}，回读 ${timeRsp.toHexString()}")
            }

            link.transfer(
                WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_STORE, WatchProtocol.DIR_WRITE,
                byteArrayOf(0),
            )
            pending = false
            ClockResult.Ok(now)
        } catch (c: CancellationException) {
            if (pending) discardPending()
            throw c
        } catch (e: Exception) {
            if (pending) discardPending()
            ClockResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /** 重新 LOAD，丢弃尚未 STORE 的写入。失败就算了，只是尽力收尾。 */
    private suspend fun discardPending() {
        try {
            link.transfer(WatchProtocol.GRP_SETTINGS, WatchProtocol.CMD_SETTINGS_LOAD, WatchProtocol.DIR_WRITE)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // 收尾失败不覆盖原始错误
        }
    }

    /**
     * 设置能力矩阵。
     *
     * 只列出**协议层真实存在**的项，并明确标注哪些写不了：
     * CR-4 的 `GRP_SETTINGS` 只暴露日期/时间/加载/存储四条命令，
     * 单位制、报警阈值、采样间隔都在固件内部，没有公开的写命令——
     * 与其做个假开关，不如把边界写清楚。
     */
    fun settingsMatrix(info: WatchInfo, now: LocalDateTime = LocalDateTime.now()): List<WatchSetting> = listOf(
        WatchSetting(
            key = "clock",
            label = "时间 / 日期",
            value = "%04d-%02d-%02d %02d:%02d:%02d".format(
                now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second,
            ),
            supported = true,
            writable = true,
            note = "下发本机时间，逐字节回读校验后落盘",
        ),
        WatchSetting(
            key = "serial",
            label = "序列号",
            value = info.serial.ifBlank { "—" },
            supported = true,
            writable = false,
            note = "只读（GRP 0xA0 / CMD 0x03）",
        ),
        WatchSetting(
            key = "firmware",
            label = "固件 / 硬件",
            value = "${info.software.trim()} / ${info.hardware.trim()}",
            supported = true,
            writable = false,
            note = if (info.fw6) "新指令集（fw6）" else "老指令集（递减编号）",
        ),
        WatchSetting(
            key = "ndives",
            label = "日志容量",
            value = "${info.ndives} 条（最新 #${info.lastIndex}）",
            supported = true,
            writable = false,
            note = "环形缓冲：写满后覆盖最旧记录并重排编号",
        ),
        WatchSetting(
            key = "units",
            label = "单位制（公制/英制）",
            value = null,
            supported = false,
            writable = false,
            note = "固件未开放写命令，只能在表上手动改",
        ),
        WatchSetting(
            key = "alarm_depth",
            label = "深度 / 时间报警",
            value = null,
            supported = false,
            writable = false,
            note = "同上：GRP 0xB0 只暴露日期/时间/存储",
        ),
        WatchSetting(
            key = "sample_rate",
            label = "采样间隔",
            value = null,
            supported = false,
            writable = false,
            note = "同上；实际采样 1s，改不了",
        ),
        WatchSetting(
            key = "gf",
            label = "保守度 / GF",
            value = null,
            supported = false,
            writable = false,
            note = "本机算法参数，不在手表里（App 侧调整）",
        ),
    )

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_ERRORS = 6
        private const val MAX_PROFILE_CHUNKS = 4096
    }
}

/** 一条下载结果写到哪里。 */
fun interface DiveIngestor {
    suspend fun ingest(deviceId: String, index: Int, fingerprint: String, bytes: ByteArray): WatchIngest
}

/** 设备信息 + 本地进度 + 计划，一起传给界面与 [WatchSync.run]。 */
data class PlanBundle(
    val watch: WatchInfo,
    val local: WatchProgress?,
    val plan: SyncPlan,
)

data class SyncProgress(
    val current: Int,
    val total: Int,
    val index: Int,
    val stage: String,
) {
    val fraction: Float get() = if (total <= 0) 0f else current.toFloat() / total
}

data class SyncResult(
    val plan: SyncPlan,
    val watch: WatchInfo,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val elapsedMs: Long = 0,
    val errors: List<String> = emptyList(),
) {
    val total: Int get() = added + updated + skipped + failed
    val text: String
        get() = buildString {
            append(
                when (plan.kind) {
                    PlanKind.UP_TO_DATE -> "已是最新"
                    PlanKind.INCREMENTAL -> "增量同步"
                    PlanKind.FULL -> if (plan.fallback) "降级全量" else "全量同步"
                }
            )
            if (plan.hasWork) {
                append("：新增 $added")
                if (updated > 0) append("，更新 $updated")
                if (skipped > 0) append("，跳过 $skipped")
                if (failed > 0) append("，失败 $failed")
                append("（${elapsedMs / 1000.0} 秒）")
            }
        }
}

/** 一条设置项的能力描述。 */
data class WatchSetting(
    val key: String,
    val label: String,
    val value: String?,
    val supported: Boolean,
    val writable: Boolean,
    val note: String? = null,
)

sealed class ClockResult {
    data class Ok(val at: LocalDateTime) : ClockResult()
    data class Failed(val reason: String) : ClockResult()
}

private fun asciiOf(b: ByteArray): String =
    buildString(b.size) {
        for (x in b) {
            val v = x.toInt() and 0xFF
            append(if (v in 32..126) v.toChar() else if (v == 0) ' ' else '.')
        }
    }
