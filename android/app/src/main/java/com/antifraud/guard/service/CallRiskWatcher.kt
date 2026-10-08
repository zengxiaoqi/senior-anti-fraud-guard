package com.antifraud.guard.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.db.CallRecordDbHelper
import com.antifraud.guard.util.HighRiskAppRegistry
import com.antifraud.guard.util.SystemPermissionState
import org.json.JSONObject

/**
 * 通话行为判定（Phase 1 · 任务 1-2 / 1-4 / 1-5，消除缺口 G6）。
 *
 * 工作方式：监听电话状态，仅在摘机（OFFHOOK）期间以 2 秒采样
 * UsageStatsManager 判定前台应用 —— 通话中前台切到高危 App（远程控制/支付类，
 * 见 [HighRiskAppRegistry]）立即上报 COERCION_RISK；挂机后通话记录落库
 * 并上报 CALL_STAT，再做时长与频次两项补充判定。
 *
 * ## 为什么不是独立 Service（对路线图 CallRiskWatcherService 的刻意偏离）
 * 独立前台服务 = 老人端通知栏塞进第 4 条常驻通知，会诱导用户一键全关；
 * 做成普通 Service 则在后台被 startService 会抛 IllegalStateException
 * （Android 8+ 后台启动限制）。挂在既有前台服务 ForegroundGuardService 的
 * 生命周期里：保活路径全部继承（开机自启/闹钟/Job/界面四条路径都会拉起它），
 * 零新增权限面、零新增通知。生命周期由 ForegroundGuardService.onCreate/onDestroy 驱动。
 *
 * ## 降级链（任务 1-6）
 *  - 未授予「使用情况访问」→ 前台应用联动静默失效（SettingsActivity 自检面板
 *    已标注"通话中的支付行为联动将不可用"），本类自动降级为仅通话时长监测
 *  - 未授予 READ_CONTACTS → 陌生号码判定不可用（三态 null，绝不把"查不了"
 *    当"陌生"上报 —— 那是误报源）；时长与频次判定不受影响
 *  - 呼出号码拿不到（需要 READ_CALL_LOG，已刻意不申请）→ number 为 null，
 *    陌生/频次判定跳过，仅时长判定有效
 */
class CallRiskWatcher(private val context: Context) {

    companion object {
        private const val TAG = "CallRiskWatcher"
        private const val SAMPLE_INTERVAL_MS = 2_000L                    // 1-2：OFFHOOK 期间 2s 采样
        private const val FREQUENCY_WINDOW_MS = 2 * 60 * 60 * 1000L      // 1-5：2 小时窗口
        private const val FREQUENCY_THRESHOLD = 3                        // 1-5：同号 ≥3 次
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dbHelper: CallRecordDbHelper? = null
    private var listener: PhoneStateListener? = null
    private var samplingRunnable: Runnable? = null

    // ── 单次通话状态（仅主线程访问：PhoneStateListener 回调在主线程）──
    private var callStartAt = 0L          // 摘机时刻；0 = 当前无通话
    private var callNumber: String? = null // RINGING 阶段拿到的来电号；呼出/受限时为 null
    private var coercionReported = false  // 本次通话是否已报 COERCION_RISK（一通电话最多一次）
    private var coercionAppName: String? = null

    private var started = false

    /** usage access 未授权只记一次日志，避免每 2 秒刷屏 */
    private var linkageDowngradeLogged = false

    @SuppressLint("MissingPermission") // 权限由 Manifest + MainActivity 启动期申请，此处只防御
    fun start() {
        if (started) return
        GuardConfig.init(context)
        dbHelper = CallRecordDbHelper(context)
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING ->
                            if (!phoneNumber.isNullOrEmpty()) callNumber = phoneNumber
                        TelephonyManager.CALL_STATE_OFFHOOK -> onOffhook()
                        TelephonyManager.CALL_STATE_IDLE -> onIdle()
                    }
                }
            }
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            started = true
            Log.i(TAG, "通话行为监听已启动（行为联动=${SystemPermissionState.isUsageAccessGranted(context)}，" +
                "通讯录=${hasContactsPermission()}）")
        } catch (e: Exception) {
            // 多为缺 READ_PHONE_STATE：整条监听线降级，不影响其他守护功能
            Log.w(TAG, "通话监听启动失败: ${e.message}")
        }
    }

    fun stop() {
        if (!started) return
        stopSampling()
        try {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
        listener = null
        started = false
        Log.i(TAG, "通话行为监听已停止")
    }

    // ──────────────────────────────────────────
    //  通话状态机
    // ──────────────────────────────────────────

    private fun onOffhook() {
        if (callStartAt != 0L) return // 已在通话中（呼叫等待切换等），不重置计时
        callStartAt = System.currentTimeMillis()
        coercionReported = false
        coercionAppName = null
        startSampling()
        Log.i(TAG, "检测到摘机，开始通话行为采样（号码=${callNumber ?: "未知/呼出"}）")
    }

    private fun onIdle() {
        if (callStartAt == 0L) return // 铃声期被叫方挂断等，未摘机不记
        val endAt = System.currentTimeMillis()
        val durationSec = ((endAt - callStartAt) / 1000).toInt()
        stopSampling()
        try {
            finishCall(endAt, durationSec)
        } catch (e: Exception) {
            // 结算失败绝不能让监听线死掉：状态复位后继续服务下一通电话
            Log.e(TAG, "通话结算异常: ${e.message}")
        } finally {
            callStartAt = 0L
            callNumber = null
            coercionReported = false
            coercionAppName = null
        }
    }

    // ──────────────────────────────────────────
    //  前台应用采样（行为联动核心）
    // ──────────────────────────────────────────

    private fun startSampling() {
        stopSampling()
        val r = object : Runnable {
            override fun run() {
                if (callStartAt == 0L) return // 通话已结束
                sampleForegroundOnce()
                mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
        samplingRunnable = r
        mainHandler.post(r)
    }

    private fun stopSampling() {
        samplingRunnable?.let { mainHandler.removeCallbacks(it) }
        samplingRunnable = null
    }

    private fun sampleForegroundOnce() {
        if (!SystemPermissionState.isUsageAccessGranted(context)) {
            if (!linkageDowngradeLogged) {
                linkageDowngradeLogged = true
                Log.w(TAG, "未授予「使用情况访问」，行为联动保护降级为仅通话时长监测（1-6 降级路径）")
            }
            return
        }
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - SAMPLE_INTERVAL_MS * 3, end)
            val e = UsageEvents.Event()
            var topPkg: String? = null
            var topAt = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // MOVE_TO_FOREGROUND 与 API 29 引入的 ACTIVITY_RESUMED 是同一常量值(1)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.timeStamp >= topAt) {
                    topAt = e.timeStamp
                    topPkg = e.packageName
                }
            }
            if (topPkg.isNullOrEmpty()) return
            val entry = HighRiskAppRegistry.lookup(topPkg) ?: return
            if (!HighRiskAppRegistry.isCallRiskEntry(entry)) return
            if (coercionReported) return // 一通电话只报一次，避免每 2 秒刷告警

            coercionReported = true
            coercionAppName = entry.name
            reportCoercionRisk(topPkg, entry.name, entry.category.name, entry.level.name)
        } catch (e: Exception) {
            // 常见：厂商 ROM 对 queryEvents 的实现差异。采样失败不终止循环
            Log.w(TAG, "前台应用采样失败: ${e.message}")
        }
    }

    private fun reportCoercionRisk(pkg: String, appName: String, category: String, level: String) {
        val details = JSONObject().apply {
            put("trigger", "foreground_app_during_call")
            put("foreground_package", pkg)
            put("foreground_app", appName)
            put("app_category", category)
            put("app_level", level)
            put("call_number", callNumber ?: "")
            when (val s = strangerState()) {
                true -> put("is_stranger", true)
                false -> put("is_stranger", false)
                null -> put("is_stranger", "unknown")
            }
            put("duration_so_far_min", (System.currentTimeMillis() - callStartAt) / 60000)
            put("event_desc", "通话中检测到高危应用「$appName」切到前台，疑似正被诱导屏幕共享或转账操作")
        }
        ApiClient.reportRiskEvent(
            eventType = "COERCION_RISK",
            severity = "HIGH",
            details = details
        )
        Log.w(TAG, "通话行为联动告警：通话中前台出现 ${entryLevelDesc(level)} $appName ($pkg)")
    }

    private fun entryLevelDesc(level: String): String =
        if (level == "CRITICAL") "远程控制类" else "支付类"

    // ──────────────────────────────────────────
    //  通话结束结算：落库 + CALL_STAT + 两项补充判定
    // ──────────────────────────────────────────

    private fun finishCall(endAt: Long, durationSec: Int) {
        val number = callNumber?.takeIf { it.isNotBlank() }
        val stranger = strangerState()
        val peakRisk = if (coercionReported) "HIGH" else "LOW"

        // 1) 落库（1-3）
        dbHelper?.insertCall(
            number = number,
            isStranger = stranger,
            startAt = callStartAt,
            endAt = endAt,
            durationSec = durationSec,
            peakRisk = peakRisk
        )

        // 2) 通话统计事件（1-7）：常规记录，恒 LOW，不触发推送
        val statDetails = JSONObject().apply {
            put("call_number", number ?: "")
            when (stranger) {
                true -> put("is_stranger", true)
                false -> put("is_stranger", false)
                null -> put("is_stranger", "unknown")
            }
            put("duration_sec", durationSec)
            put("peak_risk", peakRisk)
            put("coercion_reported", coercionReported)
            if (coercionAppName != null) put("coercion_app", coercionAppName)
        }
        ApiClient.reportRiskEvent(eventType = "CALL_STAT", severity = "LOW", details = statDetails)

        // 3) 时长预警兜底：呼叫筛选角色未持有（ScreenGuard 不工作）或呼出通话时，
        //    这里是时长监测的唯一执行者；角色在位时的呼入场景由 ScreenGuard 负责，
        //    用它上报的时间戳去重，避免同一通电话双告警
        val thresholdMin = GuardConfig.callThresholdMinutes.coerceIn(1, 240)
        val screeningMayHaveReported = SystemPermissionState.isCallScreeningRole(context) &&
            callNumber != null && // 呼入
            System.currentTimeMillis() - CallScreeningGuardService.lastDurationReportAt < 15 * 60_000L
        if (!coercionReported && !screeningMayHaveReported && durationSec >= thresholdMin * 60L) {
            val d = JSONObject().apply {
                put("caller_number", number ?: "未知号码")
                put("duration_minutes", durationSec / 60)
                put("threshold_minutes", thresholdMin)
                put("from", "call_risk_watcher")
            }
            ApiClient.reportRiskEvent(eventType = "CALL_RISK", severity = "HIGH", details = d)
            Log.w(TAG, "通话时长预警：本通 ${durationSec / 60} 分钟 ≥ 阈值 ${thresholdMin} 分钟")
        }

        // 4) 频次判定（1-5）：陌生号码 2h 内同号 ≥3 次 → MEDIUM（信任号码已在 strangerState 排除）
        if (number != null && stranger == true) {
            val recent = dbHelper?.countRecentCallsByNumber(number, endAt - FREQUENCY_WINDOW_MS) ?: 0
            if (recent >= FREQUENCY_THRESHOLD) {
                val d = JSONObject().apply {
                    put("call_number", number)
                    put("calls_in_2h", recent)
                    put("window_hours", 2)
                    put("event_desc", "陌生号码 2 小时内来电 ${recent} 次，疑似骚扰或反复诱导")
                }
                ApiClient.reportRiskEvent(eventType = "CALL_RISK", severity = "MEDIUM", details = d)
                Log.w(TAG, "陌生来电频次告警：$number 2h 内 ${recent} 次")
            }
        }
    }

    // ──────────────────────────────────────────
    //  陌生号码判定（1-4）：通讯录 + 信任列表，三态
    // ──────────────────────────────────────────

    /**
     * 三态陌生判定：true 陌生 / false 认识（通讯录或信任列表）/ null 判定不可用。
     * "查不了"绝不等于"陌生" —— 把权限缺失当成陌生上报，会让每通电话都变成误报。
     */
    private fun strangerState(): Boolean? {
        val number = callNumber?.takeIf { it.isNotBlank() } ?: return null
        if (GuardConfig.isTrustedCallNumber(number)) return false
        return isKnownContact(number)?.not()
    }

    /** 通讯录比对；null = READ_CONTACTS 未授权或查询失败（判定不可用） */
    private fun isKnownContact(number: String): Boolean? {
        if (!hasContactsPermission()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { it.count > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "通讯录查询失败: ${e.message}")
            null
        }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}
