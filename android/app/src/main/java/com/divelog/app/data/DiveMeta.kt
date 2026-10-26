package com.divelog.app.data

/**
 * 每一条**原始记录**的元数据（一行 CSV / 一次手表下载 = 一条原始记录）。
 *
 * 设计原则：**原始记录永不物理删除**。
 *   - 用户「删除」→ [userDeleted] 置位（软删除，可撤销）
 *   - 自动合并/手动合并 → [groupId] + [mergeKind] 打标
 *   - 垃圾判定 → [filterReason] 打标（只标记，不改数据）
 *   - 手表来源 → [watchIndex] + [fingerprint] 用于增量同步对齐
 *
 * 这样任何时候都能回答「这条为什么没出现在列表里」，
 * 也能在换了合并阈值之后重新物化视图而不丢数据。
 */
data class DiveMeta(
    val id: String,
    val source: DiveSource,
    /** 手表序列号；内置/文件来源为 null。 */
    val deviceId: String? = null,
    /** 手表上的日志编号（fw6 是 32 位链式编号，非 fw6 是递减序号）。 */
    val watchIndex: Int? = null,
    /** 手表 header 里的 6 字节指纹（hex），增量同步的锚点。 */
    val fingerprint: String? = null,
    /** 所属合并组；null 表示这次没被合并。 */
    val groupId: String? = null,
    val mergeKind: MergeKind = MergeKind.NONE,
    /** 自动过滤判定（垃圾日志/重复）；null 表示有效。 */
    val filterReason: FilterReason? = null,
    /** 用户显式删除（软删除）。 */
    val userDeleted: Boolean = false,
    /** 原始字节落盘路径（相对 filesDir），用于「重新解析」。 */
    val rawPath: String? = null,
    val importedAt: Long = 0L,
) {
    val isMerged: Boolean get() = groupId != null && mergeKind != MergeKind.NONE
    val isFiltered: Boolean get() = filterReason != null
    /** 界面上给这条记录打的角标；null 表示不需要角标。 */
    val badge: String?
        get() = when {
            userDeleted -> "已删除"
            filterReason != null -> filterReason.label
            mergeKind == MergeKind.MANUAL -> "手动合并"
            mergeKind == MergeKind.AUTO -> "自动合并"
            else -> null
        }
}

enum class DiveSource(val code: String) {
    /** APK 内置的真实设备数据（assets）。 */
    BUILTIN("builtin"),
    /** 用户通过系统文件选择器导入的 .bin。 */
    FILE("file"),
    /** 从手表蓝牙下载。 */
    WATCH("watch");

    companion object {
        fun of(code: String?): DiveSource = entries.firstOrNull { it.code == code } ?: BUILTIN
    }
}

enum class MergeKind(val code: String) {
    NONE("none"),
    /** 由「水面间隔 <= 阈值」自动归组。 */
    AUTO("auto"),
    /** 用户多选手动合并。 */
    MANUAL("manual");

    companion object {
        fun of(code: String?): MergeKind = entries.firstOrNull { it.code == code } ?: NONE
    }
}

enum class FilterReason(val code: String, val label: String) {
    /** 时长/深度低于垃圾阈值（见 JunkRule）。 */
    JUNK("junk", "垃圾"),
    /** 与已有记录时间+时长+字节数完全相同。 */
    DUPLICATE("dup", "重复");

    companion object {
        fun of(code: String?): FilterReason? = entries.firstOrNull { it.code == code }
    }
}
