package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager

/**
 * 角色选择页（Launcher）
 *  - 首次启动：选择「老人端 / 子女端」，选择结果持久化
 *  - 之后启动：按记住的角色直接进入对应端
 */
class RoleSelectActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        FamilyWebSocketManager.init(this)

        // 已记住角色则直接进入
        when (GuardConfig.appRole) {
            "elder" -> { goElder(); return }
            "family" -> { goFamily(); return }
        }

        setContentView(R.layout.activity_role_select)

        findViewById<Button>(R.id.btn_role_elder).setOnClickListener {
            GuardConfig.appRole = "elder"
            goElder()
        }
        findViewById<Button>(R.id.btn_role_family).setOnClickListener {
            GuardConfig.appRole = "family"
            goFamily()
        }
    }

    // NEW_TASK|CLEAR_TASK：角色切换会经过这里，必须清干净旧角色的 Activity 残留，
    // 否则"老人端 → 切换角色 → 子女端"之后按返回键会退回还在任务栈底部的老人端首页
    private fun goElder() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun goFamily() {
        val target = if (GuardConfig.familyToken.isNotEmpty()) FamilyHomeActivity::class.java
                     else FamilyLoginActivity::class.java
        startActivity(
            Intent(this, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}
