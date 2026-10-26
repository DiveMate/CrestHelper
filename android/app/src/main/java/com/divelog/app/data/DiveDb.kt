package com.divelog.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.divelog.app.Dive
import com.divelog.app.Sample

/**
 * 本地库（SQLite）。**所有原始记录 + 元数据都落在这里**，CSV 只作为首次启动的种子。
 *
 * 为什么从「CSV 追加」换成数据库：
 *  - 一条记录要同时保存「原始汇总 + 采样点 + 来源 + 手表编号/指纹 + 合并归属 + 过滤标记」，
 *    CSV 表达不了这种一对多 + 多标记的结构；
 *  - 增量同步需要按 (device_id, watch_index) 查「本地已有哪几条」，CSV 只能全表扫；
 *  - 电脑表的量级是几千条（几千 × 数百采样点 ≈ 百万行），SQLite 完全撑得住，
 *    而且原始字节仍然单独落盘，随时能重放。
 *
 * 表：
 *  dive       一条原始记录一行（含全部元数据标记）
 *  sample     采样点（不带合并后的剖面；合并是视图层算的）
 *  dive_group 手动合并组的元信息（组成员关系存在 dive.group_id 上）
 *  watch      手表档案（型号/固件/序列号/上次同步进度）
 *  sync_run   每次同步的审计日志
 */
class DiveDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dive (
                id            TEXT PRIMARY KEY,
                source        TEXT NOT NULL,
                device_id     TEXT,
                watch_index   INTEGER,
                fingerprint   TEXT,
                dive_no       INTEGER NOT NULL DEFAULT 0,
                datetime      TEXT NOT NULL,
                divetime_s    INTEGER NOT NULL,
                maxdepth_m    REAL NOT NULL,
                temp_min_c    REAL,
                nsamples      INTEGER NOT NULL DEFAULT 0,
                bytes         INTEGER NOT NULL DEFAULT 0,
                raw_path      TEXT,
                imported_at   INTEGER NOT NULL DEFAULT 0,
                group_id      TEXT,
                merge_kind    TEXT NOT NULL DEFAULT 'none',
                filter_reason TEXT,
                user_deleted  INTEGER NOT NULL DEFAULT 0,
                favorite      INTEGER NOT NULL DEFAULT 0,
                gas_o2        REAL,
                gas_he        REAL,
                gas_src       TEXT,
                -- v3：摘要列。入库时按采样点算好，列表只读这些列，不必回扫 sample 表
                avg_depth_m   REAL NOT NULL DEFAULT 0,
                temp_max_c    REAL,
                gas_count     INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_dive_time  ON dive(datetime)")
        db.execSQL("CREATE INDEX idx_dive_group ON dive(group_id)")
        db.execSQL("CREATE INDEX idx_dive_src   ON dive(source)")
        // 同一块表同一条日志只应存在一份；部分索引跳过没有手表编号的记录
        db.execSQL(
            "CREATE UNIQUE INDEX idx_dive_watch ON dive(device_id, watch_index) " +
                "WHERE device_id IS NOT NULL AND watch_index IS NOT NULL"
        )

        db.execSQL(
            """
            CREATE TABLE sample (
                dive_id TEXT NOT NULL,
                t       INTEGER NOT NULL,
                depth   REAL NOT NULL,
                temp    REAL,
                PRIMARY KEY (dive_id, t)
            ) WITHOUT ROWID
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE dive_group (
                id         TEXT PRIMARY KEY,
                kind       TEXT NOT NULL,
                si_s       INTEGER,
                created_at INTEGER NOT NULL DEFAULT 0,
                note       TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE watch (
                id           TEXT PRIMARY KEY,
                name         TEXT,
                serial       TEXT,
                firmware     TEXT,
                hardware     TEXT,
                last_index   INTEGER,
                ndives       INTEGER,
                last_sync_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE sync_run (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                watch_id    TEXT NOT NULL,
                mode        TEXT NOT NULL,
                started_at  INTEGER NOT NULL,
                finished_at INTEGER,
                added       INTEGER NOT NULL DEFAULT 0,
                updated     INTEGER NOT NULL DEFAULT 0,
                skipped     INTEGER NOT NULL DEFAULT 0,
                failed      INTEGER NOT NULL DEFAULT 0,
                note        TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2：加收藏、气体三列。**不重建表、不删数据**，只 ALTER 补列。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE dive ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE dive ADD COLUMN gas_o2 REAL")
            db.execSQL("ALTER TABLE dive ADD COLUMN gas_he REAL")
            db.execSQL("ALTER TABLE dive ADD COLUMN gas_src TEXT")
        }
        // v2 -> v3：摘要列（平均深度 / 最高水温 / 气体种类数）。
        // 老行的 avg_depth_m 留 0 表示"没算过"，由 backfillSummaries() 按采样点补一遍。
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE dive ADD COLUMN avg_depth_m REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE dive ADD COLUMN temp_max_c REAL")
            db.execSQL("ALTER TABLE dive ADD COLUMN gas_count INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ------------------------------------------------------------------ 写入

    fun isEmpty(): Boolean =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM dive", null).use { c ->
            c.moveToFirst() && c.getInt(0) == 0
        }

    fun count(filter: String = "1=1", args: Array<String> = emptyArray()): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM dive WHERE $filter", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** 批量插入（单事务）。同 id 覆盖，采样点先删后插，保证可重复执行。 */
    fun insertAll(rows: List<DiveRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val dSql = db.compileStatement(
                """
                INSERT OR REPLACE INTO dive
                (id, source, device_id, watch_index, fingerprint, dive_no, datetime, divetime_s,
                 maxdepth_m, temp_min_c, nsamples, bytes, raw_path, imported_at,
                 group_id, merge_kind, filter_reason, user_deleted, favorite,
                 gas_o2, gas_he, gas_src, avg_depth_m, temp_max_c, gas_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val sSql = db.compileStatement(
                "INSERT OR REPLACE INTO sample (dive_id, t, depth, temp) VALUES (?,?,?,?)"
            )
            val delS = db.compileStatement("DELETE FROM sample WHERE dive_id = ?")
            /* 重同步同一条记录时，用户手动打的标记不能被新解析结果抹掉：
             * 收藏、手动标注的气体、软删除、手动合并组都属于「用户意图」，
             * 覆盖前先把旧值读出来。漏了这一步，重连一次手表收藏就全没了。 */
            for (r in rows) {
                val d = r.dive
                var m = r.meta
                val old = oldMarkers(db, d.file)
                if (old != null) {
                    /* 气体：用户标注必须压过记录自带的值。
                     * 判据是"旧值是不是用户标的"，而不是"新值是不是空的" ——
                     * 手表重下这条记录时新值里一定带着 RECORD 气体，
                     * 只看非空会被它盖掉。 */
                    val userGasWins = old.gasSource == GasSource.USER
                    m = m.copy(
                        favorite = m.favorite || old.favorite,
                        gasO2 = if (userGasWins) old.gasO2 else (m.gasO2 ?: old.gasO2),
                        gasHe = if (userGasWins) old.gasHe else (m.gasHe ?: old.gasHe),
                        gasSource = if (userGasWins) GasSource.USER else (m.gasSource ?: old.gasSource),
                        userDeleted = m.userDeleted || old.userDeleted,
                        groupId = m.groupId ?: old.groupId,
                        mergeKind = if (m.mergeKind != MergeKind.NONE) m.mergeKind else old.mergeKind,
                    )
                }
                dSql.clearBindings()
                dSql.bindString(1, d.file)
                dSql.bindString(2, m.source.code)
                bindNullableText(dSql, 3, m.deviceId)
                bindNullableInt(dSql, 4, m.watchIndex)
                bindNullableText(dSql, 5, m.fingerprint)
                dSql.bindLong(6, d.no.toLong())
                dSql.bindString(7, d.datetime)
                dSql.bindLong(8, d.divetimeS.toLong())
                dSql.bindDouble(9, d.maxdepthM)
                dSql.bindDouble(10, d.tempMinC)
                dSql.bindLong(11, d.nsamples.toLong())
                dSql.bindLong(12, d.bytes)
                bindNullableText(dSql, 13, m.rawPath)
                dSql.bindLong(14, m.importedAt)
                bindNullableText(dSql, 15, m.groupId)
                dSql.bindString(16, m.mergeKind.code)
                bindNullableText(dSql, 17, m.filterReason?.code)
                dSql.bindLong(18, if (m.userDeleted) 1 else 0)
                dSql.bindLong(19, if (m.favorite) 1 else 0)
                bindNullableDouble(dSql, 20, m.gasO2)
                bindNullableDouble(dSql, 21, m.gasHe)
                bindNullableText(dSql, 22, m.gasSource?.code)
                dSql.bindDouble(23, d.avgDepthM)
                bindNullableDouble(dSql, 24, d.tempMaxC)
                dSql.bindLong(25, d.gasCount.toLong())
                dSql.executeInsert()

                delS.bindString(1, d.file)
                delS.executeUpdateDelete()
                for (s in r.samples) {
                    sSql.clearBindings()
                    sSql.bindString(1, d.file)
                    sSql.bindLong(2, s.t.toLong())
                    sSql.bindDouble(3, s.depth)
                    if (s.temp == null) sSql.bindNull(4) else sSql.bindDouble(4, s.temp)
                    sSql.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 软删除 / 恢复。 */
    fun setUserDeleted(ids: Collection<String>, deleted: Boolean) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE dive SET user_deleted = ? WHERE id = ?")
            for (id in ids) {
                st.clearBindings()
                st.bindLong(1, if (deleted) 1 else 0)
                st.bindString(2, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 收藏 / 取消收藏（纯标记）。 */
    fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE dive SET favorite = ? WHERE id = ?")
            for (id in ids) {
                st.clearBindings()
                st.bindLong(1, if (favorite) 1 else 0)
                st.bindString(2, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 手动标注一条记录的气体（FO2/He，传 null 表示清除标注）。
     * 只改标记，不动原始字节，所以随时可改回。
     */
    fun setGas(id: String, o2: Double?, he: Double? = null) {
        if (o2 == null) {
            writableDatabase.execSQL(
                "UPDATE dive SET gas_o2 = NULL, gas_he = NULL, gas_src = NULL WHERE id = ?",
                arrayOf<Any?>(id)
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE dive SET gas_o2 = ?, gas_he = ?, gas_src = ? WHERE id = ?",
                arrayOf<Any?>(o2, he ?: 0.0, GasSource.USER.code, id)
            )
        }
    }

    /** 记录里自带的气体（只写 record 来源，不覆盖用户标注）。 */
    fun setRecordGas(id: String, o2: Double, he: Double) {
        writableDatabase.execSQL(
            "UPDATE dive SET gas_o2 = ?, gas_he = ?, gas_src = ? " +
                "WHERE id = ? AND (gas_src IS NULL OR gas_src = ?)",
            arrayOf<Any?>(o2, he, GasSource.RECORD.code, id, GasSource.RECORD.code)
        )
    }


    /**
     * 把手动合并组写进库里（权威数据）。
     * 每次覆盖该组的成员，返回组 id；已有同成员集合的组会被复用。
     */
    fun saveManualGroup(memberIds: List<String>, siS: Int? = null, now: Long = System.currentTimeMillis()): String {
        val db = writableDatabase
        val key = memberIds.sorted().joinToString(",")
        val existing = manualGroups().firstOrNull { it.sorted().joinToString(",") == key }?.let { groupIdOf(it) }
        val id = existing ?: "manual_${now}_${memberIds.size}"
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO dive_group (id, kind, si_s, created_at) VALUES (?,?,?,?)",
                arrayOf<Any?>(id, MergeKind.MANUAL.code, siS, now)
            )
            val st = db.compileStatement("UPDATE dive SET group_id = ?, merge_kind = ? WHERE id = ?")
            for (m in memberIds) {
                st.clearBindings()
                st.bindString(1, id)
                st.bindString(2, MergeKind.MANUAL.code)
                st.bindString(3, m)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    /** 拆掉一个手动组（成员回到独立状态）。 */
    fun removeManualGroup(groupId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE dive SET group_id = NULL, merge_kind = ? WHERE group_id = ? AND merge_kind = ?",
                arrayOf<Any?>(MergeKind.NONE.code, groupId, MergeKind.MANUAL.code)
            )
            db.execSQL("DELETE FROM dive_group WHERE id = ?", arrayOf<Any?>(groupId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 清空所有**自动**合并标记（手动组不动），供物化前重置。 */
    fun clearAutoGroups() {
        writableDatabase.execSQL(
            "UPDATE dive SET group_id = NULL, merge_kind = ? WHERE merge_kind = ?",
            arrayOf<Any?>(MergeKind.NONE.code, MergeKind.AUTO.code)
        )
    }

    /**
     * 物化当前视图：把「自动合并归属」和「垃圾判定」写回标记位。
     *
     * 这不是数据副本，只是让后台能回答「这条为什么合并/为什么被过滤」。
     * 每次阈值或数据变化后重算一次即可，几百条 UPDATE 走一个事务。
     */
    fun materialize(groups: List<AutoGroup>, filters: Map<String, FilterReason>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE dive SET group_id = NULL, merge_kind = ? WHERE merge_kind = ?",
                arrayOf<Any?>(MergeKind.NONE.code, MergeKind.AUTO.code)
            )
            db.execSQL("UPDATE dive SET filter_reason = NULL WHERE filter_reason IS NOT NULL")

            val gst = db.compileStatement(
                "UPDATE dive SET group_id = ?, merge_kind = ? WHERE id = ?"
            )
            for (g in groups) {
                val id = g.groupId
                for (m in g.memberIds) {
                    gst.clearBindings()
                    gst.bindString(1, id)
                    gst.bindString(2, MergeKind.AUTO.code)
                    gst.bindString(3, m)
                    gst.executeUpdateDelete()
                }
            }
            val fst = db.compileStatement("UPDATE dive SET filter_reason = ? WHERE id = ?")
            for ((id, reason) in filters) {
                fst.clearBindings()
                fst.bindString(1, reason.code)
                fst.bindString(2, id)
                fst.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearSource(source: DiveSource) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM sample WHERE dive_id IN (SELECT id FROM dive WHERE source = ?)",
                arrayOf<Any?>(source.code)
            )
            db.execSQL("DELETE FROM dive WHERE source = ?", arrayOf<Any?>(source.code))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------ 读取

    fun allDives(): List<Dive> {
        val out = ArrayList<Dive>(512)
        readableDatabase.rawQuery(
            "SELECT id, dive_no, datetime, divetime_s, maxdepth_m, temp_min_c, nsamples, bytes, " +
                "avg_depth_m, temp_max_c, gas_count FROM dive ORDER BY datetime DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Dive(
                        file = c.getString(0),
                        no = c.getInt(1),
                        datetime = c.getString(2),
                        divetimeS = c.getInt(3),
                        maxdepthM = c.getDouble(4),
                        tempMinC = if (c.isNull(5)) 0.0 else c.getDouble(5),
                        nsamples = c.getInt(6),
                        bytes = c.getLong(7),
                        avgDepthM = if (c.isNull(8)) 0.0 else c.getDouble(8),
                        tempMaxC = if (c.isNull(9)) null else c.getDouble(9),
                        gasCount = if (c.isNull(10)) 0 else c.getInt(10),
                    )
                )
            }
        }
        return out
    }

    fun allSamples(): Map<String, List<Sample>> {
        val map = HashMap<String, MutableList<Sample>>(512)
        readableDatabase.rawQuery(
            "SELECT dive_id, t, depth, temp FROM sample ORDER BY dive_id, t",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val temp = if (c.isNull(3)) null else c.getDouble(3)
                map.getOrPut(id) { ArrayList() }.add(Sample(c.getInt(1), c.getDouble(2), temp))
            }
        }
        return map
    }

    /** 采样点总数：全量载入前就能拿到分母，用来显示进度。 */
    fun sampleCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sample", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * 有剖面的记录 id（按时间倒序，最常看的排在前面）。
     *
     * 载入顺序不是随便定的：列表是倒序的，用户点开头几条的概率远大于翻到 2019 年，
     * 所以先读新的——第 40 条就读到他想看的那条，不必等全量。
     */
    fun diveIdsWithSamples(): List<String> =
        readableDatabase.rawQuery(
            "SELECT d.id FROM dive d WHERE EXISTS " +
                "(SELECT 1 FROM sample s WHERE s.dive_id = d.id) ORDER BY d.datetime DESC",
            null
        ).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    /**
     * 只取指定几条记录的剖面（详情页兜底）。
     *
     * 单条查询比全量读快一个量级：全量 6 万行要几百毫秒，
     * 这里一条记录通常 150~400 行，几毫秒就回来了。
     */
    fun samplesOf(ids: Collection<String>): Map<String, List<Sample>> {
        if (ids.isEmpty()) return emptyMap()
        val map = HashMap<String, MutableList<Sample>>(ids.size * 2)
        val hold = ids.toList()
        // SQLite 的变量上限是 999，分批查
        for (chunk in hold.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT dive_id, t, depth, temp FROM sample WHERE dive_id IN ($marks) ORDER BY dive_id, t",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val temp = if (c.isNull(3)) null else c.getDouble(3)
                    map.getOrPut(id) { ArrayList() }.add(Sample(c.getInt(1), c.getDouble(2), temp))
                }
            }
        }
        return map
    }

    /** 有采样点但摘要没算过（v2 老数据）=> 需要回填。 */
    fun needsSummaryBackfill(): Boolean =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM dive WHERE avg_depth_m = 0 AND nsamples > 0 LIMIT 1",
            null
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }

    /**
     * 按采样点补算摘要（一次性迁移用）。
     *
     * 只 UPDATE 摘要三列，不碰任何标记位，也不动采样点本身。
     * gas_count 依赖采样点里的气体下标，而 sample 表不存该列，
     * 所以老数据这里只能留 0（本来 CR-4 v0 格式也没有气体）。
     */
    fun backfillSummaries(): Int {
        val avg = HashMap<String, Double>(512)
        val tmax = HashMap<String, Double>(512)
        var curId: String? = null
        var prevT = 0
        var prevD = 0.0
        var area = 0.0
        var time = 0.0

        fun flush() {
            val id = curId ?: return
            if (time > 0) avg[id] = area / time
            curId = null
        }

        readableDatabase.rawQuery(
            "SELECT dive_id, t, depth, temp FROM sample ORDER BY dive_id, t",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val t = c.getInt(1)
                val d = c.getDouble(2)
                if (id != curId) {
                    flush()
                    curId = id
                    area = 0.0
                    time = 0.0
                    prevT = t
                    prevD = d
                } else {
                    val dt = (t - prevT).toDouble()
                    if (dt > 0) {
                        area += (d + prevD) / 2.0 * dt
                        time += dt
                    }
                    prevT = t
                    prevD = d
                }
                if (!c.isNull(3)) {
                    val v = c.getDouble(3)
                    val old = tmax[id]
                    if (old == null || v > old) tmax[id] = v
                }
            }
            flush()
        }

        if (avg.isEmpty() && tmax.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "UPDATE dive SET avg_depth_m = ?, temp_max_c = ? WHERE id = ?"
            )
            for (id in (avg.keys + tmax.keys)) {
                st.clearBindings()
                st.bindDouble(1, avg[id] ?: 0.0)
                val t = tmax[id]
                if (t == null) st.bindNull(2) else st.bindDouble(2, t)
                st.bindString(3, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return avg.size
    }

    fun metaMap(): Map<String, DiveMeta> {
        val out = HashMap<String, DiveMeta>(512)
        readableDatabase.rawQuery(
            "SELECT id, source, device_id, watch_index, fingerprint, group_id, merge_kind, " +
                "filter_reason, user_deleted, raw_path, imported_at, favorite, gas_o2, gas_he, gas_src FROM dive",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                out[id] = DiveMeta(
                    id = id,
                    source = DiveSource.of(c.getString(1)),
                    deviceId = if (c.isNull(2)) null else c.getString(2),
                    watchIndex = if (c.isNull(3)) null else c.getInt(3),
                    fingerprint = if (c.isNull(4)) null else c.getString(4),
                    groupId = if (c.isNull(5)) null else c.getString(5),
                    mergeKind = MergeKind.of(c.getString(6)),
                    filterReason = FilterReason.of(if (c.isNull(7)) null else c.getString(7)),
                    userDeleted = c.getInt(8) != 0,
                    rawPath = if (c.isNull(9)) null else c.getString(9),
                    importedAt = c.getLong(10),
                    favorite = c.getInt(11) != 0,
                    gasO2 = if (c.isNull(12)) null else c.getDouble(12),
                    gasHe = if (c.isNull(13)) null else c.getDouble(13),
                    gasSource = GasSource.of(if (c.isNull(14)) null else c.getString(14)),
                )
            }
        }
        return out
    }

    /** 已软删除的记录 id（回收站）。 */
    fun deletedIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT id FROM dive WHERE user_deleted = 1", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun favoriteIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT id FROM dive WHERE favorite = 1", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** 覆盖前要保留的用户标记。 */
    private fun oldMarkers(db: SQLiteDatabase, id: String): DiveMeta? =
        db.rawQuery(
            "SELECT favorite, gas_o2, gas_he, gas_src, user_deleted, group_id, merge_kind FROM dive WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return null
            DiveMeta(
                id = id,
                source = DiveSource.BUILTIN,
                favorite = c.getInt(0) != 0,
                gasO2 = if (c.isNull(1)) null else c.getDouble(1),
                gasHe = if (c.isNull(2)) null else c.getDouble(2),
                gasSource = GasSource.of(if (c.isNull(3)) null else c.getString(3)),
                userDeleted = c.getInt(4) != 0,
                groupId = if (c.isNull(5)) null else c.getString(5),
                mergeKind = MergeKind.of(if (c.isNull(6)) null else c.getString(6)),
            )
        }

    fun maxDiveNo(): Int =
        readableDatabase.rawQuery("SELECT MAX(dive_no) FROM dive", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }

    /** 内容相同（时间+时长+原始字节数）的记录条数，排除自己那条。 */
    fun countBySignature(datetime: String, divetimeS: Int, bytes: Long, excludeId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM dive WHERE datetime = ? AND divetime_s = ? AND bytes = ? AND id <> ?",
            arrayOf(datetime, divetimeS.toString(), bytes.toString(), excludeId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 去重签名 -> 已存在。 */
    fun signatures(): MutableSet<String> {
        val out = HashSet<String>(512)
        readableDatabase.rawQuery(
            "SELECT datetime, divetime_s, bytes FROM dive", null
        ).use { c ->
            while (c.moveToNext()) out.add("${c.getString(0)}|${c.getLong(1)}|${c.getLong(2)}")
        }
        return out
    }

    fun ids(): Set<String> {
        val out = HashSet<String>(512)
        readableDatabase.rawQuery("SELECT id FROM dive", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** 当前手动合并组（成员 id 列表）。 */
    fun manualGroups(): List<List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT group_id, id FROM dive WHERE merge_kind = ? AND group_id IS NOT NULL " +
                "ORDER BY datetime",
            arrayOf(MergeKind.MANUAL.code)
        ).use { c ->
            while (c.moveToNext()) map.getOrPut(c.getString(0)) { ArrayList() }.add(c.getString(1))
        }
        return map.values.filter { it.size > 1 }.map { it.toList() }
    }

    fun groupIdOf(memberIds: List<String>): String? {
        if (memberIds.isEmpty()) return null
        readableDatabase.rawQuery(
            "SELECT group_id FROM dive WHERE id = ? AND group_id IS NOT NULL",
            arrayOf(memberIds.first())
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** 某块手表的已同步进度：最大编号 + 该编号的指纹（增量锚点）。 */
    fun watchProgress(deviceId: String): WatchProgress? {
        readableDatabase.rawQuery(
            "SELECT watch_index, fingerprint FROM dive WHERE device_id = ? AND watch_index IS NOT NULL " +
                "ORDER BY watch_index DESC LIMIT 1",
            arrayOf(deviceId)
        ).use { c ->
            if (c.moveToFirst()) {
                return WatchProgress(
                    maxIndex = c.getInt(0),
                    fingerprint = if (c.isNull(1)) null else c.getString(1),
                    count = count("device_id = ? AND watch_index IS NOT NULL", arrayOf(deviceId)),
                )
            }
        }
        return null
    }

    fun fingerprintsOf(deviceId: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        readableDatabase.rawQuery(
            "SELECT watch_index, fingerprint FROM dive " +
                "WHERE device_id = ? AND fingerprint IS NOT NULL",
            arrayOf(deviceId)
        ).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = c.getString(1)
        }
        return out
    }

    // ------------------------------------------------------------------ 手表档案

    fun upsertWatch(w: WatchRecord) {
        // 用 INSERT OR REPLACE 而不是 UPSERT：旧版 SQLite（Robolectric 的 legacy 实现、
        // 老 Android 上的基线版本）不支持 ON CONFLICT DO UPDATE，replace 语义在这里完全够用。
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO watch " +
                "(id, name, serial, firmware, hardware, last_index, ndives, last_sync_at) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                w.id, w.name, w.serial, w.firmware, w.hardware,
                w.lastIndex, w.ndives, w.lastSyncAt,
            )
        )
    }

    fun watch(id: String): WatchRecord? =
        readableDatabase.rawQuery(
            "SELECT id, name, serial, firmware, hardware, last_index, ndives, last_sync_at " +
                "FROM watch WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return null
            WatchRecord(
                id = c.getString(0),
                name = c.getString(1),
                serial = c.getString(2),
                firmware = c.getString(3),
                hardware = c.getString(4),
                lastIndex = if (c.isNull(5)) null else c.getInt(5),
                ndives = if (c.isNull(6)) null else c.getInt(6),
                lastSyncAt = if (c.isNull(7)) 0L else c.getLong(7),
            )
        }

    fun logSync(run: SyncRunRecord) {
        writableDatabase.execSQL(
            "INSERT INTO sync_run (watch_id, mode, started_at, finished_at, added, updated, " +
                "skipped, failed, note) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                run.watchId, run.mode, run.startedAt, run.finishedAt,
                run.added, run.updated, run.skipped, run.failed, run.note,
            )
        )
    }

    fun lastSync(watchId: String): SyncRunRecord? =
        readableDatabase.rawQuery(
            "SELECT watch_id, mode, started_at, finished_at, added, updated, skipped, failed, note " +
                "FROM sync_run WHERE watch_id = ? ORDER BY id DESC LIMIT 1",
            arrayOf(watchId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            SyncRunRecord(
                watchId = c.getString(0),
                mode = c.getString(1),
                startedAt = c.getLong(2),
                finishedAt = if (c.isNull(3)) 0L else c.getLong(3),
                added = c.getInt(4),
                updated = c.getInt(5),
                skipped = c.getInt(6),
                failed = c.getInt(7),
                note = if (c.isNull(8)) null else c.getString(8),
            )
        }

    // ------------------------------------------------------------------ 内部工具

    private fun bindNullableText(st: android.database.sqlite.SQLiteStatement, i: Int, v: String?) =
        if (v == null) st.bindNull(i) else st.bindString(i, v)

    private fun bindNullableInt(st: android.database.sqlite.SQLiteStatement, i: Int, v: Int?) =
        if (v == null) st.bindNull(i) else st.bindLong(i, v.toLong())

    private fun bindNullableDouble(st: android.database.sqlite.SQLiteStatement, i: Int, v: Double?) =
        if (v == null) st.bindNull(i) else st.bindDouble(i, v)

    companion object {
        const val DB_NAME = "divelog.db"
        const val DB_VERSION = 3
    }
}

/** 一次插入的完整行：汇总 + 采样点 + 元数据。 */
data class DiveRow(
    val dive: Dive,
    val samples: List<Sample>,
    val meta: DiveMeta,
)

/** 自动合并组的物化标记。 */
data class AutoGroup(
    val groupId: String,
    val memberIds: List<String>,
)

/** 本地已同步进度。 */
data class WatchProgress(
    val maxIndex: Int,
    val fingerprint: String?,
    val count: Int,
)

data class WatchRecord(
    val id: String,
    val name: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val lastIndex: Int? = null,
    val ndives: Int? = null,
    val lastSyncAt: Long = 0L,
)

data class SyncRunRecord(
    val watchId: String,
    val mode: String,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val note: String? = null,
)
