package com.antifraud.guard.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 录音待上传队列（本地持久化）。
 *
 * 为什么必须持久化而不是丢内存：
 * 老人端录音的场合恰恰是最需要它的时刻 —— 老人可能正在被骗现场，手机可能
 * 正好没网、没电、被系统回收。录音文件一旦录出来却没送出去，等子女发现时
 * 证据已经没了，报警也无从谈起。
 *
 * 所以规则是：先落本地队列，再异步上传；失败就留在队列里等重试；
 * 只有服务端明确返回成功才删除本地文件和队列记录。
 */
class UploadQueueDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "recording_upload.db"
        const val DATABASE_VERSION = 1
        const val TABLE_QUEUE = "upload_queue"

        const val COL_ID = "id"
        const val COL_ELDER_ID = "elder_id"
        const val COL_SESSION_ID = "session_id"
        const val COL_SEGMENT_INDEX = "segment_index"
        const val COL_REASON = "reason"
        const val COL_PLACE_NAME = "place_name"
        const val COL_FILE_PATH = "file_path"
        const val COL_FILE_NAME = "file_name"
        const val COL_DURATION_MS = "duration_ms"
        const val COL_RECORDED_AT = "recorded_at"
        const val COL_SHA256 = "sha256"
        const val COL_SIZE_BYTES = "size_bytes"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ADDRESS = "address"
        const val COL_RETRY_COUNT = "retry_count"
        const val COL_NEXT_RETRY_AT = "next_retry_at"
        const val COL_LAST_ERROR = "last_error"
        const val COL_CREATED_AT = "created_at"

        /** 状态：待上传 */
        const val STATUS_PENDING = "PENDING"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_QUEUE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ELDER_ID INTEGER NOT NULL,
                $COL_SESSION_ID TEXT NOT NULL,
                $COL_SEGMENT_INDEX INTEGER NOT NULL,
                $COL_REASON TEXT NOT NULL,
                $COL_PLACE_NAME TEXT,
                $COL_FILE_PATH TEXT NOT NULL,
                $COL_FILE_NAME TEXT,
                $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_RECORDED_AT INTEGER NOT NULL,
                $COL_SHA256 TEXT,
                $COL_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_LATITUDE REAL,
                $COL_LONGITUDE REAL,
                $COL_ADDRESS TEXT,
                $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_NEXT_RETRY_AT INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_ERROR TEXT,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_queue_retry ON $TABLE_QUEUE($COL_NEXT_RETRY_AT)")
        // 同一段录音（同一 session + 段序）只排一次，避免重复触发录制导致重复排队
        db.execSQL("CREATE UNIQUE INDEX idx_queue_unique ON $TABLE_QUEUE($COL_SESSION_ID, $COL_SEGMENT_INDEX)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 队列是可重建的临时数据，升级时直接清空，比迁移安全
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUEUE")
        onCreate(db)
    }

    /** 入队一个待上传录音段；已存在（同会话同段）则只更新元信息 */
    fun enqueue(item: UploadTask): Long {
        val values = ContentValues().apply {
            put(COL_ELDER_ID, item.elderId)
            put(COL_SESSION_ID, item.sessionId)
            put(COL_SEGMENT_INDEX, item.segmentIndex)
            put(COL_REASON, item.reason)
            put(COL_PLACE_NAME, item.placeName)
            put(COL_FILE_PATH, item.filePath)
            put(COL_FILE_NAME, item.fileName)
            put(COL_DURATION_MS, item.durationMs)
            put(COL_RECORDED_AT, item.recordedAt)
            put(COL_SHA256, item.sha256)
            put(COL_SIZE_BYTES, item.sizeBytes)
            put(COL_LATITUDE, item.latitude)
            put(COL_LONGITUDE, item.longitude)
            put(COL_ADDRESS, item.address)
            put(COL_RETRY_COUNT, 0)
            put(COL_NEXT_RETRY_AT, 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        // 冲突时更新（不覆盖重试计数，保留下传的优先权）
        val id = writableDatabase.insertWithOnConflict(
            TABLE_QUEUE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        return if (id > 0) id else getIdBySession(item.sessionId, item.segmentIndex)
    }

    private fun getIdBySession(sessionId: String, segmentIndex: Int): Long {
        readableDatabase.query(
            TABLE_QUEUE, arrayOf(COL_ID), "$COL_SESSION_ID = ? AND $COL_SEGMENT_INDEX = ?",
            arrayOf(sessionId, segmentIndex.toString()), null, null, null
        ).use {
            return if (it.moveToFirst()) it.getLong(0) else -1L
        }
    }

    /** 取出到期的待传任务（按录制时间从早到晚，保证证据顺序） */
    fun getDueTasks(now: Long, limit: Int = 10): List<UploadTask> {
        val tasks = mutableListOf<UploadTask>()
        readableDatabase.query(
            TABLE_QUEUE, null, "$COL_NEXT_RETRY_AT <= ?", arrayOf(now.toString()),
            null, null, "$COL_RECORDED_AT ASC", limit.toString()
        ).use {
            while (it.moveToNext()) {
                tasks.add(readTask(it))
            }
        }
        return tasks
    }

    private fun readTask(c: android.database.Cursor) = UploadTask(
        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
        elderId = c.getInt(c.getColumnIndexOrThrow(COL_ELDER_ID)),
        sessionId = c.getString(c.getColumnIndexOrThrow(COL_SESSION_ID)),
        segmentIndex = c.getInt(c.getColumnIndexOrThrow(COL_SEGMENT_INDEX)),
        reason = c.getString(c.getColumnIndexOrThrow(COL_REASON)),
        placeName = c.getString(c.getColumnIndexOrThrow(COL_PLACE_NAME)) ?: "",
        filePath = c.getString(c.getColumnIndexOrThrow(COL_FILE_PATH)),
        fileName = c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME)) ?: "",
        durationMs = c.getInt(c.getColumnIndexOrThrow(COL_DURATION_MS)),
        recordedAt = c.getLong(c.getColumnIndexOrThrow(COL_RECORDED_AT)),
        sha256 = c.getString(c.getColumnIndexOrThrow(COL_SHA256)) ?: "",
        sizeBytes = c.getInt(c.getColumnIndexOrThrow(COL_SIZE_BYTES)),
        latitude = c.getColumnIndexOrThrow(COL_LATITUDE).let {
            if (c.isNull(it)) null else c.getDouble(it)
        },
        longitude = c.getColumnIndexOrThrow(COL_LONGITUDE).let {
            if (c.isNull(it)) null else c.getDouble(it)
        },
        address = c.getString(c.getColumnIndexOrThrow(COL_ADDRESS)) ?: "",
        retryCount = c.getInt(c.getColumnIndexOrThrow(COL_RETRY_COUNT)),
        lastError = c.getString(c.getColumnIndexOrThrow(COL_LAST_ERROR)) ?: ""
    )

    /**
     * 标记上传失败并安排下次重试。
     * 退避策略：10s → 30s → 1min → 2min → 5min → 10min，之后固定 15min。
     * 上限很重要 —— 老人出门漫游时可能长时间无网，无限重试会耗电且招运营商骂。
     */
    fun markFailed(id: Long, error: String, retryCount: Int) {
        val next = retryCount + 1
        val delayMs = when {
            next <= 1 -> 10_000L
            next == 2 -> 30_000L
            next == 3 -> 60_000L
            next == 4 -> 120_000L
            next == 5 -> 300_000L
            next == 6 -> 600_000L
            else -> 900_000L
        }
        val values = ContentValues().apply {
            put(COL_RETRY_COUNT, next)
            put(COL_NEXT_RETRY_AT, System.currentTimeMillis() + delayMs)
            put(COL_LAST_ERROR, error.take(300))
        }
        writableDatabase.update(TABLE_QUEUE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** 上传成功：移出队列 */
    fun remove(id: Long) {
        writableDatabase.delete(TABLE_QUEUE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_QUEUE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun totalPendingBytes(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM($COL_SIZE_BYTES),0) FROM $TABLE_QUEUE", null).use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    /** 取最早一条待传任务（用于通知栏展示"还有 N 段待上传"） */
    fun oldestPending(): UploadTask? {
        readableDatabase.query(
            TABLE_QUEUE, null, null, null, null, null, "$COL_RECORDED_AT ASC", "1"
        ).use {
            return if (it.moveToFirst()) readTask(it) else null
        }
    }

    /**
     * 队列中最早的到期重试时间。
     * UploadQueue 用它决定"什么时候该再跑一轮"——没有这个方法，
     * 退避时间写进库里也没人读，队列会在首次失败后永久卡死。
     */
    fun earliestNextRetryAt(): Long {
        readableDatabase.rawQuery(
            "SELECT MIN($COL_NEXT_RETRY_AT) FROM $TABLE_QUEUE", null
        ).use {
            return if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }

    /** 最近一次失败原因，界面上给用户看得懂的解释 */
    fun lastErrorMessage(): String {
        readableDatabase.rawQuery(
            "SELECT $COL_LAST_ERROR FROM $TABLE_QUEUE ORDER BY $COL_NEXT_RETRY_AT ASC LIMIT 1", null
        ).use {
            return if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }
    }
}

data class UploadTask(
    val id: Long,
    val elderId: Int,
    val sessionId: String,
    val segmentIndex: Int,
    val reason: String,
    val placeName: String,
    val filePath: String,
    val fileName: String,
    val durationMs: Int,
    val recordedAt: Long,
    val sha256: String,
    val sizeBytes: Int,
    val latitude: Double?,
    val longitude: Double?,
    val address: String,
    val retryCount: Int = 0,
    val lastError: String = ""
)
