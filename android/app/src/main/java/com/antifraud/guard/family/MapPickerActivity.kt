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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Toast
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
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
            setupSearch()
        } else {
            setupWebView()
        }

        // 初始视野：优先老人最新上报位置（可疑地点大概率在老人活动轨迹附近）
        moveToElderLatestLocation()
    }

    /* ---------- 地点搜索（仅原生地图模式；WebView 回退模式不启用） ---------- */

    private lateinit var etSearch: EditText
    private lateinit var lvResults: ListView
    private var currentTips: List<Tip> = emptyList()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    /**
     * 刚点了建议项：在途的提示回调与 setText 触发的新一轮搜索都不得再把
     * 列表弹开（线上实证 v1.7.40：点完建议项地图已定位，列表却又展开把
     * 地图挡住——setText 触发 TextWatcher → 400ms 后 requestTips → 回调
     * 重新 VISIBLE，以及点击前发起的晚到回调，两条路都会"复活"列表）。
     * 用户再次输入时清零，恢复正常建议。
     */
    private var justPicked = false
    /** setText 引起的这一次 afterTextChanged 跳过防抖（避免误留标志影响下次输入） */
    private var suppressNextSearch = false

    /**
     * 搜索框 + 关键字提示（Inputtips）。
     *
     * 之前选点只能手动拖地图：用户想围"长沙市中心医院"，得先缩放到湖南再逐级放大，
     * 老人子女大多做不到。走高德 Inputtips 边输边提示，点一条直接把相机定位过去。
     *
     * 注意三点：
     *  1. 搜索 SDK 有**独立于地图 SDK** 的隐私合规开关（ServiceSettings），
     *     不先调用则所有请求静默失败——地图 SDK 调过不算数。
     *  2. InputtipsListener 只有 onGetInputtips 一个回调，失败也走它（rCode != 0），
     *     不存在 onError——按旧文档写 onError 会直接编译不过。
     *  3. Tip.getPoint() 经常为 null（只有部分提示带坐标），带 poiID 的条目要用
     *     PoiSearch.searchPOIId 精确换坐标，不能拿名字再搜一遍（同名地点会跳错）。
     */
    @SuppressLint("SetTextI18n")
    private fun setupSearch() {
        etSearch = findViewById(R.id.et_map_search)
        lvResults = findViewById(R.id.lv_search_results)
        etSearch.visibility = View.VISIBLE

        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val kw = s?.toString()?.trim().orEmpty()
                if (kw.isEmpty()) {
                    justPicked = false
                    currentTips = emptyList()
                    lvResults.visibility = View.GONE
                    return
                }
                // setText 引起的回调：跳过本轮，不算用户输入
                if (suppressNextSearch) {
                    suppressNextSearch = false
                    return
                }
                justPicked = false   // 用户又在打字：恢复正常建议流程
                // 400ms 防抖：每敲一个字都打一次 HTTP 既费配额又闪烁
                searchRunnable = Runnable { requestTips(kw) }
                searchHandler.postDelayed(searchRunnable!!, 400)
            }
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                requestTips(etSearch.text.toString().trim())
                true
            } else false
        }
        lvResults.setOnItemClickListener { _, _, pos, _ ->
            currentTips.getOrNull(pos)?.let { onTipSelected(it) }
        }
    }

    private fun requestTips(keyword: String) {
        if (keyword.isEmpty() || isFinishing || isDestroyed) return
        val q = InputtipsQuery(keyword, "")
        q.cityLimit = false   // 围栏可能登记在任意城市，不做城市限定
        val inputtips = Inputtips(this, q)
        inputtips.setInputtipsListener { tips, rCode ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 用户刚点完建议项：晚到的回调不弹列表，否则又把地图挡住
                if (justPicked) return@runOnUiThread
                if (rCode != AMapException.CODE_AMAP_SUCCESS) {
                    Toast.makeText(this, "地点搜索失败（错误码 $rCode）", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                currentTips = tips.orEmpty().filter { !it.name.isNullOrBlank() }
                if (currentTips.isEmpty()) {
                    lvResults.visibility = View.GONE
                    return@runOnUiThread
                }
                val items = currentTips.map { t ->
                    val district = t.district.orEmpty()
                    if (district.isEmpty()) t.name else "$district · ${t.name}"
                }
                lvResults.adapter = ArrayAdapter(
                    this, android.R.layout.simple_list_item_1, items
                ).apply { setDropDownViewResource(android.R.layout.simple_list_item_1) }
                lvResults.visibility = View.VISIBLE
            }
        }
        inputtips.requestInputtipsAsyn()
    }

    private fun onTipSelected(tip: Tip) {
        justPicked = true
        lvResults.visibility = View.GONE
        hideKeyboard()
        val newName = tip.name ?: ""
        // 只有文本真的会变才 setText：内容相同时 TextView 不触发 TextWatcher，
        // 此时置 suppressNextSearch 反而会误吞用户的下一次真实输入
        if (etSearch.text.toString() != newName) {
            suppressNextSearch = true
            etSearch.setText(newName)
            etSearch.setSelection(newName.length)
        }
        val p = tip.point
        if (p != null) {
            moveToGcj(p.latitude, p.longitude, 16f, label = tip.name ?: "选中地点")
        } else if (!tip.poiID.isNullOrEmpty()) {
            // 提示词不带坐标时按 poiID 精确反查：按名字重搜会命中同名地点
            resolveTipPoi(tip.poiID)
        } else {
            Toast.makeText(this, "该地点没有坐标，请换个关键词", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveTipPoi(poiId: String) {
        try {
            val search = PoiSearch(this, PoiSearch.Query("", "", ""))
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(res: PoiResult?, code: Int) {}
                override fun onPoiItemSearched(item: PoiItem?, code: Int) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val pt = item?.latLonPoint
                        if (pt != null) {
                            moveToGcj(pt.latitude, pt.longitude, 16f, label = item?.title ?: "选中地点")
                        } else {
                            Toast.makeText(this@MapPickerActivity, "未取到该地点坐标（$code）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            search.searchPOIIdAsyn(poiId)
        } catch (e: Exception) {
            Toast.makeText(this, "地点查询失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 相机直接定位到 GCJ-02 坐标（搜索结果是火星坐标，无需纠偏），并放行确认按钮 */
    private fun moveToGcj(gcjLat: Double, gcjLng: Double, zoom: Float, label: String) {
        amapController?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcjLat, gcjLng), zoom))
        cameraMoved = true
        Toast.makeText(this, "已定位：$label", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
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
        // 掐掉待执行的搜索防抖：Handler 持有 Activity 引用，销毁后不再回调
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
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
