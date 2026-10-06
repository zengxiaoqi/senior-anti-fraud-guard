package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager
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
class FamilyHomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_LOGIN = "from_login"
    }

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)

        // 未登录直接回登录页
        if (GuardConfig.familyToken.isEmpty()) {
            startActivity(Intent(this, FamilyLoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_family_home)
        bottomNav = findViewById(R.id.bottom_nav)

        bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_alerts   -> AlertsFragment()
                R.id.nav_track    -> TrackFragment()
                R.id.nav_evidence -> EvidenceFragment()
                R.id.nav_guide    -> GuideFragment()
                else              -> DashboardFragment()
            }
            switchTo(fragment)
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_dashboard
        }

        // 启动实时通道并注册预警弹窗
        FamilyWebSocketManager.init(this)
        FamilyWebSocketManager.setAlertListener { data -> showRiskAlert(data) }
        FamilyWebSocketManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyWebSocketManager.setAlertListener(null)
        FamilyWebSocketManager.stop()
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /** 收到 RISK_ALERT：弹窗提示，可选远程打断（对齐小程序 app.js 的 showModal 逻辑） */
    private fun showRiskAlert(data: JSONObject) {
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
                startActivity(Intent(this, RoleSelectActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
