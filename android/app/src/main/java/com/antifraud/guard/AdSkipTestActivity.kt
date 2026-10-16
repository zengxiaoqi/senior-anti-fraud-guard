package com.antifraud.guard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.antifraud.guard.service.AdSkipAccessibilityService
import com.antifraud.guard.util.AdSkipStats
import com.antifraud.guard.util.UiPrefs

/**
 * 「测试拦截效果」自检页：本地模拟一个带倒计时「跳过」按钮的开屏广告。
 *
 * ## 为什么要有这一页
 * 广告拦截是"点了但看不出点什么"的功能 —— 真广告被点掉后老人根本不知道
 * 刚才发生了什么。没有自检入口，开关打开后用户唯一能确认它工作的方式
 * 是"随便打开十个 App 看还有没有广告"，成本高到等于没有确认。
 *
 * ## 怎么判断这次点击是拦截服务点的还是人手点的
 * 服务点击成功后会往 [AdSkipStats] 记一笔（含时间戳）。点击回调里比对：
 * 最近一笔记录是"本包 + 本按钮文案 + 3 秒内" → 服务点的；
 * 否则是人手点的（说明开关没开或没授权）。这比"看反应速度"可靠。
 */
class AdSkipTestActivity : BaseActivity() {

    private lateinit var btnSkip: Button
    private lateinit var tvResult: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** 广告展示时刻（用于展示"多久后被点掉"） */
    private var shownAt = 0L

    /** 倒计时剩余秒数 */
    private var remain = 3

    /** 本页是否已出结果（防止倒计时跑完后重复渲染） */
    private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            remain -= 1
            if (remain > 0) {
                btnSkip.text = "跳过 $remain"
                handler.postDelayed(this, 1000)
            } else {
                btnSkip.text = "跳过"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_skip_test)

        findViewById<TextView>(R.id.tv_title).text = "测试拦截效果"
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        btnSkip = findViewById(R.id.btn_skip)
        tvResult = findViewById(R.id.tv_result)

        // 清掉历史记录再开始：否则上一次真实拦截的时间戳会干扰判定
        AdSkipStats.reset(this)
        resetAd()

        btnSkip.setOnClickListener { onSkipClicked() }
        findViewById<Button>(R.id.btn_retry).setOnClickListener { resetAd() }

        // 防护罩预览：手动弹一次全屏罩（4 秒自动撤），让家属/老人亲眼看到
        // "误触被吸收"长什么样。走静态实例引用，服务没连接时给明确提示。
        findViewById<Button>(R.id.btn_shield_preview).setOnClickListener {
            val svc = AdSkipAccessibilityService.instance
            if (svc != null) {
                svc.debugPreviewShield()
            } else {
                tvResult.visibility = android.view.View.VISIBLE
                tvResult.text = "拦截服务未连接，请先开启系统无障碍授权。"
                tvResult.setTextColor(0xFFF59E0B.toInt())
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun resetAd() {
        finished = false
        remain = 3
        shownAt = System.currentTimeMillis()
        btnSkip.text = "跳过 $remain"
        btnSkip.isEnabled = true
        tvResult.text = ""
        tvResult.visibility = android.view.View.GONE
        handler.postDelayed(tick, 1000)
    }

    private fun onSkipClicked() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        btnSkip.isEnabled = false

        val elapsed = System.currentTimeMillis() - shownAt
        tvResult.visibility = android.view.View.VISIBLE
        tvResult.text = "正在判定这次点击来自拦截服务还是手动…"
        tvResult.setTextColor(UiPrefs.dimColor(this))

        // 关键：拦截服务是在 performAction 返回之后才写统计的，而点击回调在
        // performAction 期间就触发了 —— 当场读统计会把"服务点的"误判成"手动点的"。
        // 延迟一拍再读，两种时序（同步/异步派发点击）下都成立。
        handler.postDelayed({ renderResult(elapsed) }, 400)
    }

    private fun renderResult(elapsed: Long) {
        val last = AdSkipStats.lastSkip(this)
        // 只有本 App 的拦截服务会往统计里记本包的点击；
        // 3 秒窗口内 + 包名匹配 = 这次点击就是拦截服务点的
        val byService = last != null &&
            last.first == packageName &&
            System.currentTimeMillis() - last.third < 3000

        tvResult.text = if (byService) {
            "✅ 拦截成功！\n\n拦截服务在广告出现 ${elapsed} 毫秒后自动点掉了「${btnSkip.text}」。" +
                "\n\n真实开屏广告也是这个流程：出现 → 被点掉 → 直接进应用。"
        } else {
            "这次是手动点的（${elapsed} 毫秒）。\n\n拦截没有生效，请确认两件事：\n" +
                "1. 上一页的「拦截开屏广告」开关是打开状态；\n" +
                "2. 系统设置 → 无障碍 → 长者防诈守护 是开启状态。"
        }
        tvResult.setTextColor(if (byService) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())
    }
}
