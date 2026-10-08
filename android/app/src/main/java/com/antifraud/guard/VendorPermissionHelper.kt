package com.antifraud.guard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 厂商定制权限引导器
 *
 * 「自启动」「后台弹出界面」「锁屏显示」是 MIUI/EMUI/ColorOS 等厂商定制的特殊权限，
 * 出于安全设计，三方 App 无法在安装时或运行时静默自授权，唯一合规做法是：
 * 检测品牌 → 引导用户 → 一键跳转到对应设置页，把用户找开关的成本降为零。
 */
object VendorPermissionHelper {

    private const val TAG = "VendorPermGuide"

    /** 品牌识别 */
    private val isMiui: Boolean
        get() = Build.MANUFACTURER.equals("xiaomi", true) ||
                Build.MANUFACTURER.equals("redmi", true) ||
                !getProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private val isEmui: Boolean
        get() = Build.MANUFACTURER.equals("huawei", true) ||
                Build.MANUFACTURER.equals("honor", true)

    private val isColorOs: Boolean
        get() = Build.MANUFACTURER.equals("oppo", true) ||
                Build.MANUFACTURER.equals("realme", true) ||
                Build.MANUFACTURER.equals("oneplus", true)

    private val isOriginOs: Boolean
        get() = Build.MANUFACTURER.equals("vivo", true) ||
                Build.MANUFACTURER.equals("iqoo", true)

    private fun getProperty(key: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }

    /** 当前品牌名称（空串表示原厂/其他） */
    fun brandName(): String = when {
        isMiui -> "小米/红米"
        isEmui -> "华为/荣耀"
        isColorOs -> "OPPO/realme/一加"
        isOriginOs -> "vivo/iQOO"
        else -> ""
    }

    /**
     * 电池优化白名单的厂商专属入口。
     *
     * ## 实测结论（Xiaomi 12S / Android 15 / MIUI V816）
     * 网上流传的 MIUI 组件 `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity`
     * 在这台设备上**根本不存在**（`cmd package resolve-activity` 查不到）。
     * 真正能打开的是系统自带的 `com.android.settings/.Settings$AppBatteryUsageActivity`。
     *
     * 所以这里不再猜厂商组件 —— 猜错的后果是"点了没反应"，比不给入口更糟。
     * 改为返回 null，交给调用方走已验证可用的系统页。
     * （如后续需要在旧版 MIUI 上专门处理，再按版本加回候选，并务必真机验证。）
     */
    fun batteryOptimizationIntent(context: Context): Intent? = null

/**
 * 「自启动」「后台弹出界面」「锁屏显示」等厂商定制权限的引导入口。
     * 与 [batteryOptimizationIntent] 分开，因为两者的失败回退策略不同。
  */
    fun permissionGuideIntents(activity: Activity): List<Intent> = when {
        isMiui -> listOf(
     // MIUI：后台弹出界面 / 权限管理编辑页
       Intent("miui.intent.action.APP_PERM_EDITOR").apply {
    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
    putExtra("extra_pkgname", activity.packageName)
         },
     // MIUI：自启动管理
  Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
      )
  )
        isEmui -> listOf(
       Intent().setComponent(
         ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
       ),
  Intent().setComponent(
   ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.StartupNormalAppListActivity")
         ),
            Intent().setComponent(
  ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
      )
        isColorOs -> listOf(
Intent().setComponent(
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            ),
            Intent().setComponent(
       ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
      )
 isOriginOs -> listOf(
       Intent().setComponent(
      ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            ),
            Intent().setComponent(
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        )
        )
        else -> emptyList()
    }

/**
     * 「通知使用权」与「使用情况访问」的厂商入口。
     *
     * ## 实测结论（Xiaomi 12S / Android 15 / MIUI V816）
     * 原实现一律跳 AOSP 的 `ACTION_NOTIFICATION_LISTENER_SETTINGS`，MIUI 会拦下来
     * 直接弹「系统已拒绝向此应用授予访问权限」—— 用户点「确定」就是拒绝，开关永远打不开。
     * 而且 `cmd package query-activities` 显示该 action 在这台设备上 **No activities found**
     * （AOSP 那个页面并未导出），所以它本来就是条死路。
     *
     * 真正能用的只有 MIUI 自己的权限编辑器：`com.miui.permcenter.permissions.PermissionsEditorActivity`
     * —— 这两个开关都在它的「其他权限」分类下。该组件已验证存在。
     *
     * 其余品牌的组件未经实机验证，一律返回空列表走系统页：
     * 猜错的代价是"点了没反应"，比不给入口更糟。后续要加必须真机验证过再写进来。
     */
    fun sensitivePermissionIntents(activity: Activity): List<Intent> {
        val pkg = activity.packageName
        return when {
            isMiui -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", pkg)
                }
            )
            else -> emptyList()
        }
    }

/**
     * 「默认应用 / 来电显示」的厂商入口。
     *
     * ## 实测结论（Xiaomi 12S / Android 15 / MIUI V816）
     * `com.miui.securitycenter/.appcenter.AppDetailsDefaultAppActivity` 不存在。
     * 真正响应 `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` 的是系统组件
     * `com.android.permissioncontroller/.role.ui.DefaultAppListActivity`。
     *
     * 与电池优化同理：不猜厂商组件，返回空列表让调用方走已验证的系统页。
     */
    fun defaultAppIntents(activity: Activity): List<Intent> = emptyList()

    /**
     * 按品牌优先级依次尝试跳转厂商专属权限页：
     * 自启动 / 后台弹出界面 / 锁屏显示等开关都在这些页面里。
     * 所有候选都打不开时，兜底跳 App 详情页（应用信息）。
     */
    fun openPermissionGuide(activity: Activity) {
        for (intent in permissionGuideIntents(activity)) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    Log.i(TAG, "已跳转厂商权限页: ${intent.component?.className}")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "跳转失败，尝试下一个: ${intent.component?.className}", e)
            }
        }

        // 兜底：App 详情页（各品牌通用的权限入口）
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "连应用详情页都打不开", e)
        }
    }

    /** 当前品牌对应的权限引导提示文案 */
    fun guideHint(context: Context): String {
        val brand = brandName()
        return if (brand.isEmpty()) {
            "请开启：通知、自启动、锁屏显示权限，确保紧急警报随时能弹出。"
        } else {
            "检测到 $brand 手机，请开启：通知、自启动、后台弹出界面、锁屏显示（即将跳转对应设置页）。"
        }
    }
}
