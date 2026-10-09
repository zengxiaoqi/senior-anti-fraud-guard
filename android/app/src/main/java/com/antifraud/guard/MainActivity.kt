package com.antifraud.guard

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.location.LocationPermissionPlan
import com.antifraud.guard.service.ForegroundGuardService
import com.antifraud.guard.service.GuardKeepAliveScheduler
import com.antifraud.guard.service.LocationGuardService
import com.antifraud.guard.service.RecordingGuardService
import com.antifraud.guard.service.UploadQueue
import com.antifraud.guard.util.SystemPermissionState
import com.antifraud.guard.util.optStringOrEmpty
import com.antifraud.guard.util.pickPhone
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

companion object {
        private const val REQ_LOCATION = 100
        private const val REQ_CALL_LOG = 101
    private const val REQ_RECORD_AUDIO = 102

        /** SharedPreferences 文件名：后台定位引导提示的"已提醒过"标记 */
   private const val PREFS_BG_GUIDE = "bg_location_guide"

        /** 上一次就"位置权限只是仅在使用中允许"弹出提醒的时间戳 */
        private const val KEY_BG_LOCATION_LAST_WARN = "bg_location_last_warn"

        /**
         * 弹窗最小间隔（24 小时）。
   *
         * 常驻横幅已经负责"绝不会错过"，弹窗只负责"第一次就让用户知道得特别明确"。
         * 所以这里刻意做得稀疏：每天最多一次，避免变成用户一进 App 就想卸载的骚扰。
         * 第一版是"每次都弹"，第二版是"一辈子只弹一次"（被'知道了'关掉后再无声）——
         * 两个极端都是错的。
         */
        private const val BG_LOCATION_WARN_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    // ── 页面导航 ──
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var pageHome: View
    private lateinit var pageGuard: View
    private lateinit var pageGuide: View
    private lateinit var guideContainer: LinearLayout

    // ── 状态视图 ──
    private lateinit var tvHomeGreeting: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvBackendStatus: TextView
    private lateinit var tvBindCode: TextView
    private lateinit var tvBoundFamily: TextView

    // ── 开关（有真实开/关视觉状态） ──
    private lateinit var swGuardMaster: CompoundButton
    private lateinit var swNotifPerm: CompoundButton
    private lateinit var swLocPerm: CompoundButton

    // 录音存证状态卡片
    private lateinit var llRecordingStatus: View
    private lateinit var tvRecordingStatus: TextView
    private lateinit var tvRecordingDetail: TextView
    private lateinit var tvUploadPending: TextView
    private lateinit var btnRetryUpload: Button

    // 守护失效告警横幅（常驻，不可忽略）
    private lateinit var llGuardAlert: View
    private lateinit var tvGuardAlertTitle: TextView
    private lateinit var tvGuardAlertBody: TextView

    /** 程序化同步开关状态时置 true，避免触发用户手势监听 */
    private var isSyncingStatus = false

    /** 指南页是否已构建过（决定守护人信息回来后要不要重建它） */
    private var guidePageBuilt = false

    /** 指南页数据拉取进行中，避免 onResume 与导航点击重复发请求 */
    private var guideSyncInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        // 上报上次崩溃记录（有则传，没有则空操作），诊断线上闪退用
        com.antifraud.guard.util.CrashReporter.reportPending(this)
        setContentView(R.layout.activity_main)

        // ── 底部导航 + 三个页面 ──
        bottomNav     = findViewById(R.id.bottom_nav)
        pageHome      = findViewById(R.id.page_home)
        pageGuard     = findViewById(R.id.page_guard)
        pageGuide     = findViewById(R.id.page_guide)
        guideContainer = findViewById(R.id.ll_guide_elder)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_elder_home  -> showPage(pageHome)
                R.id.nav_elder_guard -> showPage(pageGuard)
                R.id.nav_elder_guide -> {
                    // 每次进入都重建：守护人电话那一行是构建期决定的分支，
                    // 上次构建时数据还没到就永远停在"未绑定"版本
                    buildGuidePage()
                    showPage(pageGuide)
                    ensureGuideDataFresh()
                }
            }
            true
        }

        // ── View 引用 ──
        tvHomeGreeting   = findViewById(R.id.tv_home_greeting)
        tvServiceStatus  = findViewById(R.id.tv_service_status)
        tvNotifStatus    = findViewById(R.id.tv_notif_status)
        tvLocationStatus = findViewById(R.id.tv_location_status)
        tvBackendStatus  = findViewById(R.id.tv_backend_status)
        tvBindCode       = findViewById(R.id.tv_bind_code)
        tvBoundFamily    = findViewById(R.id.tv_bound_family)

        swGuardMaster = findViewById(R.id.sw_guard_master)
        swNotifPerm   = findViewById(R.id.sw_notif_perm)

        llGuardAlert     = findViewById(R.id.ll_guard_alert)
        tvGuardAlertTitle = findViewById(R.id.tv_guard_alert_title)
        tvGuardAlertBody = findViewById(R.id.tv_guard_alert_body)
        findViewById<Button>(R.id.btn_guard_alert_fix).setOnClickListener {
            SystemPermissionState.openAppLocationSettings(this)
        }
        findViewById<Button>(R.id.btn_guard_alert_more).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        swLocPerm     = findViewById(R.id.sw_location_perm)

        val etServerUrl   = findViewById<EditText>(R.id.et_server_url)
        val btnSaveUrl    = findViewById<Button>(R.id.btn_save_url)
        val btnAiScan     = findViewById<Button>(R.id.btn_ai_scan)
        val btnLogs       = findViewById<Button>(R.id.btn_view_logs)
        val btnSettings   = findViewById<Button>(R.id.btn_settings)
        val btnSos        = findViewById<Button>(R.id.btn_sos)
        val btnCopyCode   = findViewById<Button>(R.id.btn_copy_code)

        // ── 录音存证状态卡片 ──
        llRecordingStatus = findViewById(R.id.ll_recording_status)
        tvRecordingStatus = findViewById(R.id.tv_recording_status)
        tvRecordingDetail = findViewById(R.id.tv_recording_detail)
        tvUploadPending   = findViewById(R.id.tv_upload_pending)
        findViewById<Button>(R.id.btn_stop_recording).setOnClickListener { stopRecordingManually() }
        btnRetryUpload = findViewById(R.id.btn_retry_upload)
        btnRetryUpload.setOnClickListener { onRetryUploadTap() }
        UploadQueue.init(applicationContext)
        UploadQueue.setPendingListener { count -> runOnUiThread { refreshUploadHint(count) } }
        // 主动探测一次上传链路：录音传不上去十有八九是地址/网络问题，
        // 先确认服务端可达，再让用户去录，能省掉一轮无效等待
        ApiClient.checkHealth({ runOnUiThread { refreshUploadHint(UploadQueue.pendingCount()) } },
            { err -> runOnUiThread { toast("⚠️ 无法连接服务器：$err\n录音将保存在本机，恢复后自动发送") } })
        val btnRefreshCode = findViewById<Button>(R.id.btn_refresh_code)

        // ── 初始化绑定码 ──
        tvBindCode.text = GuardConfig.bindCode.ifEmpty { "加载中..." }
        syncBindCodeFromServer()
        etServerUrl.setText(GuardConfig.serverUrl)

        // ── 先探测后端连通性，通了才提示账号登记（未登记且后端可达时才弹窗，避免死循环） ──
        probeBackendThenActivate()

        // ── 首次启动：一键引导开启通知 + 厂商保活权限（自启动/后台弹出界面无法静默授权，只能引导跳转） ──
        maybeShowVendorPermissionGuide()

        // ── 保存/测试后端连接 ──
        btnSaveUrl.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) { toast("请输入有效的后端代理地址"); return@setOnClickListener }
            ApiClient.setServerBaseUrl(url)
            tvBackendStatus.setTextColor(0xFF94A3B8.toInt())
            tvBackendStatus.text = "后端连接：连接测试中..."
            ApiClient.reportRiskEvent(
                eventType = "DEVICE_ONLINE",
                severity  = "LOW",
                details   = JSONObject().apply {
                    put("type", "PING"); put("device", Build.MODEL)
                },
                onSuccess = {
                    runOnUiThread {
                        tvBackendStatus.text  = "后端连接：已连通 ✅"
                        tvBackendStatus.setTextColor(0xFF10B981.toInt())
                        com.antifraud.guard.service.GuardWebSocketManager.start()
                        toast("✅ 成功连通后端服务器！守护长连接已激活。")
                        // 连通后若还未登记账号，立即提示（这是修好地址后重新进入登记流程的入口）
                        if (!GuardConfig.elderActivated) showElderActivationDialog()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        tvBackendStatus.text  = "后端连接：连接失败 ❌"
                        tvBackendStatus.setTextColor(0xFFEF4444.toInt())
                        toast("❌ 连接失败: $err")
                    }
                }
            )
        }

        // ── 防护总开关：开启/关闭守护服务（真实开关样式） ──
        swGuardMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingStatus) return@setOnCheckedChangeListener
            GuardConfig.guardEnabled = isChecked   // 持久化用户意图，默认开启
            if (isChecked) {
                checkAndRequestPermissions()
                startGuardServices()
                toast("长者防诈亲情守护服务已启动！")
            } else {
                stopGuardServices()
                toast("守护服务已关闭，建议保持开启以获得保护")
            }
            refreshStatus()
        }

        // ── 扣款监听授权开关 ──
        swNotifPerm.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingStatus) return@setOnCheckedChangeListener
            if (isChecked) {
                toast("请在接下来的系统页面授权「通知使用权」，返回后自动生效")
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                toast("如需关闭，请到系统设置 → 通知使用权 中操作")
            }
        }

        // ── 位置权限开关 ──
        swLocPerm.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingStatus) return@setOnCheckedChangeListener
            if (isChecked) {
                requestLocationPermission()
            } else {
                toast("如需关闭，请到系统设置 → 应用权限 中操作")
            }
        }

        // ── AI 拍照识诈 ──
        btnAiScan.setOnClickListener {
            startActivity(Intent(this, AiScanActivity::class.java))
        }

        // ── 查看风险记录 ──
        btnLogs.setOnClickListener {
            startActivity(Intent(this, RiskLogActivity::class.java))
        }

        // ── 防护规则设置 ──
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // ── 修改手机号（老人换新号的主入口） ──
        findViewById<Button>(R.id.btn_change_phone).setOnClickListener { showChangePhoneDialog() }

        // ── 退出登录：停服务 + 清本机身份，回角色选择页 ──
        findViewById<Button>(R.id.btn_logout_elder).setOnClickListener { confirmElderLogout() }

        // ── 切换角色：回到角色选择页（App 级功能，从防护规则设置页迁入更多设置） ──
        findViewById<Button>(R.id.btn_switch_role).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("切换角色")
                .setMessage("将返回角色选择页，重新选择本机作为老人端或子女端。确定继续？")
                .setPositiveButton("确定") { _, _ ->
                    GuardConfig.appRole = ""
                    startActivity(Intent(this, RoleSelectActivity::class.java))
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── SOS 紧急求助 ──
        btnSos.setOnClickListener {
            showSosConfirmDialog()
        }

        // ── 绑定码操作 ──
        btnCopyCode.setOnClickListener {
            val code = tvBindCode.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bindCode", code))
            toast("绑定码已复制：$code")
        }
        btnRefreshCode.setOnClickListener {
            val newCode = generateBindCode()
            toast("绑定码刷新中...")
            ApiClient.syncElderBindCode(
                newCode = newCode,
                onSuccess = { res ->
                    val serverCode = res.optString("bindCode", newCode)
                    GuardConfig.bindCode = serverCode
                    tvBindCode.text = serverCode
                    toast("✅ 绑定码已刷新并同步到服务器")
                },
                onError = { err ->
                    toast("❌ 刷新失败（未同步到服务器）: $err")
                }
            )
        }

        refreshStatus()

        // 启动静默查一次新版本：只有真有新版本才弹窗，且同一版本一天最多一次。
        // 老人端尤其需要它——他们不会自己去翻设置页点"检查更新"。
        com.antifraud.guard.util.AppUpdateUi.checkOnStart(this)
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回时，接着把没走完的下载/安装做完
        com.antifraud.guard.util.AppUpdateUi.onResume(this)
        // 前台状态是"紧急警报怎么弹"的判断依据（前台直接启 Activity / 后台走全屏意图通知），
        // 支付告警在守护服务没跑时也会弹，所以这里必须主动同步一次，
        // 不能只依赖 GuardWebSocketManager 的生命周期回调（服务没起时它不会注册）。
        com.antifraud.guard.util.EmergencyAlertLauncher.isAppInForeground = true
        // 全天候守护默认开启：只要用户没主动关掉，进程/服务被系统回收后自动恢复
        ensureGuardRunning()
        refreshStatus()
        refreshRecordingStatus()
        UploadQueue.trigger()
        // 回前台就重新对齐服务端事实：子女端可能刚完成绑定、守护人可能刚改资料，
        // 老人端不重新拉一次就只能看到本机缓存（换手机后这里正是恢复绑定显示的关键时机）
        ensureGuideDataFresh()

        // 后台定位状态回检：用户可能刚从系统设置里把「仅在使用中允许」改成「始终允许」，
        // 也可能刚把它改回去。位置守护是否真正有效完全取决于这一项，
        // 必须在每次回到前台时重新读一次并如实告知，而不是只在首次安装时问一次。
        verifyBackgroundLocationOnResume()
    }

    /**
     * 回前台时检查后台定位授权，不合格就挂出**常驻**告警横幅。
     *
     * ## 为什么是横幅而不是弹窗
     * 第一版这里用的是一次性 AlertDialog + 「知道了」按钮，结果是同一个故障又藏了 12 小时：
     * 弹窗被关掉后没有任何东西提醒用户，而这个问题**完全静默** —— 服务在跑、通知栏在、
     * 首页写着"守护正常"、子女端也以为老人一直没动。
     *
     * 对安全类功能，"用户没注意到提示"和"没有提示"是同一件事。
     * 所以改成常驻横幅：关不掉、每次回前台重新计算、点一下直接跳设置。
     * 另外保留了弹窗，但改成**每天最多一次**（而不是每次都弹、也不是一辈子只弹一次）。
     */
    private fun verifyBackgroundLocationOnResume() {
        val foreground = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        renderGuardAlertBanner(foreground, background)

        if (!foreground) return   // 连前台都没有，属于首次授权流程，不在这里打扰
        if (background) {
            getSharedPreferences(PREFS_BG_GUIDE, Context.MODE_PRIVATE)
                .edit().putLong(KEY_BG_LOCATION_LAST_WARN, 0L).apply()
            return
        }

        // 每天最多弹一次，避免变成无法关闭的骚扰；横幅已经常驻兜底
        val prefs = getSharedPreferences(PREFS_BG_GUIDE, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_BG_LOCATION_LAST_WARN, 0L)
        val now = System.currentTimeMillis()
        if (now - last < BG_LOCATION_WARN_INTERVAL_MS) return

        prefs.edit().putLong(KEY_BG_LOCATION_LAST_WARN, now).apply()
        AlertDialog.Builder(this)
            .setTitle("⚠️ 位置守护实际上没有生效")
            .setMessage(
                "检测到位置权限只是「仅在使用中允许」。\n\n" +
                    "只要退出本应用，系统就会停止上报位置 —— 子女端会以为老人一直没动，\n" +
                    "而实际上是守护已经停了。这类问题不会报错、不会提示，只有主动检查才会发现。\n\n" +
                    "请在系统设置里把位置权限改为「始终允许」。"
            )
            .setPositiveButton("去设置") { _, _ ->
                SystemPermissionState.openAppLocationSettings(this)
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 渲染首页常驻告警横幅。
     *
     * 分两档是有意的：
     *   🚨 红 = 守护功能**已经失效**（后台定位缺失 / 总开关关闭）
     *   ⚠️ 黄 = 功能被削弱但仍能工作（保活/通知使用权/电池白名单等）
     * 绿色不显示 —— 不要让"一切正常"也占一屏，否则真正的告警会被当成噪音。
     */
    private fun renderGuardAlertBanner(foregroundLocation: Boolean, backgroundLocation: Boolean) {
        // 防御：健康自检涉及系统服务查询，任何一项在异常 ROM 上抛异常都不该
        // 把后面的权限提示流程一起带走 —— 那会让唯一的告警途径失效。
        // 这里宁可显示"状态未知"，也不能什么都不显示。
        val h = try {
            GuardKeepAliveScheduler.healthReport(this)
        } catch (e: Exception) {
            Log.w("GuardAlert", "健康自检失败，横幅降级显示: ${e.message}")
            null
        }

        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!backgroundLocation) {
            blocking += "位置权限只有「仅在使用中允许」：老人退出应用后系统就停止上报位置，位置守护等于没开"
        }
if (h == null) {
            warnings += "守护状态自检未能完成（系统服务异常），请到设置页手动核对"
        } else {
            if (!h.guardEnabled) blocking += "守护总开关已关闭，所有功能都不会生效"
      if (!foregroundLocation) blocking += "连前台定位权限都没有，位置服务无法启动"
            if (!h.foregroundRunning) blocking += "前台守护服务没在运行"
            if (!h.batteryUnrestricted) warnings += "未豁免电池优化，后台随时可能被系统或厂商杀掉"
            if (!h.keepAliveScheduled) warnings += "保活调度未挂载，重启手机后可能无法自动恢复"
      if (!h.notificationListenerEnabled) warnings += "未开启通知使用权，大额支付监听不会触发"
      if (h.heartbeatGapMinutes > 40) warnings += "保活心跳间隔超过 40 分钟，说明系统或厂商在杀后台"
      // 「使用情况访问」刻意不列：它确实属 warnings（未开启时通话中的
            // 支付/远程控制联动不告警），但该权限在 MIUI 上实测被静默拦截、
            // 只能 UI 手开，多数用户都开不了。放进首页横幅等于让一个
            // 大多数人开不了的权限变成日常噪音，反而稀释真正致命的告警。
            // 设置页「守护健康自检」是用户主动去查的地方，标 ⚠️ 足够。
            // 来电显示角色同样不列：目前没有任何已实现功能依赖它。
        }

        when {
            blocking.isNotEmpty() -> {
                llGuardAlert.visibility = View.VISIBLE
                llGuardAlert.setBackgroundColor(0xFF7F1D1D.toInt())
                tvGuardAlertTitle.text = "🚨 守护未生效（${blocking.size} 项）"
                tvGuardAlertTitle.setTextColor(0xFFFFFFFF.toInt())
                tvGuardAlertBody.text = blocking.joinToString("\n") { "• $it" } +
                    if (warnings.isNotEmpty()) "\n\n另有 ${warnings.size} 项待留意：\n" +
                        warnings.joinToString("\n") { "· $it" } else ""
                tvGuardAlertBody.setTextColor(0xFFFECACA.toInt())
            }
            warnings.isNotEmpty() -> {
                llGuardAlert.visibility = View.VISIBLE
                llGuardAlert.setBackgroundColor(0xFF78350F.toInt())
                tvGuardAlertTitle.text = "⚠️ 守护可能被削弱（${warnings.size} 项）"
                tvGuardAlertTitle.setTextColor(0xFFFFFFFF.toInt())
                tvGuardAlertBody.text = warnings.joinToString("\n") { "• $it" }
                tvGuardAlertBody.setTextColor(0xFFFDE68A.toInt())
            }
            else -> {
                llGuardAlert.visibility = View.GONE
            }
        }
    }

    // ──────────────────────────────────────────
    //  录音存证状态
    // ──────────────────────────────────────────

    /** 刷新录音状态卡片：录音中显示停止按钮，非录音态只提示待上传数量 */
    private fun refreshRecordingStatus() {
        val recording = RecordingGuardService.isRecording
        val maxMin = GuardConfig.recordingMaxMinutes

        if (recording) {
            llRecordingStatus.visibility = View.VISIBLE
            val seg = RecordingGuardService.currentSegmentIndex + 1
            val place = RecordingGuardService.activePlaceName
            tvRecordingStatus.text = if (place.isNotEmpty()) {
                "🔴 正在录音存证（$place）"
            } else {
                "🔴 正在录音存证"
            }
            tvRecordingDetail.text = "第 $seg 段 / 最多 ${GuardConfig.recordingMaxSegments} 段（$maxMin 分钟）\n录音结束后自动发送给子女"
        } else {
            // 没在录音时，只有确有积压才占空间显示，避免长期占着首页
            val pending = UploadQueue.pendingCount()
            if (pending > 0) {
                llRecordingStatus.visibility = View.VISIBLE
                val err = UploadQueue.lastError
                tvRecordingStatus.text = if (err.isNotEmpty()) "⏳ $pending 段录音未发送成功" else "⏳ $pending 段录音待发送"
                tvRecordingDetail.text = if (err.isNotEmpty()) {
                    "原因：$err\n文件已保存在本机，恢复后会自动重试"
                } else {
                    "已保存在本机，正在自动发送给子女"
                }
            } else {
                llRecordingStatus.visibility = View.GONE
            }
        }
        refreshUploadHint(UploadQueue.pendingCount())
    }

    /** 待上传提示条 */
    private fun refreshUploadHint(count: Int) {
        if (count <= 0) {
            tvUploadPending.visibility = View.GONE
            tvUploadPending.text = ""
            btnRetryUpload.visibility = View.GONE
            return
        }
        tvUploadPending.visibility = View.VISIBLE
        val err = UploadQueue.lastError
        tvUploadPending.text = if (err.isNotEmpty()) {
            "📤 $count 段录音未发送成功\n原因：$err\n文件已存在本机，不会丢失"
        } else {
            "📤 $count 段录音正在发送给子女，请保持网络畅通"
        }
        // 有失败原因时才给重试入口（正在正常发送时不必打扰）
        btnRetryUpload.visibility = if (err.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** 老人手动重试发送（失败后给的补救入口，不必等自动退避） */
    private fun onRetryUploadTap() {
        btnRetryUpload.isEnabled = false
        toast("正在重新发送录音…")
        UploadQueue.trigger()
        btnRetryUpload.postDelayed({ btnRetryUpload.isEnabled = true }, 2000)

        // 不要在这里拍脑袋定一个「等 1.5 秒再看结果」的时间：
        // 单段录音默认 5 分钟 ≈ 3.5MB，经隧道实测要传二三十秒到一分多钟，
        // 1.5 秒后上传根本没结束，读到的仍是上一次的失败原因，
        // 于是无论传多久都立刻弹「仍失败」——把"正在传"误报成失败。
        //
        // 正确做法：轮询到 UploadQueue 真正空闲（isUploading=false）为止，
        // 上限 10 分钟（对齐客户端 writeTimeout），期间界面显示"正在发送"。
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed > 10 * 60 * 1000) {
                    // 超上限：交由队列自身的退避重试继续，不误报成功也不误报失败
                    toast("仍在发送中，可在首页查看进度")
                    return
                }
                if (UploadQueue.isUploading) {
                    llRecordingStatus.postDelayed(this, 1000)
                    return
                }
                val pending = UploadQueue.pendingCount()
                val err = UploadQueue.lastError
                if (pending == 0) {
                    toast("✅ 录音已发送给子女")
                } else if (err.isNotEmpty()) {
                    // 真实原因由 ApiClient.describeUploadFailure 给出
                    // （如"上传超时""连不上服务器"），不再一律叫"网络不可达"
                    toast("仍失败：$err")
                } else {
                    toast("仍在发送中，请稍候")
                }
                refreshRecordingStatus()
            }
        }
        llRecordingStatus.postDelayed(tick, 2000)
    }

    /** 老人主动停止录音 */
    private fun stopRecordingManually() {
        if (!RecordingGuardService.isRecording) {
            toast("当前没有正在进行的录音")
            return
        }
        val stopIntent = Intent(this, RecordingGuardService::class.java).apply {
            action = RecordingGuardService.ACTION_STOP
            putExtra(RecordingGuardService.EXTRA_REASON, "SOS")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stopIntent)
            else startService(stopIntent)
            toast("已停止录音，正在发送给子女")
            // 停止后把已录的段立刻推上去，别等退避
            UploadQueue.trigger()
            // 给服务一点时间落盘入队再刷新卡片
            llRecordingStatus.postDelayed({ refreshRecordingStatus() }, 800)
        } catch (e: Exception) {
            toast("停止录音失败：${e.message}")
        }
    }

    /** 若守护意图为开启但服务不在运行中，则静默重新拉起（不弹提示、不重复申请权限） */
    private fun ensureGuardRunning() {
        // 拉起逻辑收敛到 GuardServiceStarter：开机自启、AlarmManager、JobScheduler、
        // 界面 onResume 四条路径必须用同一套判断，否则会出现"界面显示守护中、
        // 实际服务没跑"这种最难排查的状态分裂。
        com.antifraud.guard.service.GuardServiceStarter.ensureRunning(this)

        // 同时确保保活调度挂上（应用升级后系统会清掉已注册的闹钟与 Job）
        com.antifraud.guard.service.GuardKeepAliveScheduler.schedule(this)
    }

    // ──────────────────────────────────────────
    //  底部导航页面切换
    // ──────────────────────────────────────────
    private fun showPage(page: View) {
        pageHome.visibility  = if (page === pageHome)  View.VISIBLE else View.GONE
        pageGuard.visibility = if (page === pageGuard) View.VISIBLE else View.GONE
        pageGuide.visibility = if (page === pageGuide) View.VISIBLE else View.GONE
    }

    // ──────────────────────────────────────────
    //  防护指南页（长辈端专属，暗色大字版，内容程序化构建）
    //  对齐子女端 GuideFragment / 小程序 pages/guide
    // ──────────────────────────────────────────
    private data class FraudType(val name: String, val desc: String, val color: String)

    private val fraudTypes = listOf(
        FraudType("冒充公检法", "自称警察/检察官，说您涉嫌洗钱，要求把钱转到「安全账户」", "#F87171"),
        FraudType("虚假投资理财", "承诺高收益零风险，诱导下载APP或转账", "#FBBF24"),
        FraudType("保健品骗局", "免费体检/专家义诊，夸大疗效诱导购买高价药品", "#C084FC"),
        FraudType("冒充熟人借钱", "盗用子女/亲友头像手机号，说有急事要借钱", "#F472B6"),
        FraudType("中奖/退税诈骗", "通知中奖或退税，要求先交手续费/保证金", "#2DD4BF"),
        FraudType("网络贷款诈骗", "低息贷款诱导，要求先交押金/流水费", "#FB923C")
    )

    private val guideSteps = listOf(
        "① 保持冷静：遇到紧急情况先深呼吸，不要慌张，第一时间给子女打电话确认",
        "② 多方核实：公检法不会电话办案，所有要求转账的都是诈骗",
        "③ 保护信息：银行卡号、密码、短信验证码，谁都不给说",
        "④ 及时报警：发现被骗立即拨打 110，保存好转账凭证和聊天记录"
    )

    private fun buildGuidePage() {
        guideContainer.removeAllViews()
        guidePageBuilt = true

        // 页头
        guideContainer.addView(TextView(this).apply {
            text = "🛡️ 防护指南"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        })
        guideContainer.addView(TextView(this).apply {
            text = "多看一眼，骗子少一分可乘之机"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })

        // 一、常见诈骗套路
        addGuideSection("⚠️ 六大常见诈骗套路")
        fraudTypes.forEach { t ->
            addGuideCard {
                addView(TextView(context).apply {
                    text = t.name
                    setTextColor(Color.parseColor(t.color))
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = t.desc
                    setTextColor(0xFFCBD5E1.toInt())
                    textSize = 15f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }

        // 二、紧急电话（一键拨打）
        addGuideSection("🚨 紧急电话（点击直接拨打）")
        val contactCard = addGuideCard { }
        contactCard.addView(TextView(this).apply {
            text = "点击绿色「拨打」按钮即可呼出电话"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        })
        addDialRow(contactCard, "🚔 报警电话", "110")
        addDialRow(contactCard, "📞 全国反诈专线", "96110")
        addDialRow(contactCard, "🏦 银行客服", "95588")
        // 绑定的守护人电话
        val famPhone = GuardConfig.boundFamilyPhone
        if (famPhone.isNotEmpty() && !famPhone.startsWith("wx_")) {
            val famName = GuardConfig.boundFamilyName.ifEmpty { "守护人" }
            addDialRow(contactCard, "👨‍👩‍👧 我的守护人（$famName）", famPhone)
        } else {
            contactCard.addView(TextView(this).apply {
                text = "💡 绑定子女后，这里会显示 TA 的电话，一键就能拨通"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
                setPadding(0, dp(8), 0, dp(2))
            })
        }

        // 三、遇到诈骗怎么办
        addGuideSection("📖 遇到诈骗怎么办")
        addGuideCard {
            guideSteps.forEach { step ->
                addView(TextView(context).apply {
                    text = step
                    setTextColor(0xFFCBD5E1.toInt())
                    textSize = 15f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, 0, 0, dp(8))
                })
            }
        }
    }

    // ── 指南页 UI 构建辅助 ──
    private fun addGuideSection(text: String) {
        guideContainer.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFF94A3B8.toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(14), 0, dp(6))
        })
    }

    private fun addGuideCard(content: LinearLayout.() -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.content()
        guideContainer.addView(card)
        return card
    }

    private fun addDialRow(card: LinearLayout, name: String, number: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = "$name  $number"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "📞 拨打"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                setColor(0xFF10B981.toInt())
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val digits = number.filter { it.isDigit() }
                if (digits.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                } else {
                    toast("暂无可拨打的号码")
                }
            }
        })
        card.addView(row)
    }

    // ──────────────────────────────────────────
    //  状态刷新
    // ──────────────────────────────────────────
    private fun refreshStatus() {
        // 首页问候
        val elderName = GuardConfig.elderName
        tvHomeGreeting.text = if (elderName.isNotEmpty() && elderName != "默认账号") {
            "您好，$elderName 👋"
        } else {
            "您好 👋"
        }

        isSyncingStatus = true

        // 守护服务（总开关：跟随持久化的用户意图，默认开启）
        val svcRunning = isServiceRunning(ForegroundGuardService::class.java)
        val guardWanted = GuardConfig.guardEnabled
        swGuardMaster.isChecked = guardWanted
        if (svcRunning) {
            tvServiceStatus.text = "守护运行中 · 电话/扣款/位置全程保护"
            tvServiceStatus.setTextColor(0xFF34D399.toInt())
        } else if (guardWanted) {
            tvServiceStatus.text = "守护已开启 · 正在拉起服务…"
            tvServiceStatus.setTextColor(0xFFFBBF24.toInt())
        } else {
            tvServiceStatus.text = "守护未开启 · 打开右侧开关立即守护"
            tvServiceStatus.setTextColor(0xFFFBBF24.toInt())
        }

        // 扣款监听开关
        val notifGranted = isNotificationListenerEnabled()
        swNotifPerm.isChecked = notifGranted
        tvNotifStatus.text = if (notifGranted) "已授权 · 正在监听大额扣款通知" else "未授权 · 打开开关跳转授权页"
        tvNotifStatus.setTextColor(if (notifGranted) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())

        // 位置守护开关
        val locGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        swLocPerm.isChecked = locGranted
        tvLocationStatus.text = if (locGranted) "已授权 · 敏感地点停留会告警" else "未授权 · 打开开关申请定位权限"
        tvLocationStatus.setTextColor(if (locGranted) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())

        isSyncingStatus = false

        // 绑定码：只显示已同步的码（服务器为唯一事实源，本地不再生成）
        if (GuardConfig.bindCode.isNotEmpty()) {
            tvBindCode.text = GuardConfig.bindCode
        } else if (tvBindCode.text.isEmpty() || tvBindCode.text == "加载中...") {
            tvBindCode.text = "同步中..."
        }

        // 绑定状态（含守护人手机号，wx_ 开头为微信占位号，视为未登记）
        val famPhone = GuardConfig.boundFamilyPhone
        val phoneSuffix = when {
            famPhone.isNotEmpty() && !famPhone.startsWith("wx_") -> "（$famPhone）"
            else -> ""
        }
        tvBoundFamily.text = if (GuardConfig.boundFamilyName.isNotEmpty()) {
            "已绑定守护人：${GuardConfig.boundFamilyName}$phoneSuffix ✅"
        } else {
            "绑定状态：尚未绑定子女账号（将绑定码告知子女）"
        }
    }

    // ──────────────────────────────────────────
    //  后端连通性探测 + 老人账号激活登记
    //  顺序：先探测 /api/health → 可达才弹登记窗；失败只提示，由用户修好地址后
    //  点「保存/测试后端连接」再次触发，绝不自动无限重弹。
    // ──────────────────────────────────────────
    private fun probeBackendThenActivate() {
        ApiClient.checkHealth(
            onSuccess = {
                tvBackendStatus.text = "后端连接：已连通 ✅"
                tvBackendStatus.setTextColor(0xFF10B981.toInt())
                if (!GuardConfig.elderActivated) showElderActivationDialog()
            },
            onError = {
                tvBackendStatus.text = "后端连接：未连通 ❌ 请检查连接地址后点「保存并测试连接后端」"
                tvBackendStatus.setTextColor(0xFFEF4444.toInt())
                if (!GuardConfig.elderActivated) {
                    toast("后端未连通，请先配置服务器地址；连通后会自动提示账号登记")
                }
            }
        )
    }

    private fun showElderActivationDialog(retryFresh: Boolean = false) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val etName = EditText(this).apply {
            hint = "老人姓名（已登记过的账号可不填）"
            setText(if (retryFresh) "" else GuardConfig.elderName.takeIf { it != "默认账号" } ?: "")
        }
        val etPhone = EditText(this).apply {
            hint = "老人手机号（11 位）"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            // 退出登录/换机时刻意保留本机手机号（见 clearElderSession），这里回填免重输；
            // 只填手机号登记，服务端按号找回原账号，姓名、子女绑定与防护设置自动带回
            setText(GuardConfig.elderPhone)
        }
        container.addView(etName)
        container.addView(etPhone)

        AlertDialog.Builder(this)
            .setTitle("🧓 老人账号登记")
            .setMessage("已登记过的账号只需填手机号即可找回，姓名与绑定关系自动恢复；全新号码才需要再填姓名。")
            .setView(container)
            .setCancelable(true) // 允许暂时关闭去修服务器地址，连通后再登记
            .setPositiveButton("登记") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = normalizePhoneInput(etPhone.text.toString())
                // 姓名允许为空：服务端按手机号找回已有账号时沿用原姓名（COALESCE 兜底）；
                // 只有"全新手机号 + 空姓名"才会被拒（400 提示补姓名），届时重弹本窗补填
                if (!Regex("^1[3-9]\\d{9}$").matches(phone)) {
                    toast("请填写 11 位手机号")
                    showElderActivationDialog(retryFresh)
                    return@setPositiveButton
                }
                // 记住覆盖前的旧手机号：换号场景靠它走服务端 previousPhone 找回
                // （该分支必须抢在按新号认领之前，防冒用他人账号，见 routes/auth.js）
                val previousPhone = GuardConfig.elderPhone
                // 先落本地再发请求：失败重弹登记窗时，刚输过的手机号/姓名还在输入框里，不用重新输入
                GuardConfig.elderPhone = phone
                if (name.isNotEmpty()) GuardConfig.elderName = name
                // retryFresh=true 表示本地 elderId 在服务器已不存在（如重置过数据库），重新建号
                ApiClient.elderRegister(
                    name, phone,
                    elderId = if (retryFresh) 0 else if (GuardConfig.elderActivated) GuardConfig.elderId else 0,
                    previousPhone = previousPhone,
                    onSuccess = { res -> onElderRegistered(res, name, phone, "✅ 老人账号登记成功") },
                    onError = { err ->
                        toast("❌ 登记失败：$err")
                        when {
                            // 仅本地 elderId 失效这一种情况需要换全新注册重试一次
                            err.contains("不存在") ->
                                ApiClient.elderRegister(name, phone, elderId = 0,
                                    onSuccess = { res -> onElderRegistered(res, name, phone, "✅ 老人账号登记成功") },
                                    onError = { err2 -> toast("❌ 登记失败：$err2，请检查网络后重新操作") })
                            // 全新手机号没填姓名：重弹登记窗补填（手机号已回填，见上方先落本地）
                            err.contains("姓名") -> showElderActivationDialog(retryFresh)
                            // 其他错误不自动重弹，避免死循环；用户修好网络后点「保存/测试」重新触发
                        }
                    }
                )
            }
            .show()
    }

    /**
     * 登记/改资料成功后的统一落地处理。
     *
     * 换手机的核心时刻就在这里：服务端把原账号连同 bound_user_id 和 guard_settings
     * 原样返回，客户端一次性把绑定码、守护人姓名手机号、防护规则全部落本地，
     * 界面立刻就是完整的 —— 不用再赌一次"补拉守护人信息"的二次请求能否成功。
     */
    private fun onElderRegistered(res: JSONObject, name: String, phone: String, baseMsg: String) {
        GuardConfig.elderId = res.optInt("elderId", GuardConfig.elderId)
        GuardConfig.elderName = res.optStringOrEmpty("name").ifEmpty { name }
        GuardConfig.elderPhone = res.optStringOrEmpty("phone").ifEmpty { phone }
        GuardConfig.bindCode = res.optString("bindCode", "")
        GuardConfig.elderActivated = true
        tvBindCode.text = GuardConfig.bindCode

        applyBoundFamily(res.optJSONObject("boundFamily"))
        applyCloudSettings(res.optJSONObject("guardSettings"))
        refreshStatus()
        refreshGuidePageIfBuilt()

        // 提示语要说清"资料没丢"，否则用户换完号会一直担心绑定和录音是不是没了
        val msg = when {
            res.optBoolean("recoveredByPhoneChange", false) -> "$baseMsg（原账号、子女绑定与录音记录已保留）"
            res.optBoolean("recoveredExistingAccount", false) -> "$baseMsg（已找回原账号，子女绑定与设置已恢复）"
            else -> baseMsg
        }
        toast(msg)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ──────────────────────────────────────────
    //  修改手机号
    // ──────────────────────────────────────────

    /**
     * 老人换手机号后点这里改。
     *
     * 必须同步到服务器（elder-register 更新资料分支），否则会出现这种糟糕情况：
     * 老人换了号，但子女端看到的还是旧号，紧急警报打给一个已经用不了的人。
     * 换号后 elderId / 绑定码 / 历史记录都不变，所以子女端的绑定不会断。
     */
    private fun showChangePhoneDialog() {
        if (!GuardConfig.elderActivated) {
            toast("尚未登记账号，请先在首页完成「老人账号登记」")
            showElderActivationDialog()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val etPhone = EditText(this).apply {
            hint = "新的 11 位手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(GuardConfig.elderPhone)
            setSelection(text.length)
        }
        container.addView(etPhone)

        val elderName = GuardConfig.elderName.ifEmpty { "老人账号" }

        AlertDialog.Builder(this)
            .setTitle("📱 修改手机号")
            .setMessage("当前号码：${GuardConfig.elderPhone.ifEmpty { "未登记" }}\n\n换了新号码后请在这里更新，子女端才能在紧急时拨到您。")
            .setView(container)
            .setCancelable(true)
            .setPositiveButton("保存") { _, _ ->
                val newPhone = normalizePhoneInput(etPhone.text.toString())
                if (!Regex("^1[3-9]\\d{9}$").matches(newPhone)) {
                    toast("手机号格式不正确（当前 ${newPhone.length} 位，需 11 位）")
                    return@setPositiveButton
                }
                if (newPhone == GuardConfig.elderPhone) {
                    toast("号码没有变化")
                    return@setPositiveButton
                }
                submitElderProfile(elderName, newPhone, "✅ 手机号已更新为 $newPhone")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 提交老人资料（姓名+手机号）到服务器，成功后落本地。
     *
     * 换号与首次登记共用这条链路：服务端 elder-register 会判断 elderId 是否有效，
     * 有效就是"就地改资料"，无效才是"新建/找回"，所以调用方不用自己判断。
     *
     * @param successMsg 成功提示语
     */
    private fun submitElderProfile(name: String, phone: String, successMsg: String) {
        toast("正在提交…")
        ApiClient.elderRegister(
            name, phone,
            elderId = if (GuardConfig.elderActivated) GuardConfig.elderId else 0,
            // 换号走的是同一条链路：响应里已带 boundFamily / guardSettings，
            // 由 onElderRegistered 统一落地，不需要再额外补拉一次
            onSuccess = { res -> onElderRegistered(res, name, phone, "✅ $successMsg") },
            onError = { err ->
                toast("❌ 保存失败：$err")
                if (err.contains("已被其他账号使用")) {
                    AlertDialog.Builder(this)
                        .setTitle("该号码已被使用")
                        .setMessage("这个手机号已经登记过另一位用户，不能重复使用。\n\n请确认号码是否输入正确。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        )
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

    // ──────────────────────────────────────────
    //  退出登录（老人端）
    // ──────────────────────────────────────────

    /**
     * 退出老人端登录。
     *
     * 三个必须做对的地方，否则就是"看着退出了、实际上没退干净"：
     *  1. 先停所有守护/录音服务 —— 服务还在跑就会继续用旧 elderId 上报数据，
     *     清了会话之后那些数据会挂到错误账号上。
     *  2. 有待上传录音时拦一下 —— 队列里的录音带旧 elderId，
     *     直接清会话会导致它们再也传不上去（服务端会拒），等于静默丢证据。
     *  3. 不删服务器上的账号 —— 换手机号重新登记时，凭手机号就能找回原账号，
     *     绑定关系和历史录音都还在。
     */
    private fun confirmElderLogout() {
        val pending = UploadQueue.pendingCount()
        val recording = RecordingGuardService.isRecording

        // 有正在录的音或没发出去的录音，先说清楚后果，让用户自己选
        if (recording || pending > 0) {
            val detail = buildString {
                if (recording) append("当前正在录音存证。\n")
                if (pending > 0) append("还有 $pending 段录音没有发送给子女，")
                if (recording || pending > 0) append("\n\n直接退出会停止守护，这些录音可能来不及发送（文件仍留在本机，不会被删除）。")
            }
            AlertDialog.Builder(this)
                .setTitle("先处理一下再退出")
                .setMessage(detail)
                .setPositiveButton("仍然退出") { _, _ -> doElderLogout() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage(
                "退出后本机将停止防诈守护服务，回到角色选择页。\n\n" +
                "已登记的老人资料、子女绑定和历史录音都会保留，下次用同一个手机号重新登记即可继续使用。"
            )
            .setPositiveButton("退出登录") { _, _ -> doElderLogout() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doElderLogout() {
        // 先尝试把待发录音推一把，能发出去就别留在本机
        val pending = UploadQueue.pendingCount()
        if (pending > 0) UploadQueue.trigger()

        // 停掉所有可能在后台继续上报的服务（顺序：录音 → 守护 → 长连接）
        stopService(Intent(this, RecordingGuardService::class.java))
        stopGuardServices()
        com.antifraud.guard.service.GuardWebSocketManager.stop()
        // 守护总开关同时关掉，否则下次进首页 onResume 会把服务重新拉起来
        GuardConfig.guardEnabled = false
        swGuardMaster.isChecked = false

        GuardConfig.clearElderSession()
        GuardConfig.appRole = ""

        startActivity(Intent(this, RoleSelectActivity::class.java))
        finishAffinity()
        toast("已退出登录")
    }

    // ──────────────────────────────────────────
    //  SOS 弹窗确认
    // ──────────────────────────────────────────
    private fun showSosConfirmDialog() {
        // 录音权限：未授权时先申请，授权回调里继续 SOS 流程
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle("🆘 确认触发紧急求助？")
            .setMessage("将向绑定的子女发送您的实时位置与紧急求助信号，并开始本地环境录音存证。")
            .setPositiveButton("确认求助") { _, _ ->
                triggerSos()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 取各定位提供方中最近一次已知位置（兜底用，无权限时返回 null） */
    private fun getLastKnownLocation(): android.location.Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider ->
                    try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
                }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    private fun triggerSos() {
        // 1. 附带实时位置（取最近一次已知坐标；位置守护服务每 10 分钟会刷新一次）
        val details = JSONObject().apply {
            put("note", "老人主动触发一键紧急求助")
            put("device", Build.MODEL)
            put("timestamp", System.currentTimeMillis())
            val loc = getLastKnownLocation()
            if (loc != null) {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("address", "求助时的位置 (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})")
            }
        }

        // 2. 真正启动本机环境录音存证（权限已在弹窗前确认）
        val recIntent = Intent(this, RecordingGuardService::class.java).apply {
            action = RecordingGuardService.ACTION_START
            putExtra(RecordingGuardService.EXTRA_REASON, "SOS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(recIntent)
        else startService(recIntent)

        ApiClient.reportRiskEvent(
            eventType = "SOS",
            severity  = "HIGH",
            details   = details,
            onSuccess = {
                runOnUiThread { toast("🆘 SOS 已发送！守护人已收到通知，环境录音存证中。") }
            },
            onError = { err ->
                runOnUiThread { toast("❌ SOS 发送失败：$err，请检查网络连接") }
            }
        )
    }

    // ──────────────────────────────────────────
    //  启动/停止守护服务
    // ──────────────────────────────────────────
    private fun startGuardServices() {
        val fgIntent = Intent(this, ForegroundGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fgIntent)
        } else {
            startService(fgIntent)
        }

        // 如果有位置权限，同时启动位置守护服务
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val locIntent = Intent(this, LocationGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(locIntent)
            } else {
                startService(locIntent)
            }
        }
    }

    private fun stopGuardServices() {
        stopService(Intent(this, ForegroundGuardService::class.java))
        stopService(Intent(this, LocationGuardService::class.java))
    }

    /**
     * 首次启动权限引导：自启动/后台弹出界面/锁屏显示属于厂商定制权限，
     * 系统安全机制禁止三方 App 静默自授权，唯一合规做法就是引导用户一键跳转开启。
     * 每次安装只弹一次，避免打扰。
     */
    private fun maybeShowVendorPermissionGuide() {
        val prefs = getSharedPreferences("vendor_perm_guide", Context.MODE_PRIVATE)
        if (prefs.getBoolean("guide_shown", false)) return
        prefs.edit().putBoolean("guide_shown", true).apply()

        AlertDialog.Builder(this)
            .setTitle("⚠️ 请开启守护必要权限")
            .setMessage(
                "为了让子女的「紧急打断」警报在锁屏和后台也能弹出，请开启以下权限：\n\n" +
                    "1️⃣ 通知权限（如弹出系统询问请点「允许」）\n" +
                    "2️⃣ 自启动\n" +
                    "3️⃣ 后台弹出界面\n" +
                    "4️⃣ 锁屏显示\n\n" +
                    VendorPermissionHelper.guideHint(this)
            )
            .setPositiveButton("去开启") { _, _ ->
                // 先申请 Android 13+ 通知运行时权限，再跳厂商专属设置页
                checkAndRequestPermissions()
                VendorPermissionHelper.openPermissionGuide(this)
            }
            .setNegativeButton("暂时跳过") { _, _ ->
                toast("⚠️ 未开启权限时，后台紧急打断警报可能无法弹出")
            }
            .setCancelable(false)
            .show()
    }

    // ──────────────────────────────────────────
    //  权限申请
    // ──────────────────────────────────────────
    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        // READ_PHONE_STATE：Phase 1 的 CallRiskWatcher（PhoneStateListener）现在真正使用它了。
        // 仍然**不要**申请 READ_CALL_LOG：该权限已在 Manifest 移除，声明了却不申请
        // 会让 checkSelfPermission 恒为 DENIED，形成静默空循环。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_PHONE_STATE)
        // Phase 1 陌生号码判定（1-4）：读通讯录比对来电是否熟人。
        // 拒绝授权时判定降级为"未知"（绝不把"查不了"当"陌生"上报，否则每通电话都是误报），
        // 时长/频次/行为联动判定不受影响
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CONTACTS)
        // Android 13+ 通知运行时权限：紧急打断的后台全屏警报依赖通知通道，未授权会被系统静默丢弃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_CALL_LOG)
    }

    /**
 * 定位权限申请入口（前台 + 后台）。
 *
 * ## 为什么要分版本
 * `LocationManager.requestLocationUpdates()` 的回调只在 App 前台时投递，
 * 除非持有 `ACCESS_BACKGROUND_LOCATION`。只拿到「仅在使用中允许」的话，
* 老人一退出 App，位置上报就永久停止 —— 而前台服务还挂着通知栏，
    * 界面上看不出任何异常。2026-10-08 线上事故就是这个形态。
    *
    * 而"怎么申请后台定位"按版本而异（详见 [LocationPermissionPlan]）：
    * Android 10 可打包请求，Android 11+ 系统明令禁止打包，只能跳设置页。
 *
 * 分支逻辑抽成了纯函数并有单元测试钉住，别再往回挪。
 */
    private fun requestLocationPermission() {
        val foreground = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        when (LocationPermissionPlan.plan(Build.VERSION.SDK_INT, foreground, background)) {

            LocationPermissionPlan.Action.ALREADY_GRANTED -> {
                toast("位置权限已授权（含后台）✅")
                ensureGuardRunning()
            }

            LocationPermissionPlan.Action.REQUEST_FOREGROUND -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION
                )
            }

            LocationPermissionPlan.Action.REQUEST_FOREGROUND_AND_BACKGROUND -> {
                // 唯一允许把后台定位放进同一个请求数组的版本（Android 10）
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ),
                    REQ_LOCATION
                )
            }

            LocationPermissionPlan.Action.REDIRECT_TO_SETTINGS -> {
                // Android 11+：系统禁止打包请求，必须由用户在设置页自行选择「始终允许」。
                // 这类权限 App 无权代替用户决定，所以只能引导 —— 并且必须说清为什么。
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 还差最后一步：位置权限改「始终允许」")
                    .setMessage(
                        "现在只是「仅在使用中允许」，一旦退出本应用，系统就会停止上报位置，\n" +
                            "子女端会以为老人一直没动 —— 而实际上是守护已经停了。\n\n" +
                            "即将跳转系统设置，请点：\n" +
                            "　权限 → 位置信息 → 选择「始终允许」\n\n" +
                            "（不做这一步，位置防護只是看起来在运行。）"
                    )
                    .setPositiveButton("去设置") { _, _ ->
                        SystemPermissionState.openAppLocationSettings(this)
                    }
                    .setNegativeButton("暂不开启") { _, _ ->
                        toast("⚠️ 未开启后台定位时，退出应用后位置将停止上报")
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onPause() {
        // 与 onResume 成对：离开前台后紧急警报必须改走全屏意图通知通道
        com.antifraud.guard.util.EmergencyAlertLauncher.isAppInForeground = false
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("位置权限授权成功！敏感地点停留感知已开启。")
                // 重新启动位置守护服务
                val locIntent = Intent(this, LocationGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(locIntent)
                else startService(locIntent)
            } else {
                toast("位置权限被拒绝，无法开启敏感地点停留感知。")
            }
        } else if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("录音权限已授权，继续求助流程。")
                showSosConfirmDialog()  // 权限到位，重新弹出确认弹窗
            } else {
                // 录音权限被拒：仍然允许求助，但不启动录音存证
                AlertDialog.Builder(this)
                    .setTitle("🆘 继续紧急求助？")
                    .setMessage("录音权限被拒绝，本次求助将仅发送实时位置，无法录音存证。")
                    .setPositiveButton("仍要求助") { _, _ -> triggerSos() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else if (requestCode == REQ_CALL_LOG) {
            // 通知权限被拒时给出明确引导（与紧急打断警报能否弹出直接相关）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                toast("⚠️ 未开启通知权限，后台紧急打断警报将无法弹出，请到系统设置开启")
            }
        }
    }

    // ──────────────────────────────────────────
    //  辅助方法
    // ──────────────────────────────────────────
    private fun isServiceRunning(cls: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    /**
     * 从服务器拉取本老人账号（elderId）的绑定码并显示。
     * 服务器是绑定码唯一事实源；同时下发已绑定守护人的姓名/手机号用于界面展示。
     * 网络失败时回退显示本地缓存码。
     */
    private fun syncBindCodeFromServer() {
        ApiClient.syncElderBindCode(
            onSuccess = { res ->
                GuardConfig.bindCode = res.optString("bindCode", "")
                tvBindCode.text = GuardConfig.bindCode

                applyBoundFamily(res.optJSONObject("boundFamily"))
                // 云端规则一并恢复（换机后本地是默认值，必须用云端那份覆盖）
                applyCloudSettings(res.optJSONObject("guardSettings"))
                refreshStatus()
                // 守护人信息是刚拿到的，指南页可能已经用旧的空值构建过了，必须重建
                refreshGuidePageIfBuilt()
            },
            onError = { err ->
                // 网络不通时保留本地码，避免界面空白
                if (GuardConfig.bindCode.isNotEmpty()) {
                    tvBindCode.text = GuardConfig.bindCode
                    toast("⚠️ 绑定码未同步到服务器（$err），当前显示本地缓存")
                }
            }
        )
    }

    /**
     * 把服务端下发的守护人信息落到本机。
     *
     * 分三种情况，行为完全不同，不能一刀切：
     *  1. 有真实绑定 → 写姓名/手机号（换手机后这一步就是"恢复"的全部动作）
     *  2. 明确未绑定（服务端回 null）→ 清空本机残留，防止显示已解绑的旧号码
     *  3. 字段缺失（老服务端没这个字段）→ 什么都不动，避免把已有的绑定信息擦掉
     *
     * 第3 点很关键：早先的版本会把"取不到"和"没绑定"混为一谈，
     * 结果老服务端 + 已绑定的组合下，一进首页就把守护人信息清空了。
     */
    private fun applyBoundFamily(fam: JSONObject?) {
        if (fam == null) {
            // 字段缺失 vs 显式 null 靠 has() 区分，由调用方保证；这里收到 null 即视为未绑定
            GuardConfig.boundFamilyName = ""
            GuardConfig.boundFamilyPhone = ""
            return
        }
        val name = fam.optStringOrEmpty("name")
        if (name.isNotEmpty()) GuardConfig.boundFamilyName = name
        // pickPhone 会滤掉 wx_ 占位号和等于登录用户名的假号码
        GuardConfig.boundFamilyPhone = fam.pickPhone(username = GuardConfig.familyUsername)
    }

    /**
     * 应用云端防护规则，并把"恢复了哪些项"告诉用户。
     *
     * 静默恢复是不行的：老人看到阈值从 15 变成 10 会以为 App 出bug了，
     * 反过来看到什么都没变又会以为云端没生效。明确提示一次就没有这个误解。
     */
    private fun applyCloudSettings(settings: JSONObject?) {
        if (settings == null) return
        val applied = GuardConfig.applySettingsFromServer(settings)
        if (applied.isNotEmpty()) {
            refreshRecordingStatus()
            toast("☁️ 已从云端恢复防护设置：${applied.joinToString("、")}")
        }
    }

    /**
     * 指南页重建 —— 只在已经构建过时才重建。
     *
     * 为什么需要：buildGuidePage() 在构建那一刻就把"守护人电话"这个分支
     * 写死在 View 里了（显示号码行 or 显示"绑定后可见"提示，二选一）。
     * 换手机后本地本来是空的，而 syncBindCodeFromServer() 是异步的 ——
     * 用户手快点进"防护指南"就会看到未绑定的版本，数据回来后界面再也不变，
     * 除非他切走再切回来。这正是"明明绑定了却不显示"的表现。
     */
    private fun refreshGuidePageIfBuilt() {
        if (guidePageBuilt) buildGuidePage()
    }

    /**
     * 确认指南页要展示的守护人信息是最新的。
     *
     * 触发时机：进入指南页、以及从设置页/登记流程回来（onResume）。
     * 目的有两个：
     *  1. 换手机后本地本来是空的，靠这次拉取才能显示已绑定的子女
     *  2. 子女端刚刚完成绑定时，老人端不用退出重进就能看到号码出现
     *
     * 有并发去重：onResume 与导航点击可能连续触发两次，避免重复网络往返。
     */
    private fun ensureGuideDataFresh() {
        if (!GuardConfig.elderActivated || GuardConfig.elderId <= 0) return
        if (guideSyncInFlight) return
        guideSyncInFlight = true
        ApiClient.syncElderBindCode(
            onSuccess = { res ->
                guideSyncInFlight = false
                GuardConfig.bindCode = res.optString("bindCode", GuardConfig.bindCode)
                tvBindCode.text = GuardConfig.bindCode
                applyBoundFamily(res.optJSONObject("boundFamily"))
                applyCloudSettings(res.optJSONObject("guardSettings"))
                refreshStatus()
                refreshGuidePageIfBuilt()
            },
            onError = {
                guideSyncInFlight = false
                // 拉取失败就沿用本地缓存重建一次，别让界面停在旧状态
                refreshGuidePageIfBuilt()
            }
        )
    }

    private fun generateBindCode(): String {
        return (100000 + Random.nextInt(900000)).toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
