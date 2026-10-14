package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.antifraud.guard.BaseActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.antifraud.guard.BuildConfig
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.MapPickPending

/**
 * 地图选点器：在地图上拖动/点击选中一个位置，返回 WGS-84 坐标给调用方。
 *
 * 双模式（与 TrackFragment 一致）：
 *  1) 配置了 AMAP_KEY → 高德原生地图 + 固定中央图钉（拖动地图选点）
 *  2) 未配置 key → 内置 WebView 瓦片地图 pick_map.html（点击放图钉选点）
 *
 * 结果通过 setResult 返回：EXTRA_LAT / EXTRA_LNG（WGS-84，已反纠偏，可直接入库）
 */
class MapPickerActivity : BaseActivity() {

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"

        /** 调用方标识（MapPickPending.TARGET_*）：结果随进程丢失后，控制台据此把用户送回正确的页面 */
        const val EXTRA_CALLER = "extra_caller"

        /**
         * 上一轮选点页寄存的 MapView。
         *
         * ## 为什么确认后不能当场销毁 MapView
         * 选点页生命周期极短（进来拖两下就确认），MapView「创建→销毁」会撞上
         * 高德 SDK 内部 GL 渲染线程的收尾竞态 → 原生 SIGSEGV，进程被系统直接
         * 带走：ActivityResult、返回栈全部蒸发，用户的表现就是「确定选点后
         * 被踢回控制台，登记页没了」。控制台轨迹页的实证解法（2026-10-10）是
         * Fragment 保活**根本不销毁**——延迟 500ms 销毁在轨迹页场景已被实测
         * 击穿，压不住。本页是独立 Activity，每次选点都会走完整生命周期，
         * 所以把「销毁」从确认时刻整体挪走：确认后只 onPause + 离屏 + 寄存，
         * 等下一次进选点页时旧地图已停摆多时（渲染线程早已收尾，不在
         * 危险窗口），先安全销毁旧的再建新的。
         *
         * 代价：最多常驻一个寄存 MapView（连带其 Activity 引用直到下次
         * 销毁或进程结束）。选点是低频操作，可接受；崩溃的代价远大于这点内存。
         */
        private var parked: MapView? = null
    }

    private var useNativeMap = false
    private var amapView: MapView? = null
    private var amapController: AMap? = null
    private var pageReady = false

    /**
     * 相机是否发生过任何变动（初始化定位/用户拖动都会置位）。
     * 高德地图初始化失败（key 无效/引擎异常）时相机永远停在出厂位置，
     * 此时放行确认会把围栏登记到默认视野中心——比"点不动"更糟的静默错误。
     */
    private var cameraMoved = false

    private lateinit var webView: WebView
    private lateinit var btnConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)
        title = "地图选点"

        webView = findViewById(R.id.pick_web_map)
        btnConfirm = findViewById(R.id.btn_pick_confirm)
        useNativeMap = BuildConfig.AMAP_KEY.isNotBlank()

        if (useNativeMap) {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            findViewById<View>(R.id.pick_center_pin).visibility = View.VISIBLE
            btnConfirm.visibility = View.VISIBLE
            // 安全销毁上一轮寄存的旧 MapView：它自上次选点结束就已 onPause + 离屏，
            // 渲染线程早已收尾，此刻不在「刚创建就销毁」的竞态窗口里。
            parked?.let { old ->
                parked = null
                try { old.onDestroy() } catch (_: Throwable) { /* SDK 内部竞态，尽力而为 */ }
            }
            // MapView 改为程序化创建（布局里只留容器）：寄存复用方案下每轮
            // 实例都由本页自建自管，不再从 XML inflate
            val container = findViewById<FrameLayout>(R.id.pick_amap_container)
            container.visibility = View.VISIBLE
            val mv = MapView(this)
            container.addView(
                mv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            mv.onCreate(null)
            amapView = mv
            amapController = mv.map.apply {
                uiSettings.isZoomControlsEnabled = true
                uiSettings.isRotateGesturesEnabled = false
                setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(p: com.amap.api.maps.model.CameraPosition?) {
                        cameraMoved = true
                    }

                    override fun onCameraChangeFinish(p: com.amap.api.maps.model.CameraPosition?) {
                        cameraMoved = true
                    }
                })
            }
            btnConfirm.setOnClickListener { confirmNative() }
        } else {
            setupWebView()
        }

        // 初始视野：优先老人最新上报位置（可疑地点大概率在老人活动轨迹附近）
        moveToElderLatestLocation()
    }

    /* ---------- WebView 瓦片地图模式 ---------- */

    private var pendingJs: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.visibility = View.VISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(UiPrefs.bgColor(this))
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                pageReady = true
                pendingJs?.let {
                    webView.evaluateJavascript(it, null)
                    pendingJs = null
                }
            }
        }
        webView.loadUrl("file:///android_asset/pick_map.html")
    }

    /** JS 桥：网页「确认选点」回调（GCJ-02 坐标） */
    private inner class Bridge {
        @JavascriptInterface
        fun onPicked(gcjLat: Double, gcjLng: Double) {
            runOnUiThread {
                val wgs = GeoConverter.gcj2wgs(gcjLat, gcjLng)
                finishPick(wgs.first, wgs.second)
            }
        }
    }

    /* ---------- 高德原生地图模式 ---------- */

    /**
     * 确认选点：立即返回坐标，不做 App 内逆地理。
     *
     * 为什么不在选点页反查：搜索 SDK 首次调用要冷启动（联网取配置 + 建连），
     * 回调经常超过任何合理的等待上限；设短超时会丢掉迟到的回调（用户白等还拿不到名），
     * 设长超时用户又卡在选点页。地名解析已挪到 GeofenceManageActivity 异步做——
     * 登记页一直活着，可以慢慢等回调，SDK 失败还有服务端 /reverse 兜底。
     */
    private fun confirmNative() {
        val target = amapController?.cameraPosition?.target
        if (target == null || (target.latitude == 0.0 && target.longitude == 0.0)) {
            // 地图还没完成初始化就点确认：此时 target 是默认的海原点，
            // 放行会把围栏登记到"几内亚湾"，宁可让用户再等一秒
            Toast.makeText(this, "地图还在加载，请稍候再确认", Toast.LENGTH_SHORT).show()
            return
        }
        if (!cameraMoved) {
            // 相机从未动过 = 初始定位没成功、用户也没拖过：
            // 此时"选中的点"只是 SDK 的出厂视野中心，不是用户想要的任何位置
            Toast.makeText(this, "还没定位到初始位置，请拖动地图后再确认", Toast.LENGTH_LONG).show()
            return
        }
        val wgs = GeoConverter.gcj2wgs(target.latitude, target.longitude)
        finishPick(wgs.first, wgs.second)
    }

    /**
     * 统一出口：返回 WGS-84 坐标，地名由 GeofenceManageActivity 异步解析。
     *
     * 坐标在 setResult 之前先落盘（[MapPickPending.save]）：本页带着高德
     * MapView，"确认→销毁"是全 App 唯一可能触发地图引擎原生崩溃的位置，
     * 进程一旦被带走，ActivityResult 会和进程一起消失。落盘后登记页
     * onResume 能兜底恢复表单，用户永远不至于"选完点什么都没了"。
     */
    private fun finishPick(wgsLat: Double, wgsLng: Double) {
        MapPickPending.save(
            this, wgsLat, wgsLng,
            intent?.getStringExtra(EXTRA_CALLER).orEmpty()
        )
        Toast.makeText(this, String.format("已选点：%.6f, %.6f", wgsLat, wgsLng), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_LAT, wgsLat)
            putExtra(EXTRA_LNG, wgsLng)
        })
        finish()
    }

    /* ---------- 初始定位 ---------- */

    private fun moveToElderLatestLocation() {
        if (!GuardConfig.isFamilyBound) return
        ApiClient.familyGet("/api/events/location/${GuardConfig.boundElderId}", onSuccess = { data ->
            val first = data.optJSONArray("data")?.optJSONObject(0) ?: return@familyGet
            val lat = first.optDouble("latitude", 0.0)
            val lng = first.optDouble("longitude", 0.0)
            if (lat == 0.0 || lng == 0.0) return@familyGet
            moveCameraTo(lat, lng, 16f)
        }, onError = { })
    }

    private fun moveCameraTo(wgsLat: Double, wgsLng: Double, zoom: Float) {
        if (useNativeMap) {
            val g = GeoConverter.wgs2gcj(wgsLat, wgsLng)
            amapController?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.first, g.second), zoom))
        } else {
            val js = "if (window.PickMap) window.PickMap.setCenter($wgsLat, $wgsLng, ${zoom.toInt()});"
            if (pageReady) webView.evaluateJavascript(js, null)
            else pendingJs = js
        }
    }

    /* ---------- 结果返回：EXTRA_LAT / EXTRA_LNG（WGS-84），地名解析由调用方异步做 ---------- */

    override fun onResume() { super.onResume(); amapView?.onResume() }
    override fun onPause() { super.onPause(); amapView?.onPause() }

    override fun onDestroy() {
        super.onDestroy()
        // 先掐掉控制器：在途的地点查询回调会读 amapController，
        // 让它们第一时间走判空早退，而不是踩到正在销毁的地图对象
        amapController = null
        // ::webView.isInitialized 守卫：进程级的极早销毁（如后台被回收时 onCreate
        // 尚未跑到 setContentView）不能反过来把 Activity 的销毁流程也带崩
        if (!useNativeMap && ::webView.isInitialized) {
            // WebView 模式：销毁前先停加载并断 JS 桥，
            // 否则“确认选点”的桥回调仍可能踩到已经随 Activity 销毁的 WebView
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
        }
        val mv = amapView
        amapView = null
        if (mv != null) {
            // 关键：这里【绝不】调 mv.onDestroy()。刚活跃过的地图立刻销毁
            // 就是 SIGSEGV 的触发点（见类注释）。只暂停 + 离屏 + 寄存，
            // 销毁推迟到下一次进选点页时（onCreate 里先清寄存再建新图）。
            mv.onPause()
            (mv.parent as? ViewGroup)?.removeView(mv)
            parked = mv
        }
    }
}
