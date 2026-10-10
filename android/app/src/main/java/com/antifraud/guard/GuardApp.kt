package com.antifraud.guard

import android.app.Application
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.CrashReporter

/**
 * App 级初始化入口。之前项目没有 Application 类，各类 init 分散在各 Activity。
 * 现在承载两件事：
 * 1. 尽早安装全局崩溃捕获器——Activity.onCreate 之前发生的崩溃只有这里能兜住
 * 2. **尽早初始化 GuardConfig**——2026-10-09 线上实证（APP_CRASH 堆栈）：
 *    系统在进程冷启动时直接拉起 RecordingGuardService（不经过任何 Activity），
 *    buildNotification 读 GuardConfig.recordingMaxSegments 时 lateinit prefs
 *    未初始化 → 服务崩溃 → 整个进程死掉，表象就是"随便点什么都闪退"。
 *    Application.onCreate 一定先于同进程的一切 Service/Receiver/Activity，
 *    在这里 init 一次性根治这一整类问题。
 *
 * 注意：不要在这里做耗时初始化（StrictMode 会报、启动会慢），保持轻量。
 */
class GuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GuardConfig.init(this)
        // 外观偏好必须在任何 Activity.attachBaseContext 之前就绪：
        // 基类读它来套 fontScale，晚一步 Configuration 就定型了（Application.onCreate
        // 早于同进程一切 Activity，这里是最早的可靠时机）
        com.antifraud.guard.util.UiPrefs.init(this)
        CrashReporter.install(this)
    }
}
