package com.antifraud.guard.util

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/**
 * 一次性定位：拿一次坐标就取消订阅。
 *
 * ## 为什么要抽出来
 * 「📍 使用当前位置」这个按钮在子女端有两处（围栏管理、守护设置），
 * 逻辑相同且都不简单：先取缓存，超过 2 分钟就监听一次实时定位，
 * 15 秒还拿不到就回退缓存，再失败才报错。
 *
 * 复制一遍的后果是两份兜底策略各自漂移 —— 典型表现是
 * 围栏页 15 秒超时、守护设置页 30 秒超时，用户完全无法理解这个差异。
 *
 * ## 为什么不用 FusedLocationProviderClient
 * 它需要 Google Play Services，在国产 ROM 上常年不可用，
 * 而本项目的目标设备就是国产 ROM。`LocationManager` 是唯一可靠的选择。
 */
class OneShotLocation(
    private val context: Context,
    /** 拿到坐标时回调。已是 WGS-84，无需转换。 */
    private val onLocated: (Location) -> Unit,
    /** 三级兜底全部失败时回调（errMsg 面向用户，可直接展示） */
    private val onFailed: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null

    /** 执行定位。调用方需自行确认已持有 ACCESS_FINE_LOCATION。 */
    fun start() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = PROVIDERS
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }

            // 2 分钟内的缓存直接用，不去等实时定位（老人常在室内，GPS 无星）
            if (last != null && System.currentTimeMillis() - last.time < CACHE_TTL_MS) {
                onLocated(last)
                return
            }

            val l = LocationListener { loc ->
                cancel()
                onLocated(loc)
            }
            listener = l
            for (provider in LIVE_PROVIDERS) {
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, l)
                } catch (_: Exception) { }
            }

            handler.postDelayed({
                if (listener === l) {
                    cancel()
                    if (last != null) onLocated(last)
                    else onFailed("定位失败，请到空旷处重试或手动输入坐标")
                }
            }, LISTEN_TIMEOUT_MS)
        } catch (e: Exception) {
            onFailed("定位异常：${e.message}")
        }
    }

    /** 取消订阅。Activity 销毁时必须调用，否则定位回调会打到已销毁的界面。 */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { l ->
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(l)
            } catch (_: Exception) { }
        }
        listener = null
    }

    private companion object {
        /** 缓存位置在此时长内视为可用，不去等实时定位 */
        const val CACHE_TTL_MS = 2 * 60 * 1000L
        /** 等实时定位的最长时间 */
        const val LISTEN_TIMEOUT_MS = 15_000L

        /** 取缓存时遍历的 provider（含被动，被动能拿到别的 App 刚定位的结果） */
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        /** 主动监听只用前两个；PASSIVE 不可主动注册 */
        val LIVE_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }
}
