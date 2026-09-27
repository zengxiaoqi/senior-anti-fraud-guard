package com.antifraud.guard.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
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
