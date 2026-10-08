package com.antifraud.guard.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

class RiskEventDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "risk_events.db"
        const val DATABASE_VERSION = 1
        const val TABLE_EVENTS = "risk_events"

        const val COL_ID = "id"
        const val COL_ELDER_ID = "elder_id"
        const val COL_EVENT_TYPE = "event_type"
        const val COL_SEVERITY = "severity"
        const val COL_DETAILS = "details"
        const val COL_TIMESTAMP = "timestamp"

        private const val TAG = "RiskEventDb"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ELDER_ID INTEGER NOT NULL,
                $COL_EVENT_TYPE TEXT NOT NULL,
                $COL_SEVERITY TEXT NOT NULL,
                $COL_DETAILS TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())
    }

    /**
     * 增量迁移，**不再 DROP TABLE**。
     *
     * 原本这里是 `DROP TABLE` + 重建。风险事件是出事后的关键线索：
     * 用户升级 App 那一刻恰好发生过一次高危上报，这条记录就没了，
     * 而子女端看到的是"什么都没有发生过"——比误报更危险。
     *
     * 以后升版本时按 oldVersion 分段追加 SqliteMigrations.addColumnIfAbsent 即可。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "风险事件库升级 $oldVersion -> $newVersion（保留历史事件）")
        // 按时间查询是子女端的主要用法，补一个时间索引
        SqliteMigrations.createIndexIfAbsent(
            db, "idx_events_elder_time",
            "CREATE INDEX idx_events_elder_time ON $TABLE_EVENTS($COL_ELDER_ID, $COL_TIMESTAMP DESC)"
        )
        // —— 以后新增列写在这里，按 oldVersion 递增分段 ——
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "检测到版本降级 $oldVersion -> $newVersion，保留历史事件")
    }

    fun insertEvent(elderId: Int, eventType: String, severity: String, details: JSONObject?): Long {
        val values = ContentValues().apply {
            put(COL_ELDER_ID, elderId)
            put(COL_EVENT_TYPE, eventType)
            put(COL_SEVERITY, severity)
            put(COL_DETAILS, details?.toString() ?: "")
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_EVENTS, null, values)
    }

    fun getEvents(limit: Int = 100): List<RiskEvent> {
        val events = mutableListOf<RiskEvent>()
        val cursor = readableDatabase.query(
            TABLE_EVENTS, null, null, null, null, null,
            "$COL_TIMESTAMP DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                events.add(RiskEvent(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    elderId = it.getInt(it.getColumnIndexOrThrow(COL_ELDER_ID)),
                    eventType = it.getString(it.getColumnIndexOrThrow(COL_EVENT_TYPE)),
                    severity = it.getString(it.getColumnIndexOrThrow(COL_SEVERITY)),
                    details = it.getString(it.getColumnIndexOrThrow(COL_DETAILS)),
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                ))
            }
        }
        return events
    }

    fun getEventsByElder(elderId: Int, limit: Int = 100): List<RiskEvent> {
        val events = mutableListOf<RiskEvent>()
        val cursor = readableDatabase.query(
            TABLE_EVENTS, null, "$COL_ELDER_ID = ?", arrayOf(elderId.toString()),
            null, null, "$COL_TIMESTAMP DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                events.add(RiskEvent(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    elderId = it.getInt(it.getColumnIndexOrThrow(COL_ELDER_ID)),
                    eventType = it.getString(it.getColumnIndexOrThrow(COL_EVENT_TYPE)),
                    severity = it.getString(it.getColumnIndexOrThrow(COL_SEVERITY)),
                    details = it.getString(it.getColumnIndexOrThrow(COL_DETAILS)),
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                ))
            }
        }
        return events
    }

    fun clearAllEvents(): Int {
        return writableDatabase.delete(TABLE_EVENTS, null, null)
    }

    fun getEventCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}

data class RiskEvent(
    val id: Long,
    val elderId: Int,
    val eventType: String,
    val severity: String,
    val details: String,
    val timestamp: Long
)
