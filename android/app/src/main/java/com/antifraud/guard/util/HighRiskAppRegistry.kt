package com.antifraud.guard.util

import android.util.Log
import org.json.JSONArray

/**
 * 高危前台应用注册表（Phase 1 · 通话行为判定，任务 1-1）。
 *
 * 判定场景：老人在通话中（摘机状态）把以下类型的 App 切到前台，大概率正被诱导操作：
 *  - 远程控制类（CRITICAL）：冒充公检法"屏幕共享/远程协助"话术的标配工具。
 *    屏幕一共享，骗子就能实时看到短信验证码、银行余额、支付确认页。
 *  - 支付类（HIGH）：通话中打开支付/银行 App = 正在或准备转账。
 *
 * ## 可远程更新（路线图 1-1 要求）
 * 包名清单会随时间变化（新的远控工具、新的银行 App），静态表跟不上。
 * 支持从服务端 guard_settings.highRiskPackages（JSON 数组字符串）下发增量覆盖，
 * 远程表优先于静态表 —— 运营改一条规则，老人端下次配置同步即生效，不用等发版。
 * 脏数据（缺 pkg / level 非法）逐条丢弃；整体解析失败保留现有表，
 * 绝不能让一条坏配置把整张判定表搞瘫。
 */
object HighRiskAppRegistry {

    private const val TAG = "HighRiskAppRegistry"

    /** 危险等级（rank 越大越危险） */
    enum class Level(val rank: Int) {
        HIGH(2),
        CRITICAL(3);

        companion object {
            fun of(raw: String?): Level? = when (raw?.uppercase()) {
                "HIGH" -> HIGH
                "CRITICAL" -> CRITICAL
                else -> null
            }
        }
    }

    /** 应用分类 */
    enum class Category { REMOTE_CONTROL, PAYMENT }

    data class Entry(
        val pkg: String,
        val name: String,
        val category: Category,
        val level: Level
    )

    // ── 静态注册表（编译期兜底；远程更新永远优先于它）──
    // 部分银行/远控包名随版本可能变化，拿不准的一律靠远程表修正 —— 这正是远程更新的意义。
    private val staticEntries: List<Entry> = listOf(
        // 远程控制类：冒充公检法"屏幕共享"诈骗的标配
        Entry("com.teamviewer.quicksupport.market.mobile", "TeamViewer QuickSupport", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.teamviewer.teamviewer.market.mobile", "TeamViewer", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.anydesk.anydeskandroid", "AnyDesk", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.carriez.flutter_hbb", "RustDesk", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.todesk.client", "ToDesk", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.oray.sunlogin.client", "向日葵远程控制", Category.REMOTE_CONTROL, Level.CRITICAL),
        Entry("com.oray.sunlogin.service", "向日葵远程控制(被控端)", Category.REMOTE_CONTROL, Level.CRITICAL),

        // 支付类：通话中打开 = 正在/准备转账
        Entry("com.tencent.mm", "微信", Category.PAYMENT, Level.HIGH),
        Entry("com.eg.android.AlipayGphone", "支付宝", Category.PAYMENT, Level.HIGH),
        Entry("com.unionpay", "云闪付", Category.PAYMENT, Level.HIGH),
        Entry("com.icbc", "工商银行", Category.PAYMENT, Level.HIGH),
        Entry("com.chinamworld.main", "建设银行", Category.PAYMENT, Level.HIGH),
        Entry("com.android.bankabc", "农业银行", Category.PAYMENT, Level.HIGH),
        Entry("com.chinamworld.bocmbci", "中国银行", Category.PAYMENT, Level.HIGH),
        Entry("com.bankcomm.Bankcomm", "交通银行", Category.PAYMENT, Level.HIGH),
        Entry("com.cmbchina.ccd.pluto.cmbActivity", "招商银行", Category.PAYMENT, Level.HIGH)
    )

    /** 远程下发的增量覆盖：pkg -> Entry */
    @Volatile
    private var remoteEntries: Map<String, Entry> = emptyMap()

    /**
     * 应用远程更新。
     *
     * @param jsonArray 服务端 guard_settings.highRiskPackages 的原始字符串；
     *                  空 = 清空远程表回落静态表；解析失败 = 保留现有远程表
     *                  （脏配置按"无配置/旧配置"处理，不能反向搞坏正在工作的判定）
     */
    fun applyRemoteConfig(jsonArray: String?) {
        if (jsonArray.isNullOrBlank()) {
            remoteEntries = emptyMap()
            return
        }
        val parsed = mutableListOf<Entry>()
        try {
            val arr = JSONArray(jsonArray)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg").trim()
                val level = Level.of(o.optString("level"))
                // 脏条目逐条丢弃，不拖垮整表
                if (pkg.isEmpty() || level == null) continue
                val category = when (o.optString("category").uppercase()) {
                    "PAYMENT" -> Category.PAYMENT
                    else -> Category.REMOTE_CONTROL
                }
                parsed += Entry(pkg, o.optString("name", pkg), category, level)
            }
        } catch (e: Exception) {
            Log.w(TAG, "远程高危 App 规则解析失败，沿用现有表: ${e.message}")
            return
        }
        remoteEntries = parsed.associateBy { it.pkg }
        Log.i(TAG, "高危 App 远程规则已更新：${remoteEntries.size} 条（静态表 ${staticEntries.size} 条兜底）")
    }

    /** 查询一个前台包名的危险登记；远程表优先，静态表兜底；null = 不在表内 */
    fun lookup(pkg: String?): Entry? {
        if (pkg.isNullOrEmpty()) return null
        return remoteEntries[pkg] ?: staticEntries.firstOrNull { it.pkg == pkg }
    }

    /** 是否命中"通话中高危"判定（HIGH / CRITICAL 都算） */
    fun isCallRiskEntry(entry: Entry?): Boolean =
        entry != null && (entry.level == Level.HIGH || entry.level == Level.CRITICAL)

    /** 供告警详情展示的可读名称（表内返回 App 名，表外原样返回包名） */
    fun displayName(pkg: String?): String = lookup(pkg)?.name ?: (pkg ?: "未知应用")
}
