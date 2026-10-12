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

    /**
     * 分块下载的块大小。2026-10-13 实测：2MB/块 约 492KB/s、4MB 约 406KB/s、
     * 8MB 约 379KB/s，而一次性拉整个 37MB 只有 ~8KB/s。取 2MB 是吞吐与请求数的平衡点。
     * **改这个值前请重新实测**（用 curl -H "Range: bytes=0-N" 打生产域名），
     * 别沿用注释里的数字 —— 这套代码就是因为信了切换前的旧实测值才埋了雷。
     */
    private const val CHUNK_SIZE = 2L * 1024 * 1024

    /** 单块失败重试次数。分块的意义之一就是把"整包重来"降级成"重取 2MB" */
    private const val MAX_CHUNK_RETRY = 3

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

                // ── 断点续传：.part 的长度就是上次已下载的字节数 ──
                //
                // 2026-10-13 实测（务必在改动这里前重测，别沿用）：
                //   一次性拉整个 37MB → ~8KB/s，要 77 分钟，必然超时失败；
                //   按 Range 分块取     → 2MB/块 约 492KB/s、4MB 约 406KB/s、8MB 约 379KB/s，
                //                        整包约 76 秒。
                // 也就是说：Worker 流式吐"大响应体"时吞吐会断崖式劣化，但单次取几 MB 很快。
                // 所以这里必须分块请求，绝不能改回"一个请求拉全包"。
                val total = release.sizeBytes
                var received = if (part.exists()) part.length() else 0L
                if (total > 0 && received > total) received = 0 // 换过包，旧的续传点失效

                // 上次其实下完了、只是没来得及改名（例如装到一半退出）：直接校验复用
                if (total > 0 && received >= total && received > 0) {
                    if (release.sha256.isEmpty() || sha256Of(part) == release.sha256) {
                        if (part.renameTo(target)) {
                            Log.i(TAG, "复用上次已下完的安装包: ${target.name}")
                            cleanupOldApks(dir, keep = target)
                            post { onDone(target, "") }
                            return@thread
                        }
                    }
                    part.delete()
                    received = 0
                }

                val url = ApiClient.getBaseUrl() + release.downloadUrl
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    // 读超时是"两次数据之间的等待"，不是整个下载的总时限。
                    // 分块后每块 2MB 正常 4 秒左右收完，给 90s 既能容忍卡顿，
                    // 又能在真卡死时快速失败、交给下面的按块重试。
                    .readTimeout(90, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                val digest = MessageDigest.getInstance("SHA-256")
                // 续传：先把已有字节喂进摘要，否则后半段的校验值必然对不上
                if (received > 0) feedInto(part, digest)

                val buffer = ByteArray(BUFFER_SIZE)
                var lastReport = received
                post { onProgress(pctOf(received, total), received, total) }

                FileOutputStream(part, true).use { out ->
                    if (total <= 0) {
                        // 清单没给大小就没法分块，退回一次性下载（老服务端/手工塞的清单）
                        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                post { onDone(null, "下载失败：服务器返回 HTTP ${resp.code}") }
                                return@thread
                            }
                            val body = resp.body
                                ?: throw java.io.IOException("服务器没有返回文件内容")
                            pump(body.byteStream(), out, digest, buffer) { n ->
                                received += n
                                if (received - lastReport >= PROGRESS_STEP_BYTES) {
                                    lastReport = received
                                    val cur = received
                                    post { onProgress(pctOf(cur, total), cur, total) }
                                }
                            }
                        }
                    } else {
                        while (received < total) {
                            val from = received
                            val to = minOf(from + CHUNK_SIZE - 1, total - 1)
                            var ok = false
                            for (attempt in 1..MAX_CHUNK_RETRY) {
                                try {
                                    client.newCall(
                                        Request.Builder().url(url)
                                            .header("Range", "bytes=${from}-${to}")
                                            .get().build()
                                    ).execute().use { resp ->
                                        if (resp.code != 206 && resp.code != 200) {
                                            post { onDone(null, "下载失败：服务器返回 HTTP ${resp.code}") }
                                            return@thread
                                        }
                                        if (resp.code == 200 && from > 0) {
                                            // 服务端忽略了 Range、吐的是整包：已有内容必须作废，
                                            // 不清空就会把文件写重，摘要必然对不上
                                            out.channel.truncate(0)
                                            out.channel.position(0)
                                            digest.reset()
                                            received = 0
                                        }
                                        val body = resp.body
                                            ?: throw java.io.IOException("服务器没有返回文件内容")
                                        pump(body.byteStream(), out, digest, buffer) { n ->
                                            received += n
                                            if (received - lastReport >= PROGRESS_STEP_BYTES) {
                                                lastReport = received
                                                val cur = received
                                                post { onProgress(pctOf(cur, total), cur, total) }
                                            }
                                        }
                                    }
                                    ok = true
                                    break
                                } catch (e: Exception) {
                                    if (attempt >= MAX_CHUNK_RETRY) throw e
                                    Log.w(TAG, "取块 ${from}-${to} 第 ${attempt} 次失败，重试", e)
                                    Thread.sleep(400L * attempt)
                                }
                            }
                            if (!ok) break
                        }
                    }
                }

                post { onProgress(pctOf(received, total), received, total) }

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

    /** 百分比：总大小未知时给 -1，让调用方知道别显示进度条数字 */
    private fun pctOf(received: Long, total: Long): Int =
        if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else -1

    /** 把已有文件的内容喂进摘要 —— 续传时前半段是上次下的，不喂进去校验必然失败 */
    private fun feedInto(file: File, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
    }

    /** 边读边写边摘要，每读一块回调一次字节数（进度与校验共用一条数据通路，避免写两遍） */
    private inline fun pump(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        digest: MessageDigest,
        buffer: ByteArray,
        onBytes: (Long) -> Unit
    ) {
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            onBytes(n.toLong())
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
