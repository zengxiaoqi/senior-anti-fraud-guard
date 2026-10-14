package com.antifraud.guard.util

import android.content.Context

/**
 * 最近一次「地图选点」的落盘存根。
 *
 * ## 为什么选点结果不能只走 ActivityResult
 * 选点页 MapPickerActivity 关闭时要把坐标 setResult 给登记页
 * （GeofenceManageActivity / HomeBaseActivity）。这条通道的前提是
 * 登记页进程一直活着。但选点页带着高德 MapView，"创建→确认→销毁"
 * 生命周期极短，是全 App 唯一会触发地图引擎原生崩溃的场景
 * （TrackFragment 2026-10-10 实证的 SIGSEGV，延迟销毁只是大幅降低概率，
 * 无法证伪）——进程一旦被带走，ActivityResult 随进程一起消失，
 * 用户的表现就是"确定选点后回到上一页、表单还是空的、永远登记不了"。
 *
 * 因此确认选点时先把坐标写进 SharedPreferences，登记页两边接：
 *  1) ActivityResult 正常到达 → 回调里 consume，走原逻辑（主路径，不变）；
 *  2) 结果丢了（进程重启）→ onResume 里 peek 到存根 → 兜底恢复表单。
 * 用户重新进入登记页就能看到"已选位置"，不再出现死路。
 *
 * 存根带时间戳，只认 15 分钟内的：隔天残留的坐标恢复出来只会误导。
 * 坐标用字符串存（Float 只有 7 位有效数字，113.066402 会被截成 113.0664，
 * 对围栏来说就是十几米的偏差）。
 */
object MapPickPending {

    private const val PREFS = "map_pick_pending"
    private const val K_LAT = "lat"
    private const val K_LNG = "lng"
    private const val K_AT = "at"
    private const val K_TARGET = "target"

    /** 存根有效期：超过视为过期作废 */
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    /** 调用方标识：敏感地点登记页（GeofenceManageActivity） */
    const val TARGET_GEOFENCE = "geofence"

    /** 调用方标识：家基准位置页（HomeBaseActivity） */
    const val TARGET_HOME_BASE = "home_base"

    data class Pick(val lat: Double, val lng: Double, val at: Long, val target: String = "")

    /** 选点确认时调用：先落盘，再走 setResult/finish。target=调用方标识，供控制台兜底找回 */
    fun save(ctx: Context, lat: Double, lng: Double, target: String = "") {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_LAT, lat.toString())
            .putString(K_LNG, lng.toString())
            .putLong(K_AT, System.currentTimeMillis())
            .putString(K_TARGET, target)
            .apply()
    }

    /** 读取未过期的存根；没有或已过期返回 null（过期时顺带清掉） */
    fun peek(ctx: Context): Pick? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(K_AT, 0L)
        if (at == 0L) return null
        if (System.currentTimeMillis() - at > MAX_AGE_MS) {
            consume(ctx)
            return null
        }
        val lat = p.getString(K_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = p.getString(K_LNG, null)?.toDoubleOrNull() ?: return null
        return Pick(lat, lng, at, p.getString(K_TARGET, "") ?: "")
    }

    /** 消费存根（ActivityResult 主路径到达时由回调调用，避免 onResume 重复恢复） */
    fun consume(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
