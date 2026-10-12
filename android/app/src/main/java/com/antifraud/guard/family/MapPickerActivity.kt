package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
    }

    private var useNativeMap = false
    private var amapView: MapView? = null
    private var amapController: AMap? = null
    private var pageReady = false

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
            val mv = findViewById<MapView>(R.id.pick_amap_view)
            mv.visibility = View.VISIBLE
            mv.onCreate(null)
            amapView = mv
            amapController = mv.map.apply {
                uiSettings.isZoomControlsEnabled = true
                uiSettings.isRotateGesturesEnabled = false
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
        val wgs = GeoConverter.gcj2wgs(target.latitude, target.longitude)
        finishPick(wgs.first, wgs.second)
    }

    /** 统一出口：返回 WGS-84 坐标，地名由 GeofenceManageActivity 异步解析 */
    private fun finishPick(wgsLat: Double, wgsLng: Double) {
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
            // 否则"确认选点"的桥回调仍可能踩到已经随 Activity 销毁的 WebView
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
        }
        val mv = amapView
        amapView = null
        if (mv != null) {
            mv.onPause()
            // 高德 MapView 的必修姿势（TrackFragment 2026-10-10 线上实证，本页同坑）：
            // 选点页生命周期极短 —— 通常是"进来随便拖两下就点确认"，
            // 此时 SDK 内部渲染线程刚起来还没收尾，同步 onDestroy 会触发
            // 原生 SIGSEGV：进程被系统直接杀掉，Java 崩溃处理器根本没机会跑，
            // 服务器上一条崩溃记录都收不到（这正是本问题此前查不到堆栈的原因）。
            // 延迟销毁给 SDK 留足收尾时间；mv 已是独立对象，
            // 这 500ms 里用户重进页面用的是另一个新 MapView，互不影响。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { mv.onDestroy() } catch (_: Throwable) { /* SDK 内部竞态，尽力而为 */ }
            }, 500)
        }
    }
}
