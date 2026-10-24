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
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.location.HomeBasePolicy
import com.antifraud.guard.location.LocationDiagnostics
import com.antifraud.guard.location.LocationHeartbeatPolicy
import com.antifraud.guard.util.OneShotLocation
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

    companion object {
        private const val TAG = "LocationGuard"

        /** 系统缓存位置在此时长内视为可用（与 OneShotLocation 的 CACHE_TTL 同量级） */
        private const val CACHE_USABLE_MS = 30 * 60 * 1000L

        /** 主动探测的节流间隔：失败后不每次心跳都探，否则耗电且无意义 */
        private const val PROBE_MIN_INTERVAL_MS = 10 * 60 * 1000L

        /** 子女端改了家基准，请求重置内存中的基准（GuardWebSocketManager 下发） */
        const val ACTION_HOME_BASE_CHANGED = "com.antifraud.guard.action.HOME_BASE_CHANGED"

        /** debug-only：注入测试坐标（release 包不注册接收器） */
        const val ACTION_DEBUG_GEO = "com.antifraud.guard.action.DEBUG_GEO"
    }

    private val CHANNEL_ID = "location_guard_channel"
    private val NOTIF_ID   = 2002
    private val NOTIF_ID_HOME_BASE = 2003   // 1-9 首次自动设家的一次性告知，与常驻通知分开

    private var stayStartTime  = 0L
    private var lastLat        = 0.0
    private var lastLng        = 0.0
    private var homeLat        = 0.0
    private var homeLng        = 0.0
    private var homeSet        = false

    // ── 敏感地点围栏状态 ──
    private data class Geofence(
        val id: Int,
        val name: String,
        val lat: Double,
        val lng: Double,
        val radius: Double,
        val dwellMinutes: Int   // 1-8：围栏内停留告警阈值，0 = 不告警（进入即录行为不变）
    )
    private var fences: List<Geofence> = emptyList()
    private var fencesFetchedAt = 0L
    private var currentInsideFenceId = -1   // 当前所在围栏 id，-1 表示不在任何围栏内
    private var fenceEnterAt = 0L           // 1-8：进入当前围栏的时刻；0 = 不在任何围栏内
    private var dwellReported = false       // 1-8：当前围栏是否已报过停留告警（一进一出最多一次）

private lateinit var locationManager: LocationManager

    // ── 定位心跳（不依赖系统投递回调）──
    //
    // 为什么必须有这一层：`LOCATION_UPDATE` 原本只有一个产生点 —— onLocationChanged()。
    // 只要系统停止投递回调（没后台定位权限、被厂商 ROM 省电策略清掉订阅、时钟异常……），
    // 上报就永久归零，而且界面上看不出任何区别：
    //   「老人一直没动」与「定位早死了」在子女端长得一模一样。
    //
    // 心跳做两件事：
    //   1. 到了间隔就把"最后已知位置"重报一次 → 没动表现为持续收到同一坐标，
    //      死了表现为坐标戛然而止，两者立刻可区分
    //   2. 顺便检查订阅是否还在，不在就重新 requestLocationUpdates
    private val heartbeatHandler = android.os.Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                runHeartbeat()
            } catch (e: Exception) {
                Log.e(TAG, "定位心跳异常: ${e.message}")
            } finally {
                // 无论成败都排下一次，不能因为一次异常就让心跳永久停摆
                scheduleHeartbeat()
            }
        }
    }

    /** 最后一次成功上报位置的时间（毫秒）；0 = 从未上报 */
    @Volatile
    private var lastReportAt = 0L

    /** 最后一次成功注册定位订阅的时间（毫秒） */
    private var registeredAt = 0L

    /** 最后一次已知的位置，心跳兜底要用 */
    @Volatile
    private var lastKnownLocation: Location? = null

    /** 最近一次主动探测定位的时刻：探测有耗电代价，必须节流 */
    private var lastProbeAt = 0L

    /** 主动探测进行中，避免心跳与冷启动两条路径同时发起 */
    private var probing = false

    override fun onCreate() {
    super.onCreate()
        GuardConfig.init(this)
        ApiClient.init(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startLocationUpdates()
        // 关键：注册订阅 ≠ 会拿到坐标。鸿蒙/EMUI 上订阅成功但系统长期不投递回调
        // 是常见形态，只靠 onLocationChanged 会让服务"活着但永远不上报"。
        // 冷启动时先吃一次系统缓存（可能来自别的 App 或上次开机），拿不到就主动探一次。
        if (!seedFromLastKnownLocation()) probeLocationNow()
        scheduleHeartbeat()
        fetchGeofences()
        // 拉一次云端守护规则：子女端可能在远端改过家基准与阈值
        fetchGuardSettings()
        if (com.antifraud.guard.BuildConfig.DEBUG) registerDebugGeoReceiver()
    }

    // ── debug-only 测试钩子（release 包不注册，不存在攻击面）──
    //
    // MuMu 模拟器不支持 adb emu geo fix，也无法从命令行改虚拟定位。
    // 验证围栏链路需要确定性坐标注入，所以 debug 包开放一条广播：
    //   am broadcast -a com.antifraud.guard.action.DEBUG_GEO --ed lat 28.2 --ed lng 113.0
    // 坐标直接喂 handleLocation，走与真实定位回调完全相同的判定路径。
    private var debugGeoReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugGeoReceiver() {
        debugGeoReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 兼容三种 extra 形态：double（标准）、float（部分模拟器 am 只有 --ef）、
                // string（--es，MuMu 的 am 连 --ed 都没有，最通用的是它）
                val lat = readCoord(intent, "lat")
                val lng = readCoord(intent, "lng")
                if (lat == null || lng == null) {
                    Log.w(TAG, "[DEBUG] 注入坐标缺少有效 lat/lng extra，忽略")
                    return
                }
                Log.i(TAG, "[DEBUG] 注入测试坐标 ($lat, $lng)")
                handleLocation(android.location.Location("debug").apply {
                    latitude = lat
                    longitude = lng
                })
            }
        }
        // EXPORTED 仅存在于 debug 包：必须让 adb shell 的 am broadcast 能送进来
        registerReceiver(
            debugGeoReceiver,
            android.content.IntentFilter(ACTION_DEBUG_GEO),
            Context.RECEIVER_EXPORTED
        )
    }

    /** 按double → float → string 顺序读坐标 extra，任一形态有效即返回 */
    private fun readCoord(intent: Intent, key: String): Double? {
        if (intent.hasExtra(key)) {
            val d = intent.getDoubleExtra(key, Double.NaN)
            if (!d.isNaN()) return d
            val f = intent.getFloatExtra(key, Float.NaN)
            if (!f.isNaN()) return f.toDouble()
            intent.getStringExtra(key)?.trim()?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 响应"家基准已变更"的启动指令。
     *
     * 注意 startService 而不是 startForegroundService：这个服务本身已经是
     * 前台服务（onCreate 里 startForeground 过），Android 8+ 用
     * startForegroundService 反而要求在 5 秒内再调一次 startForeground，
     * 白白引入一个后台启动限制的失败点。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HOME_BASE_CHANGED) {
            if (HomeBasePolicy.shouldReset(
                    wasHomeSet = homeSet,
                    cloudHomeChanged = true,
                    serviceRunning = true)) {
                resetHomeBase()
            }
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        registerLocationUpdates("首次注册")
    }

    /**
     * 真正执行注册。抽出来是因为心跳需要**重复调用**它：
     * 厂商 ROM 会在后台悄悄清掉 `requestLocationUpdates` 的订阅且不通知 App，
     * 这是"一次订阅、永远没有回调"这类故障的常见来源。
     */
    private fun registerLocationUpdates(reason: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "未持有前台定位权限，跳过注册（$reason）")
            return
        }
        try {
            locationManager.removeUpdates(this)
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
            registeredAt = System.currentTimeMillis()
            Log.i(TAG, "定位订阅已注册（$reason）")
        } catch (e: Exception) {
            // SecurityException：没拿后台定位时系统会拒绝；其他：ROM 抽风
            Log.e(TAG, "定位注册失败（$reason）: ${e.message}")
        }
    }

    /**
     * 吃一次系统缓存位置。
     *
     * `getLastKnownLocation` 不需要等卫星，室内也常常有值（来自基站/WiFi 或其他
     * App 刚上报过的位置），是冷启动最快拿到坐标的路径。
     * 缓存太旧（超过 [CACHE_USABLE_MS]）时不当作"当前位置"上报 —— 那是编造数据，
     * 但会留作 lastKnownLocation，让心跳至少有东西可报。
     *
     * @return 是否成功上报了一次（调用方据此决定要不要主动探测）
     */
    private fun seedFromLastKnownLocation(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LocationDiagnostics.onFixFail(this, "没有前台定位权限，取不到坐标")
            return false
        }
        val cached = try {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { p -> try { locationManager.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "读取缓存位置失败: ${e.message}")
            null
        }

        if (cached == null) {
            Log.i(TAG, "系统没有可用的缓存位置，转主动探测")
            LocationDiagnostics.onFixFail(this, "系统无缓存位置（GPS/网络/被动均无）")
            return false
        }

        val ageMin = (System.currentTimeMillis() - cached.time) / 60000
        lastKnownLocation = cached
        if (System.currentTimeMillis() - cached.time > CACHE_USABLE_MS) {
            Log.i(TAG, "缓存位置已 ${ageMin} 分钟，仅留作兜底，不当作当前位置上报")
            return false
        }

        Log.i(TAG, "使用缓存位置上报（${ageMin} 分钟前）")
        LocationDiagnostics.onFixOk(this, "系统缓存")
        handleLocation(cached)
        return true
    }

    /**
     * 主动探一次位置（一次性，拿不到就报错）。
     *
     * ## 为什么必须有
     * `requestLocationUpdates` 是"等系统给"，系统不给就永久静默。
     * 2026-10-24 鸿蒙实机：订阅注册成功、权限全绿，三天零上报。
     * 主动探测把"等"变成"问"，失败时还能拿到可读原因（关定位开关/无 provider/超时），
     * 直接写进诊断记录给自检页看。
     *
     * 节流 [PROBE_MIN_INTERVAL_MS]：失败时每次心跳都探会很费电，而且老人多半
     * 就坐在同一个没信号的位置。
     */
    private fun probeLocationNow() {
        val now = System.currentTimeMillis()
        if (probing) return
        if (now - lastProbeAt < PROBE_MIN_INTERVAL_MS) return
        probing = true
        lastProbeAt = now
        LocationDiagnostics.onFixTry(this)
        Log.i(TAG, "主动探测定位（30 秒内取不到即视为失败）")

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }

        OneShotLocation(
            context = this,
            onLocated = { loc ->
                probing = false
                LocationDiagnostics.onFixOk(this, "主动探测")
                Log.i(TAG, "主动探测成功: ${loc.latitude}, ${loc.longitude}")
                handleLocation(loc)
            },
            onFailed = { err ->
                probing = false
                val reason = if (!enabled) "系统定位服务（GPS/网络定位）未开启" else err
                LocationDiagnostics.onFixFail(this, reason)
                Log.w(TAG, "主动探测失败: $reason")
            }
        ).start()
    }

    /**
     * 一次心跳：按需补报位置 + 按需重注册订阅。
     *
     * 判定逻辑全部在 [LocationHeartbeatPolicy] 里（有单元测试钉住），这里只负责执行。
     */
    private fun runHeartbeat() {
        val now = System.currentTimeMillis()

        // 1) 订阅可能已被系统悄悄清掉
        if (LocationHeartbeatPolicy.needsReregister(registeredAt, now)) {
            Log.i(TAG, "定位订阅超过安全窗口，主动重注册")
            registerLocationUpdates("心跳重注册")
        }

        // 2) 到了间隔就补报最后已知位置
        val interval = LocationHeartbeatPolicy.intervalFor(
            hasBackgroundLocation = GuardServiceStarter.hasBackgroundLocation(this)
        )
        if (!LocationHeartbeatPolicy.shouldReport(now, lastReportAt, interval)) return

        val loc = lastKnownLocation
        if (loc == null) {
            // 一次位置都没拿到过：不硬造数据，但要主动问系统一次。
            // 原实现在这里只打日志就 return —— 于是"订阅着、界面全绿、服务端零上报"
            // 可以无限期持续且无人察觉（2026-10-24 鸿蒙实机即此形态）。
            Log.w(TAG, "心跳到点但尚无任何已知位置，改走主动探测")
            probeLocationNow()
            return
        }

        Log.i(TAG, "心跳兜底：补报最后已知位置")
        handleLocation(loc)
    }

    private fun scheduleHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(
            heartbeatRunnable,
            LocationHeartbeatPolicy.MIN_EFFECTIVE_INTERVAL_MS
        )
    }

    override fun onLocationChanged(location: Location) {
        LocationDiagnostics.onFixOk(this, "系统回调")
        handleLocation(location)
    }

    /**
     * 处理一个位置点。
     *
     * 抽出来的原因：心跳兜底也要走这条路径 —— 它会拿"最后已知位置"重新喂进来，
     * 保证围栏判定、停留判定、上报三件事在任何入口下行为完全一致。
     * 之前这两条路径是分开的，很容易出现"心跳只上报不走判定"的偏差。
     */
    private fun handleLocation(location: Location) {
      val lat = location.latitude
        val lng = location.longitude

        lastKnownLocation = location
        lastReportAt = System.currentTimeMillis()

        // 围栏列表与守护规则共用一个 30 分钟窗口：后台多一次请求是要算进
        // 耗电预算的，而这两类数据的新鲜度要求相同（子女端改了都要等一会儿）。
        if (System.currentTimeMillis() - fencesFetchedAt > 30 * 60 * 1000L) {
            fetchGeofences()
            fetchGuardSettings()
        }

        // ── 敏感地点围栏判断 ──
        checkGeofences(lat, lng)

        // 1-9：家基准优先用子女端显式设置（云端 guard_settings.homeLat/homeLng）。
        // 旧逻辑"首次定位即家"：老人在商场首次打开 App 就把商场当成家，
        // 之后所有"离家"判定全部失真且无从发现。云端没设置时才回退首次定位，
        // 并发通知告知（用户可纠正，不再是静默自作主张）。
        if (!homeSet) {
            if (GuardConfig.hasFamilyHome) {
                homeLat = GuardConfig.homeLat
                homeLng = GuardConfig.homeLng
            } else {
                homeLat = lat
                homeLng = lng
                notifyHomeBaseSet(lat, lng)
            }
            homeSet = true
            stayStartTime = System.currentTimeMillis()
        }

        val distFromHome = calculateDistance(lat, lng, homeLat, homeLng)
        val distFromLast = calculateDistance(lat, lng, lastLat, lastLng)

        // 1-10：三个阈值改读 GuardConfig（本机+云端双向同步）。
        // 原先 500/100/40 全硬编码，设置页改了完全无效——和通话阈值死配置同一类病
        val awayRadius = GuardConfig.homeAwayRadiusMeters.toDouble()
        val stayMoveRadius = GuardConfig.stayMoveMeters.toDouble()
        val stayThresholdMinutes = GuardConfig.homeStayMinutes

        // 判断是否已离开家（超过可配置半径视为外出）
        val isAway = distFromHome > awayRadius

        // 判断是否在同一地点停留（移动小于阈值视为停留）
        val isStaying = distFromLast < stayMoveRadius

        if (isAway && isStaying) {
            val stayMinutes = (System.currentTimeMillis() - stayStartTime) / 60000
            // 在陌生地点停留超过阈值：上报风险
            if (stayMinutes >= stayThresholdMinutes) {
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
            details   = details,
            // 上报成败必须落盘：否则"取到坐标但传不上去"和"根本没定位"
            // 在自检页上长得一模一样，又回到原点
            onSuccess = { LocationDiagnostics.onReportOk(this) },
            onError   = { err -> LocationDiagnostics.onReportFail(this, err) }
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
        Log.w(TAG, "敏感停留告警：已在陌生地点停留 ${stayMinutes} 分钟")
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
                                radius = o.optDouble("radius", 200.0),
                                dwellMinutes = o.optInt("dwell_minutes", 0)
                            ))
                        }
                    }
                    fences = list
                    fencesFetchedAt = System.currentTimeMillis()
                    Log.i(TAG, "已同步 ${fences.size} 个敏感地点围栏")
                } catch (e: Exception) {
                    Log.e(TAG, "围栏数据解析失败: ${e.message}")
                }
            },
            onError = { err -> Log.w(TAG, "围栏拉取失败: $err") }
        )
    }

    /**
     * 拉取云端守护规则并落地到本机。
     *
     * ## 为什么需要这条兜底
     * 子女端改设置走 WS 推送，但 WS 会断、指令会丢、老人端会长时间离线。
     * 这条路径保证最长一个节流周期内（30 分钟）一定收敛到最新配置。
     *
     * ## 复用围栏的节流时钟
     * 与 fetchGeofences 共用 30 分钟窗口（见 handleLocation 里的判断），
     * 不额外增加请求 —— 老人端在后台，额外请求是要算进耗电预算的。
     */
    private fun fetchGuardSettings() {
        ApiClient.fetchElderSettings(
            elderId = GuardConfig.elderId,
            onSuccess = { settings ->
                if (settings == null) {
                    Log.i(TAG, "云端未配置守护规则，保持本机当前值")
                    return@fetchElderSettings
                }
                val applied = GuardConfig.applySettingsFromServer(settings)
                if (applied.isEmpty()) return@fetchElderSettings

                val homeChanged = applied.contains("家的基准位置")
                Log.i(TAG, "已从云端恢复守护规则：${applied.joinToString("、")}")

                // 关键：只更新 GuardConfig 不够，内存里的 homeLat/homeLng
                // 还停在旧值（homeSet 闩锁），必须显式重置才真正生效。
                if (HomeBasePolicy.shouldReset(
                        wasHomeSet = homeSet,
                        cloudHomeChanged = homeChanged,
                        serviceRunning = true)) {
                    resetHomeBase()
                }
            },
            onError = { err -> Log.w(TAG, "守护规则拉取失败: $err") }
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
                fenceEnterAt = System.currentTimeMillis()
                dwellReported = false
            } else {
                // 离开围栏：上报并停止围栏触发的录音（SOS 录音不受影响）
                onExitGeofence(lat, lng)
                fenceEnterAt = 0L
                dwellReported = false
            }
            currentInsideFenceId = insideId
        } else if (insideFence != null && !dwellReported &&
            insideFence.dwellMinutes > 0 && fenceEnterAt > 0
        ) {
            // 1-8：围栏内停留超阈值 → 额外报 GEOFENCE_DWELL。
            // 进入即录音的行为不变（那是 G12，归 Phase 2 的 ConsentGate 管）
            val stayedMin = (System.currentTimeMillis() - fenceEnterAt) / 60000
            if (stayedMin >= insideFence.dwellMinutes) {
                dwellReported = true
                reportGeofenceDwell(insideFence, lat, lng, stayedMin)
            }
        }
    }

    private fun reportGeofenceDwell(fence: Geofence, lat: Double, lng: Double, stayedMinutes: Long) {
        val details = JSONObject().apply {
            put("geofence_id", fence.id)
            put("geofence_name", fence.name)
            put("dwell_minutes_threshold", fence.dwellMinutes)
            put("stay_minutes", stayedMinutes)
            put("latitude", lat)
            put("longitude", lng)
            put("event_desc", "在敏感地点「${fence.name}」已停留 ${stayedMinutes} 分钟（阈值 ${fence.dwellMinutes} 分钟）")
        }
        ApiClient.reportRiskEvent(
            eventType = "GEOFENCE_DWELL",
            severity  = "MEDIUM",
            details   = details
        )
        Log.w(TAG, "围栏停留告警：「${fence.name}」已停留 ${stayedMinutes} 分钟")
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
        Log.w(TAG, "进入敏感地点围栏「${fence.name}」，自动开启录音存证")

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
        Log.i(TAG, "已离开敏感地点围栏，停止围栏录音")

        val stopIntent = Intent(this, RecordingGuardService::class.java).apply {
            action = RecordingGuardService.ACTION_STOP
            putExtra(RecordingGuardService.EXTRA_REASON, "GEOFENCE")
        }
        startService(stopIntent)
    }

    /**
     * 重置家基准与停留计时，让下次 handleLocation 重新取云端值。
     *
     * ## 为什么必须有这个方法
     * `homeSet` 是一次性闩锁（见 handleLocation 里的 `if (!homeSet)`），
     * 置 true 后永不回退。云端家基准改了之后，内存里的 homeLat/homeLng
     * 永远停在旧值 —— 子女端改了设置、界面上显示新坐标、实际按旧坐标
     * 判定，而且没有任何报错。
     *
     * ## 为什么 stayStartTime 必须一起清
     * 家基准换了之后，旧的停留计时是按旧基准算出来的。
     * 不清会让老人在刚改完设置的一瞬间就撞上一条
     * LOCATION_RISK「在陌生地点停留超 40 分钟」—— 而他可能根本没出门。
     *
     * 调用前请先过 [HomeBasePolicy.shouldReset]。
     */
    fun resetHomeBase() {
        if (!homeSet) return          // 本来就没设过，下次定位自然取新值
        homeSet = false
        stayStartTime = 0L
        Log.i(TAG, "家基准已重置，下次位置更新将重新取云端配置")
    }

    /**
     * 首次自动设家的一次性告知（1-9"二次确认"的轻量实现）：
     * 旧逻辑静默把首次定位当作家，用户完全无从知道这条判定基准的存在。
     * 现在至少明确告知"已设、设在哪、怎么纠正"。子女端显式设置优先级更高。
     */
    private fun notifyHomeBaseSet(lat: Double, lng: Double) {
        try {
            val intent = Intent(this, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("家的基准位置已记录")
                .setContentText(
                    "已将当前位置设为家的基准（${String.format("%.4f", lat)}, " +
                        "${String.format("%.4f", lng)}）。" +
                        "这是临时兜底：老人出门在外时首次打开 App 会被误当作家。" +
                        "可在子女端「守护设置」里改为真实住址。"
                )
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_HOME_BASE, notification)
        } catch (e: Exception) {
            Log.w(TAG, "设家通知发送失败: ${e.message}")
        }
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

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        debugGeoReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
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
