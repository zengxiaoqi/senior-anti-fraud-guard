package com.antifraud.guard

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.config.GuardConfig

class EmergencyAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_FROM = "extra_from"
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()
        setContentView(R.layout.activity_emergency_alert)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "⚠️ 紧急亲情防骗强提醒！"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "子女已检测到高危行为，请暂停当前通话与转账！"
        val from = intent.getStringExtra(EXTRA_FROM) ?: GuardConfig.boundFamilyName.ifEmpty { "子女守护人" }

        val tvTitle = findViewById<TextView>(R.id.tv_alert_title)
        val tvMessage = findViewById<TextView>(R.id.tv_alert_message)
        val tvFrom = findViewById<TextView>(R.id.tv_alert_from)
        val btnAcknowledge = findViewById<Button>(R.id.btn_acknowledge)
        val btnCallFamily = findViewById<Button>(R.id.btn_call_family)

        tvTitle.text = title
        tvMessage.text = message
        tvFrom.text = "来自：$from"

        btnAcknowledge.setOnClickListener {
            stopAlarm()
            finish()
        }

        btnCallFamily.setOnClickListener {
            stopAlarm()
            // 如果有电话直接拉起拨号盘
            val dialIntent = Intent(Intent.ACTION_DIAL)
            startActivity(dialIntent)
            finish()
        }

        playAlertFeedback()
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun playAlertFeedback() {
        try {
            // 1. 播放报警铃声
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, alertUri)
            ringtone?.play()

            // 2. 强震动
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 300, 500, 300, 1000),
                        0 // 循环
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 300, 500, 300, 1000), 0)
            }
        } catch (ignored: Exception) {}
    }

    private fun stopAlarm() {
        try {
            ringtone?.stop()
        } catch (ignored: Exception) {}
        try {
            vibrator?.cancel()
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }
}
