package com.divelog.app

import android.content.Context
import com.divelog.app.data.AutoGroup
import com.divelog.app.data.DiveDb
import com.divelog.app.data.DiveMeta
import com.divelog.app.data.DiveRow
import com.divelog.app.data.DiveSource
import com.divelog.app.data.FilterReason
import com.divelog.app.data.GasSource
import com.divelog.app.data.SyncRunRecord
import com.divelog.app.data.WatchProgress
import com.divelog.app.data.WatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 数据层：把桌面端解析好的真实潜水数据（data/dives.csv + samples.csv）
 * 打包进 APK assets，启动时一次性读进内存。
 *
 * 规模：253 条潜水 / 约 6 万个采样点，内存占用约 2~3 MB，无需数据库。
 * 解析逻辑是纯 Kotlin（只依赖 BufferedReader），可以在 JVM 单元测试里直接跑。
 */

/**
 * 一条潜水的**摘要**（对应 dive 表一行）。
 *
 * 为什么摘要要自带平均深度/最高水温/气体数：
 * 列表要给每条记录显示这些指标，如果每次都遍历采样点，253 条 × 240 点就是 6 万次读取，
 * 数据量涨到几千条时首屏会明显卡。这些值在**入库时算一次**存成列，
 * 列表就只读摘要（一行几十字节），采样点在后台另行全量载入。
 */
data class Dive(
    val file: String,
    val no: Int,
    val datetime: String,      // "2020-11-22 12:05:04"
    val divetimeS: Int,
    val maxdepthM: Double,
    val tempMinC: Double,
    val nsamples: Int,
    val bytes: Long,
    /** 时间加权平均深度（入库时按采样点算好；0 表示老数据没算过，回落到采样点）。 */
    val avgDepthM: Double = 0.0,
    /** 最高水温；记录没写就是 null。 */
    val tempMaxC: Double? = null,
    /** 记录里出现过的不同气体数（0 = 记录里没有气体信息）。 */
    val gasCount: Int = 0,
) {
    val year: Int get() = datetime.take(4).toIntOrNull() ?: 0
    val month: Int get() = datetime.drop(5).take(2).toIntOrNull() ?: 0
    val dateText: String get() = datetime.take(10)
    val timeText: String get() = datetime.drop(11)
    val durationText: String get() = Fmt.duration(divetimeS)
    val depthText: String get() = Fmt.one(maxdepthM) + " m"
}

/** 一个采样点。temperature 可能缺失（原始设备未记录）。 */
data class Sample(val t: Int, val depth: Double, val temp: Double?, val gas: Int? = null)

/**
 * 采样点 -> 摘要。**入库时算一次**，之后列表只看列，不再回扫采样点。
 *
 * 口径与 [DiveEntries.avgDepth] 完全一致（梯形积分），
 * 所以「入库算的」和「回扫算的」对同一条记录给出同一个数。
 */
object SampleStats {

    /** 时间加权平均深度；没有可用采样点时返回 0。 */
    fun avgDepth(samples: List<Sample>): Double {
        var area = 0.0
        var time = 0.0
        for (i in 1 until samples.size) {
            val dt = (samples[i].t - samples[i - 1].t).toDouble()
            if (dt <= 0) continue
            area += (samples[i].depth + samples[i - 1].depth) / 2.0 * dt
            time += dt
        }
        return if (time > 0) area / time else 0.0
    }

    fun maxTemp(samples: List<Sample>): Double? =
        samples.mapNotNull { it.temp }.maxOrNull()

    fun minTemp(samples: List<Sample>): Double? =
        samples.mapNotNull { it.temp }.minOrNull()

    /** 记录里出现过几种不同气体；0 表示没写。 */
    fun gasCount(samples: List<Sample>): Int =
        samples.mapNotNull { it.gas }.distinct().size
}

/** 补上摘要字段（入库前调用一次，之后列表只读列）。 */
fun Dive.withSummary(samples: List<Sample>): Dive = copy(
    avgDepthM = if (samples.isEmpty()) avgDepthM else SampleStats.avgDepth(samples),
    tempMaxC = SampleStats.maxTemp(samples) ?: tempMaxC,
    gasCount = SampleStats.gasCount(samples).takeIf { it > 0 } ?: gasCount,
)

/**
 * 全量数据集 = 摘要 + 元数据。
 *
 * [samples] **默认是空的**：正常启动只载入摘要，剖面走 [SampleCache]。
 * 保留这个字段是因为旧版 CSV 迁移（[ImportedStore]）与单测需要一次性拿到全量剖面。
 */
data class DiveDataSet(
    val dives: List<Dive>,
    val samples: Map<String, List<Sample>> = emptyMap(),
    /** 每条原始记录的元数据：来源 / 手表编号 / 合并归属 / 过滤标记 / 收藏 / 气体。 */
    val meta: Map<String, DiveMeta> = emptyMap(),
) {
    fun samplesOf(d: Dive): List<Sample> = samples[d.file] ?: emptyList()
    fun metaOf(d: Dive): DiveMeta? = meta[d.file]

    /** 被标为无效（垃圾/重复）的记录数；只统计，不影响展示。 */
    val filteredCount: Int get() = meta.values.count { it.filterReason != null }
    val mergedCount: Int get() = meta.values.count { it.isMerged }
    val watchCount: Int get() = meta.values.count { it.source == DiveSource.WATCH }
    val deletedCount: Int get() = meta.values.count { it.userDeleted }
    val favoriteCount: Int get() = meta.values.count { it.favorite }

    /** 记录里自带的、可用的气体（v0 格式记录没有，所以通常是空）。 */
    val gasFromRecords: Int get() = meta.values.count { it.gasSource == GasSource.RECORD }

    val totalSamples: Int get() = samples.values.sumOf { it.size }
    val totalSeconds: Long get() = dives.sumOf { it.divetimeS.toLong() }
    val maxDepth: Double get() = dives.maxOfOrNull { it.maxdepthM } ?: 0.0
}

/** CSV 解析（dives.csv / samples.csv）。 */
object DiveCsv {

    fun parseDives(reader: BufferedReader): List<Dive> {
        val out = ArrayList<Dive>(300)
        reader.useLines { lines ->
            for ((i, raw) in lines.withIndex()) {
                if (i == 0 || raw.isBlank()) continue          // 跳过表头
                val p = raw.split(',')
                if (p.size < 10) continue
                out.add(
                    Dive(
                        file = p[0],
                        no = p[1].toIntOrNull() ?: 0,
                        datetime = p[2],
                        divetimeS = p[3].toIntOrNull() ?: 0,
                        maxdepthM = p[4].toDoubleOrNull() ?: 0.0,
                        tempMinC = p[5].toDoubleOrNull() ?: 0.0,
                        nsamples = p[7].toIntOrNull() ?: 0,
                        bytes = p[9].toLongOrNull() ?: 0L,
                    )
                )
            }
        }
        return out
    }

    /**
     * samples.csv -> file -> 采样点列表。
     * 注意 temp_c 可能为空（真实数据里有），必须解析成 null 而不是 0.0。
     */
    fun parseSamples(reader: BufferedReader): Map<String, List<Sample>> {
        val map = HashMap<String, MutableList<Sample>>(300)
        reader.useLines { lines ->
            for ((i, raw) in lines.withIndex()) {
                if (i == 0 || raw.isBlank()) continue
                val p = raw.split(',')
                if (p.size < 3) continue
                val t = p[1].toIntOrNull() ?: continue
                val d = p[2].toDoubleOrNull() ?: continue
                val tp = if (p.size > 3 && p[3].isNotBlank()) p[3].toDoubleOrNull() else null
                map.getOrPut(p[0]) { ArrayList() }.add(Sample(t, d, tp))
            }
        }
        return map.mapValues { (_, v) -> v.sortedBy { it.t } }
    }
}

/**
 * 采样点缓存：**全量常驻内存**，但载入是异步的。
 *
 * 分层思路：
 *   1. 列表只要摘要（dive 表）→ 一次 SELECT 就能出首屏，几十毫秒；
 *   2. 采样点（6 万点 ≈ 3 MB）在后台一次性读进内存，
 *      读完通过 [state] 换一份快照，Compose 侧订阅后自动重绘剖面；
 *   3. 全量还没读完时点进详情页也不会干等： [ensure] 只把这条潜水补进缓存
 *      （单条查询 + 两百行），比等全量快一个量级。
 *
 * 之所以全量留在内存而不是每次查库：剖面图、NDL 积分、合并拼接都要反复读同几个点，
 * 单次查询虽快，但滚动/切算法时会查上百次；一次性 3 MB 换掉这些往返更划算。
 */
class SampleCache(
    private val db: DiveDb,
    /** 一轮读多少条记录的剖面。一批约 250 点，40 条 ≈ 1 万点 ≈ 10ms，够细也不碎。 */
    private val chunk: Int = 40,
) {

    /** 一份不可变快照，换新即触发重组。 */
    data class Snapshot(
        val map: Map<String, List<Sample>> = emptyMap(),
        /** 全量载入是否完成。false 时详情页会走 [ensure] 单条补齐。 */
        val complete: Boolean = false,
        val loading: Boolean = false,
        /** 库里的采样点总数（进度文案用）。 */
        val total: Int = 0,
        /** 有剖面的记录数。 */
        val totalDives: Int = 0,
        /** 已经进缓存多少条记录。 */
        val loadedDives: Int = 0,
    ) {
        val count: Int get() = map.values.sumOf { it.size }
        fun of(id: String): List<Sample> = map[id] ?: emptyList()
        /** 0f..1f；没有剖面时算 1f（不用显示进度条）。 */
        val progress: Float
            get() = if (totalDives <= 0) 1f else (loadedDives.toFloat() / totalDives).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * 数据版本号：导入/同步/清空时 +1。
     *
     * 用来处理「一边载入一边导数据」的竞态：载入开始时记下版本号，
     * 结束时对不上就说明中途进了新记录，这份快照不能算完整，
     * 否则那一批新记录要等下次冷启动才出现。
     */
    @Volatile
    private var generation = 0

    /**
     * 全量载入内存，**分块推进**：每读完一批就换一份快照，
     * 界面订阅 [state] 就能看到「剖面 120/253」一直往前爬，而不是卡住几百毫秒。
     *
     * 重复调用直接复用结果；[force] 可强刷（导入/同步之后）。
     * 正在载入时再次调用会直接返回当前快照，不排队也不重复读。
     */
    suspend fun loadAll(force: Boolean = false): Snapshot = mutex.withLock {
        var snap = _state.value
        if (snap.loading) return@withLock snap
        if (snap.complete && !force) return@withLock snap
        var round = 0
        while (true) {
            snap = loadRound()
            // 载入期间进过新记录就再来一轮；最多两轮，防止边同步边载入时死转
            if (snap.complete || ++round >= 2) break
        }
        snap
    }

    /** 读一轮：每批换一份快照；返回的快照 `complete` 为 false 表示数据在载入期间变过。 */
    private suspend fun loadRound(): Snapshot {
        val cur = _state.value
        val gen = generation
        val ids = withContext(Dispatchers.IO) { db.diveIdsWithSamples() }
        val totalSamples = withContext(Dispatchers.IO) { db.sampleCount() }
        // 已缓存且仍存在的记录直接留着，只补缺的
        val keep = cur.map.filterKeys { it in ids }
        val todo = ids.filter { it !in keep }
        val map = HashMap<String, List<Sample>>(keep)

        _state.value = Snapshot(
            map = map,
            complete = todo.isEmpty() && generation == gen,
            loading = todo.isNotEmpty(),
            total = totalSamples,
            totalDives = ids.size,
            loadedDives = keep.size,
        )
        if (todo.isEmpty()) return _state.value

        try {
            var loaded = keep.size
            for (part in todo.chunked(chunk)) {
                val got = withContext(Dispatchers.IO) { db.samplesOf(part) }
                map.putAll(got)
                loaded += part.size
                _state.value = Snapshot(
                    map = HashMap(map),
                    complete = false,
                    loading = true,
                    total = totalSamples,
                    totalDives = ids.size,
                    loadedDives = loaded,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 界面销毁不算失败：保留已读到的，下次接着补
            _state.value = _state.value.copy(loading = false)
            throw e
        }

        return Snapshot(
            map = HashMap(map),
            // 中途进过新记录 => 这份快照不算完整，再来一轮补齐
            complete = generation == gen,
            loading = false,
            total = totalSamples,
            totalDives = ids.size,
            loadedDives = ids.size,
        ).also { _state.value = it }
    }

    /**
     * 保证这几条记录的剖面在缓存里（详情页兜底）。
     * 已经在缓存里的不会重复读；全量载入完成后这里是纯内存命中。
     *
     * 这是「跳到任意一条记录也不用等全量」的关键：单条查询只要几毫秒。
     */
    suspend fun ensure(ids: Collection<String>): Snapshot {
        val cur = _state.value
        val missing = ids.filter { it !in cur.map }
        if (missing.isEmpty()) return cur
        val extra = withContext(Dispatchers.IO) { db.samplesOf(missing) }
        if (extra.isEmpty()) return _state.value
        val now = _state.value
        return now.copy(
            map = now.map + extra,
            loadedDives = (now.loadedDives + extra.size).coerceAtMost(now.totalDives),
        ).also { _state.value = it }
    }

    fun cached(id: String): List<Sample>? = _state.value.map[id]

    /**
     * 数据有增减（导入 / 同步 / 清空）后调用：标记缓存失效。
     * 不立刻重读——等界面下次 [loadAll] 时再补，避免导入 20 条就重读 6 万点。
     *
     * 同时推进 [generation]：即使此刻正在载入，那一轮结束时也会发现自己过期，
     * 不会把「少了几条新记录」的快照当成完整结果。
     */
    fun invalidate() {
        generation++
        val cur = _state.value
        if (cur.complete) _state.value = cur.copy(complete = false)
    }
}

/**
 * 数据仓库。
 *
 * 唯一的真源是 SQLite（[DiveDb]）：
 *   - 首次启动时把 assets 内置数据 + 旧版 CSV 导入一次性 **seed** 进库；
 *   - 之后无论文件导入还是手表下载，都只往库里写，CSV 不再参与运行时读写；
 *   - 原始字节（.bin）仍然单独落盘到 `files/raw/`，库里只存路径——这样随时能重放解析，
 *     而合并/过滤只改标记位，绝不动原始数据。
 *
 * 读取分两条通道：**[load] 只出摘要**（列表首屏），**[samples] 负责剖面**（后台全量）。
 */
class DiveRepository(private val ctx: Context) {

    val db: DiveDb = DiveDb(ctx.applicationContext)

    /** 采样点缓存：启动后由界面层触发 `loadAll()`，这里只提供通道。 */
    val samples = SampleCache(db)

    /** 旧版 CSV 导入目录：只在一次性迁移时读。 */
    private val legacyStore = ImportedStore(File(ctx.filesDir, "imported"))
    private val rawRoot = File(ctx.filesDir, "raw")

    @Volatile
    private var cached: DiveDataSet? = null

    /**
     * 载入**摘要**（不含采样点）：一次 SELECT 就够。
     *
     * 采样点走 [samples] 的独立通道 —— 列表不必等六万个点读完才能显示，
     * 这也是「先出列表、剖面后到」的实现基础。
     */
    suspend fun load(force: Boolean = false): DiveDataSet = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }
        seedIfNeeded()
        backfillSummariesIfNeeded()
        val ds = DiveDataSet(
            dives = db.allDives(),
            meta = db.metaMap(),
        )
        cached = ds
        ds
    }

    /**
     * v2 -> v3 迁移：老数据没有 avg_depth_m / temp_max_c，
     * 这里按采样点算一遍写回。只跑一次（判据是「有采样点但 avg_depth_m = 0」）。
     */
    private fun backfillSummariesIfNeeded() {
        if (!db.needsSummaryBackfill()) return
        db.backfillSummaries()
    }

    /** 库为空时的一次性初始化：内置数据 + 旧 CSV + 旧设置里的删除/合并标记。 */
    private fun seedIfNeeded() {
        if (!db.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = ArrayList<DiveRow>(300)

        val builtinSamples = ctx.assets.open("samples.csv").bufferedReader().use { DiveCsv.parseSamples(it) }
        ctx.assets.open("dives.csv").bufferedReader().use { reader ->
            for (d in DiveCsv.parseDives(reader)) {
                val s = builtinSamples[d.file] ?: emptyList()
                rows.add(
                    DiveRow(
                        dive = d.withSummary(s),
                        samples = s,
                        meta = DiveMeta(id = d.file, source = DiveSource.BUILTIN, importedAt = now),
                    )
                )
            }
        }

        // 旧版本用 CSV 存导入记录，这里搬进库（文件保留不删，出问题能回退）
        val legacy = legacyStore.load()
        for (d in legacy.dives) {
            val s = legacy.samples[d.file] ?: emptyList()
            rows.add(
                DiveRow(
                    dive = d.withSummary(s),
                    samples = s,
                    meta = DiveMeta(id = d.file, source = DiveSource.FILE, importedAt = now),
                )
            )
        }
        db.insertAll(rows)

        // 旧设置里的软删除/手动合并，迁成标记位
        val prefs = DivePrefs(ctx)
        if (prefs.deleted.isNotEmpty()) db.setUserDeleted(prefs.deleted, true)
        for (g in prefs.manualGroups) runCatching { db.saveManualGroup(g) }
    }

    /**
     * 导入原始字节（CR-4 的 `.bin`，或任何符合该格式的字节流）。
     * 去重按 [DiveIngest.signature]：同一条潜水改名再导一次不会重复。
     */
    suspend fun importBytes(files: List<ByteArray>): ImportSummary = withContext(Dispatchers.Default) {
        seedIfNeeded()
        val ids = db.ids().toMutableSet()
        val sigs = db.signatures()
        var nextNo = db.maxDiveNo() + 1
        var added = 0
        var duplicate = 0
        var failed = 0
        val rows = ArrayList<DiveRow>(files.size)
        // 记录自带的气体要在插入后再补写（insertAll 的列清单里没有它，
        // 因为它是"可选、可能为空"的派生信息，单独 UPDATE 更省事也更安全）
        val gasPatches = ArrayList<Triple<String, Double, Double>>(files.size)

        for (bytes in files) {
            val parsed = DiveParser.parse(bytes)
            if (parsed == null || parsed.divetime <= 0 || parsed.samples.isEmpty()) {
                failed++
                continue
            }
            val sig = DiveIngest.signature(parsed)
            if (sig in sigs) {
                duplicate++
                continue
            }
            val id = DiveIngest.idFor(parsed, ids)
            val dive = DiveIngest.toDive(parsed, id, nextNo++)
            val rawPath = saveRaw(id, bytes)
            rows.add(
                DiveRow(
                    dive = dive,
                    samples = DiveIngest.toSamples(parsed),
                    meta = DiveMeta(
                        id = id,
                        source = DiveSource.FILE,
                        rawPath = rawPath,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
            )
            ids += id
            sigs += sig
            DiveIngest.recordGas(parsed)?.let { (o2, he) -> gasPatches.add(Triple(id, o2, he)) }
            added++
        }
        db.insertAll(rows)
        for ((id, o2, he) in gasPatches) db.setRecordGas(id, o2, he)
        if (rows.isNotEmpty()) {
            cached = null
            samples.invalidate()
        }
        ImportSummary(added, duplicate, failed)
    }

    /**
     * 手表下载的一条记录入库（幂等）。
     *
     * 幂等键是 `(deviceId, watchIndex)`：
     *  - 同一编号同一指纹 → [WatchIngest.SKIPPED]（增量同步重跑不会产生重复）
     *  - 同一编号不同指纹 → 覆盖（设备重算/重写过的日志）
     *  - 编号新但内容与已有记录一致 → [WatchIngest.DUPLICATE]（手表日志被清空重排过）
     */
    suspend fun ingestWatch(
        deviceId: String,
        watchIndex: Int,
        fingerprint: String,
        bytes: ByteArray,
    ): WatchIngest = withContext(Dispatchers.Default) {
        seedIfNeeded()
        val parsed = DiveParser.parse(bytes)
        if (parsed == null || parsed.divetime <= 0 || parsed.samples.isEmpty()) {
            return@withContext WatchIngest.FAILED
        }
        val id = "watch_${deviceId}_$watchIndex"
        val existing = db.metaMap()[id]
        if (existing != null && existing.fingerprint == fingerprint) {
            return@withContext WatchIngest.SKIPPED
        }
        val isUpdate = existing != null
        if (!isUpdate && db.countBySignature(parsed.dateText, parsed.divetime, parsed.bytes.toLong(), id) > 0) {
            return@withContext WatchIngest.DUPLICATE
        }

        val no = if (isUpdate) (db.allDives().firstOrNull { it.file == id }?.no ?: 0) else db.maxDiveNo() + 1
        val dive = DiveIngest.toDive(parsed, id, no)
        val rawPath = saveRaw(id, bytes)
        db.insertAll(
            listOf(
                DiveRow(
                    dive = dive,
                    samples = DiveIngest.toSamples(parsed),
                    meta = DiveMeta(
                        id = id,
                        source = DiveSource.WATCH,
                        deviceId = deviceId,
                        watchIndex = watchIndex,
                        fingerprint = fingerprint,
                        rawPath = rawPath,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
            )
        )
        cached = null
        samples.invalidate()
        DiveIngest.recordGas(parsed)?.let { (o2, he) -> db.setRecordGas(id, o2, he) }
        if (isUpdate) WatchIngest.UPDATED else WatchIngest.ADDED
    }

    suspend fun markDeleted(ids: Collection<String>, deleted: Boolean) = withContext(Dispatchers.IO) {
        db.setUserDeleted(ids, deleted)
        cached = null
    }

    /** 收藏 / 取消收藏。只改标记，不动数据。 */
    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean) = withContext(Dispatchers.IO) {
        db.setFavorite(ids, favorite)
        cached = null
    }

    /**
     * 手动标注气体。传 null 清除标注（回到"按默认气体算"）。
     * 会同步更新采样点上的气体下标，剖面图的分段着色才跟得上。
     */
    suspend fun setGas(id: String, o2: Double?, he: Double? = null) = withContext(Dispatchers.IO) {
        db.setGas(id, o2, he)
        cached = null
    }

    /** 回收站：所有被软删除的记录 id（原始数据仍在库里）。 */
    suspend fun deletedIds(): Set<String> = withContext(Dispatchers.IO) { db.deletedIds() }

    suspend fun favoriteIds(): Set<String> = withContext(Dispatchers.IO) { db.favoriteIds() }

    suspend fun saveManualGroup(memberIds: List<String>, siS: Int? = null): String? =
        withContext(Dispatchers.IO) {
            if (memberIds.size < 2) null
            else {
                cached = null
                db.saveManualGroup(memberIds, siS)
            }
        }

    suspend fun removeManualGroup(groupId: String) = withContext(Dispatchers.IO) {
        db.removeManualGroup(groupId)
        cached = null
    }

    fun manualGroups(): List<List<String>> = db.manualGroups()

    /**
     * 把当前视图的合并/过滤结果写回标记位。
     * 幂等，可在数据或阈值变化后反复调用。
     */
    suspend fun materialize(entries: List<DiveEntry>, junkRule: JunkRule) = withContext(Dispatchers.IO) {
        val manualKeys = manualGroups().map { it.toSet() }.toSet()
        val groups = ArrayList<AutoGroup>()
        val filters = HashMap<String, FilterReason>()
        for (e in entries) {
            val members = e.records.map { it.file }
            if (members.size > 1 && members.toSet() !in manualKeys) {
                groups.add(AutoGroup("auto_${members.first()}", members))
            }
            if (junkRule.matches(e)) {
                for (m in members) filters[m] = FilterReason.JUNK
            }
        }
        db.materialize(groups, filters)
    }

    /** 清空非内置数据（文件导入 + 手表下载）。 */
    suspend fun clearImported() = withContext(Dispatchers.IO) {
        db.clearSource(DiveSource.FILE)
        db.clearSource(DiveSource.WATCH)
        cached = null
        samples.invalidate()
    }

    /** 清空某一来源（同步页的「清空本机手表数据」用）。 */
    suspend fun clearSource(source: DiveSource) = withContext(Dispatchers.IO) {
        db.clearSource(source)
        cached = null
        samples.invalidate()
    }

    fun watchProgress(deviceId: String): WatchProgress? = db.watchProgress(deviceId)

    fun upsertWatch(w: WatchRecord) = db.upsertWatch(w)

    fun watch(id: String): WatchRecord? = db.watch(id)

    fun logSync(run: SyncRunRecord) = db.logSync(run)

    fun lastSync(watchId: String): SyncRunRecord? = db.lastSync(watchId)

    /** 原始字节落盘，返回相对 filesDir 的路径（库里只存这个）。 */
    private fun saveRaw(id: String, bytes: ByteArray): String? = runCatching {
        if (!rawRoot.exists()) rawRoot.mkdirs()
        val f = File(rawRoot, "$id.bin")
        f.writeBytes(bytes)
        f.relativeTo(ctx.filesDir).path
    }.getOrNull()

    fun rawFile(id: String, rawPath: String?): File? {
        val p = rawPath ?: "raw/$id.bin"
        val f = File(ctx.filesDir, p)
        return if (f.exists()) f else null
    }
}

/** 单条手表记录的入库结果。 */
enum class WatchIngest { ADDED, UPDATED, SKIPPED, DUPLICATE, FAILED }

/** 统计聚合（纯函数，可单测）。 */
object Analytics {

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun startMillis(d: Dive): Long = runCatching {
        LocalDateTime.parse(d.datetime, FMT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** 无效标记：浅于 2m 或 短于 60s。 */
    fun invalidFlags(d: Dive): List<String> {
        val flags = ArrayList<String>(2)
        if (d.maxdepthM < DiveMerger.DEFAULT_MIN_DEPTH) flags.add("浅水")
        if (d.divetimeS < DiveMerger.MIN_DURATION_S) flags.add("短时")
        return flags
    }

    /**
     * 按水面间隔分组：SI <= 600s 视为同一次潜水（中途升水再下水）。
     * 与 analyze.py 的 group 规则一致；返回的分组按时间升序。
     */
    fun mergeGroups(dives: List<Dive>, siThreshold: Int = DiveMerger.DEFAULT_SI_THRESHOLD): List<List<Dive>> {
        if (dives.isEmpty()) return emptyList()
        val sorted = dives.sortedBy { startMillis(it) }
        val out = ArrayList<List<Dive>>()
        var cur = ArrayList<Dive>()
        var prevEnd = Long.MIN_VALUE
        for (d in sorted) {
            val st = startMillis(d)
            val end = st + d.divetimeS * 1000L
            if (cur.isNotEmpty()) {
                val si = (st - prevEnd) / 1000.0
                // 与 analyze.py 一致：si <= 600 都算同一次（含时间重叠的脏数据）
                if (si > siThreshold) {
                    out.add(cur)
                    cur = ArrayList()
                    prevEnd = Long.MIN_VALUE
                }
            }
            cur.add(d)
            if (end > prevEnd) prevEnd = end
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** 年度分布（升序）。 */
    fun byYear(dives: List<Dive>): List<Pair<Int, Int>> = byYearOf(dives.map { it.year })

    fun byYearOf(years: List<Int>): List<Pair<Int, Int>> =
        years.filter { it > 0 }.groupingBy { it }.eachCount().toSortedMap().map { it.key to it.value }

    /** 深度直方图。 */
    fun depthHistogram(dives: List<Dive>): List<Pair<String, Int>> = depthHistogramOf(dives.map { it.maxdepthM })

    fun depthHistogramOf(values: List<Double>): List<Pair<String, Int>> {
        val buckets = listOf(0, 5, 10, 15, 20, 25, 30, Int.MAX_VALUE)
        val counts = IntArray(buckets.size - 1)
        for (v in values) {
            for (i in 0 until buckets.size - 1) {
                if (v >= buckets[i] && v < buckets[i + 1]) { counts[i]++; break }
            }
        }
        return buckets.dropLast(1).mapIndexed { i, lo ->
            val hi = buckets[i + 1]
            val label = if (hi == Int.MAX_VALUE) "$lo+m" else "$lo-${hi}m"
            label to counts[i]
        }
    }

    /** 时长直方图（分钟）。 */
    fun durationHistogram(dives: List<Dive>): List<Pair<String, Int>> =
        durationHistogramOf(dives.map { it.divetimeS })

    fun durationHistogramOf(seconds: List<Int>): List<Pair<String, Int>> {
        val edges = listOf(0, 10, 20, 30, 45, 60, 90, Int.MAX_VALUE)   // 分钟
        val counts = IntArray(edges.size - 1)
        for (s in seconds) {
            val m = s / 60
            for (i in 0 until edges.size - 1) {
                if (m >= edges[i] && m < edges[i + 1]) { counts[i]++; break }
            }
        }
        return edges.dropLast(1).mapIndexed { i, lo ->
            val hi = edges[i + 1]
            if (hi == Int.MAX_VALUE) "$lo+m" else "$lo-${hi}m"
        }.mapIndexed { i, label -> label to counts[i] }
    }

    /** 指定年份的每月潜次（12 个）。 */
    fun monthly(dives: List<Dive>, year: Int): IntArray =
        monthlyOf(dives.map { it.year to it.month }, year)

    fun monthlyOf(pairs: List<Pair<Int, Int>>, year: Int): IntArray {
        val arr = IntArray(12)
        for ((y, m) in pairs) if (y == year && m in 1..12) arr[m - 1]++
        return arr
    }
}

/** 数值/时间格式化（避免 Locale 差异，手工拼）。 */
object Fmt {
    fun one(v: Double): String {
        val r = Math.round(v * 10.0) / 10.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    /** 3673 -> "61:13"（总分钟数，潜水日志惯例） */
    fun duration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    /** 坐标轴时间标签：秒 -> "12'" */
    fun axisMinutes(sec: Int): String = "${sec / 60}'"

    /** 数值 -> 输入框初始文本（整数不带小数点）。 */
    fun input(v: Double): String {
        val r = Math.round(v).toDouble()
        return if (abs(v - r) < 1e-9) r.toInt().toString() else v.toString()
    }
}
