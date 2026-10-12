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
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.MapPickPending
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

    /** 家基准的逆地理地名（null = 还没解析到 / 解析失败，界面退回只显示坐标） */
    private var homeName: String? = null

    /** 上次已发起解析的坐标：同一坐标不重复请求，避免 onResume 反复烧逆地理配额 */
    private var lastQueriedLat: Double? = null
    private var lastQueriedLng: Double? = null

    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        // 主路径已拿到结果：清掉落盘存根，避免 onResume 兜底重复提交
        MapPickPending.consume(this)
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

    override fun onResume() {
        super.onResume()
        restorePendingPick()
    }

    /**
     * 兜底：家基准页丢了选点结果时（进程被地图页原生崩溃带走后重启），
     * 从落盘存根恢复并直接提交（本页所有动作本就是立即生效，无中间态）。
     * 正常路径下存根已在 pickLauncher 回调里 consume，不会重复提交。
     */
    private fun restorePendingPick() {
        val p = MapPickPending.peek(this) ?: return
        MapPickPending.consume(this)
        commitHome(p.lat, p.lng)
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
        paintHomeBaseText()
        if (set) resolveHomeName()
    }

    /**
     * 纯渲染：只在 homeLat/homeLng/homeName 变化后重画一次，
     * 与逆地理请求解耦 —— 否则回调里再触发请求会形成回环。
     */
    private fun paintHomeBaseText() {
        val lat = homeLat
        val lng = homeLng
        val set = lat != null && lng != null
        tvHomeBase.text = if (set) {
            // 坐标是一串小数，子女看不出“这是不是家”——补上逆地理地名才叫“看得出生效”。
            // 地名解析失败（弱网/服务端兜底坐标串）就只显示坐标，不打断。
            val name = homeName
            val head = if (name.isNullOrEmpty()) "已设置" else "已设置：$name"
            "$head（${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}）"
        } else {
            "⚠️ 尚未设置。老人端会按「首次定位到的位置」当家，离家判定可能失真。"
        }
        tvHomeBase.setTextColor(if (set) UiPrefs.textColor(this) else warnColor())
        // 没设家基准时"清除"是个必失败的按钮，不如直接置灰
        btnClearHome.isEnabled = set
        btnClearHome.alpha = if (set) 1f else 0.5f
    }

    /**
     * 逆地理解析家基准地名（服务端 /api/geofence/reverse，与敏感地点登记同源）。
     * 同一坐标只查一次：保存成功后 merged 回来坐标若没变，不重复烧配额。
     */
    private fun resolveHomeName() {
        val lat = homeLat ?: return
        val lng = homeLng ?: return
        if (lat == lastQueriedLat && lng == lastQueriedLng) return
        lastQueriedLat = lat
        lastQueriedLng = lng
        ApiClient.familyGet(
            "/api/geofence/reverse?lat=${String.format(java.util.Locale.US, "%.6f", lat)}" +
                "&lng=${String.format(java.util.Locale.US, "%.6f", lng)}",
            onSuccess = { res ->
                val name = res.optString("name", "").trim()
                homeName = if (name.isEmpty() || name.startsWith("坐标") || name.startsWith("GPS")) null else name
                paintHomeBaseText()
            },
            onError = { /* 静默：坐标已在展示，地名拿不到不影响判断是否生效 */ }
        )
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
