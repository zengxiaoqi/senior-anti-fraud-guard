package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.antifraud.guard.BaseActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject

/**
 * 子女端「守护设置」四个子页面的共同基类。
 *
 * ## 为什么必须抽基类
 * 拆分前四个分区共用**一个**保存按钮，因此只需要一套读写代码。拆成四个独立页面后，
 * 若每个页面各写一份"拉取 → 渲染 → 提交 → 失败弹窗 → 返回拦截"，就是四份必然会漂的副本：
 * 保存失败的文案只要有一个页面写成"已保存"，子女就会以为改生效了，而老人端仍在按旧参数守护。
 *
 * ## 每页只提交自己那几个字段是安全的（前提，不是巧合）
 * 服务端 `mergeFamilySettings` 是 `Object.assign(存量, 增量)` 的**增量合并**，
 * 不是整包覆盖。所以"位置阈值页只提交 3 个字段"不会把通话阈值冲掉。
 * 这一点在 `services/guardSettingsPolicy.js` 里有明确注释与测试，
 * 改动本文件任何提交逻辑之前都要回去确认那条语义还在。
 *
 * ## 与老人端的区别
 * 本基类只在子女端使用。老人端三个子页面（资料 / 只读参数 / 健康自检）
 * 走的不是同一条 API（资料走 elder-register、另两页根本不写云端），
 * 硬套这套读写只会让它们背上无意义的 dirty 状态。
 */
abstract class GuardRuleSubActivity : BaseActivity() {

    /** 提交在飞行中：防连点造成重复提交与重复弹窗 */
    protected var saving = false

    /**
     * 本页有未保存的改动。
     *
     * 拆分后每个子页面都有自己的保存按钮，用户在 A 页改了一半退回入口页再进 B 页
     * 是完全自然的操作路径 —— 不拦一下，他会以为改过了。
     */
    protected var dirty = false

    private var loading = false

    /** 服务端最新配置；null = 未绑定 / 读取失败 */
    protected var settings: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
    }

    // ──────────────────────────────────────────
    //  读取
    // ──────────────────────────────────────────

    protected fun reload() {
        if (loading) return
        if (!GuardConfig.isFamilyBound) {
            settings = null
            onSettings(null)
            return
        }
        loading = true
        ApiClient.fetchFamilyElderSettings(
            onSuccess = { s ->
                loading = false
                settings = s
                onSettings(s)
            },
            onError = { err ->
                loading = false
                settings = null
                onSettings(null)
                toast("读取守护参数失败：$err")
            }
        )
    }

    /** 拿到（或拿不到）配置后渲染本页。子类必须实现 */
    protected abstract fun onSettings(s: JSONObject?)

    // ──────────────────────────────────────────
    //  提交
    // ──────────────────────────────────────────

    /**
     * 增量提交本页字段。
     *
     * 成功后统一用服务端返回的 merged 覆盖 [settings] 并重新渲染：
     * 客户端 clamp 与服务端口径万一漂移，界面上显示的必须是真实生效值，
     * 而不是"我以为我存进去的那个值"（见 `GuardSettingBounds` 的跨语言一致性说明）。
     */
    protected fun save(
        body: JSONObject,
        clearHome: Boolean = false,
        note: String = "",
        onSaved: ((JSONObject?) -> Unit)? = null
    ) {
        if (saving) {
            toast("正在保存，请稍候…")
            return
        }
        if (!GuardConfig.isFamilyBound) {
            toast("尚未绑定守护对象，改动无法保存")
            return
        }
        saving = true
        ApiClient.pushFamilyElderSettings(
            settings = body,
            clearHome = clearHome,
            onSuccess = { merged ->
                saving = false
                dirty = false
                settings = merged
                // note 用于"已按范围收敛：停留时长 300 → 240"这类补充告知。
                // 静默 clamp 会让人以为设了 300、实际按 240 守护，然后以为系统有 bug。
                toastLong(if (note.isEmpty()) SUCCESS_MSG else "$SUCCESS_MSG\n$note")
                if (onSaved != null) onSaved(merged) else onSettings(merged)
            },
            onError = { err ->
                saving = false
                showSaveError(body, clearHome, note, err, onSaved)
            }
        )
    }

    private companion object {
        const val SUCCESS_MSG = "✅ 已保存并同步到老人手机"
    }

    /**
     * 保存失败的文案方向与老人端相反：
     * 云端本来就是事实源，失败意味着**它从未变过**，
     * 所以不存在"会被覆盖回去"，只有"老人端仍在按旧参数守护"。
     * 不能沿用老人端那句"本机已按新设置运行"。
     */
    private fun showSaveError(
        body: JSONObject,
        clearHome: Boolean,
        note: String,
        err: String,
        onSaved: ((JSONObject?) -> Unit)?
    ) {
        AlertDialog.Builder(this)
            .setTitle("保存失败")
            .setMessage("原因：$err\n\n这次改动没有保存，老人手机仍在按原来的参数守护。\n\n请检查网络后重试。")
            .setPositiveButton("重试") { _, _ -> save(body, clearHome, note, onSaved) }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ──────────────────────────────────────────
    //  退出拦截
    // ──────────────────────────────────────────

    override fun onBackPressed() {
        if (!dirty) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("放弃这次改动？")
            .setMessage("本页有还没保存的改动，直接返回不会生效。")
            .setPositiveButton("保存") { _, _ -> onSaveRequested() }
            .setNegativeButton("放弃") { _, _ -> finish() }
            .show()
    }

    /** 返回拦截里点「保存」时走这里。子类实现真正的取值与提交 */
    protected open fun onSaveRequested() {}

    // ──────────────────────────────────────────
    //  小工具
    // ──────────────────────────────────────────

    /**
     * 用服务端值填表的过程中置 true，屏蔽 [dirtyWatcher]。
     *
     * 不加这个标记，setText 会触发 TextWatcher 把 dirty 置上，
     * 用户一进页面什么都没动就被拦一次"有未保存改动" —— 那是纯粹的噪音，
     * 而且会训练用户无条件点"放弃"，真有改动时也一样随手点掉。
     */
    protected var filling = false

    protected fun dirtyWatcher(after: () -> Unit = {}): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (filling) return
                dirty = true
                after()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }

    /**
     * 服务端没配过的字段返回空串，而不是拿默认值填表。
     *
     * 刻意如此：用户看得见"这项还没设过"，而不是看到一个看起来很正常的 500
     * 然后以为那就是实际生效值。保存时空值按 `GuardSettingBounds` 默认值补齐。
     */
    protected fun JSONObject?.optPositiveInt(key: String): String =
        this?.optInt(key, -1)?.takeIf { it > 0 }?.toString() ?: ""

    protected fun JSONObject?.optPositiveDouble(key: String): String =
        this?.optDouble(key, -1.0)?.takeIf { it > 0 }?.toString() ?: ""

    protected fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun toastLong(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 状态码颜色：与 EventLabels 口径一致，不要另写一套映射 */
    @androidx.annotation.ColorInt
    protected fun okColor() = 0xFF10B981.toInt()

    @androidx.annotation.ColorInt
    protected fun warnColor() = 0xFFF59E0B.toInt()

    @androidx.annotation.ColorInt
    protected fun dangerColor() = 0xFFEF4444.toInt()
}
