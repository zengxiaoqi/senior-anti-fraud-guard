package com.antifraud.guard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.api.ApiClient
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
 *  - 分段录音（每段默认 5 分钟，GuardConfig.recordingSegmentMinutes 可调 1~10），AAC 格式存到应用专属目录
 *  - 每段录完立即进入上传队列，断网则持久化重试，保证"录到就一定送出去"
 *
 *  停止条件（任一触发即停）：
 *      1. 老人点停止按钮（主界面按钮或通知栏按钮）
 *      2. 子女端远程停止（WS 下发 RECORDING_STOP）
 *      3. 达到最大段数（默认 3 段 × 每段 5 分钟 ≈ 15 分钟，段数与段长均可配置）
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
        private const val TAG = "RecordingGuard"

        /**
         * 单段录音时长上限（毫秒）。
         *
         * 刻意写成函数而不是常量：用户在设置页改了「单段录音时长」之后，
         * 下一次开录（乃至下一段）就该用新值，而不是等到重启服务才生效 ——
         * 守护服务是长驻的，读快照会让设置改了却"看着像没改"。
         */
        private fun segmentMaxMs(): Int = GuardConfig.recordingSegmentMinutes * 60 * 1000

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
        // 防御性初始化：系统可能在进程冷启动时直接拉起本服务（不经过任何 Activity），
        // 此时 GuardConfig/ApiClient 的单例还没 init。线上实证过 lateinit prefs
        // 未初始化导致服务崩溃、整个进程跟着死（2026-10-09 APP_CRASH 堆栈）。
        // GuardApp.onCreate 理论上已兜底，这里再补一层，两个 init 都幂等。
        GuardConfig.init(applicationContext)
        ApiClient.init(applicationContext)
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
                    announceRecordingStart(reason, place)
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

    /**
     * 录音开始时给老人一次能真正感知到的提示。
     *
     * ## 为什么必须加这个
     * 原实现在围栏触发时只做了 `startForegroundService` —— 没有弹窗、没有提示。
     * 通知栏虽然有"录音中"，但老人此刻在店里、手机揣兜里、屏幕关着，
     * **他完全不知道录音已经开始了**。
     *
     * 而同一个 App 里 SOS 路径是"先弹窗明确告知 → 用户确认 → 才录音"。
     * 两条路径没对齐，是"告知式录音"这个设计没能真正落地的直接原因 ——
     * 同一个 App 里一条路径告知、一条路径静默，说不过去。
     *
     * ## 为什么是 Toast + 振动，而不是弹窗
     * 触发时老人端 App **在后台**（服务是被 LocationGuardService 拉起的，没有任何 Activity 在前台）。
     * 此时弹 AlertDialog 会崩，必须走不需要窗口的通道。
     * 两通道并用是刻意的：屏幕亮着 → Toast 看得见；揣兜里 → 振动感觉得到。
     *
     * ## 为什么刻意不做 TTS 语音播报
     * 本函数在 `startNewSegment()` **之后**调用，此时 MediaRecorder 已经在录了。
     * TTS 从扬声器放出来的提示音会被麦克风一起录进去 ——
     * 结果是证据文件开头混进了 App 自己说的话。
     *
     * 这是"用于事后举证的录音"，混入非现场声音属于污染证据，
     * 比"老人没听到语音提示"严重得多。所以宁可少一条提示通道，也不能污染音频。
     * 真要语音告知，唯一正确的做法是先把提示播完、延时再开录，
     * 但那会丢掉开头几秒现场声音，同样不可接受 —— 两条路都不干净，索性不做。
     */
    private fun announceRecordingStart(reason: String, place: String) {
        // SOS 是老人自己按的，他完全知情，不需要再"告知"一遍
        if (reason == "SOS") return

     val toastText = if (place.isNotEmpty()) {
            "已进入登记地点「$place」，正在留存现场记录"
  } else {
            "正在留存现场记录，保护你的财产安全"
        }

        // 1) Toast：屏幕亮着时立刻可见
     try {
     Toast.makeText(applicationContext, toastText, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Toast 提示失败: ${e.message}")
   }

        // 2) 振动：屏幕关着、揣在兜里时唯一能感知的通道。
        // 用波形而不是单次震动，是为了和来电/消息的普通通知振动区分开。
     try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(
        android.os.VibrationEffect.createWaveform(longArrayOf(0L, 300L, 200L, 300L), -1)
         )
        } catch (e: Exception) {
    Log.w(TAG, "振动提示失败: ${e.message}")
        }

        Log.i(TAG, "录音开始已告知老人（无语音，避免污染录音）：$toastText")
    }

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
                setMaxDuration(segmentMaxMs())
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
            val minutes = maxSegments * GuardConfig.recordingSegmentMinutes
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
  // 文案去术语化：「环境录音存证中」是系统/开发术语，对老人没有任何意义，看到也读不懂。
        // 改成一句能让他明白"有人在保护我"的话 —— 通知要能被读懂，才谈得上告知。
        val title = when {
            place.isNotEmpty() -> "🔴 正在留存现场记录（$place）"
            else -> "🔴 正在留存现场记录"
        }
        val sub = "第 ${(segmentIndex + 1).coerceAtMost(maxSegments)}/${maxSegments} 段 · 家人可在子女端查看 · 可随时停止"
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
