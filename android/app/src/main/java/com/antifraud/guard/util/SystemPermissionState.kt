package com.antifraud.guard.util

import android.app.role.RoleManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 系统级开关/权限的**当前状态**查询。
 *
 * 与 [com.antifraud.guard.VendorPermissionHelper] 的分工：
 *   VendorPermissionHelper 负责"跳转到设置页"
 *   本文件负责"现在到底开没开"
 *
 * 为什么要分开：这两件事很容易被混为一谈，导致只做了跳转引导、
 * 却没有回检 —— 用户跳出去随便点两下就回来了，App 以为"引导完成了"，
 * 实际开关根本没开，守护静默失效。**引导之后必须回检**，这是本文件存在的理由。
 */
object SystemPermissionState {

    private const val TAG = "PermState"

    // ── 通知使用权（支付监听必需）──

    fun isNotificationListenerEnabled(context: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )
        enabled?.contains(context.packageName) == true
    } catch (e: Exception) {
        Log.w(TAG, "读取通知使用权失败: ${e.message}")
        false
    }

    /** 跳转到通知使用权设置页 */
// ──────────────────────────────────────────────
//  跳转：厂商优先 → AOSP 兜底 → 手工步骤兜底
//
//  2026-08 实机踩坑：MIUI 上直接跳 AOSP 的 ACTION_NOTIFICATION_LISTENER_SETTINGS
//  会被 MIUI 拦下并弹「系统已拒绝向此应用授予访问权限」，用户点确定就是拒绝，
//  开关永远打不开。MIUI 把这些开关放在自己的权限编辑器（"其他权限"）里。
//
//  所以规则是：先试厂商入口，再试系统入口，都不行就给出手工路径。
// 最后一档不能省 —— 深度定制 ROM 上常常所有 Intent 都解析不到，
//  此时只有把步骤写清楚才有用。
// ──────────────────────────────────────────────

    /** 从候选列表里挑第一个能解析并成功启动的；返回是否成功 */
    private fun tryLaunch(activity: android.app.Activity, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    Log.i(TAG, "已跳转: ${intent.component?.className ?: intent.action}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "跳转失败，尝试下一个: ${e.message}")
            }
        }
        return false
    }

    /**
     * 打开「通知使用权」设置。
     * @return true 表示成功拉起了某个设置页；false 表示所有 Intent 都不可用，
     *         调用方**必须**改为展示手工步骤，否则用户会卡在原地无从下手。
     */
fun openNotificationListenerSettings(activity: android.app.Activity): Boolean {
        val pkg = activity.packageName

        // 先试厂商自己的权限编辑器：MIUI 上跳 AOSP 那个页面会被它拦下并直接拒绝
        if (tryLaunch(activity, com.antifraud.guard.VendorPermissionHelper.sensitivePermissionIntents(activity))) {
            return true
        }

        // 再试"直达本应用"的通知使用权详情页（API 29+）。
        // 不带 EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME 的话，系统只会打开一个
        // 全量列表，用户得自己在几十个应用里翻找本应用 —— 这是"给了入口却等于没给"的典型。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
      .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(pkg, "com.antifraud.guard.service.NotificationPayListenerService")
       )
            if (tryLaunch(activity, listOf(detail))) return true
        }

        // 最后退到全量列表页
        return tryLaunch(activity, listOf(
Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            appDetailsIntent(activity)
        ))
    }

    /** 打开「使用情况访问」设置。返回语义同 [openNotificationListenerSettings] */
    fun openUsageAccessSettings(activity: android.app.Activity): Boolean {
        if (tryLaunch(activity, com.antifraud.guard.VendorPermissionHelper.sensitivePermissionIntents(activity))) {
            return true
        }
        return tryLaunch(activity, listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            appDetailsIntent(activity)
        ))
    }

    /**
     * 打开「默认应用 / 来电显示」设置。
     *
     * 来电显示角色（ROLE_CALL_SCREENING）在国产 ROM 上经常申请了也不被真正调用
     * （系统只在自己愿意的时候回调 onScreenCall），所以最终还是引导到"默认应用"手动切。
     */
fun openDefaultAppsSettings(activity: android.app.Activity): Boolean {
        if (tryLaunch(activity, com.antifraud.guard.VendorPermissionHelper.defaultAppIntents(activity))) {
     return true
        }
        // 注意：Settings.ACTION_MANAGE_DEFAULT_APPS_PREFERENCES 并非公开常量，
        // 这里只用公开的 ACTION_MANAGE_DEFAULT_APPS_SETTINGS，失败再退应用详情页。
        return tryLaunch(activity, listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            appDetailsIntent(activity)
        ))
    }

    // ── 呼叫筛选 / 来电显示角色（通话时长监测依赖）──

    /**
     * 本 App 是否被设为系统"呼叫筛选"应用。
     *
     * 未获此角色时 [com.antifraud.guard.service.CallScreeningGuardService] 的
     * onScreenCall **根本不会被系统调用** —— 通话监测整条线静默失效，
     * 而 App 侧看不出任何异常。所以这个状态必须能被读出来并展示给用户。
     *
     * 只查 ROLE_CALL_SCREENING 一个角色：Android 没有单独可申请的
     * "来电显示" 角色常量，用户在本系统里的开关最终都体现为呼叫筛选角色。
     */
    fun isCallScreeningRole(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) {
            Log.w(TAG, "查询呼叫筛选角色失败: ${e.message}")
            false
        }
    }

    /**
     * 引导用户把本 App 设为呼叫筛选应用。
     *
     * 本函数**只负责拉起系统角色确认框**：角色可用就 startActivity 并返回 true，
     * 其余一切情况（ROM 未实现角色 / SDK 过低 / 抛异常）一律返回 false 且**不做任何跳转**。
     *
     * 原实现会在失败分支里静默跳「默认应用」设置页，调用方并不知道跳了，
     * 还会再跳一次（双重跳转），且整条失败路径上用户得不到任何提示，
     * 一旦跳转被 ROM 拦下就表现为"点了没反应"。
     * 反馈与兜底链统一由调用方（SettingsActivity 的按钮分支）负责。
     *
     * Android 10+ 只有一个 role（呼叫筛选）可供申请，系统弹确认框；
     * Android 13+ 新增独立的"来电显示"角色。
     */
    fun requestCallScreeningRole(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = activity.getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                activity.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                true
            } else {
                Log.w(TAG, "本 ROM 未实现呼叫筛选角色，由调用方走默认应用设置兜底")
                false
            }
        } catch (e: Exception) {
            // 部分 ROM 实现了 role 但 createRequestRoleIntent 抛异常，必须兜住
            Log.w(TAG, "申请呼叫筛选角色失败: ${e.message}")
            false
        }
    }

fun openIncomingCallScreeningSettings(context: Context) {
        try {
   context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
 context.startActivity(appDetailsIntent(context))
        }
    }

    /**
     * 手工步骤文案：当所有 Intent 都解析不到时的最后兜底。
     *
     * 深度定制 ROM 上这几乎是唯一有效的路 —— 反正跳不过去，不如把步骤写清楚。
     * 按品牌给出对应路径，避免让用户自己在设置里瞎找。
     */
    fun manualSteps(target: Target): String {
        val brand = com.antifraud.guard.VendorPermissionHelper.brandName()
        return when (target) {
            Target.NOTIFICATION_ACCESS -> """
                通知使用权（用于监听扣款通知）：

                小米/红米：设置 → 应用设置 → 应用管理 → 长者防诈守护
                          → 应用权限 → 其他权限 → 通知使用权 → 允许

                华为/荣耀：设置 → 应用 → 应用启动管理 → 长者防诈守护
                          → 权限 → 通知使用权

                OPPO/一加：设置 → 隐私 → 权限管理 → 特殊权限 → 通知使用权

                vivo/iQOO：设置 → 应用与权限 → 权限管理 → 长者防诈守护 → 通知使用权
            """.trimIndent()

            Target.USAGE_ACCESS -> """
       使用情况访问（Phase 1 通话行为判定需要）：

                小米/红米：设置 → 应用设置 → 应用管理 → 长者防诈守护
                          → 应用权限 → 其他权限 → 使用情况访问 → 允许

                华为/荣耀：设置 → 应用 → 应用启动管理 → 长者防诈守护
                          → 权限 → 使用情况访问

                OPPO/一加：设置 → 隐私 → 权限管理 → 特殊权限 → 使用情况访问

                vivo/iQOO：设置 → 应用与权限 → 权限管理 → 长者防诈守护 → 使用情况访问
            """.trimIndent()

            Target.CALL_SCREENING -> """
                来电显示 / 骚扰拦截（用于通话时长监测）：

                小米/红米：设置 → 应用设置 → 默认应用 → 来电显示 → 选「长者防诈守护」
                          （若列表里没有本应用，说明该机型 ROM 不支持，改用通话状态监听即可）

                华为/荣耀：设置 → 应用 → 默认应用 → 来电显示
                          OPPO/一加：设置 → 应用管理 → 默认应用 → 来电显示
                vivo/iQOO：设置 → 应用与权限 → 默认应用 → 来电显示
            """.trimIndent()
        }.let { "$it\n\n（当前手机识别为：${brand.ifEmpty { "原厂/其他" }}）" }
    }

    enum class Target { NOTIFICATION_ACCESS, USAGE_ACCESS, CALL_SCREENING }

    // ── 使用情况访问（Phase 1 行为判定依赖，先把状态查询与跳转做好）──

    /**
 * 是否已授予"使用情况访问"。
 *
 * 未授权时 queryEvents 的行为各 ROM 不一（多数抛 SecurityException，少数静默返回空集），
 * 所以先用 AppOps 这个权威来源判断，不靠"试探有没有事件"这种间接推断。
 */
fun isUsageAccessGranted(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                as android.app.AppOpsManager
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        // 极老 ROM 上 OPSTR 常量不存在，退回"试探窄区间"的方式
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryEvents(now - 60_000L, now).hasNextEvent()
        } catch (e2: Exception) {
            false
        }
    }

fun openUsageAccessSettingsLegacy(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
      context.startActivity(appDetailsIntent(context))
        }
    }

    // ── 电池优化白名单 ──

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }

    /**
 * 打开电池优化设置。
     *
     * **刻意不声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**：那是"替用户申请豁免"的权限，
     * Google Play 政策只允许少数场景使用（闹钟/日历/设备管理/企业 MDM 等），
     * 老人防诈类应用很难通过审核，一旦被拒会影响整个上架。
     *
     * 而我们真正需要的行为并不需要那个权限 —— 让用户自己在系统列表里把本 App
     * 划掉就够了，App 只是把跳转成本降到零。所以这里只跳设置列表，
     * 不代用户申请，也不申请豁免权限。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // 连系统列表都打不开（少数 ROM 阉割了）时退到应用详情页，至少能进"电池"页
            context.startActivity(appDetailsIntent(context))
        }
    }

    // ── 后台定位（"始终允许"）──

    fun openAppLocationSettings(context: Context) {
        try {
            // 应用详情页里点"权限"再点"位置信息"才能选"始终允许"，
            // 直接跳应用权限详情页少一次点击
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "跳转应用详情页失败: ${e.message}")
        }
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 汇总为一行可读文本，供设置页/日志展示 */
    fun summary(context: Context): String {
        val app = context.applicationContext
        val items = listOf(
            "通知使用权" to isNotificationListenerEnabled(app),
            "呼叫筛选角色" to isCallScreeningRole(app),
            "使用情况访问" to isUsageAccessGranted(app),
            "电池优化豁免" to isIgnoringBatteryOptimizations(app)
        )
        return items.joinToString("｜") { (k, v) -> "$k:${if (v) "✅" else "❌"}" }
    }
}