package com.antifraud.guard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager
import com.antifraud.guard.family.RiskAlertPolicy
import com.antifraud.guard.family.AlertPresentation
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

        // App 级设置入口（版本升级 / 字体主题 / 账号）现在在控制台页标题行右端，
        // 由 DashboardFragment 绑定 —— 它只在控制台这个 tab 出现，不再是常驻顶栏

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

        // 预热"老人手机号"缓存：失联/跌倒/SOS 弹窗的「立即联系老人」要拨这个号。
        // 登录时已随 boundUser 缓存过一份，这里再静默刷一次，保证弹窗弹出时能秒拨。
        if (GuardConfig.isFamilyBound) {
            com.antifraud.guard.api.ApiClient.fetchBoundElderPhone { }
        }

        // 启动静默查一次新版本（有新版本才弹窗，同一版本一天最多提示一次）
        com.antifraud.guard.util.AppUpdateUi.checkOnStart(this)
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回时，接着把没走完的下载/安装做完
        com.antifraud.guard.util.AppUpdateUi.onResume(this)
        recoverLostPick()
    }

    /**
     * 地图选点的进程级兜底：把「选完点被踢回控制台」从死路变成自动找回。
     *
     * 正常路径下，选点结果由登记页（GeofenceManageActivity / HomeBaseActivity）
     * 的 ActivityResult 回调消费，存根即时清掉——走不到这里。能在这里读到
     * 15 分钟内的新鲜存根，说明结果随进程丢了：选点页销毁触发高德引擎原生
     * 崩溃（SIGSEGV）时进程被整个带走，返回栈清空、ActivityResult 蒸发，
     * 用户重启 App 就落回本页（控制台），表现正是「确定选点后自动回到上一页」。
     *
     * 本页在栈底，登记页不存在于屏幕上时才会 resume 到这里，弹窗不会打断任何
     * 进行中的表单。确认后跳回对应页面，由其 onResume 从存根恢复坐标。
     */
    private fun recoverLostPick() {
        val p = com.antifraud.guard.util.MapPickPending.peek(this) ?: return
        val targetIntent = when (p.target) {
            com.antifraud.guard.util.MapPickPending.TARGET_HOME_BASE ->
                Intent(this, com.antifraud.guard.family.HomeBaseActivity::class.java)
            // 旧版本存根没有 target 字段：按最高频的敏感地点登记页处理
            else ->
                Intent(this, com.antifraud.guard.family.GeofenceManageActivity::class.java)
        }
        AlertDialog.Builder(this)
            .setTitle("选点结果待恢复")
            .setMessage("刚才在地图上选的点还没保存成功（选点页意外退出）。\n\n要回到刚才的页面继续吗？")
            .setPositiveButton("回去继续") { _, _ -> startActivity(targetIntent) }
            .setNegativeButton("丢弃") { _, _ ->
                com.antifraud.guard.util.MapPickPending.consume(this)
            }
            .setCancelable(false)
            .show()
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
     *
     * 弹窗文案与主按钮由 [AlertPresentation] 按事件类型决定：
     * **失联与跌倒不给"远程打断"**。
     * 老人联系不上时远程指令必然失败（服务端会回"老人端当前不在线"），
     * 在本该联系不上的场景反复提供一个必然无效的按钮，
     * 会让子女从"立刻打电话"退化成"点一下试试"，直接延误救援。
     */
    private fun showRiskAlert(data: JSONObject) {
        if (!RiskAlertPolicy.shouldInterrupt(data)) return

        val eventType = data.optString("event_type", "风险事件")
        val p = AlertPresentation.of(eventType, data.optString("severity"), data.optJSONObject("details"))

        val builder = AlertDialog.Builder(this)
            .setTitle(p.title)
            .setMessage(p.message)
            .setNegativeButton("忽略", null)

        when (p.action) {
            AlertPresentation.Action.INTERRUPT -> builder.setPositiveButton(p.primaryLabel) { _, _ ->
                FamilyWebSocketManager.sendRemoteInterrupt(
                    "App 强打断：子女提醒您立即终止当前异常通话！"
                ) { ok, msg ->
                    val tip = if (ok) "已触发远程打断" else "触发失败：$msg"
                    android.widget.Toast.makeText(this, tip, android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            AlertPresentation.Action.CALL_ELDER -> {
                // 「立即联系老人」= 拨老人的手机号。
                // 老人端此刻可能没信号、没电、或根本不在子女身边，
                // 唯一有效的动作永远是"人打给老人"——拨子女自己的号码
                // （familyMobile 是老人端紧急警报回拨子女用的）等于让子女空等。
                builder.setPositiveButton(p.primaryLabel) { _, _ -> callElder() }
            }
        }
        builder.show()
    }

    /**
     * 「立即联系老人」：拨已绑定老人的手机号。
     *
     * 号码优先用 [GuardConfig.boundElderPhone] 缓存（登录时随 boundUser 下发），
     * 缓存为空时现拉一次 /api/auth/user/:id —— SOS/跌倒这条路上多一次请求，
     * 也比打开空拨号盘、或者拨到子女自己号上强。
     */
    private fun callElder() {
        val cached = GuardConfig.boundElderPhone
        if (cached.isNotEmpty()) {
            dialPhone(cached)
            return
        }
        if (!GuardConfig.isFamilyBound) {
            Toast.makeText(this, "尚未绑定老人，无法拨号", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在获取老人手机号…", Toast.LENGTH_SHORT).show()
        com.antifraud.guard.api.ApiClient.fetchBoundElderPhone { phone ->
            if (phone.isNotEmpty()) {
                dialPhone(phone)
            } else {
                Toast.makeText(this, "老人未登记手机号，请直接联系或尽快上门查看", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 打开拨号盘（ACTION_DIAL 而非 CALL：不给 App 自动拨号权限，也不静默拨号） */
    private fun dialPhone(mobile: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$mobile") })
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开拨号盘：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 控制台「查看全部风险动态」跳转：切到防诈告警 Tab（走同一套保活式切换）。
     *
     * @param filter 自动过滤口径（AlertsFragment 的 HIGH/MID/WEEK），null = 不过滤。
     *   控制台 Hero「最近动态」卡跳进来时带上：高危优先过滤，其次需留意——
     *   用户点的是"N 条高危"，落到的列表就该只有高危，而不是一整页流水账里自己找。
     */
    fun openAlertsTab(filter: String? = null) {
        if (filter != null) AlertsFragment.requestFilter(filter)
        bottomNav.selectedItemId = R.id.nav_alerts
    }

    /** 控制台 Hero「最新位置」卡跳转：切到亲情轨迹 Tab（默认视野即最新位置） */
    fun openTrackTab() {
        bottomNav.selectedItemId = R.id.nav_track
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
