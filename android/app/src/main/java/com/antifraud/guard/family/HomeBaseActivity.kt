package com.antifraud.guard.family

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.R
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.OneShotLocation
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 子女端「守护设置 › 家基准位置」。
 *
 * ## 为什么这一页不需要保存按钮
 * 原页面里「清除家基准」只能标记待清除、必须回到大页面点保存才生效，
 * 那是妥协产物：当时它和 8 个输入框共用保存按钮，立即提交会连带把别处
 * 还没想好的改动一起提交掉。现在这一页只有一件事，那个顾虑不存在了 ——
 * 而"点完还要记得去别处保存"恰恰是最容易让人以为改过了、其实没改的交互。
 *
 * 因此本页**不使用基类的 dirty 拦截**：每个动作都是即时生效的，没有中间态。
 *
 * ## 坐标是唯一不能 clamp 的字段
 * 把纬度 999 收敛成 90 会静默把家基准设到北极，界面看不出任何异常
 * （见 `GuardSettingBounds.validLatitude`）。所以非法坐标直接拒绝并说明原因。
 */
class HomeBaseActivity : GuardRuleSubActivity() {

    private companion object {
        const val REQ_LOCATION = 300
    }

    private lateinit var tvHomeBase: TextView
    private lateinit var btnClearHome: Button

    private var oneShot: OneShotLocation? = null

    /** 服务端当前家基准；null = 未设置 */
    private var homeLat: Double? = null
    private var homeLng: Double? = null

    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        commitHome(
            data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
            data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_base)

        findViewById<TextView>(R.id.tv_title).text = "家基准位置"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        tvHomeBase = findViewById(R.id.tv_home_base)
        btnClearHome = findViewById(R.id.btn_clear_home)

        findViewById<Button>(R.id.btn_pick_home_map).setOnClickListener {
            pickLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_pick_home_location).setOnClickListener { onUseLocationTap() }
        btnClearHome.setOnClickListener { onClearHomeTap() }

        reload()
    }

    override fun onDestroy() {
        oneShot?.cancel()
        oneShot = null
        super.onDestroy()
    }

    override fun onSettings(s: JSONObject?) {
        val lat = if (s != null && s.has("homeLat") && !s.isNull("homeLat")) s.optDouble("homeLat") else null
        val lng = if (s != null && s.has("homeLng") && !s.isNull("homeLng")) s.optDouble("homeLng") else null
        homeLat = GuardSettingBounds.validLatitude(lat)
        homeLng = GuardSettingBounds.validLongitude(lng)
        renderHomeBase()
    }

    private fun renderHomeBase() {
        val lat = homeLat
        val lng = homeLng
        val set = lat != null && lng != null
        tvHomeBase.text = if (set) {
            "已设置：${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}"
        } else {
            "⚠️ 尚未设置。老人端会按「首次定位到的位置」当家，离家判定可能失真。"
        }
        tvHomeBase.setTextColor(if (set) UiPrefs.textColor(this) else warnColor())
        // 没设家基准时"清除"是个必失败的按钮，不如直接置灰
        btnClearHome.isEnabled = set
        btnClearHome.alpha = if (set) 1f else 0.5f
    }

    // ──────────────────────────────────────────
    //  设置家基准
    // ──────────────────────────────────────────

    private fun commitHome(lat: Double, lng: Double) {
        val vLat = GuardSettingBounds.validLatitude(lat)
        val vLng = GuardSettingBounds.validLongitude(lng)
        if (vLat == null || vLng == null) {
            toast("坐标无效（纬度须 -90~90 且不为 0，经度须 -180~180 且不为 0）")
            return
        }
        homeLat = vLat
        homeLng = vLng
        save(JSONObject().apply {
            put("homeLat", vLat)
            put("homeLng", vLng)
        })
    }

    private fun onUseLocationTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            return
        }
        fetchCurrentLocation()
    }

    private fun fetchCurrentLocation() {
        oneShot?.cancel()
        toast("正在获取当前位置...")
        oneShot = OneShotLocation(
            context = this,
            onLocated = { loc -> commitHome(loc.latitude, loc.longitude) },
            onFailed = { msg -> toast(msg) }
        ).also { it.start() }
    }

    /**
     * 清除家基准。
     *
     * 高影响操作，必须二次确认（`docs/ui-guidelines.md` 第 6 节）：
     * 改家基准会直接改变「离家 / 陌生地点停留」告警的判定，
     * 误触的代价是整套位置告警静默失真，而界面上不会有任何提示。
     */
    private fun onClearHomeTap() {
        if (homeLat == null && homeLng == null) {
            toast("当前本就未设置家基准")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除家基准")
            .setMessage(
                "清除后老人端会退回「首次定位到的位置」作为家。" +
                    "若老人是在外面时首次打开 App，之后的离家判定会全部失真。\n\n" +
                    "这会立即生效。"
            )
            .setPositiveButton("确认清除") { _, _ ->
                homeLat = null
                homeLng = null
                save(JSONObject(), clearHome = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                toast("位置权限被拒绝，可用「地图选点」代替")
            }
        }
    }
}
