package com.antifraud.guard.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject

/**
 * 位置守护服务：
 *  - 每 10 分钟上报一次 GPS 坐标到后端
 *  - 当老人离开"安全区域"（家）一段时间后持续标记位置
 *  - 若在同一非家庭地点停留超过 40 分钟，上报 LOCATION_RISK 高危事件
 *  - 敏感地点围栏：从服务器拉取子女登记的可疑地点（坐标+半径），
 *    进入围栏上报 GEOFENCE_RECORDING 高危事件并自动开启环境录音，
 *    离开围栏上报 GEOFENCE_EXIT 并停止围栏录音
 */
class LocationGuardService : Service(), LocationListener {

    private val CHANNEL_ID = "location_guard_channel"
    private val NOTIF_ID   = 2002

    private var stayStartTime  = 0L
    private var lastLat        = 0.0
    private var lastLng        = 0.0
    private var homeLat        = 0.0
    private var homeLng        = 0.0
    private var homeSet        = false

    // ── 敏感地点围栏状态 ──
    private data class Geofence(val id: Int, val name: String, val lat: Double, val lng: Double, val radius: Double)
    private var fences: List<Geofence> = emptyList()
    private var fencesFetchedAt = 0L
    private var currentInsideFenceId = -1   // 当前所在围栏 id，-1 表示不在任何围栏内

    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        GuardConfig.init(this)
        ApiClient.init(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startLocationUpdates()
        fetchGeofences()
    }

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                // 每 10 分钟或每 100 米刷新一次位置
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10 * 60 * 1000L,
                    100f,
                    this
                )
                // 同时使用网络定位补充
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    10 * 60 * 1000L,
                    100f,
                    this
                )
            } catch (e: Exception) {
                Log.e("LocationGuard", "定位启动失败: ${e.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lng = location.longitude

        // 围栏列表每 30 分钟刷新一次（子女端可能新增/停用敏感地点）
        if (System.currentTimeMillis() - fencesFetchedAt > 30 * 60 * 1000L) {
            fetchGeofences()
        }

        // ── 敏感地点围栏判断 ──
        checkGeofences(lat, lng)

        // 第一次定位时，将当前位置设为"家"的基准位置
        if (!homeSet) {
            homeLat = lat
            homeLng = lng
            homeSet = true
            stayStartTime = System.currentTimeMillis()
        }

        val distFromHome = calculateDistance(lat, lng, homeLat, homeLng)
        val distFromLast = calculateDistance(lat, lng, lastLat, lastLng)

        // 判断是否已离开家（超过 500 米视为外出）
        val isAway = distFromHome > 500

        // 判断是否在同一地点停留（移动小于 100 米视为停留）
        val isStaying = distFromLast < 100

        if (isAway && isStaying) {
            val stayMinutes = (System.currentTimeMillis() - stayStartTime) / 60000
            // 在陌生地点停留超过 40 分钟：上报风险
            if (stayMinutes >= 40) {
                reportLocationRisk(lat, lng, stayMinutes)
                // 重置计时，避免重复触发
                stayStartTime = System.currentTimeMillis()
            }
        } else {
            // 位置发生明显移动，重置停留计时
            stayStartTime = System.currentTimeMillis()
        }

        // 无论如何都上报常规位置日志（每次回调时执行）
        reportNormalLocation(lat, lng, isAway)

        lastLat = lat
        lastLng = lng
    }

    private fun reportNormalLocation(lat: Double, lng: Double, isAway: Boolean) {
        val details = JSONObject().apply {
            put("latitude", lat)
            put("longitude", lng)
            put("address", "GPS 位置 (${String.format("%.4f", lat)}, ${String.format("%.4f", lng)})")
            put("is_away_from_home", isAway)
        }
        ApiClient.reportRiskEvent(
            eventType = "LOCATION_UPDATE",
            severity  = if (isAway) "LOW" else "LOW",
            details   = details
        )
    }

    private fun reportLocationRisk(lat: Double, lng: Double, stayMinutes: Long) {
        val details = JSONObject().apply {
            put("latitude", lat)
            put("longitude", lng)
            put("address", "陌生地点长时间停留 (${String.format("%.4f", lat)}, ${String.format("%.4f", lng)})")
            put("stay_minutes", stayMinutes)
        }
        ApiClient.reportRiskEvent(
            eventType = "LOCATION_RISK",
            severity  = "MEDIUM",
            details   = details
        )
        Log.w("LocationGuard", "敏感停留告警：已在陌生地点停留 ${stayMinutes} 分钟")
    }

    // ════════════════════════════════════════
    //  敏感地点围栏：进入自动录音，离开停止
    // ════════════════════════════════════════

    private fun fetchGeofences() {
        ApiClient.elderGet(
            "/api/geofence/elder/${GuardConfig.elderId}",
            onSuccess = { res ->
                try {
                    val arr = res.optJSONArray("data")
                    val list = mutableListOf<Geofence>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(Geofence(
                                id     = o.optInt("id"),
                                name   = o.optString("name", "敏感地点"),
                                lat    = o.optDouble("latitude"),
                                lng    = o.optDouble("longitude"),
                                radius = o.optDouble("radius", 200.0)
                            ))
                        }
                    }
                    fences = list
                    fencesFetchedAt = System.currentTimeMillis()
                    Log.i("LocationGuard", "已同步 ${fences.size} 个敏感地点围栏")
                } catch (e: Exception) {
                    Log.e("LocationGuard", "围栏数据解析失败: ${e.message}")
                }
            },
            onError = { err -> Log.w("LocationGuard", "围栏拉取失败: $err") }
        )
    }

    private fun checkGeofences(lat: Double, lng: Double) {
        var insideFence: Geofence? = null
        for (f in fences) {
            if (calculateDistance(lat, lng, f.lat, f.lng) <= f.radius) {
                insideFence = f
                break
            }
        }

        val insideId = insideFence?.id ?: -1
        if (insideId != currentInsideFenceId) {
            if (insideFence != null) {
                // 进入围栏：上报高危事件并自动开启环境录音
                onEnterGeofence(insideFence, lat, lng)
            } else {
                // 离开围栏：上报并停止围栏触发的录音（SOS 录音不受影响）
                onExitGeofence(lat, lng)
            }
            currentInsideFenceId = insideId
        }
    }

    private fun onEnterGeofence(fence: Geofence, lat: Double, lng: Double) {
        val details = JSONObject().apply {
            put("geofence_id", fence.id)
            put("geofence_name", fence.name)
            put("event_desc", "进入子女登记的敏感地点「${fence.name}」，环境录音已自动开启存证")
            put("latitude", lat)
            put("longitude", lng)
            put("address", "${fence.name} 附近 (${String.format("%.4f", lat)}, ${String.format("%.4f", lng)})")
        }
        ApiClient.reportRiskEvent(
            eventType = "GEOFENCE_RECORDING",
            severity  = "HIGH",
            details   = details
        )
        Log.w("LocationGuard", "进入敏感地点围栏「${fence.name}」，自动开启录音存证")

        val intent = Intent(this, RecordingGuardService::class.java).apply {
            action = RecordingGuardService.ACTION_START
            putExtra(RecordingGuardService.EXTRA_REASON, "GEOFENCE")
            putExtra(RecordingGuardService.EXTRA_PLACE, fence.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun onExitGeofence(lat: Double, lng: Double) {
        val details = JSONObject().apply {
            put("event_desc", "已离开敏感地点围栏，环境录音自动停止")
            put("latitude", lat)
            put("longitude", lng)
        }
        ApiClient.reportRiskEvent(
            eventType = "GEOFENCE_EXIT",
            severity  = "LOW",
            details   = details
        )
        Log.i("LocationGuard", "已离开敏感地点围栏，停止围栏录音")

        val stopIntent = Intent(this, RecordingGuardService::class.java).apply {
            action = RecordingGuardService.ACTION_STOP
            putExtra(RecordingGuardService.EXTRA_REASON, "GEOFENCE")
        }
        startService(stopIntent)
    }

    /** 简单球面距离计算（米） */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(this) } catch (e: Exception) {}
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置守护中")
            .setContentText("正在感知老人位置安全，防范陌生地点长时间停留风险")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "位置守护服务通道", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "老人位置感知与敏感地点停留告警" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
