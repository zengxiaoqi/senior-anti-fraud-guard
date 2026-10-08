package com.antifraud.guard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.GuardKeepAliveScheduler
import com.antifraud.guard.util.SystemPermissionState
import com.antifraud.guard.util.pickPhone

/**
 * 防护规则设置页面
 *  - 调整陌生通话预警时长阈值
 *  - 调整大额支付预警金额阈值
 *  - 调整单次录音最长时长（段数）
 *  - 老人资料（姓名/手机号）修改并同步服务器
 *  - 只读展示已绑定守护人（事实源在服务端绑定关系，见 /api/auth/elder-bind-code）
 *
 * 所有防护规则在保存时上行到服务器（guard_settings）。以前只写本机 SharedPreferences，
 * 于是"老人换手机"会把它们静默打回默认值：老手机上设的"通话超 10 分钟就告警"
 * 在新手机上无声失效，界面上还一切正常，用户完全无从察觉。
 */
class SettingsActivity : AppCompatActivity() {

    /** 保存请求已在飞行中：防止用户连点造成重复提交与重复弹窗 */
    private var saving = false

    private lateinit var tvHealthReport: TextView
    private lateinit var tvHealthAdvice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_settings)

        val etCallThreshold    = findViewById<EditText>(R.id.et_call_threshold)
        val etPaymentThreshold = findViewById<EditText>(R.id.et_payment_threshold)
        val etRecSegments      = findViewById<EditText>(R.id.et_rec_segments)
        val etRecSegmentMinutes = findViewById<EditText>(R.id.et_rec_segment_minutes)
        val tvRecMinutesHint   = findViewById<TextView>(R.id.tv_rec_minutes_hint)
        val tvFamilyName       = findViewById<TextView>(R.id.tv_family_name)
        val etElderName        = findViewById<EditText>(R.id.et_elder_name)
        val etElderPhone       = findViewById<EditText>(R.id.et_elder_phone)
        val btnSave            = findViewById<Button>(R.id.btn_save_settings)
        tvHealthReport         = findViewById<TextView>(R.id.tv_health_report)
        tvHealthAdvice         = findViewById<TextView>(R.id.tv_health_advice)

        setupHealthPanel()

        // 加载已有设置
        etCallThreshold.setText(GuardConfig.callThresholdMinutes.toString())
        etPaymentThreshold.setText(GuardConfig.paymentThreshold.toInt().toString())
        etRecSegments.setText(GuardConfig.recordingMaxSegments.toString())
        etRecSegmentMinutes.setText(GuardConfig.recordingSegmentMinutes.toString())
        etElderName.setText(if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName)
        etElderPhone.setText(GuardConfig.elderPhone)
        renderFamilyName(tvFamilyName)

        // 输入时实时换算总时长：段数或每段时长填错当场就能看出，
        // 比保存后被静默 clamp 掉再让用户困惑要好
        val updateRecHint = {
            val seg = etRecSegments.text.toString().trim().toIntOrNull() ?: 0
            val per = etRecSegmentMinutes.text.toString().trim().toIntOrNull() ?: 0
            val ok  = seg in 1..6 && per in 1..10
            tvRecMinutesHint.text = when {
                seg == 0 || per == 0 -> "请填写：段数 1~6，每段 1~10 分钟"
                !ok -> {
                    val cs = seg.coerceIn(1, 6)
                    val cp = per.coerceIn(1, 10)
                    "超出范围：保存时按 ${cs} 段 × ${cp} 分钟 = ${cs * cp} 分钟处理"
                }
                per >= 8 -> "当前：最多 $seg 段 × 每段 $per 分钟，约 ${seg * per} 分钟。每段偏长，弱网上传更易被中断"
                else -> "当前：最多 $seg 段 × 每段 $per 分钟，约 ${seg * per} 分钟"
            }
            tvRecMinutesHint.setTextColor(
                if (ok && per < 8) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
            )
        }
        val recWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateRecHint() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etRecSegments.addTextChangedListener(recWatcher)
        etRecSegmentMinutes.addTextChangedListener(recWatcher)
        updateRecHint()

        btnSave.setOnClickListener { view ->
            if (saving) {
                Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(
                etCallThreshold, etPaymentThreshold, etRecSegments, etRecSegmentMinutes,
                etElderName, etElderPhone, btnSave
            )
        }
    }

    /**
     * 从系统设置页返回后必须**回检**状态。
     *
     * 这是整个面板的关键：只做"跳转引导"而不回检，等于让用户跳出去随便点两下再回来，
     * App 就认为"引导完成了" —— 但开关可能根本没开，守护继续静默失效。
     */
    override fun onResume() {
        super.onResume()
        if (::tvHealthReport.isInitialized) refreshHealth()
    }

private fun setupHealthPanel() {
        findViewById<Button>(R.id.btn_refresh_health).setOnClickListener { refreshHealth(announce = true) }

        findViewById<Button>(R.id.btn_fix_battery).setOnClickListener {
       // 优先走厂商自己的省电策略页（进了系统白名单也常被二次管控）
  val vendorIntent = VendorPermissionHelper.batteryOptimizationIntent(this)
        try {
                if (vendorIntent != null && vendorIntent.resolveActivity(packageManager) != null) {
       startActivity(vendorIntent)
        } else {
      SystemPermissionState.openBatteryOptimizationSettings(this)
       }
            } catch (e: Exception) {
           SystemPermissionState.openBatteryOptimizationSettings(this)
            }
    }

        findViewById<Button>(R.id.btn_fix_background_location).setOnClickListener {
   SystemPermissionState.openAppLocationSettings(this)
        toast("请点「权限」→「位置信息」，选择「始终允许」")
        }

  findViewById<Button>(R.id.btn_fix_call_screening).setOnClickListener {
      // 三级兜底，且每一级都必须有可见反馈 —— "点了没反应"比报错更糟：
            // 1) Android 10+ 标准做法：拉起系统角色确认框（部分 ROM 实现了角色却弹不出框，
      //    toast 里要预告"应该看到什么"，用户才能分辨这一步是否生效）
            // 2) 角色不可用/申请失败：跳「默认应用」设置页手动切换
      // 3) 连设置页都跳不过去：把手工路径写成弹窗
            when {
                SystemPermissionState.requestCallScreeningRole(this) ->
           toast("已弹出系统确认框，请点「允许」，把本应用设为来电显示/骚扰拦截。" +
                        "若没有看到弹窗，请再点一次本按钮改走手动设置")
      SystemPermissionState.openDefaultAppsSettings(this) ->
     toast("已跳转默认应用设置，请把「来电显示」改为本应用")
                else -> showManualSteps(SystemPermissionState.Target.CALL_SCREENING)
            }
        }

   findViewById<Button>(R.id.btn_fix_notification_access).setOnClickListener {
            // 厂商优先：MIUI 上跳 AOSP 那个页面会被它自己拦下并直接拒绝
            if (!SystemPermissionState.openNotificationListenerSettings(this)) {
  showManualSteps(SystemPermissionState.Target.NOTIFICATION_ACCESS)
            }
        }

        findViewById<Button>(R.id.btn_fix_vendor).setOnClickListener {
    VendorPermissionHelper.openPermissionGuide(this)
  }

        findViewById<Button>(R.id.btn_fix_usage_access).setOnClickListener {
    if (!SystemPermissionState.openUsageAccessSettings(this)) {
      showManualSteps(SystemPermissionState.Target.USAGE_ACCESS)
  }
        }
    }

    /**
     * 跳不过去时展示手工步骤。
     *
     * 这一档是刻意设计的：深度定制 ROM 上常常所有 Intent 都解析不到，
     * 而用户看到的是一个"点了没反应"的按钮 —— 那比不给入口更糟。
     * 至少把路径写清楚，让他能自己去设置里找。
     */
    private fun showManualSteps(target: SystemPermissionState.Target) {
        AlertDialog.Builder(this)
            .setTitle("请手动前往系统设置开启")
    .setMessage(SystemPermissionState.manualSteps(target))
  .setPositiveButton("知道了", null)
     .show()
    }

    /**
     * 重绘健康自检面板。
     *
     * @param announce 点击「重新检测」按钮时为 true：检测本身是同步的（毫秒级），
     *   如果状态没变化，屏幕上什么都不会变 —— 用户会以为按钮是坏的。
     *   必须用 toast 明确告知"检测真的发生了，结论是什么"。
     *   onResume 的自动回检不打扰（announce=false），否则每次回页面都弹 toast。
     */
    private fun refreshHealth(announce: Boolean = false) {
        val h = GuardKeepAliveScheduler.healthReport(this)

        val gapText = when {
            h.heartbeatGapMinutes < 0 -> "尚未触发过保活心跳"
            h.heartbeatGapMinutes > 40 -> "${h.heartbeatGapMinutes} 分钟（⚠️ 远超预期 10~20 分钟）"
            else -> "${h.heartbeatGapMinutes} 分钟"
        }

        tvHealthReport.text = buildString {
            appendLine("守护总开关：${if (h.guardEnabled) "✅ 已开启" else "❌ 已关闭"}")
            appendLine("前台守护服务：${if (h.foregroundRunning) "✅ 运行中" else "❌ 未运行"}")
            appendLine("位置守护服务：${if (h.locationRunning) "✅ 运行中" else "❌ 未运行"}")
            appendLine("保活调度：${if (h.keepAliveScheduled) "✅ 已挂载" else "❌ 未挂载"}")
            appendLine("保活心跳间隔：$gapText（累计 ${h.heartbeatCount} 次）")
            appendLine("电池优化豁免：${if (h.batteryUnrestricted) "✅ 已豁免" else "❌ 未豁免"}")
            appendLine("位置守护：${if (h.hasBackgroundLocation) "✅ 前后台定位均已授权" else "🚨 仅前台授权，退出应用即失效"}")
            // 来电显示角色 / 使用情况访问是 Phase 1 预留项，当前没有任何已实现功能依赖它们。
            // 这里绝不能用 ❌：首页横幅刻意不把这两项算进"需要处理"（见 MainActivity.renderGuardAlertBanner），
            // 设置页标成红色"未通过"会让用户数出 3 个问题、横幅却只有 1 项 —— 口径不一致
            // 会让人以为有一处统计错了，进而对整个自检失去信任。
            appendLine("来电显示/呼叫筛选角色：${if (h.callScreeningEnabled) "✅ 已授予" else "ℹ️ 未授予（不影响现有功能）"}")
            appendLine("通知使用权：${if (h.notificationListenerEnabled) "✅ 已开启" else "❌ 未开启"}")
            appendLine("使用情况访问：${if (h.usageAccessGranted) "✅ 已开启" else "ℹ️ 未开启（不影响现有功能）"}")
            appendLine("精确闹钟权限：${if (h.canScheduleExactAlarms) "✅ 可用" else "⚠️ 不可用（保活已改用非精确闹钟，不影响）"}")
            append("手机品牌：${h.vendorBrand.ifEmpty { "原厂/其他" }}")
        }

// 分三档是刻意的，因为这三类问题的**性质**完全不同，混在一起会误导用户：
        //
        //   1. 失效  = 有功能已经不能用了，必须马上修
        //   2. 降级  = 功能还能用但会漏（通知使用权缺失时扣款监听直接不触发）
        //   3. 备用  = 当前没有任何已实现功能依赖它（Phase 1 的行为判定还没做）
        //
        // 把「来电显示角色」和「使用情况访问」跟「后台定位缺失」并排显示成红色，
        // 会让用户以为守护整体崩了 —— 实际上位置守护此时是好的。
        // 告警一旦不准，真正的告警就会被当成噪音，这正是安全类 UI 最常见的失效方式。
        val blocking = mutableListOf<String>()
        val degraded = mutableListOf<String>()
        val upcoming = mutableListOf<String>()

        if (!h.hasBackgroundLocation) {
      blocking += "位置权限只有「仅在使用中允许」：老人退出应用后系统会停止上报位置，位置守护等于没开"
 }
        if (!h.guardEnabled) blocking += "守护总开关已关闭，所有功能都不会生效"
        if (!h.foregroundRunning) blocking += "前台守护服务没在运行，请打开 App 让它自启一次"

        if (!h.notificationListenerEnabled) {
     degraded += "未开启通知使用权：大额支付监听完全不会触发（这项在国产 ROM 上较难开启）"
        }
        if (!h.keepAliveScheduled) degraded += "保活调度未挂载，重启手机后可能无法自动恢复"
        if (!h.batteryUnrestricted) degraded += "未豁免电池优化，后台随时可能被系统或厂商杀掉"
        if (h.heartbeatGapMinutes > 40) degraded += "保活心跳间隔超过 40 分钟，说明系统或厂商在杀后台"

  if (!h.callScreeningEnabled) {
 upcoming += "来电显示/呼叫筛选角色未授予：现有通话时长监测不会被系统调用。" +
          "该项将在 Phase 1 由「通话状态监听」替代，不再依赖系统角色"
    }
  if (!h.usageAccessGranted) {
            upcoming += "使用情况访问未开启：Phase 1 的「通话中打开支付 App」行为判定依赖它，" +
            "该功能尚未上线，当前不影响任何已有能力"
        }

   tvHealthAdvice.text = buildString {
            if (blocking.isNotEmpty()) {
    appendLine("🚨 ${blocking.size} 项导致守护失效：")
    blocking.forEach { appendLine("　• $it") }
  if (degraded.isNotEmpty()) appendLine()
            }
    if (degraded.isNotEmpty()) {
    appendLine("⚠️ ${degraded.size} 项功能降级：")
     degraded.forEach { appendLine("　• $it") }
    if (upcoming.isNotEmpty()) appendLine()
            }
    if (upcoming.isNotEmpty()) {
    appendLine("ℹ️ ${upcoming.size} 项暂不影响现有功能：")
       upcoming.forEach { appendLine("　• $it") }
 }
            if (blocking.isEmpty() && degraded.isEmpty() && upcoming.isEmpty()) {
        append("✅ 未发现异常，无需处理")
        }
        }
        tvHealthAdvice.setTextColor(
            when {
   blocking.isNotEmpty() -> 0xFFEF4444.toInt()   // 红：功能已经失效
   degraded.isNotEmpty() -> 0xFFFBBF24.toInt()   // 黄：能被削弱
 else -> 0xFF10B981.toInt()         // 绿：正常（"即将支持"不算问题）
            }
        )

        if (announce) {
            val needFix = blocking.size + degraded.size
            toast(
                if (needFix == 0) "✅ 已重新检测：没有需要处理的问题"
                else "✅ 已重新检测：$needFix 项需要处理，详见下方说明"
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** 守护人姓名只读展示：服务端绑定关系是唯一事实源 */
    private fun renderFamilyName(tv: TextView) {
        val name = GuardConfig.boundFamilyName
        val phone = GuardConfig.boundFamilyPhone
        tv.text = when {
            name.isNotEmpty() && phone.isNotEmpty() && !phone.startsWith("wx_") ->
                "$name（$phone）"
            name.isNotEmpty() -> "$name（该子女未登记手机号，无法一键拨通）"
            else -> "未绑定子女账号"
        }
    }

    private fun save(
        etCallThreshold: EditText,
        etPaymentThreshold: EditText,
        etRecSegments: EditText,
        etRecSegmentMinutes: EditText,
        etElderName: EditText,
        etElderPhone: EditText,
        btnSave: Button
    ) {
        val callMin = etCallThreshold.text.toString().trim().toIntOrNull()
        val payAmt  = etPaymentThreshold.text.toString().trim().toDoubleOrNull()
        val segRaw  = etRecSegments.text.toString().trim().toIntOrNull()
        val perRaw  = etRecSegmentMinutes.text.toString().trim().toIntOrNull()

        if (callMin == null || callMin < 1) {
            Toast.makeText(this, "请输入有效的通话时长阈值（分钟）", Toast.LENGTH_SHORT).show()
            return
        }
        if (payAmt == null || payAmt < 1) {
            Toast.makeText(this, "请输入有效的支付预警金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (segRaw == null || segRaw < 1) {
            Toast.makeText(this, "请输入有效的录音段数（1~6 段）", Toast.LENGTH_SHORT).show()
            return
        }
        // 超出范围不静默接受也不直接报错：明确告知会被收敛到哪个值，
        // 免得用户以为设了 9 段、实际生效 6 段还以为系统有 bug
        val segments = segRaw.coerceIn(1, 6)
        if (segments != segRaw) {
            Toast.makeText(
                this,
                "录音段数 $segRaw 超出范围（1~6），将按 $segments 段保存",
                Toast.LENGTH_LONG
            ).show()
        }
        if (perRaw == null || perRaw < 1) {
            Toast.makeText(this, "请输入有效的单段录音时长（1~10 分钟）", Toast.LENGTH_SHORT).show()
            return
        }
        val perSegment = perRaw.coerceIn(1, 10)
        if (perSegment != perRaw) {
            Toast.makeText(
                this,
                "单段录音时长 $perRaw 超出范围（1~10），将按 $perSegment 分钟保存",
                Toast.LENGTH_LONG
            ).show()
        }

        // 本地阈值先存：即便服务器同步失败，这些设置也必须在本机生效
        GuardConfig.callThresholdMinutes = callMin
        GuardConfig.paymentThreshold     = payAmt
        GuardConfig.recordingMaxSegments = segments
        GuardConfig.recordingSegmentMinutes = perSegment

        val newName = etElderName.text.toString().trim()
        val newPhone = normalizePhoneInput(etElderPhone.text.toString())
        val curName = if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName

        // 手机号有填才校验，空着表示不改号码（沿用原号码）
        if (newPhone.isNotEmpty() && !Regex("^1[3-9]\\d{9}$").matches(newPhone)) {
            Toast.makeText(this, "老人手机号格式不正确（11 位）", Toast.LENGTH_SHORT).show()
            return
        }
        val finalPhone = if (newPhone.isNotEmpty()) newPhone else GuardConfig.elderPhone

        // 姓名和手机号任意一个变了都要同步服务器。
        // 原来只看 phoneChanged，导致"只改姓名"时服务器上的资料永远不更新，
        // 子女端看到的还是旧名字。
        val profileChanged = newName != curName || (newPhone.isNotEmpty() && newPhone != GuardConfig.elderPhone)

        if (!GuardConfig.elderActivated) {
            // 未激活：只存本地，下次登记时生效
            GuardConfig.elderName = newName
            GuardConfig.elderPhone = finalPhone
            Toast.makeText(this, "✅ 设置已保存（账号登记后将同步到服务器）", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        saving = true
        btnSave.isEnabled = false

        // 1) 规则上行。老人资料没变时这一步就够了，不必再打扰用户
        if (!profileChanged) {
            pushSettingsThenFinish(btnSave)
            return
        }

        submitElderProfile(newName.ifEmpty { GuardConfig.elderName.ifEmpty { "老人账号" } }, finalPhone, btnSave)
    }

    /**
     * 把本机防护规则上行到服务器，成功后退出。
     *
     * 失败也允许退出并提示：设置在本机已经生效，硬拦住不让走反而会让人以为没保存成功。
     * 但必须明确说"未同步"，因为下次换手机时这份配置不会跟过去 —— 静默失败等于埋雷。
     */
    private fun pushSettingsThenFinish(btnSave: Button) {
        ApiClient.pushElderSettings(
            onSuccess = { merged ->
                // 用服务端合并后的最终值回写，多端并发改同一字段时以服务器为准
                merged?.let { GuardConfig.applySettingsFromServer(it) }
                finishWith("✅ 设置已保存并同步到云端（换手机也不会丢）")
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                AlertDialog.Builder(this)
                    .setTitle("设置已生效，但未同步到云端")
                    .setMessage("原因：$err\n\n本机已按新设置运行，但这次改动没有上传。换手机后可能会恢复成旧设置。\n\n是否现在重试？")
                    .setPositiveButton("重试") { _, _ ->
                        saving = false
                        btnSave.isEnabled = true
                        pushSettingsThenFinish(btnSave)
                    }
                    .setNegativeButton("先退出") { _, _ -> finishWith("设置已在本机生效（未同步云端）") }
                    .show()
            }
        )
    }

    private fun finishWith(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * 同步老人资料到服务器，并顺带把防护规则一起上行。
     *
     * elder-register 会按 elderId 是否有效区分"就地改资料"和"新建/找回"，这里不用自己判断；
     * 它的响应里已带 boundFamily 与 guardSettings，正好一并落地。
     */
    private fun submitElderProfile(name: String, phone: String, btnSave: Button) {
        Toast.makeText(this, "正在保存并同步服务器…", Toast.LENGTH_SHORT).show()
        ApiClient.elderRegister(name, phone, elderId = GuardConfig.elderId,
            onSuccess = { res ->
                GuardConfig.elderName = res.optString("name", name)
                GuardConfig.elderPhone = res.optString("phone", phone)
                GuardConfig.elderId = res.optInt("elderId", GuardConfig.elderId)
                val code = res.optString("bindCode", "")
                if (code.isNotEmpty()) GuardConfig.bindCode = code
                GuardConfig.elderActivated = true
                // 顺手同步守护人信息：换手机后第一次进设置页就能看到子女
                res.optJSONObject("boundFamily")?.let { fam ->
                    val n = fam.optString("name", "")
                    if (n.isNotEmpty()) GuardConfig.boundFamilyName = n
                    GuardConfig.boundFamilyPhone =
                        fam.pickPhone(username = GuardConfig.familyUsername)
                }
                // 资料提交成功不代表规则也上传了，单独再推一次
                ApiClient.pushElderSettings(
                    onSuccess = { merged ->
                        merged?.let { GuardConfig.applySettingsFromServer(it) }
                        finishWith("✅ 设置已保存并同步到云端")
                    },
                    onError = { finishWith("✅ 资料已同步（防护规则未同步到云端）") }
                )
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                Toast.makeText(this, "资料同步失败：$err（其余设置已在本机生效）", Toast.LENGTH_LONG).show()
            })
    }

    /**
     * 全角数字→半角，并丢弃空格/连字符。
     * 中文输入法默认打全角，服务端 /^1[3-9]\d{9}$/ 必判失败 —— 这个坑踩过不止一次。
     */
    private fun normalizePhoneInput(s: String): String {
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
}