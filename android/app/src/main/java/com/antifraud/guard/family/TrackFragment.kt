package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

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
import com.antifraud.guard.util.EventLabels
import androidx.core.content.ContextCompat
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

    /** 轨迹点（服务端返回的 WGS-84 坐标）；iso 保留原始时间戳供范围过滤 */
    private data class TrackPoint(val lat: Double, val lng: Double, val address: String, val time: String, val iso: String = "")

    /** 已登记的敏感地点围栏（服务端 /api/geofence/list 下发，含停用项） */
    private data class Fence(val id: Int, val name: String, val lat: Double, val lng: Double,
                            val radius: Double, val enabled: Boolean)

    private var lastFetch = 0L
    private var points = listOf<TrackPoint>()
    private var fences = listOf<Fence>()

    /** 时间范围：true=近 7 天（默认），false=今天。默认选周而不是天：
     *  白天早上或刚装机的用户"今天"几乎必为空表，会被当成功能坏了 */
    private var rangeWeek = true

    /** 地图半屏/全屏切换（大屏看轨迹细节） */
    private var mapExpanded = false

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

    /**
     * 是否启用高德原生 SDK 地图。
     *
     * 历史包袱（2026-10-10 线上实证）：replace() 切页导致 MapView"快速创建→销毁"，
     * 触发 SDK 内部 GL 渲染线程原生 SIGSEGV（小米/Android 15，应用层延迟销毁压不住）。
     * 已改由 FamilyHomeActivity 的 show/hide 保活切换根治——切走时地图只暂停不销毁，
     * 崩溃前提（销毁竞态）不复存在，故恢复原生模式保住体验。若未来再出现同类原生
     * 崩溃，先怀疑切页方式被改回 replace()，其次才禁用 SDK（改回 `= false`）。
     */
    private fun resolveUseNativeMap(): Boolean = BuildConfig.AMAP_KEY.isNotBlank()
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

        // 时间范围分段 + 地图全屏切换
        view.findViewById<TextView>(R.id.tv_range_today).setOnClickListener {
            rangeWeek = false; paintRangeChips(); refreshRenderings()
        }
        view.findViewById<TextView>(R.id.tv_range_week).setOnClickListener {
            rangeWeek = true; paintRangeChips(); refreshRenderings()
        }
        view.findViewById<TextView>(R.id.tv_map_toggle).setOnClickListener { toggleMapExpand() }
        paintRangeChips()

        useNativeMap = resolveUseNativeMap()

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
            webView.setBackgroundColor(UiPrefs.bgColor(requireContext()))
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
        // 先掐掉控制器再销毁视图：在途网络回调会读 amapController，
        // 让它们第一时间走判空早退，而不是踩到正在销毁的地图对象
        amapController = null
        amapMarkers = emptyList()
        fenceCircles = emptyList()
        if (!useNativeMap) {
            // WebView 销毁前先断 JS 桥并停加载，避免桥回调/渲染线程踩到已销毁的 WebView
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
        }
        val mv = amapView
        amapView = null
        if (mv != null) {
            mv.onPause()
            // 线上实证（2026-10-10）：进轨迹页马上切走，MapView"创建→立即销毁"时
            // 高德 SDK 内部渲染线程尚未收尾，立即 onDestroy 会触发原生 SIGSEGV——
            // 进程被系统直接杀掉，Java 崩溃处理器没机会执行（所以服务器收不到
            // 任何崩溃报告，这是定位本问题的关键线索）。延迟销毁给 SDK 收尾时间；
            // mv 是独立对象，500ms 内用户重新进页面也不受影响（那是新的 MapView）。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { mv.onDestroy() } catch (_: Throwable) { /* SDK 内部竞态，尽力而为 */ }
            }, 500)
        }
    }

    /** 分段 chip 画法：选中蓝底白字，未选中透明底灰字 */
    private fun paintRangeChips() {
        val view = view ?: return
        val today = view.findViewById<TextView>(R.id.tv_range_today)
        val week = view.findViewById<TextView>(R.id.tv_range_week)
        fun paint(tv: TextView, active: Boolean) {
            val d = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(if (active) 0xFF3B82F6.toInt() else Color.TRANSPARENT)
            }
            tv.background = d
            tv.setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        paint(today, !rangeWeek)
        paint(week, rangeWeek)
    }

    /** 地图 半屏 ↔ 全屏：改权重，列表让位。两种地图模式通用 */
    private fun toggleMapExpand() {
        val v = view ?: return
        mapExpanded = !mapExpanded
        val mapWeight = if (mapExpanded) 4f else 1f
        val mapV = v.findViewById<View>(if (useNativeMap) R.id.amap_view else R.id.track_map)
        (mapV.layoutParams as LinearLayout.LayoutParams).weight = mapWeight
        mapV.layoutParams = mapV.layoutParams
        v.findViewById<View>(R.id.track_list_scroll).visibility =
            if (mapExpanded) View.GONE else View.VISIBLE
        // ⤢/⤡ 字形在部分设备缺失（渲染成豆腐块），改用汉字按钮（见布局文件注释）
        v.findViewById<TextView>(R.id.tv_map_toggle).text = if (mapExpanded) "还原" else "全屏"
    }

    /** 当前时间范围下的轨迹点（今天 0 点 / 近 7 天） */
    private fun filteredPoints(): List<TrackPoint> {
        val now = System.currentTimeMillis()
        val todayStart = now - (now % 86_400_000L) - java.util.TimeZone.getDefault().rawOffset
        val start = if (rangeWeek) todayStart - 6 * 86_400_000L else todayStart
        return points.filter { p ->
            (EventLabels.parseIsoDate(p.iso)?.time ?: 0L) >= start
        }
    }

    /** 范围切换后重画列表与地图（数据已在内存，只做重渲染） */
    private fun refreshRenderings() {
        val list = filteredPoints()
        if (list.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (rangeWeek) "近 7 天暂无轨迹数据\n老人端开启位置守护后自动上报"
                else "今天暂无轨迹数据\n可切换「近 7 天」查看历史"
            listContainer.removeAllViews()
        } else {
            tvEmpty.visibility = View.GONE
            renderList()
        }
        if (useNativeMap) {
            amapView?.visibility = View.VISIBLE
            renderNativeMap()
        } else {
            webView.visibility = View.VISIBLE
            showOnMap(webMapJson())
        }
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

        // limit：服务端默认只给 10 条，而最近 10 条往往是同一地点的连续心跳，
        // 于是"近 7 天"在界面上永远只剩一个位置（线上实证：长沙那批点全被截断）。
        // 传 200 拿整个窗口，再在本地按地点聚合，地图/列表都不会被重复心跳淹没。
        ApiClient.familyGet("/api/events/location/${GuardConfig.boundElderId}?limit=200",
            onSuccess = { data ->
                // 快速切换底部菜单时本 Fragment 可能已被销毁（线上实证：进轨迹页
                // 马上点防诈告警会闪退）。回调直接丢弃，下次进入页面会重新拉取。
                if (!isAdded) return@familyGet
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
                            loc.optString("created_at", "").replace("T", " ").take(16),
                            loc.optString("created_at", "")
                        ))
                    }
                }
                points = parsed

                // 渲染统一走 refreshRenderings()：范围过滤 / 空态 / 地图 / 列表
                // 一处决策，避免"列表空了地图还有点"的口径分裂
                if (points.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = if (fences.isEmpty()) {
                        "暂无轨迹数据\n老人端开启位置守护后自动上报"
                    } else {
                        "暂无轨迹数据\n但已登记 ${fences.size} 个敏感地点围栏，见下方地图"
                    }
                    listContainer.removeAllViews()
                    if (useNativeMap) {
                        amapView?.visibility = View.VISIBLE
                        renderNativeMap()
                    } else {
                        webView.visibility = View.VISIBLE
                        showOnMap("[]")
                    }
                } else {
                    refreshRenderings()
                }
            },
            onError = {
                if (!isAdded) return@familyGet
                Toast.makeText(requireContext(), "获取位置信息失败：$it", Toast.LENGTH_SHORT).show()
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
                if (!isAdded) return@familyGet
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

    // Fragment 脱离后 resources 会抛 IllegalStateException（requireXxx 同族坑）；
    // 正常路径已被调用方的 amapController 判空守卫兜住，这里再防一手
    val res = context?.resources ?: return BitmapDescriptorFactory.defaultMarker(
        BitmapDescriptorFactory.HUE_RED)
    val density = res.displayMetrics.density
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
        val pts = filteredPoints()
        val groups = groupPoints(pts)

        // 连线：跨城市不能硬连。7 天窗口里老人可能换过城市（长沙→广州），
        // 两点相距几十公里却画一条直线，会让人读出老人"直线飞过去"的假轨迹，
        // 还会把视野拉到半个中国。超过 20km 就断开重起一段。
        polylineSegments(pts).forEach { seg ->
            if (seg.size >= 2) {
                map.addPolyline(PolylineOptions()
                    .addAll(seg.map { p -> val g = wgs2gcj(p.lat, p.lng); LatLng(g.first, g.second) })
                    .width(10f)
                    .color(Color.parseColor("#3B82F6")))
            }
        }

        // 打点按地点组而不是原始上报点：200 条心跳会给同一个位置叠上百个 marker
        amapMarkers = groups.mapIndexed { i, grp ->
                val rep = pts[grp.indices.first()]
                val g = wgs2gcj(grp.lat, grp.lng)
                val addrText = grp.name   // 与列表同一套地名口径
                val times = if (grp.indices.size > 1) "到访 ${grp.indices.size} 次" else rep.time
                map.addMarker(MarkerOptions()
                .position(LatLng(g.first, g.second))
                .title(if (i == 0) "最新位置" else grp.name)
                .snippet("$addrText\n$times")
                .icon(if (i == 0) latestLocationIcon() else BitmapDescriptorFactory.defaultMarker(
                    BitmapDescriptorFactory.HUE_AZURE))
                // 最新位置必须压在所有图层之上：老人可能就在围栏圈里，
                // zIndex 低会被围栏半透明填充盖住，"最新"就识别不出来了。
                .zIndex(if (i == 0) 100f else 10f)
                .anchor(0.5f, 1f))
        }
        amapView?.visibility = View.VISIBLE

        // 视野对齐：优先围栏（用户是去看围栏的），否则用去过的地方
        val focus: List<Pair<Double, Double>> = if (fenceLabels.isNotEmpty()) {
            fenceLabels.map { it.latitude to it.longitude }
        } else {
            groups.map { grp -> wgs2gcj(grp.lat, grp.lng) }
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

    /* ---------- 明细列表（同地点聚合 + 可设为围栏） ---------- */

    /** 展开中的地点（按组 key 记忆，切换时间范围/重渲染后保持） */
    private var expandedGroups = mutableSetOf<String>()

    /**
     * 同一个地点的多次到达合并成一行。
     * 7 天视角下老人每天在"家 / 同一个公园"来回，未聚合时列表会被同一个地点
     * 铺满几十行：既看不出"他常去哪"，也让"设为围栏"这类动作被重复稀释。
     */
    private data class TrackGroup(
        val key: String,
        val name: String,
        val isRealPlace: Boolean,
        val lat: Double,
        val lng: Double,
        val indices: MutableList<Int>          // 组内点在 filtered 列表中的下标，新→旧
    )

    companion object {
        /** 同一个地点判定半径：300m 内的两次到达算同一处（注册围栏半径默认 200m） */
        private const val MERGE_RADIUS_M = 300.0
        /** 跨城判定距离：超过这个值两点之间不连线，避免画出"直线飞过去"的假轨迹 */
        private const val POLYLINE_MAX_JUMP_M = 20_000.0
    }

    /** 地址是否算"真地名"：坐标为底的名称不能靠文本相等去合并/区分 */
    private fun placeKeyOf(p: TrackPoint): String {
        val a = p.address.trim()
        val isCoord = a.isEmpty() || a.startsWith("GPS 位置") || a.startsWith("坐标") || a == "未知位置"
        return if (isCoord) "" else a
    }

    /** 列表/气泡里展示的地点名：没有真地名时退到坐标，措辞用"已定位"而非"未识别" */
    private fun placeNameOf(p: TrackPoint): String {
        val k = placeKeyOf(p)
        return if (k.isEmpty()) "已定位（%.4f, %.4f）".format(p.lat, p.lng) else k
    }

    private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = (bLat - aLat) * 111_320.0
        val dLng = (bLng - aLng) * 111_320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }

    /** 当前时间范围聚合后的地点组（列表与地图共用同一份，避免口径漂移） */
    private fun currentGroups(): List<TrackGroup> = groupPoints(filteredPoints())

    /** 把新→旧的点列切成若干段：相邻两点距离过大时断开（换城市/信号漂移） */
    private fun polylineSegments(pts: List<TrackPoint>): List<List<TrackPoint>> {
        if (pts.size < 2) return emptyList()
        val segs = mutableListOf<MutableList<TrackPoint>>()
        var cur = mutableListOf(pts.first())
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val p = pts[i]
            if (distanceMeters(prev.lat, prev.lng, p.lat, p.lng) > POLYLINE_MAX_JUMP_M) {
                if (cur.size >= 2) segs.add(cur)
                cur = mutableListOf(p)
            } else {
                cur.add(p)
            }
        }
        if (cur.size >= 2) segs.add(cur)
        return segs
    }

    /**
     * 该地点是否已登记为敏感地点围栏（含"已停用"的那一类）。
     * 判定半径取 max(围栏半径, 聚合半径) 再放宽 100m：组和围栏都是按略近似的位置
     * 登记的，咬太死会出现"地图上明明有圈、列表却还显示可设置"的自相矛盾。
     */
    private fun fenceCovering(lat: Double, lng: Double): Fence? =
        fences.firstOrNull {
            distanceMeters(it.lat, it.lng, lat, lng) <= maxOf(it.radius, MERGE_RADIUS_M) + 100
        }

    private fun groupPoints(pts: List<TrackPoint>): List<TrackGroup> {
        val groups = mutableListOf<TrackGroup>()
        for ((i, p) in pts.withIndex()) {
            val key = placeKeyOf(p)
            val hit = groups.firstOrNull { g ->
                (g.isRealPlace && key.isNotEmpty() && g.name == key) ||
                    distanceMeters(g.lat, g.lng, p.lat, p.lng) < MERGE_RADIUS_M
            }
            if (hit != null) {
                hit.indices.add(i)
            } else {
                groups.add(TrackGroup(
                    // key 取"地名+首点坐标"，不含列表下标：切换时间范围重渲染后
                    // 同一个地点仍保持原来的展开状态
                    key = "${placeNameOf(p)}@${"%.4f".format(p.lat)},${"%.4f".format(p.lng)}",
                    name = placeNameOf(p),
                    isRealPlace = key.isNotEmpty(),
                    lat = p.lat, lng = p.lng,
                    indices = mutableListOf(i)))
            }
        }
        return groups
    }

    private fun renderList() {
        listContainer.removeAllViews()
        // 脱离状态下 layoutInflater/requireContext 都会抛；返回不渲染（下次进入重拉）
        if (!isAdded) return
        val pts = filteredPoints()
        val groups = groupPoints(pts)
        val header = TextView(requireContext()).apply {
            text = "常去地点（同一地点已合并）· 点击定位 · 可直接设为敏感地点"
            setTextColor(UiPrefs.dimColor(requireContext()))
            textSize = 12f
            setPadding(4, 0, 4, 10)
        }
        listContainer.addView(header)

        groups.forEachIndexed { gi, g ->
            val item = layoutInflater.inflate(R.layout.item_track_point, listContainer, false)
            val badge = item.findViewById<TextView>(R.id.tv_index)
            val isLatest = (gi == 0) // 服务端新→旧排序，第 0 组为最新停留点
            badge.text = if (isLatest) "最新" else "${gi + 1}"
            (badge.background.mutate() as GradientDrawable).setColor(
                if (isLatest) Color.parseColor("#EF4444") else Color.parseColor("#3B82F6"))

            val latest = pts[g.indices.first()]
            val latestTime = EventLabels.shortLocalTime(latest.iso).ifEmpty { latest.time }
            val count = g.indices.size
            val fence = fenceCovering(g.lat, g.lng)
            item.findViewById<TextView>(R.id.tv_addr).text = g.name
            item.findViewById<TextView>(R.id.tv_time).text =
                (if (count > 1) "最近 $latestTime · 到访 $count 次" else latestTime) +
                if (fence != null) " · 已设为敏感地点" else ""

            // 逐次到达明细：一次 enrolment 点一条，点击定位到那一次
            val toggle = item.findViewById<TextView>(R.id.tv_toggle)
            val details = item.findViewById<LinearLayout>(R.id.detail_container)
            if (count > 1) {
                val expanded = expandedGroups.contains(g.key)
                toggle.visibility = View.VISIBLE
                toggle.text = if (expanded) "收起明细" else "明细 $count 次"
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                details.removeAllViews()
                if (expanded) {
                    g.indices.forEach { idx ->
                        val t = EventLabels.shortLocalTime(pts[idx].iso).ifEmpty { pts[idx].time }
                        val row = TextView(requireContext()).apply {
                            text = "到访 $t"
                            setTextColor(UiPrefs.dimColor(requireContext()))
                            textSize = 12f
                            setPadding(6, 6, 6, 6)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = 8 * resources.displayMetrics.density
                                setColor(Color.parseColor("#14475869"))
                            }
                        }
                        row.setOnClickListener { focusVisit(idx) }
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                        lp.setMargins(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
                        details.addView(row, lp)
                    }
                }
                toggle.setOnClickListener {
                    if (expandedGroups.contains(g.key)) expandedGroups.remove(g.key)
                    else expandedGroups.add(g.key)
                    renderList()
                }
            } else {
                toggle.visibility = View.GONE
                details.visibility = View.GONE
            }

            // 设为/取消敏感地点围栏：聚合后每个地点只占一行，显性入口不再重复噪音。
            // 已经登记过的地点，同一个按钮变成"取消围栏"——子女一定会在列表里
            // 回头找"我刚才设在哪了"，需要一个当场能撤销的地方，而不是只能去
            // 控制台的围栏管理里翻。橙色 = 已生效的警示状态，不是"危险操作"红色。
            val fenceBtn = item.findViewById<TextView>(R.id.tv_fence)
            if (fence != null) {
                fenceBtn.text = "已设围栏 · 点击取消"
                fenceBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.c_accent_amber))
                fenceBtn.setOnClickListener { removeFence(fence) }
            } else {
                fenceBtn.text = "设为围栏"
                fenceBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.c_accent_blue))
                fenceBtn.setOnClickListener {
                    GeofenceDialogHelper.show(requireContext(), g.lat, g.lng,
                        defaultName = g.name.take(20)) {
                        Toast.makeText(context, "已登记为敏感地点，地图上以橙色圈标出", Toast.LENGTH_SHORT).show()
                        fetchFences()
                    }
                }
            }

            item.setOnClickListener { focusGroup(gi) }
            // 长按等价于点「设为围栏/取消围栏」（习惯保留）
            item.setOnLongClickListener {
                val existing = fenceCovering(g.lat, g.lng)
                if (existing != null) removeFence(existing)
                else GeofenceDialogHelper.show(requireContext(), g.lat, g.lng,
                    defaultName = g.name.take(20)) {
                    Toast.makeText(context, "已登记为敏感地点", Toast.LENGTH_SHORT).show()
                    fetchFences()
                }
                true
            }
            item.findViewById<TextView>(R.id.tv_focus).setOnClickListener { focusGroup(gi) }
            listContainer.addView(item)
        }
    }

    /**
     * 删除敏感地点围栏。
     * 必须二次确认：围栏是"老人一进某处就录音+告警"的守护配置，误删不会有任何
     * 提示性后果——子女只会发现某个地方突然不再提醒了。确认框里要明确说清后果。
     */
    private fun removeFence(fence: Fence) {
        val ctx = context ?: return
        android.app.AlertDialog.Builder(ctx)
            .setTitle("取消「${fence.name}」的敏感地点标记？")
            .setMessage("取消后，老人再进入这一带不会自动录音存证，也不会推送敏感地点告警。")
            .setNegativeButton("保留", null)
            .setPositiveButton("取消围栏") { _, _ ->
                ApiClient.familyPost("/api/geofence/delete",
                    JSONObject().put("id", fence.id),
                    onSuccess = {
                        if (!isAdded) return@familyPost
                        Toast.makeText(ctx, "已取消「${fence.name}」的敏感地点标记", Toast.LENGTH_SHORT).show()
                        fetchFences()
                    },
                    onError = { err ->
                        if (!isAdded) return@familyPost
                        Toast.makeText(ctx, "取消失败：$err", Toast.LENGTH_SHORT).show()
                    })
            }
            .show()
    }

    /**
     * 点某个地点（整行 / "定位"）→ 跳到该地点并弹出气泡。
     * 索引口径 = 聚合后的地点组：地图 marker 与 WebView 的 points 数组都是按组注入的。
     */
    private fun focusGroup(index: Int) {
        val grp = currentGroups().getOrNull(index) ?: return
        if (useNativeMap) {
            val map = amapController ?: return
            val g = wgs2gcj(grp.lat, grp.lng)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.first, g.second), 16f))
            amapMarkers.getOrNull(index)?.showInfoWindow()
        } else if (pageReady) {
            webView.evaluateJavascript("window.TrackMap.focusPoint($index)", null)
        }
        Toast.makeText(context, grp.name, Toast.LENGTH_SHORT).show()
    }

    /** 点某一次到访明细 → 定位到那一次的原始坐标（同地点多次到达位置略有差别） */
    private fun focusVisit(index: Int) {
        val p = filteredPoints().getOrNull(index) ?: return
        if (useNativeMap) {
            val map = amapController ?: return
            val g = wgs2gcj(p.lat, p.lng)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.first, g.second), 16f))
        } else if (pageReady) {
            webView.evaluateJavascript("window.TrackMap.focusLatLng(${p.lat}, ${p.lng})", null)
        }
        Toast.makeText(context, placeNameOf(p), Toast.LENGTH_SHORT).show()
    }

    /* ---------- 高德原生地图 ---------- */

    /** 轨迹刷新后重画：统一走 redrawFences()，它内部会 clear + 重画轨迹与围栏 */
    private fun renderNativeMap() {
        redrawFences()
    }

    /* ---------- WebView 瓦片地图 ---------- */

    /**
     * 注入给 WebView 地图的是**聚合后的地点组**，不是原始上报点：
     * 7 天窗口最多 200 条心跳，逐点注入既叠 marker 又拖慢渲染。
     * breakBefore 告诉网页"与上个点跨了城，别连线"。
     */
    private fun webMapJson(): String {
        val pts = filteredPoints()
        val groups = groupPoints(pts)
        val arr = JSONArray()
        groups.forEachIndexed { i, grp ->
            val rep = pts[grp.indices.first()]
            arr.put(JSONObject().apply {
                put("lat", grp.lat)
                put("lng", grp.lng)
                put("address", grp.name)
                put("time", rep.time)
                put("count", grp.indices.size)
                put("breakBefore", i > 0 && distanceMeters(
                    groups[i - 1].lat, groups[i - 1].lng, grp.lat, grp.lng) > POLYLINE_MAX_JUMP_M)
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
