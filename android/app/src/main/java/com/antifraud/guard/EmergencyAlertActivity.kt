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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.config.GuardConfig

class EmergencyAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_FROM = "extra_from"
        const val EXTRA_FAMILY_PHONE = "extra_family_phone"
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
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

        // 子女手机号：优先取指令携带的（服务端按绑定关系实时查，最准），
        // 否则回退到本地绑定时缓存的号码；都拿不到才需要提示子女先完成绑定。
        val familyPhone = pickFamilyPhone(intent.getStringExtra(EXTRA_FAMILY_PHONE))

        tvTitle.text = title
        tvMessage.text = message
        tvFrom.text = if (familyPhone.isNotEmpty()) {
            "来自：$from（$familyPhone）"
        } else {
            "来自：$from"
        }

        // 按钮文案直接带上号码，老人一眼确认拨给谁
        btnCallFamily.text = if (familyPhone.isNotEmpty()) {
            "📞 立即拨打子女电话核实\n$familyPhone"
        } else {
            "📞 拨打子女电话核实\n（未获取到号码）"
        }

        btnAcknowledge.setOnClickListener {
            stopAlarm()
            finish()
        }

        btnCallFamily.setOnClickListener {
            stopAlarm()
            if (familyPhone.isEmpty()) {
                // 没号码时不能瞎拨，引导先去绑定守护人
                Toast.makeText(
                    this, "尚未绑定子女账号，请先让子女在控制台用绑定码完成绑定后重试",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // 号码已自动填好，老人只需按一下拨出键，无需手工输入
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$familyPhone")))
            } catch (e: Exception) {
                // 极少数定制系统没有拨号盘，兜底提示号码让子女回拨
                Toast.makeText(
                    this, "本机无法打开拨号盘，请直接拨打子女电话：$familyPhone",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            finish()
        }

        playAlertFeedback()
    }

    /** 依次尝试：指令携带号码 → 本地缓存的守护人号码；只接受干净的真实号码 */
    private fun pickFamilyPhone(fromIntent: String?): String {
        listOfNotNull(
            normalizePhone(fromIntent),
            normalizePhone(GuardConfig.boundFamilyPhone)
        ).firstOrNull()?.let { return it }
        return ""
    }

    /** 全角转半角 + 剔除非法字符；微信占位号（wx_）和用户名一律视为无号码 */
    private fun normalizePhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.startsWith("wx_", ignoreCase = true)) return ""
        val digits = buildString {
            for (ch in trimmed) {
                when {
                    ch in '０'..'９' -> append('0' + (ch - '０'))
                    ch in '0'..'9' -> append(ch)
                }
            }
        }
        return if (digits.length >= 6) digits else ""
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
