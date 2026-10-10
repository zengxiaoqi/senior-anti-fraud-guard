package com.antifraud.guard.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.antifraud.guard.api.ApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * App 内自升级：查版本 → 下载 → SHA-256 校验 → 拉起系统安装界面。
 *
 * ## 为什么要有这个
 * 本 App 不进应用商店，靠自建分发。以前"升级"只能把 APK 发给对方再教他装一遍，
 * 老人几乎做不到，于是他们手机上的版本永远停在半年前 —— 修好的 bug、补上的
 * 守护能力，在最需要被保护的人那里从来没生效过。
 *
 * ## 三条硬约束（不是设计选择，是系统规则）
 *  1. Android 8.0+ 必须声明 REQUEST_INSTALL_PACKAGES，且用户**至少手动授权一次**
 *     「允许来自此来源的应用」。授权后同一来源不再询问；国产 ROM 会额外弹一次
 *     风险确认，那是系统行为，绕不过去。
 *  2. Android 7.0+ 给安装器传 file:// URI 会直接抛 FileUriExposedException，
 *     必须走 FileProvider（见 Manifest 的 provider 与 res/xml/file_paths.xml）。
 *  3. 用 versionCode 比较，绝不用 versionName 字符串比较 ——
 *     "1.10.0" < "1.9.0" 在字符串比较下是真的，按它判断会永远不提示升级。
 *
 * ## 为什么要校验 SHA-256
 * 弱网下 24MB 的文件极易中途被掐断，截断的文件依然是合法 ZIP 头，
 * 装到一半才报"解析包错误"，用户只会以为 App 坏了。下载完先比对摘要，
 * 不一致就当场删掉重来，绝不给安装器喂半个包。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdate"
    private const val UPDATE_DIR = "updates"
    private const val BUFFER_SIZE = 128 * 1024
    /** 进度回调的最小间隔：24MB 下载若每个 buffer 都回调，主线程会被刷爆 */
    private const val PROGRESS_STEP_BYTES = 512 * 1024

    /** 服务端下发的一个可升级版本 */
    data class ReleaseInfo(
        val versionCode: Int,
        val versionName: String,
        val sizeBytes: Long,
        val sha256: String,
        val changelog: String,
        val force: Boolean,
        val downloadUrl: String
    ) {
        /** 体积的可读形式，弹窗里告诉用户要下多大（老人要判断值不值得等） */
        fun sizeText(): String =
            if (sizeBytes <= 0) "未知大小"
            else "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
    }

    enum class InstallResult { OK, NEED_PERMISSION, FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ── 本机版本 ────────────────────────────────────────────────
    // 刻意不用 BuildConfig.VERSION_CODE：AGP 8 起它不再自动生成，
    // 从 PackageManager 读才是唯一稳妥的来源。

    fun currentVersionCode(context: Context): Int = try {
        val info = packageInfo(context)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (e: Exception) {
        Log.w(TAG, "读取本机 versionCode 失败", e)
        0
    }

    fun currentVersionName(context: Context): String = try {
        packageInfo(context).versionName ?: "未知"
    } catch (e: Exception) {
        "未知"
    }

    private fun packageInfo(context: Context) =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    // ── 查询最新版本 ────────────────────────────────────────────

    /**
     * 查询是否有新版本。
     *
     * @param onResult 有新版本回调 [ReleaseInfo]；已是最新 / 服务端没发布过 → null。
     *                 查不到一律当"没更新"，绝不阻塞用户：升级是锦上添花，
     *                 它的失败不能影响守护主流程。
     */
    fun checkLatest(
        context: Context,
        onResult: (ReleaseInfo?) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val code = currentVersionCode(context)
        val path = "/api/app-update/latest?versionCode=$code"
        ApiClient.elderGet(
            path,
            onSuccess = { res ->
                val info = parseRelease(res)
                post { onResult(info) }
            },
            onError = { err ->
                Log.w(TAG, "检查更新失败: $err")
                post {
                    onResult(null)
                    onError?.invoke(err)
                }
            }
        )
    }

    /** 解析 /latest 响应。字段缺失时返回 null，而不是拿半截数据去弹窗 */
    private fun parseRelease(res: JSONObject): ReleaseInfo? {
        if (!res.optBoolean("hasUpdate")) return null
        val latest = res.optJSONObject("latest") ?: return null
        val versionCode = latest.optInt("versionCode", 0)
        // downloadUrl 是服务端给的绝对路径（/api/app-update/download?...）
        val url = latest.optStringOrEmpty("downloadUrl")
        if (versionCode <= 0 || url.isEmpty()) return null
        return ReleaseInfo(
            versionCode = versionCode,
            versionName = latest.optStringOrEmpty("versionName"),
            sizeBytes = latest.optLong("sizeBytes", 0L),
            sha256 = latest.optStringOrEmpty("sha256"),
            changelog = latest.optStringOrEmpty("changelog"),
            force = latest.optBoolean("force", false) || res.optBoolean("force", false),
            downloadUrl = url
        )
    }

    // ── 下载 ────────────────────────────────────────────────────

    /**
     * 下载 APK 到应用私有目录（filesDir/updates/）。
     *
     * 放私有目录有两个好处：不需要任何存储权限，也不受 Android 11+ 分区存储限制；
     * 配合 FileProvider 依然能交给系统安装器。
     *
     * 已存在且摘要一致的包会直接复用 —— 上次下载完没装、这次又点升级时，
     * 不该让老人再等一遍 4 分钟。
     *
     * @param onProgress 主线程回调：百分比 / 已收字节 / 总字节
     * @param onDone 主线程回调：成功给 File，失败给人类可读原因
     */
    fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (percent: Int, received: Long, total: Long) -> Unit,
        onDone: (file: File?, error: String) -> Unit
    ) {
        thread {
            try {
                val dir = File(context.filesDir, UPDATE_DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    post { onDone(null, "无法创建更新目录，请检查手机存储空间") }
                    return@thread
                }
                val target = File(dir, "AntiFraudGuard-${release.versionName}-${release.versionCode}.apk")

                if (target.exists() && target.length() > 0) {
                    if (release.sha256.isEmpty() || sha256Of(target) == release.sha256) {
                        Log.i(TAG, "本地已有完整安装包，跳过下载: ${target.name}")
                        post { onDone(target, "") }
                        return@thread
                    }
                    // 摘要对不上：可能是上次下载被截断，删掉重来
                    target.delete()
                }

                val part = File(dir, "${target.name}.part")
                if (part.exists()) part.delete()

                val url = ApiClient.getBaseUrl() + release.downloadUrl
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    // 读超时是"两次数据之间的等待"，不是整个下载的总时限。
                    // 24MB 走 Cloudflare 隧道实测 4~5 分钟（约 100KB/s），
                    // 只要数据一直在流就不会触发；给 120s 是为了容忍中途卡顿。
                    .readTimeout(120, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        post { onDone(null, "下载失败：服务器返回 HTTP ${resp.code}") }
                        return@thread
                    }
                    val body = resp.body
                    val total = body?.contentLength() ?: release.sizeBytes
                    if (body == null) {
                        post { onDone(null, "下载失败：服务器没有返回文件内容") }
                        return@thread
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    var lastReport = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                received += n
                                if (received - lastReport >= PROGRESS_STEP_BYTES) {
                                    lastReport = received
                                    val pct = if (total > 0) (received * 100 / total).toInt() else -1
                                    val cur = received
                                    post { onProgress(pct, cur, total) }
                                }
                            }
                        }
                    }

                    val lastPct = if (total > 0) (received * 100 / total).toInt() else -1
                    post { onProgress(lastPct, received, total) }

                    // 校验放在 rename 之前：半成品绝不能变成"安装包"
                    if (release.sha256.isNotEmpty()) {
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(release.sha256, ignoreCase = true)) {
                            part.delete()
                            post { onDone(null, "安装包校验失败（网络中断导致文件不完整），请重试") }
                            return@thread
                        }
                    }

                    if (!part.renameTo(target)) {
                        post { onDone(null, "安装包保存失败，请检查手机存储空间") }
                        return@thread
                    }
                    Log.i(TAG, "安装包下载完成: ${target.name}（${target.length()} 字节）")
                    cleanupOldApks(dir, keep = target)
                    post { onDone(target, "") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载更新失败", e)
                post { onDone(null, describeDownloadError(e)) }
            }
        }
    }

    /** 把下载异常翻译成用户看得懂、且知道下一步该干嘛的话 */
    private fun describeDownloadError(e: Exception): String {
        val name = e.javaClass.simpleName
        return when {
            name.contains("UnknownHost") -> "域名解析失败，请检查网络后重试"
            name.contains("SocketTimeout") || name.contains("Timeout") ->
                "下载超时（网络太慢或中断），请换个网络再试"
            name.contains("Connect") || name.contains("NoRoute") ->
                "连不上服务器，请检查网络后重试"
            e.message?.contains("space", ignoreCase = true) == true ->
                "手机存储空间不足，请清理后重试"
            else -> "下载失败：${e.localizedMessage?.take(60) ?: name}"
        }
    }

    private fun sha256Of(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    /**
     * 清掉"已经装上的那些版本"的安装包。
     *
     * 一个包 24MB，老人手机存储本来就紧；升级完成后它就只剩占地方了。
     * 只删 versionCode <= 当前版本的 —— 正在等待安装的更高版本不能动。
     */
    fun cleanupInstalledApks(context: Context) {
        try {
            val dir = File(context.filesDir, UPDATE_DIR)
            val current = currentVersionCode(context)
            if (current <= 0) return
            dir.listFiles()?.forEach { f ->
                val m = Regex("AntiFraudGuard-.+-(\\d+)\\.apk").matchEntire(f.name)
                val code = m?.groupValues?.get(1)?.toIntOrNull()
                if (code != null && code <= current) {
                    if (f.delete()) Log.i(TAG, "清理已安装版本的升级包: ${f.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理升级包失败（不影响使用）", e)
        }
    }

    /** 只保留刚下载的那一个包：24MB 一个，堆几个就是上百 MB 的沉默占用 */
    private fun cleanupOldApks(dir: File, keep: File) {
        try {
            dir.listFiles()?.forEach { f ->
                if (f.absolutePath != keep.absolutePath) f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理旧安装包失败（不影响本次升级）", e)
        }
    }

    // ── 安装 ────────────────────────────────────────────────────

    /** 本 App 是否已被允许安装应用（Android 8.0+ 的"未知来源"开关） */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
        else true

    /**
     * 拉起系统安装界面。
     *
     * @return NEED_PERMISSION 时由调用方引导用户去设置页开「允许安装未知应用」，
     *         并在返回后重新调用本方法（权限是系统页动作，App 内无法代劳）。
     */
    fun install(context: Context, apk: File): InstallResult {
        if (!apk.exists()) return InstallResult.FAILED
        if (!canInstallPackages(context)) return InstallResult.NEED_PERMISSION

        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 部分 ROM 的安装器不认 FLAG_GRANT_READ_URI_PERMISSION，
            // 显式把读权限授予每一个能处理这个 Intent 的包，避免"解析包失败"。
            val resolves = context.packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            for (r in resolves) {
                val pkg = r.activityInfo?.packageName
                if (!pkg.isNullOrEmpty()) {
                    context.grantUriPermission(
                        pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            context.startActivity(intent)
            InstallResult.OK
        } catch (e: Exception) {
            Log.e(TAG, "拉起安装界面失败", e)
            InstallResult.FAILED
        }
    }

    /** 跳转到「允许安装未知应用」设置页（本 App 那一条） */
    fun openUnknownSourceSettings(context: Context): Boolean {
        return try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "跳转未知来源设置页失败", e)
            false
        }
    }
}
