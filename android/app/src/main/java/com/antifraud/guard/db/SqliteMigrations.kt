package com.antifraud.guard.db

import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * SQLite 增量迁移工具。
 *
 * ## 为什么需要它
 * 本项目两个本地库（[UploadQueueDbHelper] 录音待传队列、[RiskEventDbHelper] 风险事件）
 * 的 `onUpgrade` 原本都是 `DROP TABLE` + 重建：
 *
 * ```kotlin
 * override fun onUpgrade(db, old, new) { db.execSQL("DROP TABLE IF EXISTS ..."); onCreate(db) }
 * ```
 *
 * 注释写着"队列是可重建的临时数据，升级时直接清空，比迁移安全"。这个判断是**错的**：
 * 队列里装的是**还没送达的证据录音**。老人在被骗现场录下的东西，
 * 如果恰好在孩子更新 App 的那一刻还没传上去，就被我方的升级逻辑直接销毁了 ——
 * 而且用户完全不知情，之后报警也拿不到任何东西。
 *
 * 对"临时数据"清库可以接受，对"证据待发队列"清库不可接受。
 * 这里提供可重复执行（幂等）的增量迁移原语，让以后每次升版本都只是 ADD COLUMN。
 */
object SqliteMigrations {

    private const val TAG = "SqliteMigrations"

    /** 表里是否已有该列 */
    fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        val existed = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
        return existed
    }

    /**
     * 加列；已存在则跳过（幂等）。
     * SQL 必须是完整的 `ALTER TABLE ... ADD COLUMN ...`，这里只负责判断要不要执行。
     */
    fun addColumnIfAbsent(db: SQLiteDatabase, table: String, column: String, sql: String) {
        if (hasColumn(db, table, column)) {
            Log.i(TAG, "列已存在，跳过: $table.$column")
            return
        }
        try {
            db.execSQL(sql)
            Log.i(TAG, "已新增列: $table.$column")
        } catch (e: Exception) {
            // 迁移失败不能抛 —— 否则 SQLiteOpenHelper 会让整个 App 打不开库。
            // 记日志让数据保持原样，下一次升级重试。
            Log.e(TAG, "迁移失败（已保留原数据）: $table.$column", e)
        }
    }

    /** 建索引（已存在则跳过，幂等） */
    fun createIndexIfAbsent(db: SQLiteDatabase, index: String, sql: String) {
        val existed = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(index)
        ).use { it.moveToFirst() }
        if (existed) return
        try {
            db.execSQL(sql)
            Log.i(TAG, "已建索引: $index")
        } catch (e: Exception) {
            Log.e(TAG, "建索引失败: $index", e)
        }
    }
}