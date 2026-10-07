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

    /**
     * 按品牌优先级依次尝试跳转厂商专属权限页：
     * 自启动 / 后台弹出界面 / 锁屏显示等开关都在这些页面里。
     * 所有候选都打不开时，兜底跳 App 详情页（应用信息）。
     */
    fun openPermissionGuide(activity: Activity) {
        val candidates: List<Intent> = when {
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
                // EMUI/鸿蒙：自启动管理
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
                // ColorOS：自启动管理
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
                // OriginOS/Funtouch：后台弹出 / 自启动管理
                Intent().setComponent(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                )
            )
            else -> emptyList()
        }

        // 依次尝试能解析且能启动的入口
        for (intent in candidates) {
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
        val brand = when {
            isMiui -> "小米/红米"
            isEmui -> "华为/荣耀"
            isColorOs -> "OPPO/realme/一加"
            isOriginOs -> "vivo/iQOO"
            else -> ""
        }
        return if (brand.isEmpty()) {
            "请开启：通知、自启动、锁屏显示权限，确保紧急警报随时能弹出。"
        } else {
            "检测到 $brand 手机，请开启：通知、自启动、后台弹出界面、锁屏显示（即将跳转对应设置页）。"
        }
    }
}
