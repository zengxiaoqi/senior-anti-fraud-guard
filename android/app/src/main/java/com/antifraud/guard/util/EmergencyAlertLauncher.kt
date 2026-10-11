package com.antifraud.guard.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antifraud.guard.EmergencyAlertActivity

/**
 * 紧急警报拉起的唯一入口（三级降级链）。
 *
 * ## 为什么要抽出来
 * 原来这条链有**两份**实现，而且其中一份是坏的：
 *   - [com.antifraud.guard.service.GuardWebSocketManager]：子女远程打断，
 *     正确处理了「前台 startActivity / 后台全屏意图通知」
 *   - [com.antifraud.guard.service.NotificationPayListenerService]：本机检测到大额支付，
 *     直接 `startActivity`
 *
 * 第二份是**静默失效**的：NotificationListenerService 是绑定服务、没有前台地位，
 * Android 10+ 禁止从后台启动 Activity，`startActivity` 会被系统**静默丢弃**（不报错）。
 * 结果就是：老人在锁屏或 App 在后台时收到"你刚花了 5000 块"的通知，
 * 屏幕上什么都不会出现 —— 而这恰恰是最需要警报的时刻。
 *
 * ## 降级链
 *   1. App 在前台        → startActivity（最直接，交互最完整）
 *   2. 后台 + 通知已授权 → 全屏意图通知（Android 10+ 的合规通道，锁屏也能弹全屏）
 *   3. 后台 + 通知被禁   → 普通高优先级通知 + 警报音 + 震动（至少保证出声）
 *   4. 全屏通知也失败    → 再次尝试 startActivity 并记日志
 */
object EmergencyAlertLauncher {

    private const val TAG = "EmergencyAlert"
    const val ALERT_CHANNEL_ID = "emergency_alert_channel"
    private const val ALERT_NOTIFICATION_ID = 2002

    /** 当前 App 是否在前台（由 ActivityLifecycleCallbacks 维护） */
    @Volatile
    var isAppInForeground: Boolean = false

    /**
     * 拉起紧急警报页（带三级降级）。
     *
     * @param fromPhone 子女手机号，警报页会显示"立即拨打子女电话"，空串则隐藏该按钮
     */
    @JvmStatic
    @JvmOverloads
    fun launch(
        context: Context,
        title: String,
        message: String,
        fromUser: String,
        fromPhone: String = ""
    ) {
        val app = context.applicationContext
        val alertIntent = Intent(app, EmergencyAlertActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EmergencyAlertActivity.EXTRA_TITLE, title)
            putExtra(EmergencyAlertActivity.EXTRA_MESSAGE, message)
            putExtra(EmergencyAlertActivity.EXTRA_FROM, fromUser)
            if (fromPhone.isNotEmpty()) putExtra(EmergencyAlertActivity.EXTRA_FAMILY_PHONE, fromPhone)
        }

        val notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(app)
            .areNotificationsEnabled()

        try {
            if (isAppInForeground) {
                app.startActivity(alertIntent)
                Log.i(TAG, "警报页已直接启动（App 在前台）")
                return
            }
            if (notificationsEnabled) {
                Log.i(TAG, "App 在后台，走全屏意图通知")
                showFullScreenNotification(app, alertIntent, title, message, fromUser)
                return
            }
            // 通知被禁：至少用普通高优先级通知 + 声音 + 震动把人叫醒
            Log.w(TAG, "通知权限未授权，降级为普通高优先级通知 + 警报音")
            showFullScreenNotification(app, alertIntent, title, message, fromUser, fullScreen = false)
        } catch (e: Exception) {
            Log.e(TAG, "警报拉起失败，最后尝试直接启动警报页", e)
            try {
                app.startActivity(alertIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "直接启动警报页也被拒绝", e2)
            }
        }
    }

    /**
     * 拉起老人端「您还好吗？」确认页（疑似跌倒的反悔闸）。
     *
     * 与 [launch] 共用同一套降级链，但**页面语义不同**：
     * [launch] 是"子女在打断你"（子女是主体，老人是被通知方）；
     * 这一页是"系统问老人本人"（老人是主体）。所以这里只复用降级机制，
     * 不复用 [showFullScreenNotification] 的文案拼接 —— 那一套会把
     * "来源：xxx"拼进正文，而这一页的正文是给老人看的祈使句。
     *
     * @throws Exception 三级降级全部失败时抛出，由调用方决定兜底
     *         （ElderDangerWatcher 会直接上报告警，宁可误报也不能静默）
     */
    @JvmStatic
    fun launchElderDangerConfirm(
        context: Context,
        payload: ElderDangerConfirmActivity.Payload
    ) {
        val app = context.applicationContext
        val alertIntent = Intent(app, ElderDangerConfirmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            action = ElderDangerConfirmActivity.ACTION_CONFIRM
            putExtra(ElderDangerConfirmActivity.EXTRA_PEAK, payload.peakMagnitude)
            putExtra(ElderDangerConfirmActivity.EXTRA_IMPACT_AT, payload.impactAt)
        }

        val notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(app)
            .areNotificationsEnabled()

        var lastError: Exception? = null
        // 1) 前台：直接启动，交互最完整
        if (isAppInForeground) {
            try {
                app.startActivity(alertIntent)
                Log.i(TAG, "危险确认页已直接启动（App 在前台）")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        // 2) 后台 + 通知授权：全屏意图通知（Android 10+ 的合规通道，锁屏也能弹全屏）
        if (notificationsEnabled) {
            try {
                showFullScreenNotification(
                    app, alertIntent,
                    title = "您是否摔倒了？",
                    message = "如果没有响应，系统会自动通知您的家人。",
                    fromUser = "安全守护"
                )
                Log.i(TAG, "App 在后台，危险确认页走全屏意图通知")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        // 3) 全部降级失败：抛给调用方兜底（直接上报告警）
        throw lastError ?: IllegalStateException("危险确认页无法拉起")
    }

    private fun showFullScreenNotification(
        context: Context,
        alertIntent: Intent,
        title: String,
        message: String,
        fromUser: String,
        fullScreen: Boolean = true
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // minSdk 26，通知通道一定存在，无需再判版本
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "紧急亲情打断警报",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "大额支付核实、子女远程打断等紧急警报"
            // 穿透静音：这是唯一能把老人从"手机静音"状态里叫出来的通道
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            // 用闹钟音而不是通知默认音：老人机的提示音经常被静音或调得很小，
            // 警报必须"穿得过去"。USAGE_ALARM 是唯一不遵守勿扰音量的用途标记。
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(
            context, ALERT_NOTIFICATION_ID, alertIntent, flags
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, ALERT_NOTIFICATION_ID + 1, alertIntent, flags
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n来源：$fromUser"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)

        if (fullScreen) builder.setFullScreenIntent(fullScreenPendingIntent, true)

        manager.notify(ALERT_NOTIFICATION_ID, builder.build())
        Log.i(TAG, "紧急警报通知已发出（fullScreen=$fullScreen）")
    }
}