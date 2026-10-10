package com.antifraud.guard.util

import android.content.Context
import android.os.Build
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内置崩溃捕获器（诊断专用，零权限零依赖）。
 *
 * 背景：老人手机不方便连电脑抓 logcat，闪退原因一直靠猜。
 * 这里用最朴素的方案：全局 UncaughtExceptionHandler 把崩溃堆栈写进
 * filesDir/crash 目录（保留最近 5 个），下次启动 App 时走既有的
 * /api/events/report（eventType=APP_CRASH, severity=MEDIUM）上报到服务端，
 * 服务端 events 表能直接查到堆栈。上报成功才删本地文件，失败留着下次再试。
 *
 * 设计要点：
 * - install() 幂等，重复调用不会包两层
 * - 崩溃写入必须吞掉所有次生异常，最后无条件交还 prev handler，
 *   让系统正常走"应用已停止"流程，绝不能因为收集日志把崩溃弄成静默假死
 * - severity 用 MEDIUM：HIGH 会在服务端触发微信高危推送，诊断数据不配打扰子女
 * - 堆栈截断到 4000 字符，events.details 是 TEXT，够用且不撑大 payload
 */
object CrashReporter {

    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 5
    private const val MAX_STACK_CHARS = 4000

    @Volatile
    private var installed = false

    /** 尽早调用（Application.onCreate），覆盖全进程所有线程的未捕获异常 */
    fun install(ctx: Context) {
        if (installed) return
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(ctx.applicationContext, prev))
        installed = true
    }

    private class CrashHandler(
        private val ctx: Context,
        private val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val dir = File(ctx.filesDir, DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stack = sw.toString().take(MAX_STACK_CHARS)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val content = buildString {
                    appendLine("time: $ts")
                    appendLine("thread: ${t.name}")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
                    appendLine("app: ${appVersion(ctx)}")
                    appendLine("stack:")
                    append(stack)
                }
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(content)
                prune(dir)
            } catch (_: Throwable) {
                // 收集日志绝不能干扰原本的崩溃流程
            }
            prev?.uncaughtException(t, e)
        }

        private fun appVersion(ctx: Context): String = try {
            val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "v${pm.versionName} (${pm.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }

        private fun prune(dir: File) {
            val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.name }
                .take(files.size - MAX_FILES)
                .forEach { it.delete() }
        }
    }

    /**
     * 把历史崩溃文件逐个上报到服务端，成功才删除。App 启动时调用（主线程调，
     * 内部自起线程发请求）。未登记账号（elderId<=0）时跳过——服务端 events
     * 表对 elderId 有校验，挂 0 会白白失败，文件留着等登记后再传。
     */
    fun reportPending(ctx: Context) {
        if (GuardConfig.elderId <= 0) return
        val dir = File(ctx.filesDir, DIR_NAME)
        val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return
        for (file in files) {
            val body = try { file.readText() } catch (_: Throwable) { continue }
            val details = JSONObject().apply {
                put("source", "CrashReporter")
                put("file", file.name)
                put("stack", body.take(MAX_STACK_CHARS))
            }
            ApiClient.reportRiskEvent(
                eventType = "APP_CRASH",
                severity  = "MEDIUM",
                details   = details,
                onSuccess = { file.delete() },
                onError   = { /* 网络/后端不可用：保留文件，下次启动重试 */ }
            )
        }
    }
}
