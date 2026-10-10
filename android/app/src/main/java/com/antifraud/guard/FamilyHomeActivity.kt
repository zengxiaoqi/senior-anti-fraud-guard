package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager
import com.antifraud.guard.family.RiskAlertPolicy
import com.antifraud.guard.family.DashboardFragment
import com.antifraud.guard.family.AlertsFragment
import com.antifraud.guard.family.TrackFragment
import com.antifraud.guard.family.EvidenceFragment
import com.antifraud.guard.family.GuideFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject

/**
 * 子女端（App 版）主界面：BottomNavigationView + 5 个功能页
 * 对齐小程序 5 个 tab：控制台 / 防诈告警 / 亲情轨迹 / 维权证据 / 防护指南
 */
class FamilyHomeActivity : BaseActivity() {

    companion object {
        const val EXTRA_FROM_LOGIN = "from_login"
    }

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        // 版本检查要走网络：必须让 ApiClient 读到用户配置的服务器地址，
        // 否则它会拿出厂默认域名去查（自建服务器的用户会一直"检查失败"）
        com.antifraud.guard.api.ApiClient.init(this)
        // 上报上次崩溃记录（elderId<=0 时自动跳过），诊断线上闪退用
        com.antifraud.guard.util.CrashReporter.reportPending(this)

        // 未登录直接回登录页
        if (GuardConfig.familyToken.isEmpty()) {
            startActivity(Intent(this, FamilyLoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_family_home)
        bottomNav = findViewById(R.id.bottom_nav)

        // 右上角 ⚙️：版本升级 / 字体主题 / 账号（子女端此前没有 App 级设置入口）
        findViewById<android.widget.Button>(R.id.btn_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        bottomNav.setOnItemSelectedListener { item ->
            // 保活式切换（2026-10-10 线上实证）：此前 replace() 每次切换都销毁重建
            // Fragment，轨迹页的高德 MapView 跟着"创建→立即销毁"，触发 SDK 内部
            // GL 渲染线程的原生 SIGSEGV（应用层延迟销毁也压不住）。改为 add/show/hide
            // 保活：切走的页面只是隐藏，地图对象原封不动，竞态根源直接消失。
            // setMaxLifecycle 让隐藏页降到 STARTED、显示页回到 RESUMED，
            // 各页面的 onResume/onPause 照常触发，onResume 里的数据刷新逻辑不受影响。
            val tag = "tab_${item.itemId}"
            val fm = supportFragmentManager
            val fragment = fm.findFragmentByTag(tag) ?: when (item.itemId) {
                R.id.nav_alerts   -> AlertsFragment()
                R.id.nav_track    -> TrackFragment()
                R.id.nav_evidence -> EvidenceFragment()
                R.id.nav_guide    -> GuideFragment()
                else              -> DashboardFragment()
            }
            switchTo(fragment, tag)
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_dashboard
        }

        // 启动实时通道并注册预警弹窗
        FamilyWebSocketManager.init(this)
        FamilyWebSocketManager.setAlertListener { data -> showRiskAlert(data) }
        FamilyWebSocketManager.start()

        // 启动静默查一次新版本（有新版本才弹窗，同一版本一天最多提示一次）
        com.antifraud.guard.util.AppUpdateUi.checkOnStart(this)
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回时，接着把没走完的下载/安装做完
        com.antifraud.guard.util.AppUpdateUi.onResume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyWebSocketManager.setAlertListener(null)
        FamilyWebSocketManager.stop()
    }

    private fun switchTo(fragment: Fragment, tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        // 必须隐藏所有已添加且不是目标的其他 Fragment。
        // 不能只用 findFragmentById：多个 Fragment 共享同一个 FrameLayout 时，
        // findFragmentById 只返回第一个添加的 Fragment（控制台页），导致其余 Fragment
        // 不会被 hide，新 Fragment 的视图被当前显示 Fragment 的视图遮挡在底层，
        // 表现为"切换底部菜单页面没反应"。
        fm.fragments.filter { it.isAdded && it !== fragment }.forEach { existing ->
            if (!existing.isHidden) {
                tx.hide(existing).setMaxLifecycle(existing, Lifecycle.State.STARTED)
            }
        }

        if (!fragment.isAdded) {
            tx.add(R.id.fragment_container, fragment, tag)
        }

        tx.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        tx.commit()
    }

    /**
     * 收到 RISK_ALERT：只对**值得打断**的高危事件弹窗，其余静默入列表。
     *
     * 以前对每条 RISK_ALERT 都弹，于是定位心跳（LOCATION_UPDATE，恒 LOW）
     * 也弹"长者正处于高危状态"，把真高危告警淹掉。
     */
    private fun showRiskAlert(data: JSONObject) {
        if (!RiskAlertPolicy.shouldInterrupt(data)) return

        val eventType = data.optString("event_type", "风险事件")
        AlertDialog.Builder(this)
            .setTitle("⚠️ 收到紧急防诈预警")
            .setMessage("长者正处于高危状态 ($eventType)，是否立即发起远程打断？")
            .setPositiveButton("强行打断") { _, _ ->
                FamilyWebSocketManager.sendRemoteInterrupt(
                    "App 强打断：子女提醒您立即终止当前异常通话！"
                ) { ok, msg ->
                    val tip = if (ok) "已触发远程打断" else "触发失败：$msg"
                    android.widget.Toast.makeText(this, tip, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("忽略", null)
            .show()
    }

    /** 切换角色（供 Fragment 调用） */
    fun switchRole() {
        AlertDialog.Builder(this)
            .setTitle("切换角色")
            .setMessage("将退出子女端界面，返回角色选择页。确定继续？")
            .setPositiveButton("确定") { _, _ ->
                FamilyWebSocketManager.stop()
                GuardConfig.appRole = ""
                // NEW_TASK|CLEAR_TASK + finishAffinity：清掉整个任务栈再进角色选择页。
                // 否则此前以老人端身份打开过的 MainActivity 还留在栈底，
                // 在子女端按返回键就会"突然切回老人端"。
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

    /** 退出登录（供 Fragment 调用）：清空会话回到登录页，方便切换账号/测试 */
    fun logout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定退出当前账号？")
            .setPositiveButton("退出") { _, _ ->
                FamilyWebSocketManager.stop()
                GuardConfig.clearFamilySession()
                startActivity(Intent(this, FamilyLoginActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
