package com.divelog.app

import org.json.JSONObject

/**
 * libdc (libdivecomputer) JNI 包装：把下载到的原始 dive 字节解析成结构化数据。
 *
 * .so 由 NDK 从 libdivecomputer 的 deepsix_excursion 解析器交叉编译而来
 *（只含解析能力，不含传输层；BLE 扫描/下载由 btleplug 在 Kotlin 侧完成）。
 *
 * 分层说明：为了让 JVM 单元测试（无设备）也能覆盖 JSON→模型的转换，
 * 解析逻辑放在 [fromJson] 这一层（纯 Kotlin），native 调用只负责产 JSON。
 */
object DiveParser {

    data class Sample(val t: Int, val depth: Double, val temp: Double?)

    data class Dive(
        val bytes: Int,
        val divetime: Int,          // 秒
        val maxdepth: Double,       // 米
        val tempMin: Double,        // 摄氏度
        val gasmixCount: Int,
        val sampleCount: Int,
        val year: Int, val month: Int, val day: Int,
        val hour: Int, val minute: Int, val second: Int,
        val samples: List<Sample>
    ) {
        val dateText: String get() = "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
        val durationText: String get() = "%d:%02d".format(divetime / 60, divetime % 60)
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
                    val e = arr.getJSONObject(i)
                    samples.add(Sample(
                        t = e.getInt("t"),
                        depth = e.getDouble("depth"),
                        temp = if (e.has("temp") && !e.isNull("temp")) e.getDouble("temp") else null
                    ))
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
                samples = samples
            )
        }.getOrNull()
    }
}