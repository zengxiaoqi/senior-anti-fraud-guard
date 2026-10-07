package com.antifraud.guard.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.db.UploadQueueDbHelper
import com.antifraud.guard.db.UploadTask
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音上传执行器：把本地队列里的录音段真正送到服务器。
 *
 * 设计要点：
 *  1. 单线程串行上传 —— 老人手机带宽有限，串行能避免几个 10MB 的文件互相抢带宽；
 *  2. 指数退避重试，最长 15 分钟一次，重试次数无上限（直到成功或用户手动清理）；
 *  3. 上传成功才删本地文件。删早了，服务器写盘失败时证据就没了；
 *  4. 网络恢复（监听 ConnectivityManager）时立刻唤醒，不用等退避计时。
 */
object UploadQueue {

    private const val TAG = "UploadQueue"
    private const val MAX_CONSECUTIVE_FAILURES = 5   // 连续失败 N 次后停止本轮轮询，等网络变化再唤醒

    private var dbHelper: UploadQueueDbHelper? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recording-uploader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 防止多线程同时触发轮询 */
    private val running = AtomicBoolean(false)

    /** 待传数量变化回调，供界面展示 */
    private var pendingListener: ((Int) -> Unit)? = null

    fun init(context: Context) {
        if (dbHelper == null) {
            dbHelper = UploadQueueDbHelper(context.applicationContext)
        }
        // 冷启动时若队列里有积压（上次没传完），立即安排续传
        if (dbHelper?.pendingCount() ?: 0 > 0) {
            Log.i(TAG, "启动时发现 ${dbHelper?.pendingCount()} 段录音待上传，立即续传")
            scheduleNextRound()
        }
    }

    fun setPendingListener(listener: ((Int) -> Unit)?) {
        pendingListener = listener
    }

    fun pendingCount(): Int = dbHelper?.pendingCount() ?: 0

    /**
     * 把一个刚录完的录音段加入队列并立即尝试上传。
     * 由 RecordingGuardService 在每段录音结束时调用。
     */
    fun enqueue(
        file: File,
        sessionId: String,
        segmentIndex: Int,
        reason: String,
        placeName: String,
        durationMs: Int,
        recordedAt: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String = ""
    ) {
        val helper = dbHelper ?: return
        if (!file.exists()) {
            Log.w(TAG, "录音文件不存在，跳过入队: ${file.absolutePath}")
            return
        }
        try {
            // 本地先算一次 SHA-256：既用于服务端幂等去重，也便于排查文件是否损坏
            val sha = sha256Of(file)
            val task = UploadTask(
                id = 0,
                elderId = com.antifraud.guard.config.GuardConfig.elderId,
                sessionId = sessionId,
                segmentIndex = segmentIndex,
                reason = reason,
                placeName = placeName,
                filePath = file.absolutePath,
                fileName = file.name,
                durationMs = durationMs,
                recordedAt = recordedAt,
                sha256 = sha,
                sizeBytes = file.length().toInt(),
                latitude = latitude,
                longitude = longitude,
                address = address
            )
            helper.enqueue(task)
            Log.i(TAG, "录音第${segmentIndex}段已入队（${file.length() / 1024}KB，${file.name}）")
            notifyPending(helper.pendingCount())
            // 立刻尝试传，不让老人等待
            trigger()
        } catch (e: Exception) {
            Log.e(TAG, "录音入队失败: ${e.message}", e)
        }
    }

    /** 触发一轮上传（幂等，可重复调用） */
    fun trigger() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            var consecutiveFailures = 0
            // 清掉上一轮的错误，避免界面在"正在重传"期间还挂着旧的失败原因
            lastError = ""
            try {
                val helper = dbHelper ?: return@execute
                val now = System.currentTimeMillis()
                val due = helper.getDueTasks(now, 5)
                if (due.isEmpty()) {
                    notifyPending(helper.pendingCount())
                    return@execute
                }

                isUploading = true
                notifyPending(helper.pendingCount())

                for (task in due) {
                    val file = File(task.filePath)
                    if (!file.exists()) {
                        // 文件已被删（用户清理过），直接出队，否则会无限重试一个不存在的文件
                        Log.w(TAG, "队列中的录音文件已不存在，移出队列 #${task.id}")
                        helper.remove(task.id)
                        continue
                    }

                    val result = ApiClient.uploadRecording(task)
                    if (result.success) {
                        helper.remove(task.id)
                        // 服务端确认收下，才敢删本地文件
                        if (!file.delete()) {
                            Log.w(TAG, "上传成功但本地文件删除失败: ${file.absolutePath}")
                        }
                        lastError = ""
                        Log.i(TAG, "录音第${task.segmentIndex}段上传成功（服务端ID=${result.recordingId}）")
                    } else {
                        // 所有失败都留在队列里等退避重试（含服务端明确拒绝）：
                        // 证据只有送达子女端才算用上，不能因为一次失败就丢。
                        // 区别在于 permanent 的错误不该无休止重试，
                        // 但仍要保留在队列和本地文件里让用户看得见。
                        Log.w(TAG, "录音第${task.segmentIndex}段上传失败: ${result.error}")
                        lastError = result.error
                        helper.markFailed(task.id, result.error, task.retryCount)
                        consecutiveFailures++
                        // 服务端已明确拒绝的，重试同样会失败，让本轮停下，
                        // 避免卡在第一个坏任务上、后面的段永远排队
                        if (result.permanent) break
                    }
                }
                notifyPending(helper.pendingCount())
            } catch (e: Exception) {
                Log.e(TAG, "上传轮询异常: ${e.message}", e)
            } finally {
                running.set(false)
                isUploading = false
                // 关键：无论成功失败都要按退避表安排下一轮自调度。
                // 缺了这一步，markFailed 设的 next_retry_at 永远没人来读，
                // 队列会在首次失败后永久卡住——这就是"录音传不上去"的根因。
                scheduleNextRound()
            }
        }
    }

    /**
     * 按最早的到期时间安排下一轮上传。
     *
     * 退避数据存在 SQLite 的 next_retry_at 里，但队列本身没有时钟，
     * 必须由这里主动唤醒，否则退避只是"写下来"而不会真的重试。
     */
    private fun scheduleNextRound() {
        val helper = dbHelper ?: return
        mainHandler.removeCallbacks(retryRunnable)
        if (helper.pendingCount() == 0) {
            Log.i(TAG, "队列已清空，不再安排重试")
            return
        }
        val nextDue = helper.earliestNextRetryAt()
        val waitMs = (nextDue - System.currentTimeMillis()).coerceIn(1000L, 60_000L)
        Log.i(TAG, "安排下一轮上传，${waitMs / 1000}s 后重试")
        mainHandler.postDelayed(retryRunnable, waitMs)
    }

    private val retryRunnable = Runnable { trigger() }

    /**
     * 是否正在上传中。
     *
     * 为什么必须有这个状态：10 分钟录音约 6.87MB，实测经隧道要传近一分钟。
     * 以前「立即重试发送」点完只等 1.5 秒就去读 lastError，
     * 那时上传根本还没结束，读到的是上一次的失败原因 ——
     * 于是无论传多久都立刻弹「仍失败：xxx」，把"正在传"误报成"失败"。
     * 老人看到的就是"点了没一会就失败"，会以为功能坏了。
     */
    @Volatile
    var isUploading: Boolean = false
        private set

    /** 最近一次失败的原因，用于界面向用户解释"为什么还没发出去" */
    @Volatile
    var lastError: String = ""
        private set

    private fun notifyPending(count: Int) {
        val err = if (count > 0) (dbHelper?.lastErrorMessage() ?: "") else ""
        if (err.isNotEmpty()) lastError = err
        mainHandler.post { pendingListener?.invoke(count) }
    }

    /** 计算文件 SHA-256（分块读，避免大文件 OOM） */
    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
