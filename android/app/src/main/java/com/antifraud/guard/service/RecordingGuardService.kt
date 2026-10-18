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
 *      3. 达到最大段数（默认 3 段 × 每段 5 分钟，段数与段长均可配置）
 *      4. 离开围栏自动停（仅 GEOFENCE 生效，不会打断 SOS 录音）
 *
 *  GEOFENCE 会话的三段式节奏（短入场段 + 声音守听 + 续录）：
 *      ① ENTRY    进入围栏先固定录一小段（2 分钟），保住"到过此地"的入场证据；
 *      ② WATCHING 之后转入声音守听：用 AudioRecord 做纯能量检测（不录文件），
 *        环境安静就不消耗录音段数；守听最长 30 分钟，期间离开围栏即整场结束；
 *      ③ RESUMED  守听检测到持续说话声（≥1.5s）自动续录一段；段内连续安静
 *        ≥90s 再回到守听。已录内容每段照常入队上传，一段不丢。
 *    解决"一进围栏就录、录满 15 分钟全是走路声"的空录问题，同时不漏掉
 *    入场之后才开始的推销话术（纯静音早停方案的已知短板）。
 *    SOS 会话不参与此节奏：连续录音直到用户停止或段数用满。
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

        // ── 声音门控：短入场段 + 声音守听 + 续录 ──
        //
        // 两条检测通道，各管一个阶段：
        //   录音中（ENTRY/RESUMED）→ 轮询 MediaRecorder.getMaxAmplitude()
        //     （"距上次调用以来的峰值振幅"0~32767），零成本拿到粗糙但够用的 VAD 信号；
        //   守听中（WATCHING）→ AudioRecord 16kHz 单声道只做 RMS 能量检测，
        //     不落文件、不做识别，把麦克风功耗压到最低。

        /** 录音中振幅采样间隔。500ms 粒度足以区分"有人说话"和"环境底噪"，且功耗可忽略 */
        private const val AMP_SAMPLE_INTERVAL_MS = 500L

        /**
         * 录音中有效声音判定阈值（峰值振幅 0~32767）。
         *
         * 刻意取低值：手机揣兜里时人声被布料闷住，峰值可能只有一两千；
         * 阈值太高会把说话声当底噪漏掉 → 误停 → 丢证据。
         * 这条链路上"误停比多录更危险"——录音宁可多录几十秒，不能把骗子的声音停掉。
         */
        private const val AMP_ACTIVE_THRESHOLD = 1000

        /**
         * 入场段固定时长（毫秒）。进围栏先录满这一小段，保住"到过此地"的入场证据；
         * 之后环境再安静也不会误伤这一段。2 分钟 ≈ 走进店内坐下的时间。
         */
        private const val ENTRY_SEGMENT_MS = 2 * 60 * 1000

        /**
         * 续录段内连续安静超过该时长 → 停当前段、回到守听。
         * 会话不结束（这是与旧版"静音早停整场结束"的关键区别）：
         * 老人坐店里安静了 90 秒不代表后面没有推销——守听还在，声音一来就续录。
         */
        private const val RESUME_QUIET_GAP_MS = 90 * 1000L

        /**
         * 守听模式总时长上限。围栏退出（GEOFENCE_EXIT）本来就会停整场，
         * 这是第二道保险：老人在围栏里待了一下午也不至于守听一下午。
         * 比录音段数预算（3×5=15 分钟）宽，因为守听本身不录文件、不耗流量。
         */
        private const val WATCH_MAX_MS = 30 * 60 * 1000L

        // ── 守听用 AudioRecord 参数 ──

        /** 守听采样率。16kHz 单声道够做能量检测，功耗和缓冲都最小 */
        private const val WATCH_SAMPLE_RATE = 16000

        /** 守听每次读取的音频块时长（毫秒） */
        private const val WATCH_CHUNK_MS = 200L

        /**
         * 守听"有声音"的 RMS 阈值（16bit PCM 满量程 32767）。
         * 与 AMP_ACTIVE_THRESHOLD 同一取值哲学：宁可低估安静、不可漏掉人声。
         */
        private const val WATCH_RMS_THRESHOLD = 800

        /**
         * 守听中累计有声达到该时长才触发续录。
         * 1.5s 能滤掉关门声、咳嗽、一声招呼这类瞬态——
         * 骗子开口推销是连续说话，不会只有一秒半。
         * 累计用"衰减"而不是"清零"：字与字之间的短暂停顿不至于把进度归零。
         */
        private const val RESUME_SOUND_MS = 1500L

        /** debug-only：模拟"守听检测到持续说话声"，触发续录（release 包不注册接收器） */
        const val ACTION_DEBUG_WATCH_RESUME = "com.antifraud.guard.action.DEBUG_WATCH_RESUME"

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

    // ── 声音门控运行态 ──
    private val ampHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 本段累计采样数（用于统计有效声音占比，日志/上报用） */
    private var segTotalSamples = 0

    /** 本段累计"有声音"采样数 */
    private var segActiveSamples = 0

    /** 当前连续静音时长（毫秒），检测到有效声音即清零 */
    private var continuousSilenceMs = 0L

    /** 上次采样时刻，用于计算两次采样间的真实间隔（Segment 切换期间跳过） */
    private var lastAmpAt = 0L

    /** 本段是否已消费"start() 后首次恒为 0"的读数（每段重置） */
    private var firstAmpSample = true

    /**
     * GEOFENCE 会话阶段：ENTRY（入场段）→ WATCHING（守听）⇄ RESUMED（续录）。
     * SOS 会话恒为 null（连续录音，无阶段切换）。
     */
    private enum class Phase { ENTRY, WATCHING, RESUMED }
    private var phase: Phase? = null

    // ── 守听运行态（仅 WATCHING 阶段有效）──
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchThread: Thread? = null

    /** 守听循环退出旗标。volatile：主线程置位、守听线程轮询 */
    @Volatile
    private var watchStopFlag = false

    /** 守听开始时刻，用于 30 分钟上限 */
    private var watchStartedAt = 0L

    /** 守听中累计有声时长（毫秒），衰减制：安静一帧扣一帧，不清零 */
    private var watchedSoundMs = 0L

    /** debug 守听 RMS 日志的节流计数 */
    private var watchRmsLogTick = 0

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
        if (com.antifraud.guard.BuildConfig.DEBUG) registerDebugReceiver()
    }

    // ── debug-only 测试钩子（release 包不注册，不存在攻击面）──
    //
    // 守听续录依赖真实环境声，模拟器上无法从命令行往麦克风注音。
    // debug 包开放一条广播，走与"RMS 达标"完全相同的续录路径：
    //   am broadcast -a com.antifraud.guard.action.DEBUG_WATCH_RESUME
    private var debugReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugReceiver() {
        debugReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_DEBUG_WATCH_RESUME) return
                mainHandler.post {
                    if (phase != Phase.WATCHING) {
                        Log.i(TAG, "[DEBUG] 收到模拟续录广播，但当前不在守听阶段（phase=$phase），忽略")
                        return@post
                    }
                    Log.i(TAG, "[DEBUG] 模拟守听检测到持续说话声，触发续录")
                    stopWatchThread()
                    resumeFromWatch()
                }
            }
        }
        // EXPORTED 仅存在于 debug 包：必须让 adb shell 的 am broadcast 能送进来
        registerReceiver(
            debugReceiver,
            android.content.IntentFilter(ACTION_DEBUG_WATCH_RESUME),
            Context.RECEIVER_EXPORTED
        )
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
                    // GEOFENCE 从入场段开始（2 分钟短段），SOS 直接连续录音
                    phase = if (reason == "GEOFENCE") Phase.ENTRY else null
                    captureLocationSnapshot()
                    startForeground(NOTIF_ID, buildNotification(place))
                    startNewSegment(entry = reason == "GEOFENCE")
                    startAmplitudeMonitor()
                    reportState("STARTED", reason, place)
                    announceRecordingStart(reason, place)
                    Log.i(TAG, "开始环境录音存证：来源=$reason 阶段=$phase 会话=$sessionId")
                } else if (activeReason == "GEOFENCE" && reason == "SOS") {
                    // 升级为 SOS：围栏退出会发 GEOFENCE 停止，但此时 activeReason 已是 SOS，不会被误停
                    val wasWatching = phase == Phase.WATCHING
                    activeReason = "SOS"
                    activePlaceName = ""
                    phase = null
                    if (wasWatching) {
                        // 守听中来了 SOS：守听立即让位给连续录音（SOS 是更高优先级语义）
                        stopWatchThread()
                        startNewSegment()
                        startAmplitudeMonitor()
                        Log.i(TAG, "守听被 SOS 打断，立即恢复连续录音")
                    }
                    updateNotification("")
                    Log.i(TAG, "录音来源升级为 SOS（围栏录音并入同一次存证）")
                }
                return START_STICKY
            }
        }
    }

    // ──────────────────────────────────────────
    //  录音中的振幅采样（ENTRY / RESUMED 阶段）
    // ──────────────────────────────────────────

    /**
     * 启动振幅采样循环。只轮询、不额外持有麦克风 —— MediaRecorder 本来就在录，
     * maxAmplitude 是顺带的读数，所以这条路不增加任何权限和系统资源开销。
     */
    private fun startAmplitudeMonitor() {
        ampHandler.removeCallbacks(ampRunnable)
        ampHandler.postDelayed(ampRunnable, AMP_SAMPLE_INTERVAL_MS)
    }

    private fun stopAmplitudeMonitor() {
        ampHandler.removeCallbacks(ampRunnable)
    }

    // 自引用不能出现在匿名对象的初始化表达式里（编译期报"must be initialized"），
    // 与 LocationGuardService.heartbeatRunnable 同款解法：重排逻辑收进独立方法
    private fun scheduleAmpSample() {
        // 守听阶段没有 MediaRecorder 可采样，循环必须歇着；resumeFromWatch 会重启它。
        // 会话已结束（stopRecording 里会置 isRecording=false）同样不再排下一次，
        // 否则停止之后采样循环还挂着，服务都 stopSelf 了回调还在跑
        if (isRecording && phase != Phase.WATCHING) {
            ampHandler.postDelayed(ampRunnable, AMP_SAMPLE_INTERVAL_MS)
        }
    }

    private val ampRunnable: Runnable = object : Runnable {
        override fun run() {
            try {
                sampleAmplitude()
            } catch (e: Exception) {
                Log.w(TAG, "振幅采样异常: ${e.message}")
            } finally {
                scheduleAmpSample()
            }
        }
    }

    /** 采样一次振幅，更新统计并执行"续录段安静回守听"判定 */
    private fun sampleAmplitude() {
        val rec = recorder ?: return   // 段与段切换的空档，跳过
        val amp = try { rec.maxAmplitude } catch (e: Exception) { return }

        val now = System.currentTimeMillis()
        val elapsed = if (lastAmpAt == 0L) AMP_SAMPLE_INTERVAL_MS
                      else (now - lastAmpAt).coerceIn(0L, AMP_SAMPLE_INTERVAL_MS * 4)
        lastAmpAt = now

        // MediaRecorder 语义：start() 后第一次读 maxAmplitude 恒为 0。
        // 只丢弃这一次读数（用独立旗标，不能用 segTotalSamples==0 判断——
        // 模拟器虚拟麦克风恒输出 0，按旧写法所有 0 值样本都被当"首次"丢弃，
        // 采样永远不启动、安静计时永远不累积，E2E 实测踩中）
        if (firstAmpSample) {
            firstAmpSample = false
            if (amp == 0) return
        }

        segTotalSamples++
        val active = amp >= AMP_ACTIVE_THRESHOLD
        if (active) segActiveSamples++

        continuousSilenceMs = if (active) 0L else continuousSilenceMs + elapsed

        // 续录段连续安静 → 停段回守听，会话继续（声音一来就再续录）。
        // 入场段不做静音判断：总共就 2 分钟，录满就是它的使命。
        if (activeReason == "GEOFENCE" && phase == Phase.RESUMED &&
            continuousSilenceMs >= RESUME_QUIET_GAP_MS
        ) {
            Log.i(TAG, "续录段已连续安静 ${continuousSilenceMs / 1000}s，停段回守听" +
                "（本段有效声音占比 ${segmentSoundRatioText()}，已录内容照常入队）")
            pauseSegmentToWatch()
        }
    }

    /** 本段有效声音占比（0~100 的文本）；无采样时返回占位说明 */
    private fun segmentSoundRatioText(): String =
        if (segTotalSamples > 0) "${segActiveSamples * 100 / segTotalSamples}%"
        else "无采样"

    private fun phaseLabel(): String = when (phase) {
        Phase.ENTRY -> "入场段"
        Phase.WATCHING -> "守听"
        Phase.RESUMED -> "续录"
        null -> "连续录音"
    }

    // ──────────────────────────────────────────
    //  声音守听（WATCHING）
    //
    //  守听 = 用 AudioRecord 做纯能量检测：16kHz 单声道 PCM 分块读入、
    //  算 RMS、不落文件不做识别。媒体录音器此时是停着的，段数预算不消耗。
    //  麦克风互斥是这条路的生死线：AudioRecord 和 MediaRecorder 不能同时
    //  持有麦克风，切换时必须"先还、后拿"（见 startWatchThread 里的顺序注释）。
    // ──────────────────────────────────────────

    /** 录完一段后转入守听（入场段录完 / 续录段安静收尾，都汇到这里） */
    private fun enterWatchMode() {
        phase = Phase.WATCHING
        watchStartedAt = System.currentTimeMillis()
        watchedSoundMs = 0L
        updateNotification(activePlaceName)
        startWatchThread()
        // 守听期间没有"正在录音"的事实，对子女端如实降级为已停——
        // 否则"正在录音"红条亮着、点开却全是 2 分钟前的入场段，是欺骗式 UI。
        // 听到声音续录时会再报 STARTED，sessionId 不变，段仍归同一场存证。
        reportState("STOPPED", "GEOFENCE", activePlaceName, stopReason = "入场段已存证，转入声音守听")
        Log.i(TAG, "进入声音守听：「$activePlaceName」，持续说话声 ≥1.5s 将自动续录（守听上限 30 分钟）")
    }

    /** 续录段安静收尾：等价于"这一段录完了"，路由交给 handleSegmentFinished */
    private fun pauseSegmentToWatch() {
        stopAmplitudeMonitor()
        val file = currentFile
        releaseRecorder()
        currentFile = null
        if (file != null && file.exists() && file.length() > 0) {
            handleSegmentFinished(file)
        } else {
            // 极端情况：段刚开始就安静到没录出有效内容，直接回守听
            enterWatchMode()
        }
    }

    private fun startWatchThread() {
        watchStopFlag = false   // 新循环从"运行"开始；旧循环已在停段时自行退出
        watchThread = Thread {
            var record: android.media.AudioRecord? = null
            try {
                val channel = android.media.AudioFormat.CHANNEL_IN_MONO
                val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBuf = android.media.AudioRecord.getMinBufferSize(WATCH_SAMPLE_RATE, channel, encoding)
                record = android.media.AudioRecord(
                    MediaRecorder.AudioSource.MIC, WATCH_SAMPLE_RATE, channel, encoding,
                    maxOf(minBuf, (WATCH_SAMPLE_RATE * WATCH_CHUNK_MS / 1000).toInt() * 4)
                )
                if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "守听 AudioRecord 初始化失败，结束本场存证")
                    mainHandler.post { if (phase == Phase.WATCHING) stopSession("守听初始化失败") }
                    return@Thread
                }
                record.startRecording()

                val chunkShorts = (WATCH_SAMPLE_RATE * WATCH_CHUNK_MS / 1000).toInt()
                val buf = ShortArray(chunkShorts)
                while (!watchStopFlag) {
                    // 循环内 record 会被置 null（续录交接时），智能转换失效，
                    // 每轮取一次局部非空快照
                    val rec = record ?: break
                    val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { break }
                    if (n <= 0) { Thread.sleep(50); continue }

                    var sum = 0.0
                    for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                    val rms = Math.sqrt(sum / n)

                    val now = System.currentTimeMillis()
                    if (now - watchStartedAt >= WATCH_MAX_MS) {
                        Log.i(TAG, "守听已达 30 分钟上限，结束本场存证")
                        mainHandler.post { if (phase == Phase.WATCHING) stopSession("守听超时，自动结束") }
                        break
                    }

                    if (rms >= WATCH_RMS_THRESHOLD) watchedSoundMs += WATCH_CHUNK_MS
                    else watchedSoundMs = (watchedSoundMs - WATCH_CHUNK_MS).coerceAtLeast(0)

                    // debug 包每 5 秒打一次能量读数，用于验证守听灵敏度与注音是否生效。
                    // 仅 debug：release 的 minify 是关的，Log.d 会原样留在包里，别制造日志噪音
                    if (com.antifraud.guard.BuildConfig.DEBUG) {
                        watchRmsLogTick++
                        if (watchRmsLogTick >= (5000 / WATCH_CHUNK_MS).toInt()) {
                            watchRmsLogTick = 0
                            Log.d(TAG, "[DEBUG] 守听 RMS=${rms.toInt()} 累计有声=${watchedSoundMs}ms")
                        }
                    }

                    if (watchedSoundMs >= RESUME_SOUND_MS) {
                        watchedSoundMs = 0L
                        // 顺序生死线：先把麦克风还给系统（stop+release 必须在守听线程做），
                        // 再 post 主线程去启动 MediaRecorder —— 两把麦克风抢起来，
                        // MediaRecorder.start() 会直接失败，证据就断了
                        try { rec.stop() } catch (_: Exception) {}
                        try { rec.release() } catch (_: Exception) {}
                        record = null
                        mainHandler.post { resumeFromWatch() }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "守听线程异常: ${e.message}")
                if (!watchStopFlag) {
                    mainHandler.post { if (phase == Phase.WATCHING) stopSession("守听异常结束") }
                }
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
            }
        }.apply {
            name = "sound-watch"
            start()
        }
    }

    private fun stopWatchThread() {
        // 只置旗标不阻塞：AudioRecord.read 阻塞中，线程会在 ≤1 个 chunk（200ms）内
        // 自行退出并释放麦克风。不在主线程 join，避免卡 UI/服务生命周期
        watchStopFlag = true
        watchThread = null
    }

    /** 守听检测到持续说话声：恢复录音（主线程执行） */
    private fun resumeFromWatch() {
        if (!isRecording || activeReason != "GEOFENCE" || phase != Phase.WATCHING) return
        phase = Phase.RESUMED
        Log.i(TAG, "守听检测到持续说话声，续录第${segmentIndex + 1}段")
        startNewSegment()
        startAmplitudeMonitor()
        updateNotification(activePlaceName)
        reportState("STARTED", "GEOFENCE", activePlaceName)
    }

    /** 整场结束的统一出口：存证当前段 → 退前台 → 停服务 */
    private fun stopSession(reason: String) {
        stopRecording(reason)
        stopForeground(true)
        stopSelf()
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

    /** 开一段新录音。entry=true 时是入场段，用固定的 2 分钟短时长 */
    private fun startNewSegment(entry: Boolean = false) {
        releaseRecorder()

        // 新一段的统计从零开始：占比是"每段"粒度，连续静音计时也重新给机会 ——
        // 上一段安静不代表这一段安静（老人可能刚走进店内）
        segTotalSamples = 0
        segActiveSamples = 0
        continuousSilenceMs = 0L
        lastAmpAt = 0L
        firstAmpSample = true

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
                setMaxDuration(if (entry) ENTRY_SEGMENT_MS else segmentMaxMs())
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
     * 一段录完：先交给上传队列，再按会话类型决定下一步。
     *
     * 顺序不能反 —— 老人手机可能下一秒就没电/没网，
     * 把文件交给持久化队列比"赶紧录下一段"优先级更高。
     *
     * 路由规则：
     *   SOS            → 没到段数上限就继续连录（原语义，永不静音中断）；
     *   GEOFENCE 入场段 → 回守听；
     *   GEOFENCE 续录段 → 回守听（安静收尾和录满上限殊途同归）；
     *   段数预算用完    → 整场结束。
     */
    private fun handleSegmentFinished(file: File) {
        val durationMs = (System.currentTimeMillis() - segmentStartAt).toInt().coerceAtLeast(0)
        releaseRecorder()
        currentFile = null

        enqueueUpload(file, durationMs, segmentIndex + 1)
        segmentIndex++
        currentSegmentIndex = segmentIndex
        reportState("SEGMENT", activeReason ?: "SOS", activePlaceName)
        Log.i(TAG, "第${segmentIndex}段录完（${phaseLabel()}）：有效声音占比 ${segmentSoundRatioText()}")

        val maxSegments = GuardConfig.recordingMaxSegments
        if (activeReason != "GEOFENCE") {
            // SOS：连续录满 N 段为止
            if (segmentIndex >= maxSegments) {
                val minutes = maxSegments * GuardConfig.recordingSegmentMinutes
                Log.i(TAG, "已达到最大录音段数（$maxSegments 段 / 约 $minutes 分钟），自动停止")
                stopSession("达到最大录音时长")
            } else if (activeReason != null) {
                Log.i(TAG, "第${segmentIndex}段已入队待上传，继续录制第${segmentIndex + 1}段")
                startNewSegment()
                updateNotification(activePlaceName)
            }
            return
        }

        // GEOFENCE：段数预算用完 → 整场结束；否则回守听等下一次声音
        if (segmentIndex >= maxSegments) {
            Log.i(TAG, "守听续录已用完全部 $maxSegments 段预算，结束本场存证")
            stopSession("围栏录音段数预算用完")
        } else {
            enterWatchMode()
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
        // 采样循环与守听循环必须先停：早停路径里 stopRecording 之后紧跟 stopSelf，
        // 循环若还在跑，下一次采样会撞上已 release 的 recorder / AudioRecord
        stopAmplitudeMonitor()
        stopWatchThread()
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
        phase = null
        isRecording = false
        activePlaceName = ""
        reportState("STOPPED", "SOS", "", stopReason = reason)
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
    private fun reportState(state: String, reason: String, place: String, stopReason: String = "") {
        try {
            val payload = JSONObject().apply {
                put("type", "RECORDING_STATE")
                put("state", state)
                put("reason", reason)
                put("place", place)
                put("segmentIndex", segmentIndex)
                put("sessionId", sessionId)
                // STOPPED 时说明停止原因（用户停止/达上限/环境安静早停/服务回收）。
                // 子女端不认识该字段会自动忽略，向后兼容
                put("stopReason", stopReason)
            }
            GuardWebSocketManager.sendState(payload)
        } catch (e: Exception) {
            Log.w(TAG, "上报录音状态失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (isRecording) stopRecording("服务被系统回收")
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
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
        // 守听阶段单独一支文案：此刻没有在录音，不能谎报"正在留存"。
        val watching = phase == Phase.WATCHING
        val title = when {
            watching && place.isNotEmpty() -> "🟡 现场守听中（$place）"
            watching -> "🟡 现场守听中"
            place.isNotEmpty() -> "🔴 正在留存现场记录（$place）"
            else -> "🔴 正在留存现场记录"
        }
        val sub = if (watching) {
            "听到说话声会自动继续记录 · 家人可在子女端查看 · 可随时停止"
        } else {
            "第 ${(segmentIndex + 1).coerceAtMost(maxSegments)}/${maxSegments} 段 · 家人可在子女端查看 · 可随时停止"
        }
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
