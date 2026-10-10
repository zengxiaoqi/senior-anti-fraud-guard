package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.antifraud.guard.FamilyHomeActivity
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.pickPhone
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
    private lateinit var tvGeofenceSummary: TextView

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
        tvGeofenceSummary  = view.findViewById(R.id.tv_geofence_summary)

        view.findViewById<TextView>(R.id.btn_switch_role).setOnClickListener {
            (activity as? FamilyHomeActivity)?.switchRole()
        }

        view.findViewById<TextView>(R.id.btn_logout).setOnClickListener {
            (activity as? FamilyHomeActivity)?.logout()
        }

        btnBind.setOnClickListener { onBindTap() }
        btnUnbind.setOnClickListener { onUnbindTap() }
        btnScan.setOnClickListener { onScanTap() }
        view.findViewById<Button>(R.id.btn_geofence).setOnClickListener {
            if (!GuardConfig.isFamilyBound) {
                Toast.makeText(context, "请先绑定老人", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GeofenceManageActivity.start(requireContext())
        }

        // 守护设置（家基准 + 阈值）：子女端是这些参数的唯一入口
        view.findViewById<Button>(R.id.btn_guard_settings).setOnClickListener {
            if (!GuardConfig.isFamilyBound) {
                Toast.makeText(context, "请先绑定老人", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(android.content.Intent(requireContext(), ElderGuardSettingsActivity::class.java))
        }

        // 「我的手机号」常驻卡片：换手机号后子女端必须能改回来。
        // 旧实现把按钮藏在 ll_mobile_missing 里，一旦填过号码整个容器 GONE，
        // 于是"改过之后反而再也改不了"—— 正好在最需要的时候没有入口。
        view.findViewById<Button>(R.id.btn_edit_mobile).setOnClickListener { showSetMobileDialog() }
        refreshMobileMissingBanner()
    }

    /**
     * 刷新「我的手机号」卡片。
     * 以登录态缓存为准：手机号是登录时由服务端下发的，用户改完立刻写回缓存。
     */
    private fun refreshMobileMissingBanner() {
        val missing = GuardConfig.familyMobileMissing ||
                (GuardConfig.familyMobile.isEmpty() && GuardConfig.familyUserId > 0)
        view?.findViewById<View>(R.id.ll_mobile_missing)?.visibility =
                if (missing) View.VISIBLE else View.GONE

        val mobile = GuardConfig.familyMobile
        val hasMobile = mobile.isNotEmpty()
        view?.findViewById<TextView>(R.id.tv_my_mobile_value)?.apply {
            text = if (hasMobile) mobile else "未填写"
            setTextColor(if (hasMobile) UiPrefs.textColor(requireContext()) else 0xFFF59E0B.toInt())
        }
        view?.findViewById<TextView>(R.id.tv_my_mobile_title)?.text =
                if (hasMobile) "我的手机号（紧急时拨给我）" else "我的手机号（未填写）"
        view?.findViewById<Button>(R.id.btn_edit_mobile)?.text =
                if (hasMobile) "修改手机号" else "填写手机号"
        view?.findViewById<TextView>(R.id.tv_my_mobile_hint)?.text =
                if (hasMobile) "换了手机号请点下方修改，否则老人端紧急警报会拨到旧号码。"
                else "老人端紧急警报会拨这个号码核实，未填写则无法一键拨打。"
    }

    /** 填写/修改手机号弹窗（全角数字自动转半角，避免中文输入法打出的全角号码被判格式错误） */
    private fun showSetMobileDialog() {
        val isUpdate = GuardConfig.familyMobile.isNotEmpty()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val etPhone = EditText(requireContext()).apply {
            hint = "11 位手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            if (isUpdate) setText(GuardConfig.familyMobile)
        }
        etPhone.filters = arrayOf(android.text.InputFilter.LengthFilter(13))
        container.addView(etPhone)

        AlertDialog.Builder(requireContext())
            .setTitle(if (isUpdate) "📱 修改手机号" else "📱 填写你的手机号")
            .setMessage(
                if (isUpdate)
                    "当前：${GuardConfig.familyMobile}\n\n换了新号码请在这里更新，否则老人端紧急警报会拨到已经用不了的旧号码。"
                else "老人端弹出紧急警报时，会用这个号码一键拨给你核实。请填真实手机号。"
            )
            .setView(container)
            .setCancelable(true)
            .setPositiveButton("保存") { _, _ ->
                val phone = normalizePhone(etPhone.text.toString())
                if (!Regex("^1[3-9]\\d{9}$").matches(phone)) {
                    Toast.makeText(context, "手机号格式不正确（当前 ${phone.length} 位，需 11 位）", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (phone == GuardConfig.familyMobile) {
                    Toast.makeText(context, "号码没有变化", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ApiClient.familyUpdateMobile(phone,
                    onSuccess = {
                        Toast.makeText(context, "✅ 手机号已保存，老人端紧急警报可一键拨给你", Toast.LENGTH_LONG).show()
                        refreshMobileMissingBanner()
                    },
                    onError = { err ->
                        Toast.makeText(context, "❌ 保存失败：$err", Toast.LENGTH_LONG).show()
                    })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 全角数字→半角，并丢弃空格/连字符 */
    private fun normalizePhone(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim()) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch in '0'..'9' -> sb.append(ch)
                ch == '-' || ch == '－' || ch == ' ' -> {}
            }
        }
        return sb.toString()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshBindStatus()
        fetchData()
        loadElderPhone()
        // 围栏摘要不走30s 节流：每次回到控制台都要反映最新（从二级页增删围栏后返回也立刻生效）
        fetchGeofenceSummary()
    }

    /** 绑定成功后在状态栏补充展示老人手机号（wx_ 开头的占位号视为未登记） */
    private var elderPhoneLoaded = false
    private fun loadElderPhone() {
        if (elderPhoneLoaded || !GuardConfig.isFamilyBound) return
        ApiClient.familyGet("/api/auth/user/${GuardConfig.boundElderId}", onSuccess = { res ->
            val user = res.optJSONObject("data") ?: JSONObject()
            // 统一走 pickPhone：optString 遇JSON null 会返回字符串 "null"
            val phone = user.pickPhone(username = GuardConfig.familyUsername)
            val suffix = if (phone.isNotEmpty()) "（$phone）" else "（未登记手机号）"
            tvBindStatus.text = "✅ 已绑定守护对象：${GuardConfig.boundElderName}$suffix"
            elderPhoneLoaded = true
        }, onError = { })
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
                val time = first.optString("created_at", "").replace("T", " ").take(16)
                tvOverviewLocation.text = buildString {
                    append("📍 最新位置：")
                    append(formatPlaceName(first))
                    append("（")
                    append(time)
                    append("）")
                }
            } else {
                tvOverviewLocation.text = "📍 最新位置：暂无上报"
            }
        }, onError = { })
    }

/**
 * 把地点对象格式成可读文案。
     *
     * 服务端已做多层兜底：地图地名 → 附近用户命名地点 → 停留热点（"常去地点①"）→ 坐标。
     * 所以这里绝大多数情况拿到的是真实地名或"XX附近"，只有实在没有任何信息时
     * 才显示坐标 —— 且**不叫"未识别到地名"**，那句话看起来像功能坏了，
     * 实际只是"这附近没名字可查"，措辞必须区分开。
     */
    private fun formatPlaceName(o: JSONObject): String {
        val addr = o.optString("address", "").trim()
        val lat = o.optDouble("latitude", 0.0)
        val lng = o.optDouble("longitude", 0.0)
        val coord = if (lat != 0.0 && lng != 0.0) "%.4f, %.4f".format(lat, lng) else ""

        val isCoordOnly = addr.isEmpty() ||
                addr.startsWith("GPS 位置") ||
                addr.startsWith("坐标") ||
                addr == "未知位置"

        return if (isCoordOnly) {
            // 没有地名但有坐标：说明是"查不到名字"，不是"没定位到"
            if (coord.isEmpty()) "定位待确认" else "已定位 · $coord"
        } else {
            if (coord.isNotEmpty() && !addr.contains(coord) && addr.length < 18) "$addr · $coord" else addr
        }
    }

    /**
     * 读取已登记的敏感地点，直接显示在控制台卡片里。
     *
     * 为什么要内嵌：原先这里只有一个「配置敏感地点围栏」按钮，跳转二级页才能看到列表。
     * 于是产生一种"配置好像没了"的错觉——尤其是围栏名被截断成坐标串时，用户在控制台
     * 完全看不到任何"我已经配过地点"的痕迹。绑定/解绑后也必须刷新，否则状态是旧的。
     */
    private fun fetchGeofenceSummary() {
        if (!GuardConfig.isFamilyBound) {
            tvGeofenceSummary.text = "尚未绑定守护对象，绑定后即可登记敏感地点"
            return
        }
        ApiClient.familyGet("/api/geofence/list/${GuardConfig.boundElderId}",
            onSuccess = { res ->
                val arr = res.optJSONArray("data")
                val n = arr?.length() ?: 0
                if (n == 0) {
                    tvGeofenceSummary.text =
                        "尚未登记敏感地点。点下方按钮添加后，老人进入该范围会自动开启环境录音存证。"
                    return@familyGet
                }
                val enabledCount = (0 until n).count { i ->
                    arr!!.optJSONObject(i)?.optInt("enabled", 1) == 1
                }
                val sb = StringBuilder()
                sb.append("已登记 $n 个敏感地点，其中 $enabledCount 个生效中\n")
                for (i in 0 until n) {
                    val f = arr!!.optJSONObject(i) ?: continue
                    val on = f.optInt("enabled", 1) == 1
                    sb.append("· ").append(f.optString("name", "未命名地点"))
                        .append("（半径 ").append(f.optInt("radius", 200)).append(" 米")
                        .append(if (on) "）" else " · 已停用）")
                }
                tvGeofenceSummary.text = sb.toString()
            },
            onError = { err ->
                tvGeofenceSummary.text = "敏感地点读取失败：$err\n点下方按钮可进入管理页查看"
            })
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
                elderPhoneLoaded = false
                refreshBindStatus()
                fetchData()
                loadElderPhone()
                fetchGeofenceSummary()
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
                    elderPhoneLoaded = false
                    refreshBindStatus()
                    tvOverviewEvents.text = "绑定老人后展示最近风险动态"
                    tvOverviewLocation.text = "📍 最新位置：--"
                    fetchGeofenceSummary()
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
            onSuccess = { res ->
                // 响应改为完整 JSON：扫描结果在 data 节点
                val data = res.optJSONObject("data") ?: JSONObject()
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
        "COERCION_RISK" -> "🚨 通话中被诱导操作"
        "CALL_STAT" -> "📞 通话记录"
        "GEOFENCE_DWELL" -> "⏱ 敏感地点停留过久"
        "PAYMENT_RISK" -> "💳 大额扣款"
        "SOS" -> "🆘 紧急求助"
        "REMOTE_INTERRUPT" -> "🚨 远程打断"
        "LOCATION_RISK" -> "📍 敏感地点停留"
        "GEOFENCE_RECORDING" -> "📍 进入敏感地点（自动录音）"
        "GEOFENCE_EXIT" -> "📍 离开敏感地点"
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
