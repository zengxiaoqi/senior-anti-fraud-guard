package com.antifraud.guard.family

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject

/**
 * 敏感地点围栏管理（子女端 App 版）
 * 对齐小程序 pages/geofence：增删改查登记的可疑地点围栏，
 * 老人进入围栏后自动开启环境录音存证并推送高危告警。
 */
class GeofenceManageActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION = 200
        fun start(context: Context) {
            context.startActivity(Intent(context, GeofenceManageActivity::class.java))
        }
    }

    private lateinit var etName: EditText
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etRadius: EditText
    private lateinit var etDwell: EditText
    private lateinit var btnAdd: Button
    private lateinit var btnUseLocation: Button
    private lateinit var btnPickMap: Button
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var llList: LinearLayout

    /** 地图选点结果（WGS-84 坐标） */
    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            etLat.setText(String.format("%.6f", data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0)))
            etLng.setText(String.format("%.6f", data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0)))
            Toast.makeText(this, "已填入选点坐标", Toast.LENGTH_SHORT).show()
        }
    }

    /** 单次定位回调（"使用当前位置"） */
    private var singleShotListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_manage)
        title = "敏感地点守护"

        etName = findViewById(R.id.et_geofence_name)
        etLat = findViewById(R.id.et_geofence_lat)
        etLng = findViewById(R.id.et_geofence_lng)
        etRadius = findViewById(R.id.et_geofence_radius)
        etDwell = findViewById(R.id.et_geofence_dwell)
        btnAdd = findViewById(R.id.btn_geofence_add)
        btnUseLocation = findViewById(R.id.btn_use_location)
        btnPickMap = findViewById(R.id.btn_pick_map)
        tvCount = findViewById(R.id.tv_geofence_count)
        tvEmpty = findViewById(R.id.tv_geofence_empty)
        llList = findViewById(R.id.ll_geofence_list)

        btnAdd.setOnClickListener { onAddTap() }
        btnUseLocation.setOnClickListener { onUseLocationTap() }
        btnPickMap.setOnClickListener {
            pickLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadFences()
    }

    override fun onDestroy() {
        cancelSingleShot()
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

            tvDelete.setOnClickListener {
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
        val radius = etRadius.text.toString().trim().toIntOrNull() ?: 200
        // 1-8 停留告警阈值：空/0 = 只录音不额外告警；上限 720 分钟（12 小时）
        val dwell = etDwell.text.toString().trim().toIntOrNull() ?: 0
        if (dwell !in 0..720) {
            Toast.makeText(this, "停留告警阈值需在 0~720 分钟之间", Toast.LENGTH_SHORT).show()
            return
        }

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
                etLat.setText("")
                etLng.setText("")
                etRadius.setText("200")
                etDwell.setText("")
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
        fillFromLastKnownOrListen()
    }

    private fun fillFromLastKnownOrListen() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
            // 2 分钟内的缓存位置视为可用，否则监听一次实时定位
            if (last != null && System.currentTimeMillis() - last.time < 2 * 60 * 1000) {
                fillCoordinates(last)
                return
            }

            Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show()
            val listener = LocationListener { loc -> fillCoordinates(loc) }
            singleShotListener = listener
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener)
                } catch (_: Exception) {}
            }
            // 15 秒拿不到实时定位就取消，回退缓存位置
            handler.postDelayed({
                if (singleShotListener === listener) {
                    cancelSingleShot()
                    if (last != null) fillCoordinates(last)
                    else Toast.makeText(this, "定位失败，请到空旷处重试或手动输入坐标", Toast.LENGTH_SHORT).show()
                }
            }, 15_000)
        } catch (e: Exception) {
            Toast.makeText(this, "定位异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fillCoordinates(loc: Location) {
        cancelSingleShot()
        etLat.setText(String.format("%.6f", loc.latitude))
        etLng.setText(String.format("%.6f", loc.longitude))
        Toast.makeText(this, "已填入当前位置坐标", Toast.LENGTH_SHORT).show()
    }

    private fun cancelSingleShot() {
        singleShotListener?.let {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(it)
            } catch (_: Exception) {}
        }
        singleShotListener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fillFromLastKnownOrListen()
            } else {
                Toast.makeText(this, "位置权限被拒绝，请手动输入坐标", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
