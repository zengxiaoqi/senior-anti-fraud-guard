package com.antifraud.guard.family

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.antifraud.guard.FamilyHomeActivity
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 控制台：亲情绑定管理 + 守护概览 + AI 风险扫描
 * 对齐小程序 pages/dashboard/dashboard
 */
class DashboardFragment : Fragment() {

    private var lastFetch = 0L

    private lateinit var tvBindStatus: TextView
    private lateinit var etBindCode: EditText
    private lateinit var btnBind: Button
    private lateinit var btnUnbind: Button
    private lateinit var tvOverviewEvents: TextView
    private lateinit var tvOverviewLocation: TextView
    private lateinit var etScanText: EditText
    private lateinit var btnScan: Button
    private lateinit var tvScanResult: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvBindStatus       = view.findViewById(R.id.tv_bind_status)
        etBindCode         = view.findViewById(R.id.et_bind_code)
        btnBind            = view.findViewById(R.id.btn_bind)
        btnUnbind          = view.findViewById(R.id.btn_unbind)
        tvOverviewEvents   = view.findViewById(R.id.tv_overview_events)
        tvOverviewLocation = view.findViewById(R.id.tv_overview_location)
        etScanText         = view.findViewById(R.id.et_scan_text)
        btnScan            = view.findViewById(R.id.btn_scan)
        tvScanResult       = view.findViewById(R.id.tv_scan_result)

        view.findViewById<TextView>(R.id.btn_switch_role).setOnClickListener {
            (activity as? FamilyHomeActivity)?.switchRole()
        }

        btnBind.setOnClickListener { onBindTap() }
        btnUnbind.setOnClickListener { onUnbindTap() }
        btnScan.setOnClickListener { onScanTap() }
    }

    override fun onResume() {
        super.onResume()
        refreshBindStatus()
        fetchData()
    }

    private fun refreshBindStatus() {
        if (GuardConfig.isFamilyBound) {
            tvBindStatus.text = "✅ 已绑定守护对象：${GuardConfig.boundElderName}"
            tvBindStatus.setTextColor(0xFF10B981.toInt())
        } else {
            tvBindStatus.text = "⚠️ 尚未绑定老人，请输入老人端 6 位绑定码"
            tvBindStatus.setTextColor(0xFFF59E0B.toInt())
        }
    }

    /** 30s 节流（对齐小程序） */
    private fun fetchData() {
        if (!GuardConfig.isFamilyBound) return
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30_000) return
        lastFetch = now

        val elderId = GuardConfig.boundElderId
        ApiClient.familyGet("/api/events/list/$elderId?limit=3", onSuccess = { data ->
            tvOverviewEvents.text = formatRecentEvents(data)
        }, onError = { })

        ApiClient.familyGet("/api/events/location/$elderId", onSuccess = { data ->
            val first = data.optJSONArray("data")?.optJSONObject(0)
            if (first != null) {
                val addr = first.optString("address", "未知位置")
                val time = first.optString("created_at", "").replace("T", " ").take(16)
                tvOverviewLocation.text = "📍 最新位置：$addr（$time）"
            } else {
                tvOverviewLocation.text = "📍 最新位置：暂无上报"
            }
        }, onError = { })
    }

    private fun formatRecentEvents(arr: JSONObject): String {
        val list = arr.optJSONArray("data") ?: return "暂无风险动态"
        if (list.length() == 0) return "✅ 暂无风险动态，老人一切正常"
        val sb = StringBuilder("最近风险动态：")
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            sb.append("\n• ").append(eventName(e.optString("event_type")))
                .append("（").append(severityName(e.optString("severity"))).append("）")
        }
        return sb.toString()
    }

    private fun onBindTap() {
        val code = etBindCode.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(code)) {
            Toast.makeText(context, "绑定码为 6 位数字", Toast.LENGTH_SHORT).show()
            return
        }
        btnBind.isEnabled = false
        ApiClient.familyPost("/api/auth/bind", JSONObject().put("bindCode", code),
            onSuccess = { data ->
                btnBind.isEnabled = true
                etBindCode.setText("")
                val boundUser = data.optJSONObject("boundUser")
                GuardConfig.boundElderId = boundUser?.optInt("id", 0) ?: 0
                GuardConfig.boundElderName = boundUser?.optString("name", "守护对象") ?: "守护对象"
                lastFetch = 0
                refreshBindStatus()
                fetchData()
                Toast.makeText(context, "✅ 亲情绑定成功", Toast.LENGTH_SHORT).show()
            },
            onError = { err ->
                btnBind.isEnabled = true
                Toast.makeText(context, "绑定失败：$err", Toast.LENGTH_SHORT).show()
            })
    }

    private fun onUnbindTap() {
        AlertDialog.Builder(requireContext())
            .setTitle("解除绑定")
            .setMessage("确定要解除与老人的绑定吗？")
            .setPositiveButton("确定") { _, _ ->
                ApiClient.familyPost("/api/auth/unbind", null, onSuccess = {
                    GuardConfig.boundElderId = 0
                    GuardConfig.boundElderName = ""
                    lastFetch = 0
                    refreshBindStatus()
                    tvOverviewEvents.text = "绑定老人后展示最近风险动态"
                    tvOverviewLocation.text = "📍 最新位置：--"
                    Toast.makeText(context, "已解除绑定", Toast.LENGTH_SHORT).show()
                }, onError = { err ->
                    Toast.makeText(context, "解除失败：$err", Toast.LENGTH_SHORT).show()
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onScanTap() {
        val text = etScanText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(context, "请先粘贴可疑文案", Toast.LENGTH_SHORT).show()
            return
        }
        btnScan.isEnabled = false
        tvScanResult.visibility = View.VISIBLE
        tvScanResult.text = "扫描中..."
        ApiClient.familyPost("/api/ai/scan", JSONObject().put("textContent", text),
            onSuccess = { data ->
                btnScan.isEnabled = true
                val level = data.optString("risk_level", "UNKNOWN")
                val levelText = if (level == "HIGH") "🔴 高风险" else "🟢 未检出风险"
                tvScanResult.text = buildString {
                    append("扫描结果：$levelText（置信度 ${(data.optDouble("confidence", 0.0) * 100).toInt()}%）\n")
                    val kw = data.optJSONArray("matched_keywords") ?: JSONArray()
                    if (kw.length() > 0) {
                        append("命中关键词：")
                        for (i in 0 until kw.length()) append(kw.optString(i)).append(" ")
                        append("\n")
                    }
                    append(data.optString("analysis_report", "")).append("\n")
                    append("建议：").append(data.optString("suggested_action", ""))
                }
            },
            onError = { err ->
                btnScan.isEnabled = true
                tvScanResult.text = "扫描失败：$err"
            })
    }

    private fun eventName(type: String) = when (type) {
        "CALL_RISK" -> "📞 通话风险"
        "PAYMENT_RISK" -> "💳 大额扣款"
        "SOS" -> "🆘 紧急求助"
        "REMOTE_INTERRUPT" -> "🚨 远程打断"
        "LOCATION_RISK" -> "📍 敏感地点停留"
        "LOCATION_UPDATE" -> "📍 位置更新"
        "DEVICE_ONLINE" -> "🟢 设备上线"
        else -> type
    }

    private fun severityName(sev: String) = when (sev) {
        "HIGH" -> "高危"
        "MEDIUM" -> "中危"
        else -> "低危"
    }
}
