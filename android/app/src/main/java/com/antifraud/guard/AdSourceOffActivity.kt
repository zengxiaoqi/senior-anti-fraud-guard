package com.antifraud.guard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.antifraud.guard.util.FamilyData
import com.antifraud.guard.util.UiPrefs

/**
 * 广告源头关闭：把每类广告的开关页**直达**出来，而不是只给文字指引。
 *
 * ## 能力边界（为什么不是全自动）
 * 关广告开关本身是别的 App 的界面，无障碍点击竞态多、且摇一摇/通知广告拦不住，
 * 所以这里借鉴 android-ad-cleaner（MIT）的「入口速查表」：每个品牌家族 × 分组
 * 三档降级——①直达页面（实测过的包名/Activity）→ ②拉起对应 App → ③打开系统
 * 设置 + 菜单路径提示。全自动拨开关（pm suspend 等）需要 shell 权限，App 内做不到。
 *
 * ## 直达失败的诚实处理
 * 厂商页面可能没导出/被组件权限保护/ROM 裁剪，直达抛异常就静默换下一档；
 * 全部失败时落到系统设置首页并把菜单路径 toast 出来——用户永远有路可走。
 */
class AdSourceOffActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_source_off)

        findViewById<TextView>(R.id.tv_title).text = "关闭广告（源头）"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        render()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.container_entries)
        val family = FamilyData.familyOf(Build.BRAND)
        val brandText = "${Build.BRAND} ${Build.MODEL}".trim()

        // 头卡：识别结果 + 用法说明
        addCard(container) { card ->
            card.addView(boldText("已识别手机：$brandText"))
            card.addView(dimText(
                if (family != null) "系统：${FamilyData.label(family)}。每类广告点「直达」打开对应开关页，找到关掉即可；"
                else "本机型暂无直达表，可按下方通用方法关闭；"
            ))
            card.addView(dimText(
                "关一次永久生效，比弹广告后再拦截彻底。误关提醒类开关会影响天气预警/订单通知，每页注意事项都写了。"
            ))
        }

        val rows = FamilyData.rowsFor(family)
        if (rows == null) {
            // 未知品牌：给通用兜底（通知管理是所有品牌都有的直达）
            addCard(container) { card ->
                card.addView(boldText("通用：管理应用通知（营销通知广告的主要来源）"))
                card.addView(dimText("设置 → 通知管理 → 逐个 App 关掉营销/推广类通知"))
                card.addView(jumpButton("打开通知管理") { openOrToast(
                    // API 33 stubs 已删 ACTION_NOTIFICATION_SETTINGS 常量，
                    // 但 action 字符串 "android.settings.NOTIFICATION_SETTINGS" 仍然稳定
                    Intent("android.settings.NOTIFICATION_SETTINGS"), "通知管理") })
            }
        } else {
            for (row in rows) addEntryCard(container, row)
        }

        // 尾注：更彻底的手段
        addCard(container) { card ->
            card.addView(boldText("想更彻底？"))
            card.addView(dimText(
                "① 完整关闭清单（7 品牌 112 项）在「小程序 → 防护指南 → 关闭老人手机里的广告」，子女可远程照着做；\n" +
                "② 摇一摇广告、通知栏广告由拦截开屏广告服务兜底（防护规则 → 拦截开屏广告）；\n" +
                "③ 挂起广告 SDK 包等更强手段需要电脑连手机操作，家属到场时一次配好。"
            ))
        }
    }

    private fun addEntryCard(container: LinearLayout, row: FamilyData.EntryRow) {
        addCard(container) { card ->
            card.addView(boldText(row.name))
            card.addView(dimText("路径：${row.menu}"))
            if (row.searchHint.isNotBlank()) card.addView(dimText("找不到时：在设置里搜索「${row.searchHint}」"))
            if (row.note.isNotBlank()) card.addView(dimText("注意：${row.note}"))
            card.addView(jumpButton("直达开关页") { jump(row) })
        }
    }

    /** 三档降级：直达组件 → action/uri 深链 → 拉起对应 App → 系统设置 + 路径提示 */
    private fun jump(row: FamilyData.EntryRow) {
        // ① 实测过的直达页面（可能没导出/被保护，失败换下一档）
        for (d in row.directs) {
            val intent = Intent().setClassName(d.pkg, d.activity)
            if (tryStart(intent)) return
        }
        // ② 厂商私有 action（如 ColorOS 的 oppo.intent.action.APP_SETTINGS）
        for (a in row.actions) {
            val intent = Intent(a.action).apply { if (a.pkg.isNotBlank()) setPackage(a.pkg) }
            if (tryStart(intent)) return
        }
        for (u in row.uris) {
            if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return
        }
        // ③ 先拉起对应 App，让用户在 App 里点菜单
        if (row.app.isNotBlank()) {
            val launch = packageManager.getLaunchIntentForPackage(row.app)
            if (tryStart(launch)) {
                toast("已打开「相关应用」，请按路径进入设置")
                return
            }
        }
        // ④ 兜底：系统设置首页 + 路径提示（永远有路可走）
        tryStart(Intent(Settings.ACTION_SETTINGS))
        toast("直达不可用，请按提示路径操作：${row.menu}")
    }

    private fun openOrToast(intent: Intent, name: String) {
        if (!tryStart(intent)) toast("无法打开$name，请在系统设置里查找")
    }

    private fun tryStart(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            // SecurityException 等（组件未导出/权限保护）也算这一档不通
            Log.d("AdSourceOff", "直达被拒: ${intent.component}", e)
            false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── 动态卡片构建（UiPrefs 语义色，深浅主题都可读） ──────────────

    private fun addCard(container: LinearLayout, build: (LinearLayout) -> Unit) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_theme)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        build(card)
        container.addView(card)
    }

    private fun boldText(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(UiPrefs.color(this@AdSourceOffActivity, R.attr.appText))
        setPadding(0, 0, 0, 8)
    }

    private fun dimText(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(UiPrefs.color(this@AdSourceOffActivity, R.attr.appTextDim))
        setLineSpacing(0f, 1.3f)
        setPadding(0, 0, 0, 10)
    }

    private fun jumpButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        setBackgroundResource(R.drawable.bg_btn_tonal)
        setTextColor(UiPrefs.color(this@AdSourceOffActivity, R.attr.appText))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4 }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
