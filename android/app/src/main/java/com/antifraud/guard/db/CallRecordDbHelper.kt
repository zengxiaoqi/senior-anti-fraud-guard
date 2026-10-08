package com.antifraud.guard.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 通话记录本地落库（Phase 1 · 任务 1-3）。
 *
 * 每通电话（摘机→挂断）记一行。两个消费方：
 *  1. 频次判定（1-5）：2 小时内同一陌生号码来电 ≥3 次 → 中危告警
 *  2. 事后追溯：子女端问"最近有没有可疑来电"时，云端 risk_events 只有
 *     触发过告警的通话，本地表才有全量（含未触发任何规则的正常通话）
 *
 * 隐私边界：号码只存本机（app 私有目录），上报到服务端的只有
 * risk_events 事件详情，不在云端建通话明细表。
 */
class CallRecordDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "call_records.db"
        const val DATABASE_VERSION = 1
        const val TABLE_CALLS = "call_records"

        const val COL_ID = "id"
        const val COL_NUMBER = "number"
        const val COL_IS_STRANGER = "is_stranger"        // 1 陌生 / 0 认识 / NULL 判定不可用（三态）
        const val COL_START_AT = "start_at"              // 摘机时间（epoch ms）
        const val COL_END_AT = "end_at"
        const val COL_DURATION_SEC = "duration_sec"
        const val COL_HANGUP_BY = "hangup_by"            // 预留：谁挂断的（本版本无法可靠判定）
        const val COL_PEAK_RISK = "peak_risk"            // 本次通话最高风险：LOW / MEDIUM / HIGH
        const val COL_CREATED_AT = "created_at"

        private const val TAG = "CallRecordDb"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_CALLS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NUMBER TEXT,
                $COL_IS_STRANGER INTEGER,
                $COL_START_AT INTEGER NOT NULL,
                $COL_END_AT INTEGER NOT NULL,
                $COL_DURATION_SEC INTEGER NOT NULL,
                $COL_HANGUP_BY TEXT DEFAULT '',
                $COL_PEAK_RISK TEXT NOT NULL DEFAULT 'LOW',
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL(
            "CREATE INDEX idx_calls_number_time ON $TABLE_CALLS($COL_NUMBER, $COL_START_AT DESC)"
        )
    }

    /**
     * 增量迁移，**不再 DROP TABLE**（Phase 0 确立的铁律：通话记录是事后追溯
     * 的关键线索，升级丢数据 = 子女端看到"什么都没发生过"）。
     * 升版本时按 oldVersion 分段追加 SqliteMigrations.addColumnIfAbsent。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "通话记录库升级 $oldVersion -> $newVersion（保留历史记录）")
        // —— 以后新增列写在这里，按 oldVersion 递增分段 ——
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "检测到版本降级 $oldVersion -> $newVersion，保留历史记录")
    }

    /**
     * 落一行通话记录。
     * @param isStranger 三态：true 陌生 / false 认识 / null 判定不可用（权限被拒或无号码）
     * @return 行 id，失败返回 -1
     */
    fun insertCall(
        number: String?,
        isStranger: Boolean?,
        startAt: Long,
        endAt: Long,
        durationSec: Int,
        peakRisk: String,
        hangupBy: String = ""
    ): Long {
        val values = ContentValues().apply {
            if (number != null) put(COL_NUMBER, number) else putNull(COL_NUMBER)
            if (isStranger != null) put(COL_IS_STRANGER, if (isStranger) 1 else 0)
            else putNull(COL_IS_STRANGER)
            put(COL_START_AT, startAt)
            put(COL_END_AT, endAt)
            put(COL_DURATION_SEC, durationSec)
            put(COL_HANGUP_BY, hangupBy)
            put(COL_PEAK_RISK, peakRisk)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return try {
            writableDatabase.insert(TABLE_CALLS, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "通话记录落库失败: ${e.message}")
            -1
        }
    }

    /**
     * 统计某号码在 [sinceMs] 之后的通话次数（含刚落库的当前通话）。
     * 频次判定（1-5）专用；number 为空的通话不参与统计。
     */
    fun countRecentCallsByNumber(number: String, sinceMs: Long): Int {
        if (number.isBlank()) return 0
        return try {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_CALLS WHERE $COL_NUMBER = ? AND $COL_START_AT >= ?",
                arrayOf(number, sinceMs.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (e: Exception) {
            Log.e(TAG, "频次统计查询失败: ${e.message}")
            0
        }
    }

    /** 最近 N 条通话（时间倒序），供日后本机展示/调试用 */
    fun getRecentCalls(limit: Int = 50): List<CallRecord> {
        val out = mutableListOf<CallRecord>()
        return try {
            readableDatabase.query(
                TABLE_CALLS, null, null, null, null, null,
                "$COL_START_AT DESC", limit.toString()
            ).use { c ->
                val iNumber = c.getColumnIndexOrThrow(COL_NUMBER)
                val iStranger = c.getColumnIndexOrThrow(COL_IS_STRANGER)
                val iStart = c.getColumnIndexOrThrow(COL_START_AT)
                val iEnd = c.getColumnIndexOrThrow(COL_END_AT)
                val iDur = c.getColumnIndexOrThrow(COL_DURATION_SEC)
                val iPeak = c.getColumnIndexOrThrow(COL_PEAK_RISK)
                while (c.moveToNext()) {
                    out += CallRecord(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        number = if (c.isNull(iNumber)) null else c.getString(iNumber),
                        isStranger = if (c.isNull(iStranger)) null else c.getInt(iStranger) == 1,
                        startAt = c.getLong(iStart),
                        endAt = c.getLong(iEnd),
                        durationSec = c.getInt(iDur),
                        peakRisk = c.getString(iPeak)
                    )
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "通话记录查询失败: ${e.message}")
            out
        }
    }
}

data class CallRecord(
    val id: Long,
    val number: String?,
    val isStranger: Boolean?,   // null = 判定不可用
    val startAt: Long,
    val endAt: Long,
    val durationSec: Int,
    val peakRisk: String
)
