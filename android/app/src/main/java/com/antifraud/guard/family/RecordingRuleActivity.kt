package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.antifraud.guard.R
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 子女端「守护设置 › 录音存证」。
 *
 * 只提交 `recordingAutoUpload / recordingMaxSegments / recordingSegmentMinutes` 三个字段。
 *
 * ## 实时换算那行为什么必须留着
 * 段数与单段时长分开摆，会让人以为自己设的是"总时长"，实际是两数相乘。
 * 不在输入过程中把乘积算出来，用户保存完才会发现"我以为是 30 分钟结果是 50"，
 * 而那时他已经不知道该改哪个框了。
 */
class RecordingRuleActivity : GuardRuleSubActivity() {

    private lateinit var swAutoUpload: Switch
    private lateinit var etRecSegments: EditText
    private lateinit var etRecSegmentMinutes: EditText
    private lateinit var tvRecTotalHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_rule)

        findViewById<TextView>(R.id.tv_title).text = "录音存证"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        swAutoUpload = findViewById(R.id.sw_auto_upload)
        etRecSegments = findViewById(R.id.et_rec_segments)
        etRecSegmentMinutes = findViewById(R.id.et_rec_segment_minutes)
        tvRecTotalHint = findViewById(R.id.tv_rec_total_hint)

        swAutoUpload.setOnCheckedChangeListener { _, _ -> if (!filling) dirty = true }
        etRecSegments.addTextChangedListener(dirtyWatcher { renderRecHint() })
        etRecSegmentMinutes.addTextChangedListener(dirtyWatcher { renderRecHint() })
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSaveRequested() }

        reload()
    }

    override fun onSettings(s: JSONObject?) {
        filling = true
        etRecSegments.setText(s.optPositiveInt("recordingMaxSegments"))
        etRecSegmentMinutes.setText(s.optPositiveInt("recordingSegmentMinutes"))
        swAutoUpload.isChecked = s?.optBoolean("recordingAutoUpload", true) ?: true
        filling = false
        renderRecHint()
    }

    /**
     * 实时换算录音总时长。
     *
     * 照搬原「守护设置」页的既有做法：填错当场就能看出，
     * 比保存后被静默 clamp 再让用户困惑要好。
     */
    private fun renderRecHint() {
        val seg = etRecSegments.text.toString().trim().toIntOrNull() ?: 0
        val per = etRecSegmentMinutes.text.toString().trim().toIntOrNull() ?: 0
        if (seg == 0 || per == 0) {
            tvRecTotalHint.text = "请填写：段数 1~6，每段 1~10 分钟"
            tvRecTotalHint.setTextColor(UiPrefs.dimColor(this))
            return
        }
        val cs = GuardSettingBounds.recordingSegments(seg)
        val cp = GuardSettingBounds.recordingSegmentMinutes(per)
        val clamped = cs != seg || cp != per
        tvRecTotalHint.text = if (clamped) {
            "超出范围：将按 $cs 段 × $cp 分钟 = ${cs * cp} 分钟保存"
        } else {
            "当前：$seg 段 × $per 分钟，约 ${seg * per} 分钟。弱网下单段越短越不容易上传失败。"
        }
        tvRecTotalHint.setTextColor(
            if (clamped) warnColor() else if (per < 8) okColor() else warnColor()
        )
    }

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    override fun onSaveRequested() {
        val seg = etRecSegments.text.toString().trim().toIntOrNull()
        val perMin = etRecSegmentMinutes.text.toString().trim().toIntOrNull()
        if (seg == null || perMin == null) {
            toast("有输入框为空或格式不正确，请检查")
            return
        }

        val clamped = listOf(
            "录音段数" to (seg to GuardSettingBounds.recordingSegments(seg)),
            "单段时长" to (perMin to GuardSettingBounds.recordingSegmentMinutes(perMin))
        ).filter { it.second.first != it.second.second }

        val note = if (clamped.isEmpty()) "" else {
            "已按范围收敛：" + clamped.joinToString("、") {
                "${it.first} ${it.second.first} → ${it.second.second}"
            }
        }

        save(
            JSONObject().apply {
                put("recordingAutoUpload", swAutoUpload.isChecked)
                put("recordingMaxSegments", GuardSettingBounds.recordingSegments(seg))
                put("recordingSegmentMinutes", GuardSettingBounds.recordingSegmentMinutes(perMin))
            },
            note = note
        )
    }
}
