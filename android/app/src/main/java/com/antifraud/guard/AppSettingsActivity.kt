package com.antifraud.guard

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.family.ElderGuardSettingsActivity
import com.antifraud.guard.family.GeofenceManageActivity
import com.antifraud.guard.service.FamilyWebSocketManager
import com.antifraud.guard.util.AppUpdateManager
import com.antifraud.guard.util.AppUpdateUi
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 通用设置页（老人端 / 子女端共用）。
 *
 * 为什么单独建一页而不并入原 SettingsActivity：
 * 那一页是**防护规则**页（预警阈值、老人资料、守护自检），语义是"守护怎么配"；
 * 而"App 装在哪个版本、字要大还是要小、要不要换号退出"是**本机/账号**层面的事。
 * 两者混在一起的直接后果就是 —— 版本号被挤到页面最底部，
 * 远程帮老人排查时永远要问"你现在装的是哪个版本"。
 *
 * 本页把版本升级放在**第一屏最上面**，就是这个原因。
 */
class AppSettingsActivity : BaseActivity() {

    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvDisplayStatus: TextView

    /** 保存/测试后端连接期间防止连点 */
    private var testingBackend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_app_settings)

        tvUpdateStatus = findViewById(R.id.tv_update_status)
        tvDisplayStatus = findViewById(R.id.tv_display_status)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        setupVersionCard()
        setupDisplayCard()
        setupAccountCard()
        setupMoreCard()
        setupBackendCard()
    }

    // ──────────────────────────────────────────
    //  版本与升级
    // ──────────────────────────────────────────

    private fun setupVersionCard() {
        findViewById<TextView>(R.id.tv_app_version).text =
            "当前版本：v${AppUpdateManager.currentVersionName(this)}" +
            "（版本号 ${AppUpdateManager.currentVersionCode(this)}）"
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            tvUpdateStatus.text = "正在检查更新…"
            AppUpdateUi.checkManually(this) { status -> tvUpdateStatus.text = status }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」的系统设置页返回时，接着把没走完的下载/安装做完
        AppUpdateUi.onResume(this)
    }

    // ──────────────────────────────────────────
    //  显示：字体大小 + 深浅主题
    // ──────────────────────────────────────────

    private fun setupDisplayCard() {
        val fontButtons = listOf(
            findViewById<Button>(R.id.btn_font_standard) to UiPrefs.FONT_STANDARD,
            findViewById<Button>(R.id.btn_font_large) to UiPrefs.FONT_LARGE,
            findViewById<Button>(R.id.btn_font_xlarge) to UiPrefs.FONT_XLARGE
        )
        fontButtons.forEach { (btn, scale) ->
            btn.setOnClickListener {
                if (UiPrefs.fontScale(this) == scale) return@setOnClickListener
                UiPrefs.setFontScale(this, scale)
                // 先弹提示再重建：recreate 之后这个 Activity 实例就作废了，
                // 拿作废的上下文去弹 Toast，部分机型会直接抛 "Activity has leaked window"
                toast("字体已切换为「${UiPrefs.fontLabel(this)}」，正在刷新…")
                // 字体倍率写在 Configuration 上，必须重建才能对所有已加载的 View 生效
                recreate()
            }
        }

        val themeButtons = listOf(
            findViewById<Button>(R.id.btn_theme_system) to UiPrefs.ThemeMode.SYSTEM,
            findViewById<Button>(R.id.btn_theme_light) to UiPrefs.ThemeMode.LIGHT,
            findViewById<Button>(R.id.btn_theme_dark) to UiPrefs.ThemeMode.DARK
        )
        themeButtons.forEach { (btn, mode) ->
            btn.setOnClickListener {
                if (UiPrefs.themeMode(this) == mode) return@setOnClickListener
                UiPrefs.setThemeMode(this, mode)
                toast("已切换为「${mode.label}」主题，正在重启应用…")
                restartApp()
            }
        }

        renderDisplayOptions(fontButtons, themeButtons)
    }

    /**
     * 主题切换必须**重启整个进程**，不能用 recreate()：
     * 带 `?attr/` 的 shape drawable（卡片底色那些）是在 Resources 级的
     * drawable 缓存里解析主题色的，recreate 只重建 Activity、不清这个缓存，
     * 旧主题解析出的颜色会被继续复用 —— 实测表现是"页面白了、首页那张卡还是黑的"。
     * 进程重启后所有资源按新主题重新解析，一处不漏。
     */
    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: Intent(this, RoleSelectActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    /**
     * 渲染分段按钮的选中态。
     *
     * 这一步不能省：这几个按钮长得一模一样，如果当前选中项没有任何视觉标记，
     * 用户点完"大"再回来，根本看不出自己选的是哪个 —— 等于设置不可读。
     */
    private fun renderDisplayOptions(
        fontButtons: List<Pair<Button, Float>>,
        themeButtons: List<Pair<Button, UiPrefs.ThemeMode>>
    ) {
        val curFont = UiPrefs.fontScale(this)
        fontButtons.forEach { (btn, scale) -> markSelected(btn, scale == curFont) }

        val curTheme = UiPrefs.themeMode(this)
        themeButtons.forEach { (btn, mode) -> markSelected(btn, mode == curTheme) }

        tvDisplayStatus.text = "当前：字体「${UiPrefs.fontLabel(this)}」· 主题「${UiPrefs.themeLabel(this)}」"
    }

    private fun markSelected(btn: Button, selected: Boolean) {
        btn.backgroundTintList = ColorStateList.valueOf(
            if (selected) Color.parseColor("#10B981") else attrColor(R.attr.appCardAlt)
        )
        btn.setTextColor(if (selected) Color.WHITE else attrColor(R.attr.appText))
    }

    /** 取当前主题下的语义色（布局里写的是 ?attr/xxx，代码里只能这样取） */
    private fun attrColor(attr: Int): Int = UiPrefs.color(this, attr)

    // ──────────────────────────────────────────
    //  账号
    // ──────────────────────────────────────────

    private fun setupAccountCard() {
        val isElder = GuardConfig.appRole == "elder"

        findViewById<Button>(R.id.btn_change_phone).apply {
            if (isElder) {
                text = "📱 修改手机号 / 老人资料"
                setOnClickListener {
                    // 手机号与姓名的编辑、保存、上行链路都已在防护规则设置页实现，
                    // 这里复用而不是复制一份 —— 两处都能改就会改出两份不一致的资料
                    startActivity(Intent(this@AppSettingsActivity, SettingsActivity::class.java))
                }
            } else {
                visibility = android.view.View.GONE
            }
        }

        findViewById<Button>(R.id.btn_switch_role).setOnClickListener { confirmSwitchRole() }
        findViewById<Button>(R.id.btn_logout).setOnClickListener { confirmLogout() }
    }

    private fun confirmSwitchRole() {
        AlertDialog.Builder(this)
            .setTitle("切换角色")
            .setMessage("将返回角色选择页，重新选择本机作为老人端或子女端。确定继续？")
            .setPositiveButton("确定") { _, _ ->
                if (GuardConfig.appRole == "family") {
                    FamilyWebSocketManager.stop()
                } else {
                    // 老人端切走前先停守护服务与长连接：不然后台还在录音/上报，
                    // 通知栏的"守护中"点击又会打开老人端首页（账号会话保留，切回即恢复）
                    stopService(Intent(this, com.antifraud.guard.service.RecordingGuardService::class.java))
                    stopService(Intent(this, com.antifraud.guard.service.ForegroundGuardService::class.java))
                    stopService(Intent(this, com.antifraud.guard.service.LocationGuardService::class.java))
                    com.antifraud.guard.service.GuardWebSocketManager.stop()
                }
                GuardConfig.appRole = ""
                // NEW_TASK|CLEAR_TASK + finishAffinity：清掉旧角色的 Activity 残留，
                // 否则子女端按返回键会退回栈底还在的老人端首页
                startActivity(
                    Intent(this, RoleSelectActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
                finishAffinity()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLogout() {
        val isElder = GuardConfig.appRole == "elder"
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage(
                if (isElder) "退出后这台手机将不再守护。确定继续？"
                else "确定退出当前子女端账号？"
            )
            .setPositiveButton("退出") { _, _ ->
                if (isElder) {
                    // 走与首页退出完全相同的收尾（停录音 → 停守护 → 停长连接 → 关总开关）
                    com.antifraud.guard.util.ElderSession.logout(this)
                    startActivity(
                        Intent(this, RoleSelectActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finishAffinity()
                } else {
                    FamilyWebSocketManager.stop()
                    GuardConfig.clearFamilySession()
                    startActivity(Intent(this, FamilyLoginActivity::class.java))
                }
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ──────────────────────────────────────────
    //  更多
    // ──────────────────────────────────────────

    private fun setupMoreCard() {
        val isElder = GuardConfig.appRole == "elder"

        findViewById<Button>(R.id.btn_guard_rules).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    if (isElder) SettingsActivity::class.java
                    else ElderGuardSettingsActivity::class.java
                )
            )
        }

        findViewById<Button>(R.id.btn_health_check).apply {
            if (isElder) {
                setOnClickListener {
                    // 守护自检面板在防护规则设置页（含 6 项系统权限修复入口）
                    startActivity(Intent(this@AppSettingsActivity, SettingsActivity::class.java))
                }
            } else {
                visibility = android.view.View.GONE
            }
        }

        findViewById<Button>(R.id.btn_geofence).apply {
            if (isElder) {
                visibility = android.view.View.GONE
            } else {
                setOnClickListener {
                    startActivity(Intent(this@AppSettingsActivity, GeofenceManageActivity::class.java))
                }
            }
        }
    }

    // ──────────────────────────────────────────
    //  后端连接（从老人端首页搬来：属于"装好之后就再也不会碰"的一类）
    // ──────────────────────────────────────────

    private fun setupBackendCard() {
        val card = findViewById<android.view.View>(R.id.card_backend)
        if (GuardConfig.appRole != "elder") {
            card.visibility = android.view.View.GONE
            return
        }

        val etServerUrl = findViewById<EditText>(R.id.et_server_url)
        val tvBackendStatus = findViewById<TextView>(R.id.tv_backend_status)
        etServerUrl.setText(GuardConfig.serverUrl)

        findViewById<Button>(R.id.btn_save_url).setOnClickListener {
            if (testingBackend) return@setOnClickListener
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                toast("请输入有效的后端代理地址")
                return@setOnClickListener
            }
            testingBackend = true
            ApiClient.setServerBaseUrl(url)
            tvBackendStatus.setTextColor(attrColor(R.attr.appTextDim))
            tvBackendStatus.text = "后端连接：连接测试中..."
            ApiClient.reportRiskEvent(
                eventType = "DEVICE_ONLINE",
                severity = "LOW",
                details = JSONObject().apply {
                    put("type", "PING"); put("device", Build.MODEL)
                },
                onSuccess = {
                    runOnUiThread {
                        testingBackend = false
                        tvBackendStatus.text = "后端连接：已连通 ✅"
                        tvBackendStatus.setTextColor(Color.parseColor("#10B981"))
                        com.antifraud.guard.service.GuardWebSocketManager.start()
                        toast("✅ 成功连通后端服务器！守护长连接已激活。")
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        testingBackend = false
                        tvBackendStatus.text = "后端连接：连接失败 ❌"
                        tvBackendStatus.setTextColor(Color.parseColor("#EF4444"))
                        toast("❌ 连接失败: $err")
                    }
                }
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
