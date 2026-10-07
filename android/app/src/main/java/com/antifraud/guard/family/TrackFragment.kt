package com.antifraud.guard.family

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.antifraud.guard.BuildConfig
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 亲情轨迹：老人端上报位置轨迹可视化（真实地图）
 *
 * 双模式：
 * 1) local.properties 配置了 AMAP_KEY → 高德原生 SDK（key 经 BuildConfig 注入 manifest meta-data）
 * 2) 未配置 key → 回退内置 WebView 瓦片地图 assets/track_map.html（免 key，高德瓦片 + GCJ-02 纠偏）
 *
 * 轨迹明细列表可点击：点击任意一条，地图定位（CameraUpdate / JS focusPoint）到对应轨迹点。
 * 支持把轨迹点/地图长按位置一键登记为敏感地点围栏（老人进入后自动录音上报）。
 */
class TrackFragment : Fragment() {

    /** 轨迹点（服务端返回的 WGS-84 坐标） */
    private data class TrackPoint(val lat: Double, val lng: Double, val address: String, val time: String)

    /** 已登记的敏感地点围栏（服务端 /api/geofence/list 下发，含停用项） */
    private data class Fence(val id: Int, val name: String, val lat: Double, val lng: Double,
                            val radius: Double, val enabled: Boolean)

    private var lastFetch = 0L
    private var points = listOf<TrackPoint>()
    private var fences = listOf<Fence>()

    // WebView 瓦片地图模式
    private var pageReady = false
    private var pendingPointsJson: String? = null
    private lateinit var webView: WebView

    /** JS 桥：网页地图长按回调（GCJ-02 坐标，反纠偏后设围栏） */
    private inner class MapBridge {
        @android.webkit.JavascriptInterface
        fun onMapLongPress(gcjLat: Double, gcjLng: Double) {
            val wgs = GeoConverter.gcj2wgs(gcjLat, gcjLng)
            requireActivity().runOnUiThread {
                GeofenceDialogHelper.show(requireContext(), wgs.first, wgs.second)
            }
        }
    }

    // 高德原生 SDK 模式
    private var useNativeMap = false
    private var amapView: MapView? = null
    private var amapController: AMap? = null
    private var amapMarkers = listOf<Marker>()
    private var fenceCircles = listOf<Circle>()
    /** 围栏中心坐标（用于把视野对齐到围栏；中心不再放 marker，避免盖住"最新位置"） */
    private var fenceLabels = mutableListOf<LatLng>()
    /** "最新位置"图标缓存：BitmapDescriptor 重复创建会耗内存，且 SDK 不保证可安全重建 */
    private var latestIconCache: BitmapDescriptor? = null

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_track, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listContainer = view.findViewById(R.id.track_list_container)
        tvEmpty = view.findViewById(R.id.tv_track_empty)
        webView = view.findViewById(R.id.track_map)

        useNativeMap = BuildConfig.AMAP_KEY.isNotBlank()

        if (useNativeMap) {
            // 高德 SDK 9.x 起需先完成隐私合规接口，否则地图初始化不生效
            MapsInitializer.updatePrivacyShow(requireContext(), true, true)
            MapsInitializer.updatePrivacyAgree(requireContext(), true)

            val mv = view.findViewById<MapView>(R.id.amap_view)
            mv.visibility = View.VISIBLE
            webView.visibility = View.GONE
            mv.onCreate(null)
            amapView = mv
            amapController = mv.map.apply {
                uiSettings.isZoomControlsEnabled = true
                uiSettings.isRotateGesturesEnabled = false
                // 长按地图任意位置 → 登记为敏感地点围栏
                setOnMapLongClickListener { latLng ->
                    val wgs = GeoConverter.gcj2wgs(latLng.latitude, latLng.longitude)
                    GeofenceDialogHelper.show(requireContext(), wgs.first, wgs.second)
                }
            }
        } else {
            // WebView 模式：加载本地瓦片地图页
            webView.visibility = View.VISIBLE
            view.findViewById<View>(R.id.amap_view).visibility = View.GONE
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.setBackgroundColor(0xFFE2E8F0.toInt())
            webView.addJavascriptInterface(MapBridge(), "AndroidBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    pageReady = true
                    pendingPointsJson?.let {
                        webView.evaluateJavascript("window.TrackMap.setPoints($it)", null)
                        pendingPointsJson = null
                    }
                    // 页面就绪时若围栏已先于页面拉到，必须补发一次，
                    // 否则会出现"轨迹有、围栏没有"的时序竞态
                    if (fences.isNotEmpty()) {
                        webView.evaluateJavascript("window.TrackMap.setFences(${fencesToJson()})", null)
                    }
                }
            }
            webView.loadUrl("file:///android_asset/track_map.html")
        }
    }

    override fun onResume() {
        super.onResume()
        amapView?.onResume()
        fetchLocations()
    }

    override fun onPause() {
        super.onPause()
        amapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        amapView?.onDestroy()
        amapView = null
        amapController = null
    }

    /** 30s 节流（对齐小程序） */
    private fun fetchLocations() {
        if (!GuardConfig.isFamilyBound) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "尚未绑定老人\n请先到「控制台」完成亲情绑定"
            webView.visibility = View.GONE
            amapView?.visibility = View.GONE
            listContainer.removeAllViews()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30_000) return
        lastFetch = now

        // 围栏与轨迹是两份独立数据，各自请求、互不阻塞。
        // 围栏要在地图上标出来，否则用户在轨迹页看不出"哪些地方是敏感地带"。
        fetchFences()

        ApiClient.familyGet("/api/events/location/${GuardConfig.boundElderId}",
            onSuccess = { data ->
                val arr = data.optJSONArray("data")
                val parsed = mutableListOf<TrackPoint>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val loc = arr.optJSONObject(i) ?: continue
                        val lat = loc.optDouble("latitude", 0.0)
                        val lng = loc.optDouble("longitude", 0.0)
                        if (lat == 0.0 || lng == 0.0) continue
                        parsed.add(TrackPoint(
                            lat, lng,
                            loc.optString("address", ""),
                            loc.optString("created_at", "").replace("T", " ").take(16)
                        ))
                    }
                }
                points = parsed

                if (points.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = if (fences.isEmpty()) {
                        "暂无轨迹数据\n老人端开启位置守护后自动上报"
                    } else {
                        "暂无轨迹数据\n但已登记 ${fences.size} 个敏感地点围栏，见下方地图"
                    }
                    // 注意：即使没有轨迹点，只要登记了围栏就必须显示地图，
                    // 否则"只有围栏没有轨迹"的用户在界面上完全看不到自己配的地点。
                    if (useNativeMap) {
                        amapView?.visibility = View.VISIBLE
                        renderNativeMap()
                    } else {
                        webView.visibility = View.VISIBLE
                        showOnMap(pointsToJson(points))
                    }
                    listContainer.removeAllViews()
                } else {
                    tvEmpty.visibility = View.GONE
                    if (useNativeMap) {
                        amapView?.visibility = View.VISIBLE
                        renderNativeMap()
                    } else {
                        webView.visibility = View.VISIBLE
                        showOnMap(pointsToJson(points))
                    }
                    renderList()
                }
            },
            onError = {
                Toast.makeText(context, "获取位置信息失败：$it", Toast.LENGTH_SHORT).show()
            })
    }

    /**
     * 拉取已登记的敏感地点围栏。
     * 老人端只拉enabled=1（/geofence/elder），这里要展示"全部含停用"，
     * 否则用户在子女端会以为停用的围栏消失了。
     */
    private fun fetchFences() {
        if (!GuardConfig.isFamilyBound) return
        ApiClient.familyGet("/api/geofence/list/${GuardConfig.boundElderId}",
            onSuccess = { res ->
                val arr = res.optJSONArray("data")
                val parsed = mutableListOf<Fence>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        parsed.add(Fence(
                            id = f.optInt("id"),
                            name = f.optString("name", "未命名地点"),
                            lat = f.optDouble("latitude", 0.0),
                            lng = f.optDouble("longitude", 0.0),
                            radius = f.optDouble("radius", 200.0),
                            enabled = f.optInt("enabled", 1) == 1
                        ))
                    }
                }
                fences = parsed
                redrawFences()
                renderFenceSummary()
            },
            onError = { })
    }

    /**
 * 地图上已有的围栏图形，重画前先清掉。
 *
 * 注意：高德 3dmap 9.8.2 的 AMap **没有 remove(Overlay) 方法**——
 * Marker 继承了 BasePointOverlay.remove()，但 Circle 继承的 BaseOverlay 没有，
 * 编译器直接报"Unresolved reference / receiver type mismatch"。
 * 所以统一走 map.clear() 后整体重绘，简单可靠且不会漏删。
 */
private fun clearFenceOverlay() {
        if (useNativeMap) {
            amapController?.clear()
            amapMarkers = emptyList()
        } else if (pageReady) {
            webView.evaluateJavascript("window.TrackMap.setFences([])", null)
        }
        fenceCircles = emptyList()
        fenceLabels = mutableListOf()
    }

    /**
     * 重画整个地图图层（轨迹 + 围栏）。
     * 高德原生模式用 Circle 画半径圈（中心不放手势标记）；WebView 模式交给网页地图画。
     */
    private fun redrawFences() {
        clearFenceOverlay()
        if (useNativeMap) {
            val map = amapController ?: return
            val circles = mutableListOf<Circle>()
            fenceLabels = mutableListOf()
            for (f in fences) {
                if (f.lat == 0.0 || f.lng == 0.0) continue
                val g = wgs2gcj(f.lat, f.lng)
                val center = LatLng(g.first, g.second)
                // 停用围栏用灰色半透明，一眼能看出"登记了但当前不生效"
                circles.add(map.addCircle(CircleOptions()
                    .center(center)
                    .radius(f.radius)
                    .strokeColor(Color.parseColor(if (f.enabled) "#F59E0B" else "#94A3B8"))
                    .fillColor(Color.parseColor(if (f.enabled) "#22F59E0B" else "#2294A3B8"))
                    .strokeWidth(3f)))
                // 围栏中心**不放 marker**：围栏往往就是按老人当时的位置登记的，
                // 中心和"最新位置"完全重合，橙色 marker 叠在红色上面就把"最新"
                // 这个最重要的视觉锚点盖掉了（用户反馈"没法区分"）。
                // 围栏靠橙色圈 + 顶部说明文字表达，不与人形标记抢位置。
                fenceLabels.add(center)
            }
            fenceCircles = circles
            renderNativeMapOverlays()
        } else if (pageReady) {
            webView.evaluateJavascript("window.TrackMap.setFences(${fencesToJson()})", null)
        }
    }

    /**
     * 绘制轨迹覆盖物（polyline + marker）并把视野对齐到"轨迹点 + 围栏"。
     * clear() 之后必须整体重画，否则围栏会被一起抹掉（用户会以为围栏失效了）。
     */
    /**
 * "最新位置"专用图标。
 *
 * 不能直接用 defaultMarker(HUE_RED)：那是个很小的图钉，叠在围栏半透明圆里
 * 几乎认不出。用户需要一眼就看到"红色 = 最新位置"，所以这里手绘一个
 * 大号红点 + 白边 + 光晕 + 顶部「最新」标签，缩放到任何层级都醒目。
 */
private fun latestLocationIcon(): BitmapDescriptor {
    latestIconCache?.let { return it }

    val density = resources.displayMetrics.density
    val w = (40 * density).toInt()
    val h = (52 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val r = 11 * density
    val cy = h - r - 4 * density

    // 外层光晕（半透明红），先画大的再画实心点
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55EF4444")
    }
    c.drawCircle(cx, cy, r * 1.75f, halo)

    // 白色描边圈：把点从围栏的橙色填充里"托"出来
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    c.drawCircle(cx, cy, r + 3 * density, white)

    // 实心红点
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF4444") }
    c.drawCircle(cx, cy, r, red)

    // 「最新」标签
    val label = "最新"
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        textSize = 10 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val tw = tp.measureText(label)
    val padH = 5 * density
    val padV = 3 * density
    val lx = cx - tw / 2 - padH
    val ly = cy - r - 4 * density - tp.textSize - padV * 2
    c.drawRoundRect(
        lx, ly, lx + tw + padH * 2, ly + tp.textSize + padV * 2,
        8 * density, 8 * density, red)
    c.drawText(label, cx, ly + padV + tp.textSize * 0.82f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    })

    val desc = BitmapDescriptorFactory.fromBitmap(bmp)
    latestIconCache = desc
    return desc
}

private fun renderNativeMapOverlays() {
        val map = amapController ?: return
        val gcj = points.map { wgs2gcj(it.lat, it.lng) }

        if (gcj.size >= 2) {
            map.addPolyline(PolylineOptions()
                .addAll(gcj.map { LatLng(it.first, it.second) })
                .width(10f)
                .color(Color.parseColor("#3B82F6")))
        }

        amapMarkers = gcj.mapIndexed { i, g ->
            val a = points[i].address.trim()
            val addrText = if (a.isEmpty() || a.startsWith("GPS 位置") || a.startsWith("坐标") || a == "未知位置") {
                "%.4f, %.4f".format(points[i].lat, points[i].lng)
            } else {
                a
            }
            map.addMarker(MarkerOptions()
                .position(LatLng(g.first, g.second))
                .title(if (i == 0) "最新位置" else "轨迹点 ${i + 1}")
                .snippet(addrText + if (points[i].time.isNotEmpty()) "\n${points[i].time}" else "")
                .icon(if (i == 0) latestLocationIcon() else BitmapDescriptorFactory.defaultMarker(
                    BitmapDescriptorFactory.HUE_AZURE))
                // 最新位置必须压在所有图层之上：老人可能就在围栏圈里，
                // zIndex 低会被围栏半透明填充盖住，"最新"就识别不出来了。
                .zIndex(if (i == 0) 100f else 10f)
                .anchor(0.5f, 1f))
        }
        amapView?.visibility = View.VISIBLE

        // 视野对齐：优先围栏（用户是去看围栏的），否则用轨迹点
        val focus: List<Pair<Double, Double>> = if (fenceLabels.isNotEmpty()) {
            fenceLabels.map { it.latitude to it.longitude }
        } else {
            gcj
        }
        when {
            focus.size == 1 -> map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(focus[0].first, focus[0].second), 16f))
            focus.size > 1 -> {
                val b = LatLngBounds.Builder()
                focus.forEach { b.include(LatLng(it.first, it.second)) }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
            }
        }
    }

    /** 轨迹列表顶部说明当前围栏数量与生效情况 */
    private fun renderFenceSummary() {
        val view = view ?: return
        val tv = view.findViewById<TextView>(R.id.tv_track_fence_summary) ?: return
        if (fences.isEmpty()) {
            tv.visibility = View.GONE
            tv.text = ""
            return
        }
        val on = fences.count { it.enabled }
        tv.visibility = View.VISIBLE
        tv.text = buildString {
            append("🛡️ 地图上橙色圈为敏感地点围栏，共 ${fences.size} 个")
            append(if (on == fences.size) "（全部生效中）" else "（$on 个生效，${fences.size - on} 个已停用）")
            append("。老人进入圈内自动开启环境录音存证。")
        }
    }

    private fun fencesToJson(): String {
        val arr = JSONArray()
        fences.forEach { f ->
            arr.put(JSONObject().apply {
                put("lat", f.lat)
                put("lng", f.lng)
                put("name", f.name)
                put("radius", f.radius)
                put("enabled", f.enabled)
            })
        }
        return arr.toString()
    }

    /* ---------- 明细列表（可点击定位） ---------- */

    private fun renderList() {
        listContainer.removeAllViews()
        val header = TextView(requireContext()).apply {
            text = "轨迹明细（新→旧）· 点击条目在地图上定位"
            setTextColor(Color.parseColor("#475569"))
            textSize = 12f
            setPadding(4, 0, 4, 10)
        }
        listContainer.addView(header)

        points.forEachIndexed { i, p ->
            val item = layoutInflater.inflate(R.layout.item_track_point, listContainer, false)
            val badge = item.findViewById<TextView>(R.id.tv_index)
            val isLatest = (i == 0) // 服务端新→旧排序，第 0 条为最新位置
            badge.text = if (isLatest) "最新" else "${i + 1}"
            (badge.background.mutate() as GradientDrawable).setColor(
                if (isLatest) Color.parseColor("#EF4444") else Color.parseColor("#3B82F6"))

// 服务端多层兜底后，addr 通常已是"XX附近 / 常去地点①"这类可读描述。
      // 只有实在没信息时才退到坐标，且措辞用"已定位"而非"未识别"——
      // 后者听起来像功能坏了，实际只是这附近没名字可查。
      val addr = p.address.trim()
      val isCoordOnly = addr.isEmpty() ||
              addr.startsWith("GPS 位置") || addr.startsWith("坐标") ||
              addr == "未知位置"
      item.findViewById<TextView>(R.id.tv_addr).text = if (isCoordOnly) {
        "已定位（%.4f, %.4f）".format(p.lat, p.lng)
      } else {
        addr
      }
            item.findViewById<TextView>(R.id.tv_time).text = p.time
            item.setOnClickListener { focusOnPoint(i) }
// 一键把该轨迹点登记为敏感地点围栏（轨迹点本身就是 WGS-84，直接入库）
      item.findViewById<TextView>(R.id.tv_set_fence).setOnClickListener {
        val shown = item.findViewById<TextView>(R.id.tv_addr).text.toString()
        // 去掉"累计 N 次上报"这类统计后缀，围栏名要短且是人话
        val defaultName = shown.substringBefore("（").substringBefore(" ·").trim().take(20)
        GeofenceDialogHelper.show(requireContext(), p.lat, p.lng, defaultName = defaultName)
      }
            listContainer.addView(item)
        }
    }

    /** 点击明细条目 → 地图定位到该轨迹点 */
    private fun focusOnPoint(index: Int) {
        val p = points.getOrNull(index) ?: return
        if (useNativeMap) {
            val map = amapController ?: return
            val g = wgs2gcj(p.lat, p.lng)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.first, g.second), 16f))
            amapMarkers.getOrNull(index)?.showInfoWindow()
        } else {
            if (pageReady) {
                webView.evaluateJavascript("window.TrackMap.focusPoint($index)", null)
            }
        }
        Toast.makeText(context, p.address.ifEmpty { "已定位到轨迹点 ${index + 1}" }, Toast.LENGTH_SHORT).show()
    }

    /* ---------- 高德原生地图 ---------- */

    /** 轨迹刷新后重画：统一走 redrawFences()，它内部会 clear + 重画轨迹与围栏 */
    private fun renderNativeMap() {
        redrawFences()
    }

    /* ---------- WebView 瓦片地图 ---------- */

    private fun pointsToJson(list: List<TrackPoint>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("address", p.address)
                put("time", p.time)
            })
        }
        return arr.toString()
    }

    /** 注入轨迹点到 WebView 地图；页面未就绪时先缓存，onPageFinished 后补发 */
    private fun showOnMap(json: String) {
        if (pageReady) {
            webView.evaluateJavascript("window.TrackMap.setPoints($json)", null)
        } else {
            pendingPointsJson = json
        }
    }

    /* ---------- WGS-84 -> GCJ-02（火星坐标）纠偏，供原生地图使用 ---------- */

    private fun wgs2gcj(wgsLat: Double, wgsLng: Double): Pair<Double, Double> =
        GeoConverter.wgs2gcj(wgsLat, wgsLng)
}
