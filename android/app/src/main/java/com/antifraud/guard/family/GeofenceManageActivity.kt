package com.antifraud.guard.family

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.antifraud.guard.BaseActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.R
import com.antifraud.guard.BuildConfig
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.MapPickPending
import com.antifraud.guard.util.OneShotLocation
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 敏感地点围栏管理（子女端 App 版）
 * 对齐小程序 pages/geofence：增删改查登记的可疑地点围栏，
 * 老人进入围栏后自动开启环境录音存证并推送高危告警。
 */
class GeofenceManageActivity : BaseActivity() {

    companion object {
        private const val REQ_LOCATION = 200
        fun start(context: Context) {
            context.startActivity(Intent(context, GeofenceManageActivity::class.java))
        }
    }

    private lateinit var etName: EditText
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var btnAdd: Button
    private lateinit var btnUseLocation: Button
    private lateinit var btnPickMap: Button
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var llList: LinearLayout
    private lateinit var tvCoordPick: TextView
    private lateinit var llManualCoord: LinearLayout
    private lateinit var tvToggleManual: TextView

    /** 半径与停留告警的档位选中值（chips 交互，撤掉了裸数字输入框） */
    private var radiusMeters = 200
    private var dwellMinutes = 0

    /**
     * 名称框当前是否为自动回填。
     * 换一个选点应当覆盖上一次自动填的名字（用户没打过字）；
     * 但用户手打的名称绝不能被静默覆盖 —— 这是自动填充的基本礼貌。
     */
    private var nameAutoFilled = false

    private val radiusOptions = linkedMapOf(
        R.id.chip_r100 to 100,
        R.id.chip_r200 to 200,
        R.id.chip_r500 to 500,
        R.id.chip_r1000 to 1000
    )
    private val dwellOptions = linkedMapOf(
        R.id.chip_d0 to 0,
        R.id.chip_d15 to 15,
        R.id.chip_d30 to 30,
        R.id.chip_d60 to 60
    )

    /** 地图选点结果（WGS-84 坐标 + 可选的 App 内反查地名） */
    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            // 主路径已拿到结果：把落盘存根清掉，避免 onResume 兜底重复恢复
            MapPickPending.consume(this)
            applyPick(
                data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
                data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0)
            )
        }
    }

    /**
     * 选点结果填表（ActivityResult 主路径与 onResume 兜底恢复共用）。
     * 顺带把默认折叠的手动坐标区展开：坐标是这次操作唯一的“凭据”，
     * 藏在折叠区里用户看不见，就会以为没生效。
     */
    private fun applyPick(lat: Double, lng: Double) {
        etLat.setText(String.format("%.6f", lat))
        etLng.setText(String.format("%.6f", lng))
        llManualCoord.visibility = View.VISIBLE
        tvToggleManual.text = "手动输入坐标（收起） ▾"
        renderPickedCoord()
        // 地名解析统一走 reverseNameSdkThenServer：App 内 SDK 反查优先，
        // 拿不到再走服务端 /api/geofence/reverse 兜底
        reverseNameSdkThenServer(lat, lng)
    }

    /** 自动回填地名：名称为空或上次也是自动填的才覆盖，用户手打的不动 */
    private fun fillNameAuto(name: String) {
        val cur = etName.text.toString().trim()
        if (cur.isEmpty() || nameAutoFilled) {
            etName.setText(name)
            nameAutoFilled = true
        }
    }

    /** 必须持有引用，否则 GeocodeSearch 回调前可能被 GC */
    private var searchRef: com.amap.api.services.geocoder.GeocodeSearch? = null

    /**
     * 拿到坐标后解析地名（统一入口：地图选点 / 使用当前位置 都走这里）。
     *
     * 两级策略：
     *  1) App 内高德 GeocodeSearch（走 Android key，真实 POI 名如"XX大药房"）。
     *     不设人工超时——本页面一直活着，等回调就好；选点页设短超时会在 SDK
     *     冷启动（首次联网取配置）时丢回调，表现为"确认选点永远拿不到名字"。
     *  2) SDK 失败/没给名字 → 服务端 /api/geofence/reverse 兜底（已配 AMAP_WEB_KEY）。
     */
    private fun reverseNameSdkThenServer(wgsLat: Double, wgsLng: Double) {
        if (BuildConfig.AMAP_KEY.isBlank()) {
            // 没配 Android key（WebView 模式）：直接服务端兜底
            resolvePlaceName()
            return
        }
        try {
            // 搜索 SDK 的隐私合规开关与地图 SDK（MapsInitializer）相互独立，
            // 未同意时逆地理请求被 SDK 静默拒绝（不回调或 code!=1000）
            try {
                com.amap.api.services.core.ServiceSettings.updatePrivacyShow(this, true, true)
                com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(this, true)
            } catch (ignored: Throwable) {
            }

            val search = com.amap.api.services.geocoder.GeocodeSearch(this)
            search.setOnGeocodeSearchListener(object :
                com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(
                    result: com.amap.api.services.geocoder.RegeocodeResult?,
                    code: Int
                ) {
                    var name: String? = null
                    if (code == 1000 && result?.regeocodeAddress != null) {
                        // 优先 POI 名（"XX大药房"），退而取格式化地址（"…路…号"）
                        name = result.regeocodeAddress.pois?.firstOrNull()?.title?.takeIf { it.isNotBlank() }
                            ?: result.regeocodeAddress.formatAddress?.takeIf { it.isNotBlank() }
                    }
                    val clean = name?.trim().orEmpty()
                    if (clean.isNotEmpty() && !looksLikeCoordString(clean)) {
                        runOnUiThread { fillNameAuto(clean) }
                    } else {
                        resolvePlaceName() // SDK 没给名字（key/网络问题）：服务端兜底
                    }
                }

                override fun onGeocodeSearched(
                    result: com.amap.api.services.geocoder.GeocodeResult?,
                    code: Int
                ) { /* 只用逆地理 */ }
            })
            searchRef = search
            // RegeocodeQuery 只吃 GCJ-02：表单/库里是 WGS-84，先纠偏再查询
            val g = GeoConverter.wgs2gcj(wgsLat, wgsLng)
            val query = com.amap.api.services.geocoder.RegeocodeQuery(
                com.amap.api.services.core.LatLonPoint(g.first, g.second), 50f,
                com.amap.api.services.geocoder.GeocodeSearch.AMAP
            )
            query.extensions = "all" // 要 POI 列表
            search.getFromLocationAsyn(query)
        } catch (e: Exception) {
            // SDK 初始化失败（key 无效/so 缺失等）：服务端兜底
            resolvePlaceName()
        }
    }

    private fun looksLikeCoordString(s: String): Boolean =
        s.startsWith("坐标") || s.startsWith("GPS")

    /**
     * 选点/定位后请求服务端逆地理编码，把地名预填进名称框。
     * 复用与围栏落库同一个 regeo 服务（/api/geofence/reverse），预填名与最终落库名同源。
     * 失败静默：名称框留空由用户手填，解析失败不值得打断选点流程。
     */
    private fun resolvePlaceName() {
        val lat = etLat.text.toString().trim().toDoubleOrNull() ?: return
        val lng = etLng.text.toString().trim().toDoubleOrNull() ?: return
        ApiClient.familyGet(
            "/api/geofence/reverse?lat=${String.format(java.util.Locale.US, "%.6f", lat)}" +
                "&lng=${String.format(java.util.Locale.US, "%.6f", lng)}",
            onSuccess = { res ->
                val name = res.optString("name", "").trim()
                // 坐标兜底串没有预填价值（用户看到"坐标 28.2, 113.0"只会困惑）
                if (name.isEmpty() || name.startsWith("坐标") || name.startsWith("GPS")) return@familyGet
                fillNameAuto(name)
            },
            onError = { /* 静默：表单仍可手填 */ }
        )
    }

    /** 单次定位（"使用当前位置"） */
    private var oneShot: OneShotLocation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_manage)
        title = "敏感地点守护"

        etName = findViewById(R.id.et_geofence_name)
        etLat = findViewById(R.id.et_geofence_lat)
        etLng = findViewById(R.id.et_geofence_lng)
        btnAdd = findViewById(R.id.btn_geofence_add)
        btnUseLocation = findViewById(R.id.btn_use_location)
        btnPickMap = findViewById(R.id.btn_pick_map)
        tvCount = findViewById(R.id.tv_geofence_count)
        tvEmpty = findViewById(R.id.tv_geofence_empty)
        llList = findViewById(R.id.ll_geofence_list)
        tvCoordPick = findViewById(R.id.tv_coord_pick)
        llManualCoord = findViewById(R.id.ll_manual_coord)
        tvToggleManual = findViewById(R.id.tv_toggle_manual)

        btnAdd.setOnClickListener { onAddTap() }
        btnUseLocation.setOnClickListener { onUseLocationTap() }
        btnPickMap.setOnClickListener {
            pickLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        // 手动坐标折叠：主路径是地图选点，手填经纬度只留给自建服务器/无地图场景
        tvToggleManual.setOnClickListener {
            val show = llManualCoord.visibility != View.VISIBLE
            llManualCoord.visibility = if (show) View.VISIBLE else View.GONE
            tvToggleManual.text = if (show) "手动输入坐标（收起） ▾" else "手动输入坐标（自建服务器 / 无地图时） ▸"
        }

        // 半径/停留档位 chips：点选即改值，选中项蓝底白字
        radiusOptions.forEach { (id, v) ->
            findViewById<TextView>(id).setOnClickListener { radiusMeters = v; paintOptionChips() }
        }
        dwellOptions.forEach { (id, v) ->
            findViewById<TextView>(id).setOnClickListener { dwellMinutes = v; paintOptionChips() }
        }
        paintOptionChips()
    }

    private fun paintOptionChips() {
        fun paint(tv: TextView, active: Boolean) {
            val d = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(if (active) 0xFF3B82F6.toInt() else UiPrefs.cardColor(this@GeofenceManageActivity))
            }
            tv.background = d
            tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else UiPrefs.dimColor(this@GeofenceManageActivity))
        }
        radiusOptions.forEach { (id, v) -> paint(findViewById(id), v == radiusMeters) }
        dwellOptions.forEach { (id, v) -> paint(findViewById(id), v == dwellMinutes) }
    }

    /** 选点/定位结果回显：让用户看得见「位置已经选好」 */
    private fun renderPickedCoord() {
        val lat = etLat.text.toString().trim().toDoubleOrNull()
        val lng = etLng.text.toString().trim().toDoubleOrNull()
        if (lat == null || lng == null) {
            tvCoordPick.text = "尚未选点：点上方「地图选点」在地图上标记可疑地点"
            return
        }
        tvCoordPick.text = "✅ 已选位置：$lat, $lng\n下一步：填「地点名称」→ 点底部「登记围栏」即生效"
    }

    override fun onResume() {
        super.onResume()
        loadFences()
        restorePendingPick()
    }

    /**
     * 兜底：登记页丢了选点结果时（进程被地图页的原生崩溃带走后重启，
     * 或登记页在后台被系统回收后重建），从落盘存根恢复表单——
     * 保证“确定选点”之后永远能走到登记，不存在死路。
     * 正常路径下存根已在 pickLauncher 回调里 consume，这里不会触发。
     */
    private fun restorePendingPick() {
        if (etLat.text.isNotBlank() && etLng.text.isNotBlank()) return
        val p = MapPickPending.peek(this) ?: return
        MapPickPending.consume(this)
        applyPick(p.lat, p.lng)
        Toast.makeText(this, "已恢复上次选的点，填好名称后点「登记围栏」", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        oneShot?.cancel()
        oneShot = null
        super.onDestroy()
    }

    // ──────────────────────────────────────────
    //  列表加载与渲染
    // ──────────────────────────────────────────

    private fun loadFences() {
        if (!GuardConfig.isFamilyBound) {
            tvCount.text = "请先在控制台绑定老人"
            tvEmpty.text = "绑定守护对象后即可登记敏感地点"
            tvEmpty.visibility = TextView.VISIBLE
            llList.removeAllViews()
            return
        }
        ApiClient.familyGet("/api/geofence/list/${GuardConfig.boundElderId}",
            onSuccess = { res ->
                val arr = res.optJSONArray("data")
                renderList(arr)
            },
            onError = { err ->
                Toast.makeText(this, "加载围栏失败：$err", Toast.LENGTH_SHORT).show()
            })
    }

    private fun renderList(arr: org.json.JSONArray?) {
        llList.removeAllViews()
        val list = arr ?: org.json.JSONArray()
        tvCount.text = "📋 已登记地点 (${list.length()})"
        tvEmpty.visibility = if (list.length() == 0) TextView.VISIBLE else TextView.GONE

        val inflater = LayoutInflater.from(this)
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val view = inflater.inflate(R.layout.item_geofence, llList, false)
            val tvName = view.findViewById<TextView>(R.id.tv_item_name)
            val tvMeta = view.findViewById<TextView>(R.id.tv_item_meta)
            val swEnabled = view.findViewById<Switch>(R.id.sw_item_enabled)
            val tvDelete = view.findViewById<TextView>(R.id.tv_item_delete)

            val id = item.optInt("id")
            val enabled = item.optInt("enabled", 1) == 1
            val dwellMin = item.optInt("dwell_minutes", 0)
            tvName.text = item.optString("name", "未命名地点")
            tvMeta.text = "半径 ${item.optInt("radius", 200)} 米 · " +
                    "${String.format("%.4f", item.optDouble("latitude", 0.0))}, " +
                    "${String.format("%.4f", item.optDouble("longitude", 0.0))}" +
                    (if (dwellMin > 0) " · 停留≥${dwellMin} 分钟告警" else "") +
                    if (enabled) "" else " · 已停用"
            swEnabled.isChecked = enabled

            swEnabled.setOnCheckedChangeListener { _, checked ->
                ApiClient.familyPost("/api/geofence/update",
                    JSONObject().put("id", id).put("enabled", checked),
                    onSuccess = { loadFences() },
                    onError = { err ->
                        Toast.makeText(this, "更新失败：$err", Toast.LENGTH_SHORT).show()
                        loadFences()
                    })
            }

            tvDelete.setOnClickListener { anchor ->
                // ⋮ 溢出菜单：删除从"一眼可见的红字"降级为两步操作，
                // 误触成本 ≈ 0，破坏性二次确认仍保留
                val popup = android.widget.PopupMenu(this, anchor)
                popup.menu.add("删除此地点")
                popup.setOnMenuItemClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删除敏感地点")
                        .setMessage("删除后老人进入该地点将不再自动录音上报，确认删除？")
                        .setPositiveButton("删除") { _, _ ->
                            ApiClient.familyPost("/api/geofence/delete",
                                JSONObject().put("id", id),
                                onSuccess = { loadFences() },
                                onError = { err ->
                                    Toast.makeText(this, "删除失败：$err", Toast.LENGTH_SHORT).show()
                                })
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                popup.show()
            }

            llList.addView(view)
        }
    }

    // ──────────────────────────────────────────
    //  新增围栏
    // ──────────────────────────────────────────

    private fun onAddTap() {
        val name = etName.text.toString().trim()
        val lat = etLat.text.toString().trim().toDoubleOrNull()
        val lng = etLng.text.toString().trim().toDoubleOrNull()
        val radius = radiusMeters
        // 停留告警阈值：0 = 只录音不额外告警（档位 chips 给的是 0/15/30/60）
        val dwell = dwellMinutes

        if (!GuardConfig.isFamilyBound) {
            Toast.makeText(this, "请先在控制台绑定老人", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isEmpty()) {
            Toast.makeText(this, "请填写地点名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (lat == null || lat < -90 || lat > 90 || lng == null || lng < -180 || lng > 180) {
            Toast.makeText(this, "经纬度无效，可点「使用当前位置」自动填写", Toast.LENGTH_SHORT).show()
            return
        }

        btnAdd.isEnabled = false
        ApiClient.familyPost("/api/geofence/add",
            JSONObject()
                .put("name", name)
                .put("latitude", lat)
                .put("longitude", lng)
                .put("radius", radius)
                .put("dwellMinutes", dwell),
            onSuccess = {
                btnAdd.isEnabled = true
                etName.setText("")
                nameAutoFilled = false
                etLat.setText("")
                etLng.setText("")
                radiusMeters = 200
                dwellMinutes = 0
                paintOptionChips()
                renderPickedCoord()
                Toast.makeText(this, "✅ 敏感地点已登记", Toast.LENGTH_SHORT).show()
                loadFences()
            },
            onError = { err ->
                btnAdd.isEnabled = true
                Toast.makeText(this, "登记失败：$err", Toast.LENGTH_SHORT).show()
            })
    }

    // ──────────────────────────────────────────
    //  使用当前位置填写坐标
    // ──────────────────────────────────────────

    private fun onUseLocationTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
            return
        }
        fetchCurrentLocation()
    }

    /** 一次性定位：逻辑与超时策略集中在 [OneShotLocation]，与守护设置页共用 */
    private fun fetchCurrentLocation() {
        oneShot?.cancel()
        Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show()
        oneShot = OneShotLocation(
            context = this,
            onLocated = { loc ->
                etLat.setText(String.format("%.6f", loc.latitude))
                etLng.setText(String.format("%.6f", loc.longitude))
                renderPickedCoord()
                resolvePlaceName()
            },
            onFailed = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        ).also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                Toast.makeText(this, "位置权限被拒绝，请手动输入坐标", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
