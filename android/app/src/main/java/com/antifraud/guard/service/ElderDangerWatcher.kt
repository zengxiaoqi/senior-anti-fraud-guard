package com.antifraud.guard.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.db.RiskEventDbHelper
import com.antifraud.guard.sensor.FallDetectionPolicy
import com.antifraud.guard.util.ElderDangerConfirmActivity
import com.antifraud.guard.util.EmergencyAlertLauncher
import com.antifraud.guard.util.OneShotLocation
import org.json.JSONObject

/**
 * 老人端危险检测（加速度计跌倒判定）。
 *
 * ## 为什么需要它
 * 防诈系统解决的是"被骗钱"，但老人出事的第一原因往往是**物理意外**：
 * 浴室摔倒、独居老人突发不适、手机摔坏够不着。
 * 这些场景的共同点是**老人自己发不出任何信号** ——
 * 通话监控、支付监听、位置心跳、围栏录音，在这一刻全部失效。
 * 恰恰是最需要告警的时刻，守护系统是哑的。
 * 手机在口袋/裤兜里，加速度计是唯一还能工作的检测面。
 *
 * ## 为什么不是独立 Service（对"新增一个前台服务"的刻意回避）
 * 独立前台服务 = 老人端通知栏多出一条常驻通知，会诱导用户一键全关；
 * 做成普通 Service 则后台启动受限（Android 8+ IllegalStateException）。
 * 与 [CallRiskWatcher] 完全同构：挂在既有前台服务 ForegroundGuardService
 * 的生命周期里，保活路径（开机自启/闹钟/Job/界面）全部继承，
 * 零新增权限面、零新增常驻通知。
 *
 * ## 告警链路：先问老人，再打扰子女
 * 1. 判定为疑似跌倒（[FallDetectionPolicy]）→ **不立即上报**
 * 2. 拉起 [ElderDangerConfirm] 全屏页，60 秒倒计时：
 *    - 老人点"我没事" → 取消，不上报（这是绝大多数误报）
 *    - 无人应答 → 才真正上报 FALL_RISK（HIGH）+ 开启环境录音存证
 *
 * 老人摔倒后不可能按下任何按钮，所以"没有响应"本身就是最可靠的信号。
 * 误报一次子女就被半夜叫醒一次，几次之后告警会被集体静音 ——
 * 那时真摔倒也没人管，所以这一步是整条链路里最不能省的。
 *
 * ## 降级
 * 设备没有加速度计（部分平板/模拟器）→ 直接不监听并记日志，
 * 整条链路静默失效但不影响其他守护功能。绝不能因为缺传感器而抛异常。
 */
class ElderDangerWatcher(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ElderDanger"
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private val detector = FallDetectionPolicy.Detector()
    private var started = false

    /** 上一次上报时刻（毫秒），0 = 从未上报；用于冷却判定 */
    @Volatile
    private var reportedAt = 0L

    /** 上一次拉起确认页的时刻，防止重复叠页 */
    @Volatile
    private var confirmShownAt = 0L

    fun start() {
        if (started) return
        GuardConfig.init(context)

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            Log.w(TAG, "本机无加速度传感器，跌倒检测不可用（不影响其他守护功能）")
            return
        }
        try {
            // SENSOR_DELAY_UI(≈60ms) 比 GAME(20ms) 省电，而撞击峰值持续时间在百毫秒级，
            // 60ms 采样不会漏掉峰值；这里用 50ms（取 SENSOR_DELAY_UI 与 GAME 之间不可直接指定，
            // 故按 UI 档注册，判定阈值本身对采样率不敏感）。
            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
            started = true
            Log.i(TAG, "老人危险检测（加速度计跌倒判定）已启动")
        } catch (e: Exception) {
            Log.w(TAG, "加速度传感器注册失败，跌倒检测不可用: ${e.message}")
        }
    }

    fun stop() {
        if (!started) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "加速度传感器注销失败: ${e.message}")
        }
        started = false
        Log.i(TAG, "老人危险检测已停止")
    }

    /** 当前是否仍在监听（设置页自检用） */
    fun isRunning(): Boolean = started

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        val mag = FallDetectionPolicy.magnitude(e.values[0], e.values[1], e.values[2])

        val confirmed = try {
            detector.onSample(mag, now)
        } catch (ex: Exception) {
            Log.e(TAG, "跌倒判定异常: ${ex.message}")
            false
        }
        if (!confirmed) return

        Log.w(TAG, "⚠️ 判定为疑似跌倒（峰值 ${detector.peakMagnitude} m/s²），拉起老人确认页")
        launchConfirm()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 拉起老人确认页。
     *
     * 后台启动 Activity 在 Android 10+ 受限（且是**静默丢弃**，不报错），
     * 所以这里必须走 EmergencyAlertLauncher 的降级链；
     * 降级到"后台拉不起"时立刻直接上报 —— 宁可误报一次，
     * 也不能出现"检测到老人摔倒但系统一声不吭"这种最坏的失败模式。
     */
    private fun launchConfirm() {
        // 同一时刻只允许一个确认页：连点/多次触发会叠出两层倒计时，
        // 老人看到的倒计时会互相盖掉，而且两次 onFinish 可能各发一次告警。
        val now = System.currentTimeMillis()
        if (confirmShownAt != 0L && now - confirmShownAt < 90_000L) return
        confirmShownAt = now

        val payload = ElderDangerConfirmActivity.Payload(
            peakMagnitude = detector.peakMagnitude,
            impactAt = detector.impactAtMs
        )
        try {
            EmergencyAlertLauncher.launchElderDangerConfirm(context, payload)
        } catch (e: Exception) {
            Log.e(TAG, "拉起危险确认页失败，直接上报：${e.message}")
            reportDanger(payload, confirmedByElder = false)
        }
    }

    /** 老人点「我没事」：清掉本轮检测状态，让下一次跌倒能被正常检出 */
    fun onElderCancelled() {
        confirmShownAt = 0L
        detector.onSensorReset()
        Log.i(TAG, "老人已确认平安，本次疑似跌倒不再上报")
    }

    /**
     * 真正上报疑似跌倒。
     *
     * @param confirmedByElder true = 老人点过「我没事」但仍需留痕；
     *        false = 超时未响应，或确认页拉起失败（这是最需要被子女看到的一条）
     */
    fun reportDanger(payload: ElderDangerConfirmActivity.Payload, confirmedByElder: Boolean) {
        val now = System.currentTimeMillis()
        // 冷却：同一次摔倒只报一次。老人在地上滚动、被扶起都可能二次触发。
        if (FallDetectionPolicy.inCooldown(reportedAt, now)) {
            Log.i(TAG, "疑似跌倒仍在冷却期（${(now - reportedAt) / 1000}s 前已报过），不重复上报")
            return
        }
        reportedAt = now
        confirmShownAt = 0L

        // 本地先落库：网络可能正好是断的（这也是危险场景之一）。
        // 本地留痕意味着子女端即使这次没收到推送，风险记录页仍能看到。
        try {
            val db = RiskEventDbHelper(context)
            val details = buildDetails(payload, confirmedByElder)
            db.insertEvent(GuardConfig.elderId, "FALL_RISK", "HIGH", details)
            Log.w(TAG, "疑似跌倒已记录到本地（峰值 ${payload.peakMagnitude} m/s²）")
        } catch (e: Exception) {
            Log.e(TAG, "疑似跌倒写入本地库失败: ${e.message}")
        }

        // 附带实时位置：子女接到告警的第一反应就是"人在哪"。
        // 用 OneShotLocation 而不是自己再写一遍取缓存逻辑 ——
        // 这个类已经封装了"缓存优先 / 15s 实时 / 超时回退缓存"的三级兜底，
        // 复制一遍必然出现两处超时策略不一致（围栏页 15s、这里 30s 之类）。
        val details = buildDetails(payload, confirmedByElder)
        var cancelled = false
        val locator = OneShotLocation(
            context,
            onLocated = { loc ->
                if (cancelled) return@OneShotLocation
                try {
                    details.put("latitude", loc.latitude).put("longitude", loc.longitude)
                } catch (e: Exception) { /* 附坐标失败不阻断上报 */ }
                ApiClient.reportRiskEvent(
                    eventType = "FALL_RISK",
                    severity = "HIGH",
                    details = details,
                    onSuccess = { Log.w(TAG, "疑似跌倒已上报给守护人（含坐标）") },
                    onError = { err -> Log.e(TAG, "疑似跌倒上报失败（已留本地记录）: $err") }
                )
            },
            onFailed = { err ->
                if (cancelled) return@OneShotLocation
                Log.w(TAG, "危险告警取位置失败（$err），按无坐标上报")
                ApiClient.reportRiskEvent(
                    eventType = "FALL_RISK",
                    severity = "HIGH",
                    details = details,
                    onSuccess = { Log.w(TAG, "疑似跌倒已上报给守护人（无坐标）") },
                    onError = { e2 -> Log.e(TAG, "疑似跌倒上报失败（已留本地记录）: $e2") }
                )
            }
        )
        try {
            locator.start()
        } catch (e: Exception) {
            // 没定位权限时 OneShotLocation 会抛 SecurityException。
            // 这里必须兜住：无坐标也要报，绝不能因为拿不到位置就把告警吞掉。
            Log.w(TAG, "危险告警定位异常，按无坐标上报: ${e.message}")
            ApiClient.reportRiskEvent(eventType = "FALL_RISK", severity = "HIGH", details = details)
        }
    }

    private fun buildDetails(
        payload: ElderDangerConfirmActivity.Payload,
        confirmedByElder: Boolean
    ): JSONObject = JSONObject().apply {
        put("trigger", "accelerometer_fall_detection")
        put("peak_magnitude", payload.peakMagnitude)
        put("impact_at", payload.impactAt)
        put("device", Build.MODEL)
        // elder_confirmed=false 是最需要被子女看到的一条：
        // "老人 60 秒没有任何响应" 比 "传感器判定像摔倒" 严重得多
        put("elder_confirmed", confirmedByElder)
        put(
            "event_desc",
            if (confirmedByElder)
                "传感器检测到疑似跌倒（老人已确认平安）"
            else
                "传感器检测到疑似跌倒，且老人 ${FallDetectionPolicy.CONFIRM_WINDOW_SEC} 秒内无任何响应，请立即电话或上门核实"
        )
    }
}
