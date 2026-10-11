package com.antifraud.guard.util

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.antifraud.guard.BaseActivity
import com.antifraud.guard.R
import com.antifraud.guard.sensor.FallDetectionPolicy

/**
 * 老人端「您还好吗？」确认页 —— 疑似跌倒告警的第一道闸。
 *
 * ## 为什么必须先问老人，而不是直接报给子女
 * 加速度计判定"疑似跌倒"必然有误报（手机放桌上、从椅子上站起来、
 * 被猫撞了一下）。每一次误报都会把子女从睡梦中叫醒，
 * 而人一旦被叫醒几次就会开始忽略这类推送 ——
 * 等到真摔倒时，第一条真告警和那些误报长得一模一样，也就被一起忽略了。
 * 这是**漏报方向**的告警疲劳，比没有这个功能更危险。
 *
 * 所以流程是：检测到 → 先问老人 → 60 秒内点「我没事」就取消 →
 * 无人应答才上报。老人真摔倒时不可能按下任何按钮，
 * 所以"没有响应"恰恰是最可靠的求助信号。
 *
 * ## 与 EmergencyAlertActivity 的区别（不要混用）
 * 那一页是**子女发给老人的打断警报**（老人是被通知方）；
 * 这一页是**系统问老人本人**（老人是主体）。语义、配色、按钮都不同，
 * 混用会让老人以为子女已经知道了，从而不再操作。
 *
 * ## 为什么上报不由本页执行
 * 本页是 Activity，随时可能被系统回收；而"上报"必须有一次就成功的机会。
 * 所以这里只负责**问**，答案通过静态回调交给长驻的
 * [com.antifraud.guard.service.ElderDangerWatcher] 去上报 ——
 * 页面被杀掉、超时回调已经发出，都不影响告警链路。
 */
class ElderDangerConfirmActivity : BaseActivity() {

    companion object {
        private const val TAG = "ElderDangerConfirm"

        const val ACTION_CONFIRM = "com.antifraud.guard.action.DANGER_CONFIRM"
        const val EXTRA_PEAK = "extra_peak_magnitude"
        const val EXTRA_IMPACT_AT = "extra_impact_at"

        /**
         * 确认页与上报之间的回调（由 ForegroundGuardService 注入）。
         *
         * 用静态回调而不是让页面自己调 ApiClient，理由见类注释：
         * 上报必须由长驻服务兜住，不能依附于 Activity 生命周期。
         */
        @Volatile
        @JvmStatic
        var onTimeout: ((Payload) -> Unit)? = null

        /** 老人点「我没事」 */
        @Volatile
        @JvmStatic
        var onCancelled: ((Payload) -> Unit)? = null

        /** 拉起确认页 */
        @JvmStatic
        fun launch(context: Context, payload: Payload) {
            val intent = Intent(context, ElderDangerConfirmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                action = ACTION_CONFIRM
                putExtra(EXTRA_PEAK, payload.peakMagnitude)
                putExtra(EXTRA_IMPACT_AT, payload.impactAt)
            }
            context.startActivity(intent)
        }
    }

    data class Payload(val peakMagnitude: Float, val impactAt: Long)

    private var timer: CountDownTimer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elder_danger_confirm)

        // 必须亮屏 + 覆盖锁屏：老人摔在地上时屏幕是灭的，
        // 确认页不主动点亮就等于不存在，60 秒倒计时白走。
        wakeAndShowOverLockScreen()

        val payload = readPayload()

        findViewById<TextView>(R.id.tv_danger_body).text = buildString {
            append("检测到手机受到强烈撞击后长时间没有移动。\n\n")
            append("如果您没事，请点击下方「我没事」按钮，本次提醒会自动取消。\n")
            append("如果没有响应，系统会在 ${FallDetectionPolicy.CONFIRM_WINDOW_SEC} 秒后通知家人。")
        }
        findViewById<TextView>(R.id.tv_danger_peak).text =
            "撞击强度 ${"%.1f".format(payload.peakMagnitude)} m/s²"

        findViewById<Button>(R.id.btn_danger_ok).setOnClickListener { onElderOk(payload) }

        startRinging()
        startCountdown(payload)
    }

    private fun readPayload(): Payload = Payload(
        peakMagnitude = intent.getFloatExtra(EXTRA_PEAK, 0f),
        impactAt = intent.getLongExtra(EXTRA_IMPACT_AT, 0L)
    )

    private fun startCountdown(payload: Payload) {
        val tvCountdown = findViewById<TextView>(R.id.tv_danger_countdown)
        timer = object : CountDownTimer(
            FallDetectionPolicy.CONFIRM_WINDOW_SEC * 1000L, 1000L
        ) {
            override fun onTick(millisUntilFinished: Long) {
                val remain = ((millisUntilFinished + 999L) / 1000L).toInt()
                tvCountdown.text = "$remain 秒后通知家人"
            }

            override fun onFinish() {
                tvCountdown.text = "正在通知家人…"
                onTimeout?.invoke(payload)
            }
        }.also { it.start() }
    }

    private fun onElderOk(payload: Payload) {
        if (finished) return
        Log.i(TAG, "老人已确认平安，取消本次疑似跌倒告警")
        onCancelled?.invoke(payload)
        finishQuietly()
    }

    /** 关闭页面：必须静音 + 停震动，否则老人点完「我没事」耳朵里还在响铃 */
    private fun finishQuietly() {
        if (finished) return
        finished = true
        stopRinging()
        finish()
    }

    private fun wakeAndShowOverLockScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                    ?.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            // 老设备上锁屏相关 API 可能抛异常：亮屏失败不影响倒计时逻辑
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "safg:danger-confirm"
            )
            wake.setReferenceCounted(false)
            wake.acquire(FallDetectionPolicy.CONFIRM_WINDOW_SEC * 1000L + 5000L)
        } catch (e: Exception) {
        }
    }

    private fun startRinging() {
        try {
            // 用闹钟音：老人机提示音常被静音或调得很小，
            // 这一页必须"穿得过去" —— 它的对手是"老人躺在地上一动不动"。
            ringtone = RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setLooping(true)
                play()
            }
        } catch (e: Exception) {
            // 部分设备无闹钟铃声：静默失败，倒计时仍在跑
        }
        try {
            vibrator = (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600), 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrate(longArrayOf(0, 600, 400, 600), 0)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.let {
                // 必须用 setLooping(false) 而不是 Kotlin 的 isLooping = false：
                // Ringtone 的 setLooping 是 API 28 才加的合成属性，
                // 用属性语法会被编译成对 isLooping() getter 的调用而报 unresolved。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.setLooping(false)
                it.stop()
            }
        } catch (e: Exception) {
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        ringtone = null
        vibrator = null
    }

    override fun onDestroy() {
        stopRinging()
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    /**
     * 老人按返回键 / 手势返回时**不能当作取消**。
     *
     * 这一页被误关掉的后果是"检测到摔倒但什么也没发生" ——
     * 而它恰好出现在老人摔倒、意识不清、手机被压在身下的时刻，
     * 一个无意识的返回手势就会吞掉整条告警。
     * 所以返回手势被吞掉，页面只能由「我没事」或倒计时结束来关闭。
     */
    @Deprecated("刻意吞掉返回手势，理由见上方注释")
    override fun onBackPressed() {
        // no-op
    }
}
