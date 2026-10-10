package com.antifraud.guard.util

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.config.GuardConfig
import java.io.File

/**
 * App 内升级的界面流程：提示 → 下载进度 → 授权引导 → 安装。
 *
 * 抽成一个对象的理由和 EmergencyAlertLauncher 一样：这条链会被三个地方调用
 * （设置页按钮、老人端主页启动、子女端主页启动），写三份必然出现
 * "其中一份忘了判权限" 这类静默失效。
 *
 * ## 权限这一步为什么必须在流程里
 * Android 8.0+ 把「允许安装未知应用」变成了按来源逐项授予的开关。
 * 没授权就跳安装界面，系统会**直接吞掉这次跳转**（不报错、不弹窗），
 * 用户看到的是"点了升级，什么都没发生"。所以下载之前先查，
 * 没授权就先讲清楚要去哪里开，而不是默默失败。
 */
object AppUpdateUi {

    /** 等待"授权后继续下载"的版本（从系统设置页返回时用） */
    private var pendingRelease: AppUpdateManager.ReleaseInfo? = null
    /** 已下载完、等待"授权后继续安装"的文件 */
    private var pendingApk: File? = null

    /**
     * 手动检查（设置页按钮）。
     *
     * 手动点的必须**一定有反馈**：查不到新版本也要说"已是最新版本"，
     * 否则按钮点了没反应，用户只会以为 App 又坏了。
     */
    fun checkManually(activity: AppCompatActivity, onStatus: ((String) -> Unit)? = null) {
        AppUpdateManager.cleanupInstalledApks(activity)
        onStatus?.invoke("正在检查更新…")
        AppUpdateManager.checkLatest(
            activity,
            onResult = { release ->
                if (activity.isFinishing || activity.isDestroyed) return@checkLatest
                if (release == null) {
                    val text = "已是最新版本（v${AppUpdateManager.currentVersionName(activity)}）"
                    onStatus?.invoke(text)
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                    return@checkLatest
                }
                onStatus?.invoke("发现新版本 v${release.versionName}")
                showUpdateDialog(activity, release)
            },
            onError = { err ->
                if (activity.isFinishing || activity.isDestroyed) return@checkLatest
                val text = "检查更新失败：$err"
                onStatus?.invoke(text)
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * 启动时静默检查。
     *
     * 刻意"静默"：查不到、查失败都一声不吭，只有真有新版本才弹窗。
     * 老人每天开十次 App，不该每次都被"正在检查更新"打扰；
     * 而同一个版本一天最多提示一次（见 GuardConfig.shouldPromptUpdate），
     * 点过"稍后"就不再纠缠。
     */
    fun checkOnStart(activity: AppCompatActivity) {
        // 上次升级留下来的安装包已经装上了，先清掉（24MB，老人手机存储紧张）
        AppUpdateManager.cleanupInstalledApks(activity)
        AppUpdateManager.checkLatest(
            activity,
            onResult = { release ->
                if (release == null) return@checkLatest
                if (activity.isFinishing || activity.isDestroyed) return@checkLatest
                if (!release.force && !GuardConfig.shouldPromptUpdate(release.versionCode)) return@checkLatest
                showUpdateDialog(activity, release)
            },
            onError = { /* 静默：升级失败不该影响守护主流程 */ }
        )
    }

    /**
     * 从系统设置页返回时调用（对应 Activity 的 onResume）。
     * 用户开完「允许安装未知应用」回来，这里接着把下载/安装走完，
     * 否则他还得再点一次升级按钮 —— 多数人会就此放弃。
     */
    fun onResume(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!AppUpdateManager.canInstallPackages(activity)) return

        pendingApk?.let { apk ->
            pendingApk = null
            finishInstall(activity, apk)
            return
        }
        pendingRelease?.let { release ->
            pendingRelease = null
            startDownload(activity, release)
        }
    }

    // ── 更新提示弹窗 ────────────────────────────────────────────

    private fun showUpdateDialog(activity: AppCompatActivity, release: AppUpdateManager.ReleaseInfo) {
        GuardConfig.markUpdatePrompted(release.versionCode)

        val body = buildString {
            append("新版本：v").append(release.versionName)
            append("（").append(release.sizeText()).append("）\n\n")
            if (release.changelog.isNotEmpty()) append(release.changelog).append("\n\n")
            append("安装包约需几分钟下载完成，建议连接 WiFi 后进行。\n")
            append("升级不会丢失任何数据。")
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (release.force) "必须升级后才能继续使用" else "发现新版本")
            .setMessage(body)
            .setPositiveButton("立即升级") { _, _ -> startDownload(activity, release) }

        // 强制升级不给"稍后"：老版本存在已知问题时，让用户停留在上面才是风险。
        // 但也不做成"取消不掉"——留个退出 App 的路（返回键），避免把人彻底锁死。
        if (!release.force) {
            builder.setNegativeButton("稍后再说", null)
        } else {
            builder.setCancelable(false)
        }
        safeShow(activity, builder)
    }

    // ── 下载 ────────────────────────────────────────────────────

    private fun startDownload(activity: AppCompatActivity, release: AppUpdateManager.ReleaseInfo) {
        if (!AppUpdateManager.canInstallPackages(activity)) {
            showPermissionGuide(activity, release, null)
            return
        }

        val context = activity
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true // 拿不到 Content-Length 时先转圈，有长度后切成确定进度
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val label = TextView(context).apply {
            text = "正在连接服务器…"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.START
            setPadding(0, 16, 0, 0)
        }
        wrap.addView(bar)
        wrap.addView(label)

        val dialog = AlertDialog.Builder(context)
            .setTitle("正在下载更新")
            .setView(wrap)
            .setCancelable(false)
            .show()

        AppUpdateManager.download(
            context,
            release,
            onProgress = { percent, received, total ->
                if (percent >= 0) {
                    bar.isIndeterminate = false
                    bar.progress = percent
                    label.text = "已下载 $percent%（${mb(received)} / ${mb(total)}）"
                } else {
                    label.text = "已下载 ${mb(received)}"
                }
            },
            onDone = { file, error ->
                dialog.dismiss()
                if (file != null) finishInstall(activity, file)
                else Toast.makeText(context, error.ifEmpty { "下载失败" }, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

    // ── 安装 ────────────────────────────────────────────────────

    private fun finishInstall(activity: AppCompatActivity, apk: File) {
        when (AppUpdateManager.install(activity, apk)) {
            AppUpdateManager.InstallResult.OK ->
                Toast.makeText(activity, "下载完成，请在弹出的界面点「安装」", Toast.LENGTH_LONG).show()
            AppUpdateManager.InstallResult.NEED_PERMISSION ->
                showPermissionGuide(activity, null, apk)
            AppUpdateManager.InstallResult.FAILED ->
                Toast.makeText(activity, "无法打开安装界面，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }

    /** 引导去开「允许安装未知应用」。回来后由 [onResume] 接上未完成的一步 */
    private fun showPermissionGuide(
        activity: AppCompatActivity,
        release: AppUpdateManager.ReleaseInfo?,
        apk: File?
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("需要开启一次安装授权")
            .setMessage(
                "在 App 内升级，需要先允许本应用安装应用。\n\n" +
                    "将打开系统设置页，请把「允许来自此来源的应用」打开，" +
                    "然后返回本页，升级会自动继续。\n\n" +
                    "这是手机系统的安全要求，只需设置一次。"
            )
            .setPositiveButton("去设置") { _, _ ->
                pendingRelease = release
                pendingApk = apk
                if (!AppUpdateManager.openUnknownSourceSettings(activity)) {
                    Toast.makeText(activity, "无法打开设置页，请手动在系统设置中开启", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
        safeShow(activity, builder)
    }

    /** Activity 已销毁时 show() 会抛 BadTokenException，静默跳过（界面都没了，弹给谁看） */
    private fun safeShow(activity: AppCompatActivity, builder: AlertDialog.Builder) {
        try {
            if (!activity.isFinishing && !activity.isDestroyed) builder.show()
        } catch (e: Exception) {
            android.util.Log.w("AppUpdateUi", "弹窗展示失败", e)
        }
    }
}
