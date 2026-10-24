package com.antifraud.guard.location

import android.content.Context

/**
 * 定位链路诊断记录（本机落盘，守护健康自检页读取）。
 *
 * ## 为什么需要它
 * 2026-10-24 实机排查：鸿蒙手机上权限全绿（后台定位、电池豁免、自启动、后台活动都开了），
 * 服务端却**一条 LOCATION_UPDATE 都没有**，而且 App 界面完全看不出异常 ——
 * 权限、服务、通知栏三样都正常，唯独"一次坐标都没取到"这件事没有任何出口。
 *
 * 根因在链路中段：`LocationManager` 的定位回调只有真的拿到坐标才触发，
 * 而心跳兜底只在 `lastKnownLocation != null` 时才上报。两者中间那段
 * （"订阅上了但系统从不给坐标"）是纯静默区，日志本机没人看、服务端也没有痕迹。
 *
 * 所以这里把四个时刻落盘：
 *   1. 最近一次**取到坐标**的时刻与来源（回调 / 缓存 / 主动探测）
 *   2. 最近一次**主动探测失败**的原因（无 provider 可用 / 定位服务关闭 / 超时）
 *   3. 最近一次**上报成功**的时刻（服务端真的收下了）
 *   4. 最近一次**上报失败**的原因（HTTP 码 / 网络异常）
 *
 * 有这四个时刻，"没有位置"就能被拆成三种完全不同的故障，自检页直接告诉用户是哪一种：
 *   - 取不到坐标 → 系统定位服务/精确度的问题，改 App 权限没用
 *   - 取到但上报失败 → 服务地址/网络的问题
 *   - 都成功 → 那才真的是"老人没动"
 */
object LocationDiagnostics {

    private const val PREFS = "location_diag"

    private const val K_FIX_OK_AT = "fix_ok_at"
    private const val K_FIX_OK_SRC = "fix_ok_src"
    private const val K_FIX_TRY_AT = "fix_try_at"
    private const val K_FIX_FAIL_AT = "fix_fail_at"
    private const val K_FIX_FAIL = "fix_fail"
    private const val K_REPORT_OK_AT = "report_ok_at"
    private const val K_REPORT_FAIL_AT = "report_fail_at"
    private const val K_REPORT_FAIL = "report_fail"

    /** 自检页要展示的全部诊断状态；时间戳为 0 表示"从未发生" */
    data class Snapshot(
        val lastFixOkAt: Long,
        val lastFixOkSource: String,
        val lastFixTryAt: Long,
        val lastFixFailAt: Long,
        val lastFixFailReason: String,
        val lastReportOkAt: Long,
        val lastReportFailAt: Long,
        val lastReportFailReason: String
    )

    @JvmStatic
    fun onFixOk(context: Context, source: String) {
        prefs(context).edit()
            .putLong(K_FIX_OK_AT, System.currentTimeMillis())
            .putString(K_FIX_OK_SRC, source)
            .apply()
    }

    /** 记录"准备去取一次坐标"的时刻，用于区分"从没试过"与"试了失败" */
    @JvmStatic
    fun onFixTry(context: Context) {
        prefs(context).edit().putLong(K_FIX_TRY_AT, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun onFixFail(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(K_FIX_TRY_AT, now)
            .putLong(K_FIX_FAIL_AT, now)
            .putString(K_FIX_FAIL, reason.take(160))
            .apply()
    }

    @JvmStatic
    fun onReportOk(context: Context) {
        prefs(context).edit()
            .putLong(K_REPORT_OK_AT, System.currentTimeMillis())
            .putString(K_REPORT_FAIL, "")
            .apply()
    }

    @JvmStatic
    fun onReportFail(context: Context, reason: String) {
        prefs(context).edit()
            .putLong(K_REPORT_FAIL_AT, System.currentTimeMillis())
            .putString(K_REPORT_FAIL, reason.take(160))
            .apply()
    }

    @JvmStatic
    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            lastFixOkAt = p.getLong(K_FIX_OK_AT, 0L),
            lastFixOkSource = p.getString(K_FIX_OK_SRC, "").orEmpty(),
            lastFixTryAt = p.getLong(K_FIX_TRY_AT, 0L),
            lastFixFailAt = p.getLong(K_FIX_FAIL_AT, 0L),
            lastFixFailReason = p.getString(K_FIX_FAIL, "").orEmpty(),
            lastReportOkAt = p.getLong(K_REPORT_OK_AT, 0L),
            lastReportFailAt = p.getLong(K_REPORT_FAIL_AT, 0L),
            lastReportFailReason = p.getString(K_REPORT_FAIL, "").orEmpty()
        )
    }

    /**
     * 切换老人账号 / 退出登录时清空。
     *
     * 不清的后果：换号后自检页拿上一位老人的"定位成功时刻"自证健康，
     * 而新账号其实一次都没定位过 —— 又是一次"看起来正常"。
     */
    @JvmStatic
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
