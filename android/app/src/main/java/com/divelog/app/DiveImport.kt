package com.divelog.app

import java.io.File
import java.io.FileWriter

/**
 * 本地日志导入：把外部拿到的原始 `.bin`（CR-4 导出文件 / 以后从手表下载的字节）
 * 解析成 [Dive] + 采样点，落盘到应用私有目录，之后与内置数据一起展示。
 *
 * 为什么要先做这一步：
 *  - 手表还没到，但"拿到字节 → 解析 → 入库 → 画图"这条链路可以先用文件跑通，
 *    等 Phase 3 接上 BLE，只把"字节来源"从文件换成蓝牙下载即可；
 *  - 落盘格式直接复用 assets 里 dives.csv / samples.csv 的同构 CSV，
 *    解析器（[DiveCsv]）零改动，也不会引入数据库依赖。
 *
 * 本文件的分组/命名/去重逻辑是纯 Kotlin，可被 JVM 单测覆盖（见 ImportTest）。
 */

/** 一次导入的结果汇总。 */
data class ImportSummary(
    val added: Int = 0,
    val duplicate: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = added + duplicate + failed
    val text: String
        get() = when {
            total == 0 -> "没有选择文件"
            added == 0 && duplicate == 0 -> "导入失败 $failed 条（不是有效的 CR-4 日志）"
            else -> buildString {
                append("成功导入 $added 条")
                if (duplicate > 0) append("，跳过重复 $duplicate 条")
                if (failed > 0) append("，失败 $failed 条")
            }
        }
}

/** 纯逻辑：解析结果 -> 入库模型。 */
object DiveIngest {

    /**
     * 去重签名：同一次潜水无论改名多少次，签名都相同。
     * 用「时间 + 水下时长 + 原始字节数」——设备日志里这三点足以唯一确定一条记录。
     */
    fun signature(d: Dive): String = "${d.datetime}|${d.divetimeS}|${d.bytes}"

    fun signature(p: DiveParser.Dive): String =
        "${p.dateText}|${p.divetime}|${p.bytes}"

    /** 生成库内唯一 id，避开内置文件与已导入文件的命名。 */
    fun idFor(p: DiveParser.Dive, existing: Set<String>): String {
        val stamp = p.dateText.replace('-', '_').replace(':', '_').replace(' ', '_')
        val base = "imp_${stamp}_${p.bytes}"
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }

    /** 解析结果 -> [Dive]。 */
    fun toDive(p: DiveParser.Dive, id: String, no: Int): Dive = Dive(
        file = id,
        no = no,
        datetime = p.dateText,
        divetimeS = p.divetime,
        maxdepthM = p.maxdepth,
        tempMinC = p.tempMin,
        nsamples = if (p.sampleCount > 0) p.sampleCount else p.samples.size,
        bytes = p.bytes.toLong(),
    )

    fun toSamples(p: DiveParser.Dive): List<Sample> =
        p.samples.map { Sample(it.t, it.depth, it.temp) }
}

/**
 * 已导入日志的落盘存储（应用私有目录，CSV 与 assets 同构）。
 *
 * 目录结构：
 *   filesDir/imported/dives.csv    表头同 assets/dives.csv
 *   filesDir/imported/samples.csv  表头同 assets/samples.csv
 */
class ImportedStore(private val dir: File) {

    private val divesFile: File get() = File(dir, "dives.csv")
    private val samplesFile: File get() = File(dir, "samples.csv")

    fun load(): DiveDataSet {
        if (!divesFile.exists()) return DiveDataSet(emptyList(), emptyMap())
        val dives = runCatching {
            divesFile.bufferedReader().use { DiveCsv.parseDives(it) }
        }.getOrElse { emptyList() }
        val samples = if (samplesFile.exists()) {
            runCatching {
                samplesFile.bufferedReader().use { DiveCsv.parseSamples(it) }
            }.getOrElse { emptyMap() }
        } else emptyMap()
        return DiveDataSet(dives, samples)
    }

    fun count(): Int = load().dives.size

    /** 追加一条。调用方需保证 id 不与库内既有条目冲突。 */
    fun append(dive: Dive, samples: List<Sample>) {
        if (!dir.exists()) dir.mkdirs()

        FileWriter(divesFile, true).use { w ->
            if (divesFile.length() == 0L) w.write(DIVES_HEADER)
            w.write(
                listOf(
                    dive.file,
                    dive.no.toString(),
                    dive.datetime,
                    dive.divetimeS.toString(),
                    num(dive.maxdepthM),
                    num(dive.tempMinC),
                    "0",
                    dive.nsamples.toString(),
                    "0",
                    dive.bytes.toString(),
                    "imported",
                ).joinToString(",") + "\n"
            )
        }

        FileWriter(samplesFile, true).use { w ->
            if (samplesFile.length() == 0L) w.write(SAMPLES_HEADER)
            val sb = StringBuilder(samples.size * 24)
            for (s in samples) {
                sb.append(dive.file).append(',')
                    .append(s.t).append(',')
                    .append(num(s.depth)).append(',')
                    .append(if (s.temp == null) "" else num(s.temp))
                    .append('\n')
            }
            w.write(sb.toString())
        }
    }

    fun clear() {
        divesFile.delete()
        samplesFile.delete()
    }

    /** 存储里已有的 id 集合。 */
    fun ids(): Set<String> = load().dives.map { it.file }.toSet()

    private fun num(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private companion object {
        const val DIVES_HEADER =
            "file,dive_no,datetime,divetime_s,maxdepth_m,temp_min_c,gasmixes,nsamples,nevents,bytes,path\n"
        const val SAMPLES_HEADER = "file,t_sec,depth_m,temp_c\n"
    }
}
