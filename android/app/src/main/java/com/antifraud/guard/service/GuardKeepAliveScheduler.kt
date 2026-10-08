package com.antifraud.guard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.SystemPermissionState
import java.util.concurrent.TimeUnit

/**
 * 保活调度器：AlarmManager + JobScheduler 双保险。
 *
 * ## 背景与教训
 * 原实现只有「前台服务 + WebSocket 心跳 + 用户每次打开 App 时补拉」这一条路。
 * 老人一旦不打开 App、系统一旦回收进程，守护就彻底失守，而且没有任何迹象。
 *
 * 这里用两条互相独立的调度通道：
 *   - AlarmManager.setAndAllowWhileIdle()：约 10 分钟一次，能在 Doze 里被唤醒
 *   - JobScheduler：系统级调度，掉电重启后仍在（配 setPersisted）
 * 任何一条被厂商 ROM 拦死，另一条仍有机会把服务拉回来。
 *
 * ## 为什么用 setAndAllowWhileIdle 而不是 setExactAndAllowWhileIdle
 * 精确闹钟在 Android 12+ 需要 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 权限，
 * USE_EXACT_ALARM 在 Google Play 上属受限权限（仅闹钟/日历类可申请），
 * 会给上架带来不必要的审核风险。而"守护心跳"本来就不需要精确到秒，
 * 10 分钟级的非精确闹钟完全够用，且无需任何权限。
 *
 * ## 关于能不能真的活下来
 * 国产 ROM（MIUI/EMUI/ColorOS/OriginOS）对后台启动的限制差异极大，
 * 光看代码无法判断。所以这里自带心跳留痕（[heartbeatGapMinutes]）：
 * 装到真机上跑一天就能读出真实的存活间隔，不必靠猜。
 * 读取入口在「设置 → 守护健康自检」面板。
 */
object GuardKeepAliveScheduler {

    private const val TAG = "GuardKeepAlive"
    private const val ALARM_INTERVAL_MS = 10 * 60 * 1000L        // 10 分钟
    private const val PREFS = "guard_keepalive"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat_at"
    private const val KEY_HEARTBEAT_COUNT = "heartbeat_count"
    private const val KEEPALIVE_JOB_ID = 4201

    const val ACTION_KEEPALIVE = "com.antifraud.guard.action.KEEPALIVE"

    // ── 对外入口 ──

    /** 挂上/刷新保活调度。幂等，可反复调用。 */
    @JvmStatic
    fun schedule(context: Context) {
        val app = context.applicationContext
        scheduleAlarm(app)
        scheduleJob(app)
    }

    /** 取消保活（暂停守护时用） */
    @JvmStatic
    fun cancel(context: Context) {
        val app = context.applicationContext
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(newAlarmIntent(app))
        } catch (e: Exception) {
            Log.w(TAG, "取消闹钟失败: ${e.message}")
        }
        try {
            (app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
                .cancel(KEEPALIVE_JOB_ID)
        } catch (e: Exception) {
            Log.w(TAG, "取消 Job 失败: ${e.message}")
        }
    }

    // ── 通道一：AlarmManager ──

    private fun scheduleAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                newAlarmIntent(context)
            )
            Log.i(TAG, "保活闹钟已挂载，间隔 ${TimeUnit.MILLISECONDS.toMinutes(ALARM_INTERVAL_MS)} 分钟")
        } catch (e: Exception) {
            // Android 12+ 若厂商禁止后台闹钟会走到这里；JobScheduler 仍会兜底
            Log.w(TAG, "挂载保活闹钟失败，将依赖 JobScheduler 兜底: ${e.message}")
        }
    }

    private fun newAlarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = ACTION_KEEPALIVE
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── 通道二：JobScheduler ──

    private fun scheduleJob(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(KEEPALIVE_JOB_ID, ComponentName(context, KeepAliveJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)   // 无网也要能起（录音要落本地队列）
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))          // 周期下限 15 分钟
                .setPersisted(true)                                   // 掉电重启后仍在
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            js.schedule(info)
            Log.i(TAG, "保活 Job 已挂载")
        } catch (e: Exception) {
            Log.w(TAG, "挂载保活 Job 失败: ${e.message}")
        }
    }

    // ── 心跳留痕（用于真机验证国产 ROM 保活是否真的有效）──

    /** 记录一次心跳，返回距上次心跳的分钟数（首次为 -1） */
    @JvmStatic
    fun recordHeartbeat(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        val gapMinutes = if (last <= 0L) -1L else (now - last) / 60_000L
        val count = prefs.getInt(KEY_HEARTBEAT_COUNT, 0) + 1
        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT, now)
            .putInt(KEY_HEARTBEAT_COUNT, count)
            .apply()
        Log.i(TAG, "心跳留痕，距上次 $gapMinutes 分钟，累计 $count 次")
        return gapMinutes
    }

    /**
     * 距上次心跳的分钟数。-1 表示装好之后还没跑过一次保活。
     * 正常应稳定在 10~20 分钟；明显大于 40 说明系统或厂商在杀后台。
     */
    @JvmStatic
    fun heartbeatGapMinutes(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        if (last <= 0L) return -1L
        return (System.currentTimeMillis() - last) / 60_000L
    }

    @JvmStatic
    fun heartbeatCount(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HEARTBEAT_COUNT, 0)

    // ── 自检：把"守护到底有没有真的在工作"变成可读的数据 ──

    data class HealthReport(
        val guardEnabled: Boolean,
        val foregroundRunning: Boolean,
        val locationRunning: Boolean,
        val keepAliveScheduled: Boolean,
        val heartbeatGapMinutes: Long,
        val heartbeatCount: Int,
        val batteryUnrestricted: Boolean,
        val canScheduleExactAlarms: Boolean,
        val hasBackgroundLocation: Boolean,
        val callScreeningEnabled: Boolean,
        val notificationListenerEnabled: Boolean,
        val usageAccessGranted: Boolean,
        val vendorBrand: String
    )

    /**
     * 汇总当前守护健康状况。
     *
     * 这个方法存在的意义：守护失效绝大多数情况是**静默**的 —— 界面显示"守护中"，
     * 实际某个必要权限没给或服务被系统杀了。除了做自检上报，没有任何办法在
     * 出事之后判断"当时到底有没有在跑"。出事后的这份数据就是证据。
     */
    @JvmStatic
    fun healthReport(context: Context): HealthReport {
        val app = context.applicationContext
        GuardConfig.init(app)

        val canScheduleExactAlarms = try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }

        val keepAliveScheduled = isAlarmRegistered(app) || isJobRegistered(app)

        return HealthReport(
            guardEnabled = GuardConfig.guardEnabled,
            foregroundRunning = GuardServiceStarter.isRunning(app, ForegroundGuardService::class.java),
            locationRunning = GuardServiceStarter.isRunning(app, LocationGuardService::class.java),
            keepAliveScheduled = keepAliveScheduled,
            heartbeatGapMinutes = heartbeatGapMinutes(app),
            heartbeatCount = heartbeatCount(app),
            batteryUnrestricted = SystemPermissionState.isIgnoringBatteryOptimizations(app),
            canScheduleExactAlarms = canScheduleExactAlarms,
            hasBackgroundLocation = GuardServiceStarter.hasBackgroundLocation(app),
            callScreeningEnabled = SystemPermissionState.isCallScreeningRole(app),
            notificationListenerEnabled = SystemPermissionState.isNotificationListenerEnabled(app),
            usageAccessGranted = SystemPermissionState.isUsageAccessGranted(app),
            vendorBrand = com.antifraud.guard.VendorPermissionHelper.brandName()
        )
    }

    /**
     * 探测保活闹钟是否已注册。
     *
     * 用 FLAG_NO_CREATE 只查询不创建；随后 cancel() 释放的是本地这个引用，
     * **不会**取消 AlarmManager 里已登记的闹钟（闹钟是按 Intent 匹配持有的，
     * 不依赖 PendingIntent 对象生命周期）。
     */
    private fun isAlarmRegistered(context: Context): Boolean = try {
        val probe = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, KeepAliveReceiver::class.java).apply { action = ACTION_KEEPALIVE },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        val registered = probe != null
        probe?.cancel()
        registered
    } catch (e: Exception) {
        false
    }

    private fun isJobRegistered(context: Context): Boolean = try {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        js.allPendingJobs.any { it.id == KEEPALIVE_JOB_ID }
    } catch (e: Exception) {
        false
    }
}

/**
 * AlarmManager 保活通道的落地点。
 *
 * 广播里只做"拉服务 + 记心跳"，不碰任何 UI，
 * 这样即使系统在后台杀掉 App，10~20 分钟内也会被重新拉起。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GuardKeepAliveScheduler.ACTION_KEEPALIVE) return
        try {
            GuardConfig.init(context.applicationContext)
            val gap = GuardKeepAliveScheduler.recordHeartbeat(context)
            GuardServiceStarter.ensureRunning(context)
            // setAndAllowWhileIdle 是一次性闹钟，必须重新挂下一次，否则只救活这一次
            GuardKeepAliveScheduler.schedule(context)
            Log.i(TAG, "保活心跳生效（距上次 $gap 分钟），已重新挂载下一次")
        } catch (e: Exception) {
            Log.e(TAG, "保活心跳失败", e)
        }
    }

    private companion object {
        const val TAG = "GuardKeepAliveRx"
    }
}

/** JobScheduler 保活通道的落地点 */
class KeepAliveJob : android.app.job.JobService() {

    override fun onStartJob(params: android.app.job.JobParameters?): Boolean {
        try {
            GuardConfig.init(applicationContext)
            GuardKeepAliveScheduler.recordHeartbeat(applicationContext)
            GuardServiceStarter.ensureRunning(applicationContext)
        } catch (e: Exception) {
            Log.e("GuardKeepAliveJob", "Job 保活失败", e)
        }
        // 返回 false：本次任务已同步完成，不需要系统为它持续保持唤醒
        return false
    }

    override fun onStopJob(params: android.app.job.JobParameters?): Boolean {
        // 系统在配额耗尽前抢停，说明该通道被限制了；请求稍后重试
        return true
    }
}