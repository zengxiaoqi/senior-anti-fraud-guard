package com.antifraud.guard.family

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.antifraud.guard.BaseActivity
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.SettingsEntry
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 子女端「守护设置」入口页。
 *
 * ## 为什么改成入口页
 * 原先这一页塞了家基准 + 8 个输入框 + 6 个档位 chip + 一个总保存按钮，滚到底要划四屏。
 * 真正的代价不是"长"：
 *   1. 用户在末尾点保存时，看不见前面家基准那一栏填了什么 —— 保存的是一整屏记不住的状态；
 *   2. 某项填错了，错误提示和它相距三屏，提示等于没有；
 *   3.「清除家基准」只能标记、必须滚到底点保存才生效，
 *      于是"我明明清除了"成为一类无法复现的 bug。
 *
 * ## 这一页的职责
 * 只回答"每一项现在是什么状态"。摘要是必填的：
 * 用户靠扫一眼摘要决定要不要点进去，没有摘要的入口等于"一个不知道通向哪的按钮"。
 *
 * ## 摘要必须是服务端值，不是本机缓存
 * 云端是事实源。若拿本机缓存显示，子页面保存成功后返回，
 * 入口页还显示旧值，用户就会以为保存没生效而再改一遍。
 * 所以 [onResume] 无条件重新拉取。
 */
class ElderGuardSettingsActivity : BaseActivity() {

    private lateinit var entryHomeBase: View
    private lateinit var entryLocation: View
    private lateinit var entryCallPayment: View
    private lateinit var entryRecording: View
    private lateinit var tvError: TextView
    private lateinit var tvIntro: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_guard_settings)

        findViewById<TextView>(R.id.tv_title).text = "守护设置"
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        entryHomeBase = findViewById(R.id.entry_home_base)
        entryLocation = findViewById(R.id.entry_location)
        entryCallPayment = findViewById(R.id.entry_call_payment)
        entryRecording = findViewById(R.id.entry_recording)
        tvError = findViewById(R.id.tv_hub_error)
        tvIntro = findViewById(R.id.tv_hub_intro)

        SettingsEntry.bind(entryHomeBase, "🏠", "家基准位置") {
            startActivity(Intent(this, HomeBaseActivity::class.java))
        }
        SettingsEntry.bind(entryLocation, "📍", "位置守护阈值") {
            startActivity(Intent(this, LocationRuleActivity::class.java))
        }
        SettingsEntry.bind(entryCallPayment, "📞", "通话与支付") {
            startActivity(Intent(this, CallPaymentRuleActivity::class.java))
        }
        SettingsEntry.bind(entryRecording, "🎙️", "录音存证") {
            startActivity(Intent(this, RecordingRuleActivity::class.java))
        }

        setRowsEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        // 从任一子页面返回后必须重拉：子页面可能刚改过某一项
        load()
    }

    // ──────────────────────────────────────────
    //  读取与摘要
    // ──────────────────────────────────────────

    private fun load() {
        if (!GuardConfig.isFamilyBound) {
            tvIntro.text = "尚未绑定守护对象，绑定后才能设置守护参数。"
            tvError.visibility = View.GONE
            setRowsEnabled(false)
            listOf(entryHomeBase, entryLocation, entryCallPayment, entryRecording).forEach {
                SettingsEntry.summary(it, "绑定后可设置", UiPrefs.dimColor(this))
            }
            return
        }
        setRowsEnabled(true)
        ApiClient.fetchFamilyElderSettings(
            onSuccess = { s -> render(s) },
            onError = { err ->
                // 不吞错误：摘要读不出来时若显示空白，用户会以为"这些项都没配过"
                tvError.visibility = View.VISIBLE
                tvError.text = "读取失败：$err。各项显示的可能不是最新值，请重试。"
            }
        )
    }

    private fun setRowsEnabled(enabled: Boolean) {
        listOf(entryHomeBase, entryLocation, entryCallPayment, entryRecording).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun render(s: JSONObject?) {
        tvError.visibility = View.GONE

        // 家基准：未设置是要紧事，用警示黄，不能和已设置同色
        val hasLat = s != null && s.has("homeLat") && !s.isNull("homeLat")
        val hasLng = s != null && s.has("homeLng") && !s.isNull("homeLng")
        SettingsEntry.summary(
            entryHomeBase,
            if (hasLat && hasLng) {
                "已设置：${String.format("%.6f", s!!.optDouble("homeLat"))}, " +
                    String.format("%.6f", s.optDouble("homeLng"))
            } else {
                "⚠️ 未设置，离家判定可能失真"
            },
            if (hasLat && hasLng) UiPrefs.dimColor(this) else warnColor()
        )

        val away = s?.optInt("homeAwayRadiusMeters", -1)?.takeIf { it > 0 }
        val stayMin = s?.optInt("homeStayMinutes", -1)?.takeIf { it > 0 }
        SettingsEntry.summary(
            entryLocation,
            if (away != null && stayMin != null) "离家超过 $away 米 · 停留超过 $stayMin 分钟"
            else SettingsEntry.UNSET
        )

        val call = s?.optInt("callThresholdMinutes", -1)?.takeIf { it > 0 }
        val pay = s?.optDouble("paymentThreshold", -1.0)?.takeIf { it > 0 }
        SettingsEntry.summary(
            entryCallPayment,
            when {
                call != null && pay != null -> "通话超过 $call 分钟 · 单笔超 ${money(pay)} 元"
                call != null -> "通话超过 $call 分钟 · 支付金额${SettingsEntry.UNSET}"
                pay != null -> "通话时长${SettingsEntry.UNSET} · 单笔超 ${money(pay)} 元"
                else -> SettingsEntry.UNSET
            }
        )

        val seg = s?.optInt("recordingMaxSegments", -1)?.takeIf { it > 0 }
        val per = s?.optInt("recordingSegmentMinutes", -1)?.takeIf { it > 0 }
        val auto = s?.optBoolean("recordingAutoUpload", true) ?: true
        SettingsEntry.summary(
            entryRecording,
            when {
                seg != null && per != null ->
                    "$seg 段 × $per 分钟 ≈ ${seg * per} 分钟 · ${if (auto) "自动上传已开" else "仅存本地"}"
                else -> SettingsEntry.UNSET
            }
        )
    }

    /** 2000.0 显示成 "2000"，金额里出现小数点会让人怀疑填错了 */
    private fun money(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun warnColor() = 0xFFF59E0B.toInt()
}
