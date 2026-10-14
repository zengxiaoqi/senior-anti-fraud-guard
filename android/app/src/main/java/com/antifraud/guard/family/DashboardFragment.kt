package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

import android.content.Intent
import android.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.antifraud.guard.AiScanActivity
import com.antifraud.guard.AppSettingsActivity
import com.antifraud.guard.FamilyHomeActivity
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.EventLabels
import com.antifraud.guard.util.pickPhone
import org.json.JSONArray
import org.json.JSONObject

/**
 * 控制台（2026-10-10 改版）：Hero 老人状态卡 + 风险动态聚合 + 敏感地点 + AI 扫描 + 账号与绑定
 * 对齐小程序 pages/dashboard/dashboard；设计稿见 docs/ui-audit-family-dashboard.html
 *
 * 改版要点（相对旧版）：
 *  - 首屏从"设置项清单"变成"老人此刻状态"：Hero 卡回答"安全吗 / 在哪 / 最近有什么事"。
 *  - 风险动态走 EventLabels.formatOverview 去重聚合，不再把 APP_CRASH 枚举名/
 *    重复两条"位置更新"原样甩给用户。
 *  - 手机号大卡片降级为"账号与绑定"聚合卡里的一行（整行点击），警示横幅条件化。
 *  - 绑定三态（未绑/已绑/换绑）分开渲染：已绑定时隐藏绑定码输入，"解除"降级为行内
 *    文字链接（保留二次确认）。
 *  - 退出登录/切换角色从红色顶栏移到页面底部低调文字按钮。
 */
class DashboardFragment : Fragment() {

    private var lastFetch = 0L

    private lateinit var tvBindStatus: TextView
    private lateinit var llBindInput: LinearLayout
    private lateinit var etBindCode: EditText
    private lateinit var btnBind: Button
    private lateinit var btnUnbind: TextView
    private lateinit var tvOverviewEvents: TextView
    private lateinit var tvOverviewLocation: TextView
    private lateinit var tvGeofenceSummary: TextView

    // Hero 状态卡
    private lateinit var tvHeroAvatar: TextView
    private lateinit var tvHeroName: TextView
    private lateinit var tvHeroSub: TextView
    private lateinit var tvHeroStatus: TextView
    private lateinit var tvHeroLocation: TextView
    private lateinit var tvHeroRisk: TextView
    private lateinit var tvHeroLastSeen: TextView
    // 失联/静默横幅（默认隐藏，仅服务端判定异常时出现）
    private lateinit var llLivenessBanner: View
    private lateinit var tvLivenessTitle: TextView
    private lateinit var tvLivenessBody: TextView
    private lateinit var btnLivenessCall: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvBindStatus       = view.findViewById(R.id.tv_bind_status)
        llBindInput        = view.findViewById(R.id.ll_bind_input)
        etBindCode         = view.findViewById(R.id.et_bind_code)
        btnBind            = view.findViewById(R.id.btn_bind)
        btnUnbind          = view.findViewById(R.id.btn_unbind)
        tvOverviewEvents   = view.findViewById(R.id.tv_overview_events)
        tvOverviewLocation = view.findViewById(R.id.tv_overview_location)
        tvGeofenceSummary  = view.findViewById(R.id.tv_geofence_summary)
        tvHeroAvatar       = view.findViewById(R.id.tv_hero_avatar)
        tvHeroName         = view.findViewById(R.id.tv_hero_name)
        tvHeroSub          = view.findViewById(R.id.tv_hero_sub)
        tvHeroStatus       = view.findViewById(R.id.tv_hero_status)
        tvHeroLocation     = view.findViewById(R.id.tv_hero_location)
        tvHeroRisk         = view.findViewById(R.id.tv_hero_risk)
        tvHeroLastSeen     = view.findViewById(R.id.tv_hero_last_seen)
        llLivenessBanner   = view.findViewById(R.id.ll_liveness_banner)
        tvLivenessTitle    = view.findViewById(R.id.tv_liveness_title)
        tvLivenessBody     = view.findViewById(R.id.tv_liveness_body)
        btnLivenessCall    = view.findViewById(R.id.btn_liveness_call)

        // 标题行右端 ⚙️：版本升级 / 字体主题 / 账号 / 后端连接。
        // 这个入口以前是一条 56dp 常驻顶栏、五个 tab 共用；现在只在控制台页出现，
        // 跟着内容滚走，把首屏还给"老人此刻怎么样"。
        view.findViewById<android.widget.Button>(R.id.btn_app_settings).setOnClickListener {
            startActivity(Intent(requireContext(), AppSettingsActivity::class.java))
        }

        view.findViewById<TextView>(R.id.btn_switch_role).setOnClickListener {
            (activity as? FamilyHomeActivity)?.switchRole()
        }

        view.findViewById<TextView>(R.id.btn_logout).setOnClickListener {
            (activity as? FamilyHomeActivity)?.logout()
        }

        // 风险动态"查看全部"：切到防诈告警 Tab（保活式切换，见 FamilyHomeActivity.openAlertsTab）
        view.findViewById<TextView>(R.id.btn_view_alerts).setOnClickListener {
            (activity as? FamilyHomeActivity)?.openAlertsTab()
        }

        btnBind.setOnClickListener { onBindTap() }
        btnUnbind.setOnClickListener { onUnbindTap() }
        // AI 识诈：与老人端复用同一个 AiScanActivity（拍照/相册/OCR/结构化结论一套实现），
        // 2026-10-12 起不再内嵌旧版纯文本扫描表单
        view.findViewById<Button>(R.id.btn_ai_scan_entry).setOnClickListener {
            startActivity(Intent(requireContext(), AiScanActivity::class.java))
        }
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

        // 「紧急联络手机号」聚合行：整行点击 = 旧版 btn_edit_mobile 按钮。
        // 换手机号后子女端必须能改回来，所以常驻可见；但不再用全宽大按钮占首屏。
        view.findViewById<View>(R.id.ll_my_mobile).setOnClickListener { showSetMobileDialog() }
        refreshMobileMissingBanner()
    }

    /**
     * 刷新「紧急联络手机号」行。
     * 以登录态缓存为准：手机号是登录时由服务端下发的，用户改完立刻写回缓存。
     * 警示横幅条件化：只在未填写时出现——已填写时旧版还常驻"换号会拨到旧号码"
     * 的红色警示，属于反复施压（真实风险场景是"未填"，不是"已填"）。
     */
    private fun refreshMobileMissingBanner() {
        val missing = GuardConfig.familyMobileMissing ||
                (GuardConfig.familyMobile.isEmpty() && GuardConfig.familyUserId > 0)
        view?.findViewById<View>(R.id.ll_mobile_missing)?.visibility =
                if (missing) View.VISIBLE else View.GONE

        val mobile = GuardConfig.familyMobile
        val hasMobile = mobile.isNotEmpty()
        view?.findViewById<TextView>(R.id.tv_my_mobile_value)?.apply {
            text = if (hasMobile) maskMobile(mobile) else "未填写"
            setTextColor(if (hasMobile) UiPrefs.textColor(requireContext()) else ContextCompat.getColor(requireContext(), R.color.c_accent_amber))
        }
        view?.findViewById<TextView>(R.id.tv_my_mobile_title)?.text =
                if (hasMobile) "紧急联络手机号" else "紧急联络手机号（未填写）"
        view?.findViewById<TextView>(R.id.tv_my_mobile_hint)?.text =
                if (hasMobile) "点击修改 · 老人端紧急警报将拨打此号"
                else "点击填写 · 老人端紧急警报将拨打此号"
    }

    /** 手机号脱敏：132****0011（聚合行空间小，全号不展示，弹窗里可见全号） */
    private fun maskMobile(m: String): String {
        val clean = m.trim()
        return if (clean.length == 11) clean.take(3) + "****" + clean.takeLast(4) else clean
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

    /** 绑定成功后在状态行补充展示老人手机号（wx_ 开头的占位号视为未登记） */
    private var elderPhoneLoaded = false
    private fun loadElderPhone() {
        if (elderPhoneLoaded || !GuardConfig.isFamilyBound) return
        ApiClient.familyGet("/api/auth/user/${GuardConfig.boundElderId}", onSuccess = { res ->
            val user = res.optJSONObject("data") ?: JSONObject()
            // 统一走 pickPhone：optString 遇JSON null 会返回字符串 "null"
            val phone = user.pickPhone(username = GuardConfig.familyUsername)
            // 完整号码进缓存：失联横幅的「立即拨打」拨的是老人，只存脱敏展示号会拨不出去
            GuardConfig.boundElderPhone = phone
            val masked = if (phone.isNotEmpty()) maskMobile(phone) else "未登记手机号"
            tvBindStatus.text = "已绑定 · ${GuardConfig.boundElderName}（$masked）"
            tvBindStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.c_accent_green))
            tvHeroSub.text = "守护对象 · 手机号 $masked"
            elderPhoneLoaded = true
            // 本请求与 fetchLiveness 并行发起，谁先回来不一定：
            // 横幅已经显示过的话，用刚拿到的完整号码把按钮刷新成可拨号
            if (llLivenessBanner.visibility == View.VISIBLE) updateLivenessCallButton()
        }, onError = { })
    }

    /**
     * 绑定三态渲染：未绑（展开输入区）/ 已绑（收起 + 行内解除链接）。
     * 旧版三态共用一套 UI，已绑定时绑定码输入框仍然显示，用户会困惑"再输一次会怎样"。
     */
    private fun refreshBindStatus() {
        if (GuardConfig.isFamilyBound) {
            tvBindStatus.text = "已绑定 · ${GuardConfig.boundElderName}"
            tvBindStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.c_accent_green))
            llBindInput.visibility = View.GONE
            btnUnbind.visibility = View.VISIBLE
            tvHeroAvatar.text = "👴"
            tvHeroName.text = GuardConfig.boundElderName
            tvHeroSub.text = "守护对象"
            if (tvHeroStatus.text == "待绑定") setHeroPill(ok = true, text = "检测中…")
        } else {
            tvBindStatus.text = "尚未绑定，请输入老人端 6 位绑定码"
            tvBindStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.c_accent_amber))
            llBindInput.visibility = View.VISIBLE
            btnUnbind.visibility = View.GONE
            tvHeroAvatar.text = "👤"
            tvHeroName.text = "未绑定守护对象"
            tvHeroSub.text = "绑定后自动展示老人状态"
            setHeroPill(ok = false, text = "待绑定")
            tvHeroLocation.text = "--"
            tvHeroRisk.text = "--"
        }
    }

    /** Hero 状态徽章：ok=true 绿色"一切正常"，false 琥珀色警示（文案由调用方给） */
    private fun setHeroPill(ok: Boolean, text: String) {
        tvHeroStatus.text = text
        tvHeroStatus.setTextColor(
            if (ok) ContextCompat.getColor(requireContext(), R.color.c_accent_green)
            else ContextCompat.getColor(requireContext(), R.color.c_accent_amber)
        )
        tvHeroStatus.setBackgroundResource(if (ok) R.drawable.bg_pill_ok else R.drawable.bg_pill_alert)
    }

    /** 30s 节流（对齐小程序）。拉 20 条供计数与聚合（旧版 3 条既看不出趋势也不够聚合）。 */
    private fun fetchData() {
        if (!GuardConfig.isFamilyBound) return
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30_000) return
        lastFetch = now

        val elderId = GuardConfig.boundElderId
        ApiClient.familyGet("/api/events/list/$elderId?limit=20", onSuccess = { data ->
            val list = data.optJSONArray("data")
            tvOverviewEvents.text = EventLabels.formatOverview(list)
            renderHeroRisk(list)
        }, onError = { })

        ApiClient.familyGet("/api/events/location/$elderId", onSuccess = { data ->
            val first = data.optJSONArray("data")?.optJSONObject(0)
            if (first != null) {
                val time = EventLabels.shortLocalTime(first.optString("created_at", ""))
                val place = formatPlaceName(first)
                val locText = if (time.isNotEmpty()) "$place · $time" else place
                tvHeroLocation.text = locText
                tvOverviewLocation.text = "📍 最新位置：$locText"
            } else {
                tvHeroLocation.text = "暂无上报"
                tvOverviewLocation.text = "📍 最新位置：暂无上报"
            }
        }, onError = { })

        fetchLiveness(elderId)
    }

    /**
     * 拉取守护在线状态并渲染横幅。
     *
     * ## 为什么必须独立请求，不能从事件列表推算
     * "3 小时前上报过一次位置"和"从来没上报过"在事件列表里长得几乎一样
     * （一条 vs 空），但语义完全相反。而且阈值是服务端配置
     * （guard_settings.liveness），客户端不该各自硬编码一份 ——
     * 复制一份的结果是"子女端看到的失联时间和服务端判的不是一回事"。
     *
     * 不走 30s 节流：失联是安全信息，多拉一次几乎没有成本，
     * 而节流会让"刚发生失联时切回控制台"看到旧状态。
     */
    private fun fetchLiveness(elderId: Int) {
        ApiClient.familyGet("/api/events/status/$elderId", onSuccess = { res ->
            val d = res.optJSONObject("data") ?: JSONObject()
            val state = d.optString("state", "ONLINE")
            val minutes = d.optInt("minutes_since_report", -1)

            // "最后上报"独立成行：位置可能是很久以前的家门口，
            // 光看位置看不出"从那时起就联系不上了"
            val lastSeen = d.optString("last_report_at", "")
            tvHeroLastSeen.text = if (lastSeen.isEmpty()) {
                "最后上报：暂无记录"
            } else {
                "最后上报：${EventLabels.shortLocalTime(lastSeen).ifEmpty { lastSeen.take(16).replace('T', ' ') }}"
            }

            renderLivenessBanner(state, minutes, d.optInt("offline_minutes", 240))
        }, onError = {
            // 状态拉取失败时**不要**显示告警横幅：
            // "网络没连上"与"老人失联"是两件事，把前者画成后者
            // 会训练用户忽略这个横幅 —— 而它出现时是真的出事。
            llLivenessBanner.visibility = View.GONE
        })
    }

    private fun renderLivenessBanner(state: String, minutes: Int, offlineMinutes: Int) {
        if (state != "SILENT" && state != "OFFLINE") {
            llLivenessBanner.visibility = View.GONE
            return
        }
        val offline = state == "OFFLINE"
        llLivenessBanner.visibility = View.VISIBLE

        tvLivenessTitle.text = if (offline) "📴 老人已失联" else "🔕 长时间没有上报"
        tvLivenessTitle.setTextColor(
            if (offline) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B")
        )

        val human = humanMinutes(minutes)
        tvLivenessBody.text = if (offline) {
            "$human 完全没有上报任何数据。\n可能是手机没电、被摔坏，或老人无法操作手机。\n请立即电话联系，打不通请尽快上门查看。"
        } else {
            "$human 没有任何上报数据。\n可能只是静音、没电或在休息，建议先打个电话问问。\n超过 ${humanMinutes(offlineMinutes)}会被判定为失联。"
        }

        // 横幅文案是"请立即电话联系"——失联时唯一有效的动作是人打给老人，
        // 所以按钮拨老人的号码。familyMobile 是子女自己的号（给老人端紧急警报
        // 回拨子女用），方向相反；拨它等于让子女对着自己的手机空等。
        btnLivenessCall.visibility = View.VISIBLE
        updateLivenessCallButton()
    }

    /**
     * 失联横幅拨号按钮：拨**老人**的手机号。
     *
     * 号码来源见 [loadElderPhone]（登录时也会缓存一份到 GuardConfig.boundElderPhone，
     * 横幅弹出时通常已有缓存，无需等本轮请求）。
     * 老人未登记号码时不给一个点不动的死按钮，改为明示"无法一键拨打"并建议上门。
     */
    private fun updateLivenessCallButton() {
        val phone = GuardConfig.boundElderPhone
        if (phone.isNotEmpty()) {
            val who = GuardConfig.boundElderName.ifEmpty { "老人" }
            btnLivenessCall.text = "📞 立即拨打${who}的手机"
            btnLivenessCall.setOnClickListener { dialPhone(phone) }
        } else {
            btnLivenessCall.text = "老人未登记手机号 · 无法一键拨打"
            btnLivenessCall.setOnClickListener {
                Toast.makeText(context, "老人未登记手机号，请直接联系或尽快上门查看", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun humanMinutes(m: Int): String {
        if (m < 0) return "长时间"
        if (m < 60) return "$m 分钟"
        val h = m / 60
        val rest = m % 60
        return if (rest > 0) "$h 小时 $rest 分钟" else "$h 小时"
    }

    /** 打开拨号盘（ACTION_DIAL：不需要 CALL_PHONE 权限，也不做静默拨号） */
    private fun dialPhone(mobile: String) {
        try {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_DIAL)
                    .apply { data = android.net.Uri.parse("tel:$mobile") }
            )
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开拨号盘：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Hero"最近动态"计数 + 状态徽章：只统计真正值得留意的（中危及以上） */
    private fun renderHeroRisk(list: JSONArray?) {
        if (list == null || list.length() == 0) {
            tvHeroRisk.text = "无异常"
            setHeroPill(ok = true, text = "● 一切正常")
            return
        }
        var attention = 0
        var high = 0
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val type = e.optString("event_type", "")
            // 心跳噪音不计入（与 EventLabels.formatOverview 口径一致）
            if (type == "PING_TEST" || type == "DEVICE_ONLINE") continue
            when (e.optString("severity", "")) {
                "HIGH" -> { attention++; high++ }
                "MEDIUM" -> attention++
            }
        }
        if (attention == 0) {
            tvHeroRisk.text = "无异常"
            setHeroPill(ok = true, text = "● 一切正常")
        } else {
            tvHeroRisk.text = "${attention} 条需留意"
            setHeroPill(ok = false, text = if (high > 0) "⚠️ $high 条高危" else "⚠️ $attention 条需留意")
        }
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
                // 换绑必须同步换号码缓存：否则失联横幅/告警弹窗还会拨上一个老人的号
                GuardConfig.boundElderPhone =
                    boundUser?.pickPhone(username = GuardConfig.familyUsername) ?: ""
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
            .setMessage("确定要解除与老人的绑定吗？解除后你将不再收到老人的风险告警与轨迹。")
            .setPositiveButton("确定") { _, _ ->
                ApiClient.familyPost("/api/auth/unbind", null, onSuccess = {
                    GuardConfig.boundElderId = 0
                    GuardConfig.boundElderName = ""
                    // 号码缓存一并清掉：留着旧老人的号，换绑前任何告警都会拨错人
                    GuardConfig.boundElderPhone = ""
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

}
