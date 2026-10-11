package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.antifraud.guard.R
import com.antifraud.guard.util.PresetChip
import org.json.JSONObject

/**
 * 子女端「守护设置 › 跌倒检测」。
 *
 * 只提交 `fallFreefallMinMs` 一个字段（判定灵敏度）。
 *
 * ## 为什么是三档预设而不是数字输入框
 * 这个参数的本质是「掉落多高才算摔」（h=½gt² 换算），子女理解不了 150ms 是什么，
 * 但理解得了"我妈习惯把手机揣裤兜"。三档各配一句「适合谁」让人对号入座；
 * 真要精调时服务端 API 仍接受 100~400 任意值，UI 只是不暴露而已。
 *
 * ## 档位与值的映射是本文件唯一事实源
 * 老人端 GuardConfig.fallFreefallMinMs 的注释、服务端 bounds(100~400) 都引用这套语义；
 * 改档位值时三处要一起改。默认档是「标准」（150，与 FallDetectionPolicy.FREEFALL_MIN_MS 一致）。
 */
class FallRuleActivity : GuardRuleSubActivity() {

    private companion object {
        /** 三档：灵敏 120（≈7cm）/ 标准 150（≈11cm，默认）/ 保守 250（≈30cm） */
        val LEVELS = listOf(
            Level("灵敏", 120, "最容易检出摔倒（手机揣裤兜、滑兜式跌倒也接得住），代价是轻放手机更容易误报"),
            Level("标准", 150, "推荐。手机从 11 厘米以上掉落才算摔，玩手机放下、随手轻放都不会触发"),
            Level("保守", 250, "几乎零误报（30 厘米以上掉落才算摔），适合老人习惯把手机放手边/桌上的情况")
        )
        /** 服务端未配置时 UI 默认落在哪一档（= 服务端 bounds 的 def，也是检测器编译期默认） */
        const val DEFAULT_MS = 150
    }

    private data class Level(val label: String, val ms: Int, val desc: String)

    private lateinit var chips: List<TextView>
    private lateinit var tvHint: TextView

    /** 当前选中的失重时长下限（毫秒） */
    private var selectedMs: Int = DEFAULT_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fall_rule)

        findViewById<TextView>(R.id.tv_title).text = "跌倒检测"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        chips = listOf(
            findViewById(R.id.chip_fall_fast),
            findViewById(R.id.chip_fall_standard),
            findViewById(R.id.chip_fall_conservative)
        )
        tvHint = findViewById(R.id.tv_fall_hint)

        LEVELS.forEachIndexed { i, _ ->
            chips[i].setOnClickListener {
                if (!filling) dirty = true
                selectedMs = LEVELS[i].ms
                renderSelection()
            }
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSaveRequested() }

        reload()
    }

    override fun onSettings(s: JSONObject?) {
        filling = true
        // 服务端没配过 → 落默认档；显示出来让人知道"现在实际按什么在判定"
        selectedMs = s?.optInt("fallFreefallMinMs", -1)?.takeIf { it in 100..400 } ?: DEFAULT_MS
        filling = false
        renderSelection()
    }

    /** 选中态 + 该档位的"适合谁"说明，一次渲染完 */
    private fun renderSelection() {
        val idx = LEVELS.indexOfFirst { it.ms == selectedMs }.takeIf { it >= 0 } ?: 1
        LEVELS.forEachIndexed { i, _ -> PresetChip.paint(chips[i], i == idx) }
        val level = LEVELS[idx]
        tvHint.text = "${level.label}：${level.desc}"
        tvHint.setTextColor(if (idx == 1) okColor() else warnColor())
    }

    override fun onSaveRequested() {
        save(JSONObject().apply { put("fallFreefallMinMs", selectedMs) })
    }
}
