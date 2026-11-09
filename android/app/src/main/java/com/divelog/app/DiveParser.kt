package com.divelog.app

import org.json.JSONObject
import java.util.Locale

/**
 * libdc (libdivecomputer) JNI 包装：把下载到的原始 dive 字节解析成结构化数据。
 *
 * .so 由 NDK 从 libdivecomputer 的 deepsix_excursion 解析器交叉编译而来
 *（只含解析能力，不含传输层；BLE 扫描/下载由 Kotlin 侧 BluetoothGatt 完成）。
 *
 * 分层说明：为了让 JVM 单元测试（无设备）也能覆盖 JSON→模型的转换，
 * 解析逻辑放在 [fromJson] 这一层（纯 Kotlin），native 调用只负责产 JSON。
 */
object DiveParser {

    /**
     * 采样点。[gas] 是该时刻正在呼吸的气体在 [Dive.gases] 里的下标；
     * 记录本身没带气体信息时为 null（CR-4 的 v0 格式就是这样）。
     */
    data class Sample(val t: Int, val depth: Double, val temp: Double?, val gas: Int? = null)

    /** 一种呼吸气体，o2/he 是体积分数（0.32 = EAN32）。 */
    data class Gas(val o2: Double, val he: Double = 0.0) {
        val n2: Double get() = (1.0 - o2 - he).coerceAtLeast(0.0)
        val isNitrox: Boolean get() = o2 > 0.215
        val label: String
            get() = when {
                he > 0.001 -> "Tx ${pct(o2)}/${pct(he)}"
                o2 > 0.995 -> "纯氧"
                isNitrox -> "EAN${pct(o2)}"
                else -> "空气"
            }

        private fun pct(v: Double) = Math.round(v * 100).toString()
    }

    data class Dive(
        val bytes: Int,
        val divetime: Int,          // 秒
        val maxdepth: Double,       // 米
        val tempMin: Double,        // 摄氏度
        val gasmixCount: Int,
        val sampleCount: Int,
        val year: Int, val month: Int, val day: Int,
        val hour: Int, val minute: Int, val second: Int,
        val samples: List<Sample>,
        /** 记录里带的气体表；空表示这份记录没写气体（此时无法判断是否高氧）。 */
        val gases: List<Gas> = emptyList(),
    ) {
        // 必须显式 Locale.US：默认 Locale 在阿拉伯语/波斯语等地区会把 %04d 输出成本地数字，
        // 之后 datetime 解析失败、归零，统计与去重签名全跟着坏。
        val dateText: String
            get() = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
        val durationText: String get() = String.format(Locale.US, "%d:%02d", divetime / 60, divetime % 60)

        /** 主力气体：出现在剖面里的第一种；没有气体信息时为 null。 */
        val primaryGas: Gas? get() = gases.firstOrNull()
    }

    /** .so 是否成功加载。加载失败不抛异常，界面据此降级显示。 */
    val isAvailable: Boolean = runCatching { System.loadLibrary("dcbridge") }.isSuccess

    /** 加载失败时的异常（界面上可展示原因），成功为 null。 */
    val loadError: String? = if (isAvailable) null else
        runCatching { System.loadLibrary("dcbridge") }.exceptionOrNull()?.toString()

    /** .so 版本/能力描述，便于确认加载成功。 */
    external fun nativeVersion(): String

    /** 原始字节 -> JSON 字符串（失败返回含 ok:false 的 JSON）。 */
    external fun nativeParseDive(data: ByteArray): String?

    /** 原始字节 -> Dive；任一步失败返回 null（不抛异常，避免在 Compose 里崩溃）。 */
    fun parse(data: ByteArray): Dive? {
        if (!isAvailable) return null
        val json = runCatching { nativeParseDive(data) }.getOrNull() ?: return null
        return fromJson(json)
    }

    /**
     * JSON -> Dive。纯逻辑，不触碰 native，可直接跑 JVM 单元测试。
     * 任何非法输入都返回 null（不抛异常）。
     */
    fun fromJson(s: String?): Dive? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(s)
            if (!o.optBoolean("ok", false)) return null
            val dt = o.optJSONObject("datetime")
            val arr = o.optJSONArray("samples")
            val samples = ArrayList<Sample>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // 单个采样点缺字段只丢这个点，不要把整条潜水判成解析失败
                    val e = arr.optJSONObject(i) ?: continue
                    if (!e.has("t") || !e.has("depth")) continue
                    val depth = e.optDouble("depth", Double.NaN)
                    if (!depth.isFinite()) continue
                    samples.add(Sample(
                        t = e.optInt("t"),
                        depth = depth,
                        temp = if (e.has("temp") && !e.isNull("temp")) e.optDouble("temp").takeIf { it.isFinite() } else null,
                        gas = if (e.has("g") && !e.isNull("g")) e.optInt("g") else null,
                    ))
                }
            }
            val garr = o.optJSONArray("gas")
            val gases = ArrayList<Gas>(garr?.length() ?: 0)
            if (garr != null) {
                for (i in 0 until garr.length()) {
                    val e = garr.getJSONObject(i)
                    gases.add(Gas(e.optDouble("o2", 0.21), e.optDouble("he", 0.0)))
                }
            }
            Dive(
                bytes = o.optInt("bytes"),
                divetime = o.optInt("divetime"),
                maxdepth = o.optDouble("maxdepth", 0.0),
                tempMin = o.optDouble("tempMin", 0.0),
                gasmixCount = o.optInt("gasmixCount"),
                sampleCount = o.optInt("sampleCount"),
                year = dt?.optInt("year") ?: 0,
                month = dt?.optInt("month") ?: 0,
                day = dt?.optInt("day") ?: 0,
                hour = dt?.optInt("hour") ?: 0,
                minute = dt?.optInt("minute") ?: 0,
                second = dt?.optInt("second") ?: 0,
                samples = samples,
                gases = gases,
            )
        }.getOrNull()
    }
}
