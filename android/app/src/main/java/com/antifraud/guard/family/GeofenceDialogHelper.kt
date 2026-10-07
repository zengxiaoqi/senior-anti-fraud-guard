package com.antifraud.guard.family

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.antifraud.guard.api.ApiClient
import org.json.JSONObject

/**
 * 「设为敏感地点」弹窗：输入名称 + 选触发半径 → POST /api/geofence/add
 * 供轨迹页（点明细条目 / 地图长按）复用。
 *
 * @param wgsLat 入库坐标必须是 WGS-84（服务端与老人端 GPS 一致）
 */
object GeofenceDialogHelper {

    private val RADIUS_OPTIONS = intArrayOf(100, 200, 500, 1000)

    fun show(context: Context, wgsLat: Double, wgsLng: Double, defaultName: String = "", onDone: (() -> Unit)? = null) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val etName = EditText(context).apply {
            hint = "地点名称（如：XX 养生讲座馆）"
            setText(defaultName)
            setSingleLine(true)
        }
        container.addView(etName)

        val rgRadius = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        RADIUS_OPTIONS.forEachIndexed { i, r ->
            rgRadius.addView(RadioButton(context).apply {
                text = "半径 ${r} 米"
                id = i + 1
                isChecked = (r == 200)
                textSize = 14f
            })
        }
        container.addView(rgRadius)

        AlertDialog.Builder(context)
            .setTitle("📍 设为敏感地点围栏")
            .setMessage(String.format("坐标：%.6f, %.6f\n老人进入该范围将自动录音存证并推送高危告警。", wgsLat, wgsLng))
            .setView(container)
            .setPositiveButton("登记围栏") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, "名称为空，未登记", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val radius = RADIUS_OPTIONS[rgRadius.checkedRadioButtonId - 1]
                ApiClient.familyPost("/api/geofence/add",
                    JSONObject()
                        .put("name", name)
                        .put("latitude", wgsLat)
                        .put("longitude", wgsLng)
                        .put("radius", radius),
                    onSuccess = {
                        Toast.makeText(context, "✅ 已登记「$name」，老人进入将自动录音上报", Toast.LENGTH_LONG).show()
                        onDone?.invoke()
                    },
                    onError = { err ->
                        Toast.makeText(context, "登记失败：$err", Toast.LENGTH_SHORT).show()
                    })
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
