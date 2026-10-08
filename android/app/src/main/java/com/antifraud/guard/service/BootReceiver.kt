package com.antifraud.guard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后自动恢复守护。
 *
 * 之前这个能力**完全不存在**：AndroidManifest 里一个 <receiver> 都没有，
 * 而 ForegroundGuardService 的注释却写着"本服务可能在开机自启时被系统拉起"。
 * 老人手机一重启，守护就永久失效 —— 直到老人自己想起来打开 App。
 * 一次重启就足以让整个防诈系统静默失守，这是最不可接受的一种失效方式。
 *
 * 监听三类广播：
 *   BOOT_COMPLETED      —— 常规开机
 *   QUICKBOOT_*         —— 长按电源键快启（华为/小米等会发这个而不是 BOOT_COMPLETED）
 *   MY_PACKAGE_REPLACED —— 应用升级后（升级会杀掉全部进程，等同于一次"重启"）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.i(TAG, "收到广播 $action，恢复守护服务")

        // 必须同步完成：onReceive 返回后进程可能立刻被回收，
        // 异步起服务会在部分 ROM 上失败。
        try {
            com.antifraud.guard.config.GuardConfig.init(context.applicationContext)
            val started = GuardServiceStarter.ensureRunning(context)
            // 同时把保活闹钟重新挂上（升级后系统会清掉已注册的闹钟）
            GuardKeepAliveScheduler.schedule(context)
            Log.i(TAG, "开机恢复完成，拉起: $started")
        } catch (e: Exception) {
            // 广播接收器里抛异常会被系统判定为 App 崩溃，必须兜住
            Log.e(TAG, "开机恢复守护失败", e)
        }
    }

    private companion object {
        const val TAG = "GuardBootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}