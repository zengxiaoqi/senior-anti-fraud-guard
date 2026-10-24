package com.antifraud.guard.util

import android.content.Context
import android.os.Build
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
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
     * 崩溃循环专用：**阻塞**地把上次留下的崩溃栈送出去，最多占 [budgetMs]。
     *
     * ## 为什么必须阻塞
     * 正常的 [reportPending] 是异步的：起线程 → 发请求 → 回调删文件。
     * 但若 App 每次启动都在同一处崩溃（v1.8.6 鸿蒙实机就是这个形态），进程活不过
     * 一两秒，异步请求全被掐死 —— 服务端永远收不到栈，只能靠猜。
     * 这里在 Application.onCreate 的最早时机，用短超时的阻塞线程把最多 2 条记录
     * 硬发出去：宁可用启动慢 3 秒换一条真实堆栈。
     *
     * 只在**确实存在崩溃文件**时才付出这个代价，正常启动零开销。
     */
    fun flushBlocking(ctx: Context, budgetMs: Long = 3000L) {
        val dir = File(ctx.filesDir, DIR_NAME)
        val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return
        if (files.isEmpty()) return

        val mine = GuardConfig.elderId.takeIf { it > 0 }
        val elderId = mine ?: GuardConfig.boundElderId
            .takeIf { GuardConfig.isFamilyBound && it > 0 } ?: return
        val role = if (mine != null) "elder" else "family"

        val targetUrl = try {
            ApiClient.init(ctx)
            ApiClient.getServerUrl()
        } catch (_: Throwable) {
            return
        }

        val worker = Thread {
            for (file in files.sortedBy { it.name }.take(2)) {
                try {
                    val body = file.readText()
                    val payload = JSONObject().apply {
                        put("elderId", elderId)
                        put("eventType", "APP_CRASH")
                        put("severity", "MEDIUM")
                        put("details", JSONObject().apply {
                            put("source", "CrashReporter(blocking)")
                            put("file", file.name)
                            put("role", role)
                            put("stack", body.take(MAX_STACK_CHARS))
                        })
                    }
                    val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        doOutput = true
                        connectTimeout = 2500
                        readTimeout = 2500
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200 || code == 201) file.delete()
                } catch (_: Throwable) {
                    // 发不出去就留着，下次启动再试
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(budgetMs)
        } catch (_: InterruptedException) {
            // 中断就让它继续在后台跑，不影响启动
        }
    }

    /**
     * 把历史崩溃文件逐个上报到服务端，成功才删除。App 启动时调用（主线程调，
     * 内部自起线程发请求）。
     *
     * 归属判定（2026-10-12 修正）：原先只有老人端（elderId>0）才上报，
     * 子女端本机根本没有 elderId，于是子女端 App 的崩溃**一条都上不来**
     * ——那部分代码处在彻底的诊断盲区里，"点了就闪退"只能靠猜。现在回退到
     * 本机绑定的老人 id：对外业务语义一致（崩溃总是发生在守护某个老人的过程里），
     * 也保证 role 标签能区分来源。
     *
     * 局限要心里有数：这只覆盖 **Java 层** 的未捕获异常。原生崩溃（SIGSEGV 等）
     * 由系统直接杀进程，UncaughtExceptionHandler 拿不到控制权，服务端不会有记录
     * ——典型的高德 MapView 竞态就属于这一类，只能靠生命周期写法规避（见
     * MapPickerActivity.onDestroy / TrackFragment.onDestroyView 的注释）。
     */
    fun reportPending(ctx: Context) {
        val mine = GuardConfig.elderId.takeIf { it > 0 }
        val role = if (mine != null) "elder" else {
            val bound = GuardConfig.boundElderId.takeIf { GuardConfig.isFamilyBound && it > 0 } ?: return
            "family"
        }
        val elderId = mine ?: GuardConfig.boundElderId
        val dir = File(ctx.filesDir, DIR_NAME)
        val files = dir.listFiles { f -> f.name.startsWith("crash-") } ?: return
        for (file in files) {
            val body = try { file.readText() } catch (_: Throwable) { continue }
            val details = JSONObject().apply {
                put("source", "CrashReporter")
                put("file", file.name)
                put("role", role)
                put("stack", body.take(MAX_STACK_CHARS))
            }
            ApiClient.reportRiskEvent(
                elderId    = elderId,
                eventType  = "APP_CRASH",
                severity   = "MEDIUM",
                details    = details,
                onSuccess  = { file.delete() },
                onError    = { /* 网络/后端不可用：保留文件，下次启动重试 */ }
            )
        }
    }
}
