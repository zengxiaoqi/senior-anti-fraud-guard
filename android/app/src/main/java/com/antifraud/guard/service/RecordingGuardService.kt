package com.antifraud.guard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 环境录音存证前台服务：
 *  - 触发来源（通过 Intent extra "reason" 传入）：
 *      · SOS       —— 老人一键紧急求助，随求助一起开始录音
 *      · GEOFENCE  —— 老人进入子女登记的敏感地点围栏，自动开启录音
 *  - 分段录音（每段上限 10 分钟），AAC 格式存到应用专属目录
 *  - 每段录完立即进入上传队列，断网则持久化重试，保证"录到就一定送出去"
 *
 *  停止条件（任一触发即停）：
 *      1. 老人点停止按钮（主界面按钮或通知栏按钮）
 *      2. 子女端远程停止（WS 下发 RECORDING_STOP）
 *      3. 达到最大段数（默认 3 段 ≈ 30 分钟，可配置）
 *    另有离开围栏自动停（仅 GEOFENCE 生效，不会打断 SOS 录音）
 *
 * 合规说明：仅在老人一键求助或进入子女登记的可疑地点时触发，
 * 不做常态化监听；录音立即上传给已绑定的子女，用于维权存证。
 */
class RecordingGuardService : Service() {

    companion object {
        const val ACTION_START = "com.antifraud.guard.action.RECORDING_START"
        const val ACTION_STOP  = "com.antifraud.guard.action.RECORDING_STOP"
        const val EXTRA_REASON = "reason"          // SOS / GEOFENCE
        const val EXTRA_PLACE  = "place"           // 围栏名称（GEOFENCE 时携带）

        /** 通知栏「停止录音」按钮专用 action，与普通 STOP 区分来源便于日志排查 */
        const val ACTION_STOP_FROM_NOTIFICATION = "com.antifraud.guard.action.RECORDING_STOP_NOTIF"

        private const val CHANNEL_ID = "recording_guard_channel"
        private const val NOTIF_ID   = 2003
        private const val SEGMENT_MAX_MS = 10 * 60 * 1000   // 单段录音上限 10 分钟
        private const val TAG = "RecordingGuard"

        /** 对外运行态，供主界面显示「录音中」与停止按钮 */
        @Volatile
        var isRecording = false
            private set

        @Volatile
        var activePlaceName: String = ""
            private set

        @Volatile
        var currentSessionId: String = ""
            private set

        @Volatile
        var currentSegmentIndex: Int = 0
            private set
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var segmentStartAt = 0L

    /** 当前录音触发来源，决定谁能停止录音 */
    private var activeReason: String? = null
    private var segmentIndex = 0

    /** 一次连续录音的会话 ID：同一次求助的所有分段共用，服务端据此聚合 */
    private var sessionId: String = ""

    /** 录音起始位置快照，让每段录音都能对应到具体地点 */
    private var startLatitude: Double? = null
    private var startLongitude: Double? = null
    private var startAddress: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        UploadQueue.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_STOP_FROM_NOTIFICATION -> {
                val reason = intent.getStringExtra(EXTRA_REASON)
                // GEOFENCE 只能停 GEOFENCE 的录音；SOS 录音不被围栏退出打断
                if (reason == null || reason == activeReason) {
                    val byWhom = if (intent.action == ACTION_STOP_FROM_NOTIFICATION) "通知栏按钮" else "手动/远程"
                    Log.i(TAG, "收到停止指令（${byWhom}），结束本次录音存证")
                    stopRecording("用户${byWhom}停止")
                    stopForeground(true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                val reason = intent?.getStringExtra(EXTRA_REASON) ?: "SOS"
                val place  = intent?.getStringExtra(EXTRA_PLACE) ?: ""
                if (activeReason == null) {
                    activeReason = reason
                    segmentIndex = 0
                    sessionId = "S_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    activePlaceName = place
                    currentSessionId = sessionId
                    currentSegmentIndex = 0
                    isRecording = true
                    captureLocationSnapshot()
                    startForeground(NOTIF_ID, buildNotification(place))
                    startNewSegment()
                    reportState("STARTED", reason, place)
                    Log.i(TAG, "开始环境录音存证：来源=$reason 会话=$sessionId")
                } else if (activeReason == "GEOFENCE" && reason == "SOS") {
                    // 升级为 SOS：围栏退出会发 GEOFENCE 停止，但此时 activeReason 已是 SOS，不会被误停
                    activeReason = "SOS"
                    activePlaceName = ""
                    updateNotification("")
                    Log.i(TAG, "录音来源升级为 SOS（围栏录音并入同一次存证）")
                }
                return START_STICKY
            }
        }
    }

    // ──────────────────────────────────────────
    //  分段录音
    // ──────────────────────────────────────────

    private fun startNewSegment() {
        releaseRecorder()

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val file = File(dir, "REC_${stamp}_${sessionId.takeLast(6)}_${segmentIndex + 1}.m4a")

        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setMaxDuration(SEGMENT_MAX_MS)
                setMaxFileSize(50L * 1024 * 1024)   // 单段 50MB 兜底
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    // 达到单段时长/大小上限：存证本段，再决定续录还是收尾
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        handleSegmentFinished(file)
                    }
                }
                prepare()
                start()
            }
            currentFile = file
            segmentStartAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
            releaseRecorder()
            currentFile = null
        }
    }

    /**
     * 一段录完：先交给上传队列，再决定续录还是整体结束。
     *
     * 顺序不能反 —— 老人手机可能下一秒就没电/没网，
     * 把文件交给持久化队列比"赶紧录下一段"优先级更高。
     */
    private fun handleSegmentFinished(file: File) {
        val durationMs = (System.currentTimeMillis() - segmentStartAt).toInt().coerceAtLeast(0)
        releaseRecorder()
        currentFile = null

        enqueueUpload(file, durationMs, segmentIndex + 1)
        segmentIndex++
        currentSegmentIndex = segmentIndex
        reportState("SEGMENT", activeReason ?: "SOS", activePlaceName)

        val maxSegments = GuardConfig.recordingMaxSegments
        if (segmentIndex >= maxSegments) {
            val minutes = maxSegments * (SEGMENT_MAX_MS / 60000).toInt()
            Log.i(TAG, "已达到最大录音段数（$maxSegments 段 / 约 $minutes 分钟），自动停止")
            stopRecording("达到最大录音时长")
            stopForeground(true)
            stopSelf()
        } else {
            Log.i(TAG, "第${segmentIndex}段已入队待上传，继续录制第${segmentIndex + 1}段")
            if (activeReason != null) {
                startNewSegment()
                updateNotification(activePlaceName)
            }
        }
    }

    private fun enqueueUpload(file: File, durationMs: Int, segNo: Int) {
        try {
            UploadQueue.enqueue(
                file = file,
                sessionId = sessionId,
                segmentIndex = segNo,
                reason = activeReason ?: "SOS",
                placeName = activePlaceName,
                durationMs = durationMs,
                recordedAt = segmentStartAt,
                latitude = startLatitude,
                longitude = startLongitude,
                address = startAddress
            )
        } catch (e: Exception) {
            Log.e(TAG, "录音入队失败: ${e.message}", e)
        }
    }

    private fun stopRecording(reason: String) {
        // 正在录的这一段同样要保住：先停录再入队，别因为"停止"把当前这段丢掉
        val file = currentFile
        val startedAt = segmentStartAt
        releaseRecorder()
        if (file != null && file.exists() && file.length() > 0) {
            val durationMs = (System.currentTimeMillis() - startedAt).toInt().coerceAtLeast(0)
            enqueueUpload(file, durationMs, segmentIndex + 1)
        }
        currentFile = null
        activeReason = null
        isRecording = false
        activePlaceName = ""
        reportState("STOPPED", "SOS", "")
        Log.i(TAG, "录音已停止：$reason")
    }

    private fun releaseRecorder() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
    }

    /** 录音开始时取一次位置，附在所有分段上 */
    private fun captureLocationSnapshot() {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return

            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
            }.maxByOrNull { it.time }
            if (loc != null) {
                startLatitude = loc.latitude
                startLongitude = loc.longitude
                startAddress = "(${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取录音起始位置失败: ${e.message}")
        }
    }

    /** 通知服务端录音状态，让子女端看到"正在录音"并能远程停止 */
    private fun reportState(state: String, reason: String, place: String) {
        try {
            val payload = JSONObject().apply {
                put("type", "RECORDING_STATE")
                put("state", state)
                put("reason", reason)
                put("place", place)
                put("segmentIndex", segmentIndex)
                put("sessionId", sessionId)
            }
            GuardWebSocketManager.sendState(payload)
        } catch (e: Exception) {
            Log.w(TAG, "上报录音状态失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (isRecording) stopRecording("服务被系统回收")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────
    //  前台通知
    // ──────────────────────────────────────────

    private fun buildNotification(place: String): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 通知栏直接给「停止录音」按钮，老人不进 App 也能结束录音
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, RecordingGuardService::class.java).apply {
                action = ACTION_STOP_FROM_NOTIFICATION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val maxSegments = GuardConfig.recordingMaxSegments
        val title = when {
            place.isNotEmpty() -> "🔴 环境录音存证中（${place}）"
            else -> "🔴 环境录音存证中"
        }
        val sub = "第 ${(segmentIndex + 1).coerceAtMost(maxSegments)}/${maxSegments} 段 · 点此可停止"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止录音", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(place: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(place))
        } catch (e: Exception) {
            Log.w(TAG, "更新录音通知失败: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "环境录音存证通道", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "一键求助或进入敏感地点时本机环境录音存证" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
