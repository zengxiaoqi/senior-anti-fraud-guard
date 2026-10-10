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
                finishWithResult(wgs.first, wgs.second)
            }
        }
    }

    /* ---------- 高德原生地图模式 ---------- */

    private fun confirmNative() {
        val target = amapController?.cameraPosition?.target ?: run {
            Toast.makeText(this, "地图尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val wgs = GeoConverter.gcj2wgs(target.latitude, target.longitude)
        finishWithResult(wgs.first, wgs.second)
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

    /* ---------- 结果返回 ---------- */

    private fun finishWithResult(wgsLat: Double, wgsLng: Double) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_LAT, wgsLat)
            putExtra(EXTRA_LNG, wgsLng)
        })
        finish()
        Toast.makeText(this, String.format("已选点：%.6f, %.6f", wgsLat, wgsLng), Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); amapView?.onResume() }
    override fun onPause() { super.onPause(); amapView?.onPause() }

    override fun onDestroy() {
        amapView?.onDestroy()
        amapView = null
        amapController = null
        super.onDestroy()
    }
}
