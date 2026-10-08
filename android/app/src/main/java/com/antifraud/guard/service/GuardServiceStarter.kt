package com.antifraud.guard.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 守护服务拉起的唯一入口。
 *
 * 为什么需要单独一个类：老人端的守护服务有三条互相独立的拉起路径 ——
 *   1. 用户打开 App（MainActivity.onResume）
 *   2. 开机 / 应用更新（BootReceiver）
 *   3. 系统把进程回收后（GuardKeepAliveScheduler：AlarmManager + JobScheduler）
 * 三条路径的判断条件必须完全一致，否则会出现"界面显示守护中，实际服务没跑"
 * 或者"服务在跑但界面说没跑"这种最难排查的状态分裂。
 * 以前这三条路径各自写了一遍 startForegroundService，条件已经漂移。
 */
object GuardServiceStarter {

    private const val TAG = "GuardStarter"

    /**
     * 确保守护服务在运行中。幂等：已在跑就直接返回，不重复启动。
     *
     * @return 实际被拉起的服务名列表（便于日志与自检上报）
     */
    @JvmStatic
    fun ensureRunning(context: Context): List<String> {
        val app = context.applicationContext
        if (!com.antifraud.guard.config.GuardConfig.guardEnabled) {
            Log.i(TAG, "守护总开关已关闭，不拉起服务")
            return emptyList()
        }

        val started = mutableListOf<String>()

        if (!isRunning(app, ForegroundGuardService::class.java)) {
            if (startForegroundSafe(app, ForegroundGuardService::class.java)) {
                started += "ForegroundGuardService"
            }
        }

        // 位置服务依赖定位权限。没拿到就跳过，界面会显示"位置守护未开启"，
        // 而不是抛异常崩溃 —— 老人端崩溃一次，用户就不会再用第二次了。
        if (hasLocationPermission(app) &&
            !isRunning(app, LocationGuardService::class.java)
        ) {
            if (startForegroundSafe(app, LocationGuardService::class.java)) {
                started += "LocationGuardService"
            }
        }

        if (started.isNotEmpty()) {
            Log.i(TAG, "已拉起守护服务: $started")
        }
        return started
    }

    /** 停止守护服务（设置页"暂停守护"用） */
    @JvmStatic
    fun stopAll(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, ForegroundGuardService::class.java))
        app.stopService(Intent(app, LocationGuardService::class.java))
        Log.i(TAG, "已停止全部守护服务")
    }

    /**
     * 启动前台服务并吞掉"后台启动被拒"异常。
     *
     * Android 12(S) 起系统禁止从后台启动前台服务，抛
     * ForegroundServiceStartNotAllowedException。开机广播、精确闹钟、JobScheduler
     * 属于豁免场景，但厂商 ROM 经常把豁免也砍掉 —— 所以这里统一兜底：
     * 起不来只记日志，不让崩溃蔓延到广播接收器（否则系统会标记 App 崩溃）。
     */
    private fun startForegroundSafe(context: Context, cls: Class<*>): Boolean = try {
        // minSdk 26，startForegroundService 一定可用
        context.startForegroundService(Intent(context, cls))
        true
    } catch (e: Exception) {
        // Android 12+ 的后台启动限制、厂商 ROM 的自启动黑名单都会走到这里
        Log.w(TAG, "启动 ${cls.simpleName} 被系统拒绝（通常是后台启动限制或自启动未开启）: ${e.message}")
        false
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 是否已获得"始终允许"的后台定位权限。
     *
     * Android 10+ 把定位权限拆成"仅前台"与"始终允许"两档。只拿到前台权限时，
     * App 一退到后台定位就停，围栏进入/停留判定会静默失效 —— 而界面上看不出任何异常。
     * 这是最容易让人误以为"功能正常其实没在工作"的一个坑。
     */
    fun hasBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isRunning(context: Context, cls: Class<*>): Boolean = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        // getRunningServices 自 API 26 起对非本应用进程返回空，
        // 但本应用自己的前台服务仍会列出，所以只用于判断"自己的服务"，不做跨应用监控。
        manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    } catch (e: Exception) {
        false
    }
}