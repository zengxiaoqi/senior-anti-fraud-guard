package com.antifraud.guard.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * PackageInstaller 会话式安装的状态回执。
 *
 * ## 为什么存在
 * 旧的 ACTION_VIEW 安装有两个体验硬伤：
 *  1. 系统成功页出不出「打开」按钮看 ROM 脸色，不少 ROM 只有「完成」；
 *  2. 安装覆盖本包时本进程被杀，点「完成」想回 App 也回不去（任务已死），
 *     直接落到桌面——用户就以为"点了完成什么都没发生"。
 *
 * 会话式安装 commit 时挂上本 Receiver，装完系统会回调我们：
 *  - STATUS_PENDING_USER_ACTION：转发系统确认页（必须由我们拉起）
 *  - STATUS_SUCCESS：延迟 1.2s 把**新版本**拉到系统成功页底下——
 *    成功页有「打开」点它进新版本；只有「完成」的 ROM 点完成也落回 App。
 *
 * goAsync()：onReceive 返回后进程可能被回收，postDelayed 的回调就没了。
 * goAsync 换来约 10s 的存活窗口，finish() 必须在 finally 里调。
 */
class InstallCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        Log.i(TAG, "安装回执 status=$status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统把确认页 Intent 塞在 EXTRA_INTENT 里，必须由我们启动
                @Suppress("DEPRECATION")
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        context.startActivity(
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "拉起安装确认页失败", e)
                    }
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION 但没有确认页 Intent")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val appContext = context.applicationContext
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val launch = appContext.packageManager
                            .getLaunchIntentForPackage(appContext.packageName)
                        if (launch != null) {
                            launch.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            )
                            appContext.startActivity(launch)
                            Log.i(TAG, "新版本已自动拉起（垫在系统成功页下）")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "自动打开新版本失败（不影响已安装）", e)
                    } finally {
                        pending.finish()
                    }
                }, AUTO_OPEN_DELAY_MS)
            }
        }
    }

    companion object {
        private const val TAG = "InstallComplete"
        private const val AUTO_OPEN_DELAY_MS = 1200L

        /** AppUpdateManager 建会话时用：把回执精确路由到本次安装 */
        const val ACTION_INSTALL_STATUS = "com.antifraud.guard.action.INSTALL_STATUS"
    }
}
