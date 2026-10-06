package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager

/**
 * 角色选择页（Launcher）
 *  - 首次启动：选择「老人端 / 子女端」，选择结果持久化
 *  - 之后启动：按记住的角色直接进入对应端
 */
class RoleSelectActivity : AppCompatActivity() {

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

    private fun goElder() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goFamily() {
        val target = if (GuardConfig.familyToken.isNotEmpty()) FamilyHomeActivity::class.java
                     else FamilyLoginActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
