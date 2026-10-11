package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.antifraud.guard.R
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.PresetChip
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 子女端「守护设置 › 位置守护阈值」。
 *
 * 只提交 `homeAwayRadiusMeters / stayMoveMeters / homeStayMinutes` 三个字段：
 * 服务端是增量合并（`mergeFamilySettings`），不会冲掉通话/支付/录音那些项。
 *
 * ## 档位在前、数字框在后
 * 要求守护人理解「停留判定半径」是什么并不现实，所以先给三档，
 * 数字框只做微调兜底。档位高亮跟随输入框的实际值：
 * 手动改到和任何一档都不一致时不高亮任何档 —— 不假装"现在还是标准档"。
 */
class LocationRuleActivity : GuardRuleSubActivity() {

    private lateinit var etAwayRadius: EditText
    private lateinit var etStayMove: EditText
    private lateinit var etStayMinutes: EditText
    private lateinit var tvHint: TextView

    /** 位置档位：离家半径 / 停留判定半径 / 停留时长，取值全部在合法区间内 */
    private val presets = mapOf(
        R.id.chip_loc_relaxed to Triple(2000, 500, 60),
        R.id.chip_loc_standard to Triple(
            GuardSettingBounds.AWAY_RADIUS_DEFAULT,
            GuardSettingBounds.STAY_MOVE_DEFAULT,
            GuardSettingBounds.STAY_MINUTES_DEFAULT
        ),
        R.id.chip_loc_strict to Triple(200, 50, 15)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_rule)

        findViewById<TextView>(R.id.tv_title).text = "位置守护阈值"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        etAwayRadius = findViewById(R.id.et_away_radius)
        etStayMove = findViewById(R.id.et_stay_move)
        etStayMinutes = findViewById(R.id.et_stay_minutes)
        tvHint = findViewById(R.id.tv_loc_hint)

        setupChips()
        listOf(etAwayRadius, etStayMove, etStayMinutes).forEach { et ->
            et.addTextChangedListener(dirtyWatcher { renderHint() })
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSaveRequested() }

        reload()
    }

    override fun onSettings(s: JSONObject?) {
        filling = true
        // 服务端没配过的字段显示空：用户看得见"这项还没设过"，
        // 而不是看到一个看起来很正常的 500 然后以为那就是实际生效值
        etAwayRadius.setText(s.optPositiveInt("homeAwayRadiusMeters"))
        etStayMove.setText(s.optPositiveInt("stayMoveMeters"))
        etStayMinutes.setText(s.optPositiveInt("homeStayMinutes"))
        filling = false
        renderHint()
        refreshPresetHighlights()
    }

    // ──────────────────────────────────────────
    //  档位
    // ──────────────────────────────────────────

    private fun setupChips() {
        presets.forEach { (id, p) ->
            findViewById<TextView>(id).setOnClickListener {
                filling = true
                etAwayRadius.setText(p.first.toString())
                etStayMove.setText(p.second.toString())
                etStayMinutes.setText(p.third.toString())
                filling = false
                dirty = true
                renderHint()
                refreshPresetHighlights()
            }
        }
    }

    private fun refreshPresetHighlights() {
        val away = etAwayRadius.text.toString().trim().toIntOrNull()
        val stayMove = etStayMove.text.toString().trim().toIntOrNull()
        val stayMin = etStayMinutes.text.toString().trim().toIntOrNull()
        presets.forEach { (id, p) ->
            PresetChip.paint(
                findViewById(id),
                away == p.first && stayMove == p.second && stayMin == p.third
            )
        }
    }

    // ──────────────────────────────────────────
    //  实时换算
    // ──────────────────────────────────────────

    /** 把三个裸数字翻译成一句人话，填错当场就能看出 */
    private fun renderHint() {
        val away = etAwayRadius.text.toString().trim().toIntOrNull()
        val stayMove = etStayMove.text.toString().trim().toIntOrNull()
        val stayMin = etStayMinutes.text.toString().trim().toIntOrNull()
        if (away == null || stayMove == null || stayMin == null) {
            tvHint.text = "三项都要填：离家半径 100~5000 米、停留半径 20~1000 米、停留时长 5~240 分钟"
            tvHint.setTextColor(UiPrefs.dimColor(this))
            return
        }
        val cAway = GuardSettingBounds.awayRadius(away)
        val cMove = GuardSettingBounds.stayMoveMeters(stayMove)
        val cMin = GuardSettingBounds.stayMinutes(stayMin)
        val clamped = cAway != away || cMove != stayMove || cMin != stayMin
        tvHint.text = if (clamped) {
            "超出范围，将按：离家超过 $cAway 米 · 停留半径 $cMove 米 · 超过 $cMin 分钟"
        } else {
            "当前：离家超过 $cAway 米告警；在同一地点（半径 $cMove 米内）待超过 $cMin 分钟告警"
        }
        tvHint.setTextColor(if (clamped) warnColor() else UiPrefs.dimColor(this))
    }

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    override fun onSaveRequested() {
        val away = etAwayRadius.text.toString().trim().toIntOrNull()
        val stayMove = etStayMove.text.toString().trim().toIntOrNull()
        val stayMin = etStayMinutes.text.toString().trim().toIntOrNull()
        if (away == null || stayMove == null || stayMin == null) {
            toast("有输入框为空或格式不正确，请检查")
            return
        }

        // 收敛必须说得出口：静默 clamp 会让人以为设了 6000、实际按 5000 守护，
        // 然后以为系统有 bug
        val clamped = listOf(
            "离家半径" to (away to GuardSettingBounds.awayRadius(away)),
            "停留判定半径" to (stayMove to GuardSettingBounds.stayMoveMeters(stayMove)),
            "停留时长" to (stayMin to GuardSettingBounds.stayMinutes(stayMin))
        ).filter { it.second.first != it.second.second }

        val note = if (clamped.isEmpty()) "" else {
            "已按范围收敛：" + clamped.joinToString("、") {
                "${it.first} ${it.second.first} → ${it.second.second}"
            }
        }

        save(
            JSONObject().apply {
                put("homeAwayRadiusMeters", GuardSettingBounds.awayRadius(away))
                put("stayMoveMeters", GuardSettingBounds.stayMoveMeters(stayMove))
                put("homeStayMinutes", GuardSettingBounds.stayMinutes(stayMin))
            },
            note = note
        )
    }
}
