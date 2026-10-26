package com.divelog.app.sync

import com.divelog.app.data.WatchProgress

/**
 * 同步策略（纯逻辑，无 IO，可单测）。
 *
 * 两种模式：
 *  - **全量**：从手表最新一条往回走 `ndives` 步，逐条读 header 比指纹，缺的补、变的重下。
 *    慢，但能发现「编号重排」「旧记录被改写」这类异常，是兜底手段。
 *  - **增量**：只补 `lastIndex - localMaxIndex` 条。为了让这个减法可信，开跑前必须验一次锚点：
 *    读手表上 `localMaxIndex` 那条的指纹，和本地存的对得上，才说明编号没被重排。
 *
 * 为什么不能只用 `lastIndex - maxIndex`：
 *  CR-4 的日志是环形缓冲，写满后会覆盖最旧的、编号重排。
 *  这时减法算出来的「缺口」全是错的，必须退回全量。
 *  多读一次 header（260 字节）就能判定，代价远小于重下几十条。
 */
enum class SyncMode {
    /** 有增量条件就增量，否则全量（默认）。 */
    AUTO,
    /** 强制全量：逐条校验指纹。 */
    FULL,
    /** 强制增量：锚点校验不过会 fallback 成全量，并在结果里说明原因。 */
    INCREMENTAL,
}

enum class PlanKind { UP_TO_DATE, INCREMENTAL, FULL }

data class SyncPlan(
    val kind: PlanKind,
    /** 实际采用的模式（AUTO 会被解析成具体一种）。 */
    val resolvedMode: SyncMode,
    /** 预计从哪条到哪条（编号，含两端）；UP_TO_DATE 时为 0。 */
    val fromIndex: Int,
    val toIndex: Int,
    /** 需要从 last 往回走的步数 = 实际要检查的记录数。 */
    val steps: Int,
    /** 给用户看的决策理由。 */
    val reason: String,
    /** 增量不可用而降级成全量。 */
    val fallback: Boolean = false,
) {
    val hasWork: Boolean get() = kind != PlanKind.UP_TO_DATE && steps > 0
}

object SyncPlanner {

    /**
     * @param anchorFingerprint 手表上 `local.maxIndex` 那条的指纹；读不到传 null。
     *        调用方负责在计划前多读一次 header（见 WatchSync.anchorFingerprint）。
     */
    fun plan(
        mode: SyncMode,
        watch: WatchInfo,
        local: WatchProgress?,
        anchorFingerprint: String?,
    ): SyncPlan {
        if (watch.ndives <= 0 || watch.lastIndex <= 0) {
            return SyncPlan(PlanKind.UP_TO_DATE, mode, 0, 0, 0, "手表上没有日志")
        }

        if (mode == SyncMode.FULL) {
            return full(watch, "手动全量：逐条校验 ${watch.ndives} 条")
        }

        if (local == null || local.maxIndex <= 0) {
            return full(watch, "本地没有这块表的记录，首次同步取全量")
        }

        val delta = watch.lastIndex - local.maxIndex

        // 编号比本地还小：手表日志被清空后从 1 重新计数，不能当成「没有新数据」
        if (delta < 0) {
            return full(
                watch,
                "手表最新编号 #${watch.lastIndex} 小于本地 #${local.maxIndex}，日志被清空/重排，改走全量",
                fallback = true,
            )
        }
        if (delta == 0) {
            if (watch.ndives < local.count) {
                return full(
                    watch,
                    "编号没变但手表只剩 ${watch.ndives} 条（本地 ${local.count}），日志被覆盖过，改走全量",
                    fallback = true,
                )
            }
            return SyncPlan(
                PlanKind.UP_TO_DATE, mode, local.maxIndex, watch.lastIndex, 0,
                "已是最新（手表 ${watch.lastIndex} / 本地 ${local.maxIndex}）",
            )
        }

        // 手表条数比本地还少 → 旧记录被覆盖，编号已经重排，减法算出来的缺口不可信
        if (watch.ndives < local.count) {
            return full(
                watch,
                "手表只有 ${watch.ndives} 条，少于本地 ${local.count} 条，日志被清空/覆盖过，改走全量",
                fallback = true,
            )
        }

        if (delta > watch.ndives) {
            return full(
                watch,
                "缺口 $delta 超过手表存量 ${watch.ndives}，编号不可信，改走全量",
                fallback = true,
            )
        }

        if (local.fingerprint.isNullOrBlank()) {
            return full(watch, "本地缺锚点指纹（#${local.maxIndex}），无法校验编号，改走全量", fallback = true)
        }
        if (anchorFingerprint == null) {
            return full(watch, "读不到手表上 #${local.maxIndex} 的 header，改走全量", fallback = true)
        }
        if (!anchorFingerprint.equals(local.fingerprint, ignoreCase = true)) {
            return full(
                watch,
                "锚点指纹不一致（本地 ${local.fingerprint} / 手表 $anchorFingerprint），日志已重排，改走全量",
                fallback = true,
            )
        }

        return SyncPlan(
            kind = PlanKind.INCREMENTAL,
            resolvedMode = SyncMode.INCREMENTAL,
            fromIndex = local.maxIndex + 1,
            toIndex = watch.lastIndex,
            steps = delta,
            reason = "增量：锚点 #${local.maxIndex} 指纹一致，只补 $delta 条",
        )
    }

    private fun full(watch: WatchInfo, reason: String, fallback: Boolean = false) = SyncPlan(
        kind = PlanKind.FULL,
        resolvedMode = SyncMode.FULL,
        fromIndex = (watch.lastIndex - watch.ndives + 1).coerceAtLeast(0),
        toIndex = watch.lastIndex,
        steps = watch.ndives,
        reason = reason,
        fallback = fallback,
    )
}
