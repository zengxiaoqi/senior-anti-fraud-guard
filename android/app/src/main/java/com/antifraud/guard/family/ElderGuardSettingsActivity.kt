package com.antifraud.guard.family
import com.antifraud.guard.util.UiPrefs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.antifraud.guard.BaseActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.OneShotLocation
import org.json.JSONObject

/**
 * 子女端守护设置：家基准 + 全部守护规则阈值的唯一入口。
 *
 * ## 为什么是唯一入口
 * 老人端设置页原先有 4 个阈值输入框，但**没有任何一处能设置家基准与
 * 位置阈值** —— `homeAwayRadiusMeters` / `stayMoveMeters` / `homeStayMinutes`
 * 在整个 Android UI 层零出现，只是被 `currentSettingsPayload()` 把代码默认值
 * 推到了云端。看着像"已接好同步只差 UI"，实际是"没人能改"。
 *
 * 现在规则全部收到这里，老人端改只读。这样：
 *   - 单一写入方，不会出现「子女改了、老人端页面显示旧值、实际已被覆盖」
 *   - 老人不用理解这些参数（他们本来也不会配置）
 *   - 子女才是真正知道"通话超过几分钟该提醒"的人
 *
 * ## 保存失败文案的方向
 * 与老人端 `pushSettingsThenFinish` 相反：老人端失败是"本机已生效但没同步"，
 * 这里是"云端没改成，老人端仍在按旧参数守护"。
 * 云端本来就是事实源，保存失败时它从未变过，不存在"会被覆盖回去"。
 */
class ElderGuardSettingsActivity : BaseActivity() {

    private companion object {
        const val REQ_LOCATION = 300
    }

    private lateinit var tvHomeBase: TextView
    private lateinit var etAwayRadius: EditText
    private lateinit var etStayMove: EditText
    private lateinit var etStayMinutes: EditText
    private lateinit var etCallThreshold: EditText
    private lateinit var etPaymentThreshold: EditText
    private lateinit var swAutoUpload: Switch
    private lateinit var etRecSegments: EditText
    private lateinit var etRecSegmentMinutes: EditText
    private lateinit var tvRecTotalHint: TextView
    private lateinit var btnSave: Button

    private var oneShot: OneShotLocation? = null
    private var saving = false

    /** 待写入云端的家基准；null 表示"未设置"，保存时下发 homeCleared */
    private var homeLat: Double? = null
    private var homeLng: Double? = null
    /** 用户点过「清除家基准」但还没保存 */
    private var homeClearedPending = false

    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        setHome(
            data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
            data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0),
            "已选点为家基准，保存后生效"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_guard_settings)
        title = "守护设置"

        tvHomeBase            = findViewById(R.id.tv_home_base)
        etAwayRadius          = findViewById(R.id.et_away_radius)
        etStayMove            = findViewById(R.id.et_stay_move)
        etStayMinutes         = findViewById(R.id.et_stay_minutes)
        etCallThreshold       = findViewById(R.id.et_call_threshold)
        etPaymentThreshold    = findViewById(R.id.et_payment_threshold)
        swAutoUpload          = findViewById(R.id.sw_auto_upload)
        etRecSegments         = findViewById(R.id.et_rec_segments)
        etRecSegmentMinutes   = findViewById(R.id.et_rec_segment_minutes)
        tvRecTotalHint        = findViewById(R.id.tv_rec_total_hint)
        btnSave               = findViewById(R.id.btn_save_guard_settings)

        findViewById<Button>(R.id.btn_pick_home_map).setOnClickListener {
            pickLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_pick_home_location).setOnClickListener { onUseLocationTap() }
        findViewById<Button>(R.id.btn_clear_home).setOnClickListener { onClearHomeTap() }
        btnSave.setOnClickListener { onSaveTap() }

        val recWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { renderRecHint() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etRecSegments.addTextChangedListener(recWatcher)
        etRecSegmentMinutes.addTextChangedListener(recWatcher)

        loadSettings()
    }

    override fun onDestroy() {
        oneShot?.cancel()
        oneShot = null
        super.onDestroy()
    }

    // ──────────────────────────────────────────
    //  读取
    // ──────────────────────────────────────────

    private fun loadSettings() {
        if (!GuardConfig.isFamilyBound) {
            tvHomeBase.text = "尚未绑定守护对象，绑定后即可设置守护参数"
            btnSave.isEnabled = false
            return
        }
        tvHomeBase.text = "正在读取…"
        ApiClient.fetchFamilyElderSettings(
            onSuccess = { s -> renderSettings(s) },
            onError = { err ->
                tvHomeBase.text = "读取失败：$err"
                Toast.makeText(this, "读取守护参数失败：$err", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 用服务端返回的原始配置填表。
     *
     * 刻意不用默认值填表：服务端没配过的字段显示空，用户看得见
     * "这项还没设过"，而不是看到一个看起来很正常的 500 然后以为那就是实际值。
     * 保存时所有空值按 [GuardSettingBounds] 的默认值补齐。
     */
    private fun renderSettings(s: JSONObject?) {
        if (s == null) {
            tvHomeBase.text = "尚未设置家基准"
            fillDefaults()
            return
        }

        val hasLat = s.has("homeLat") && !s.isNull("homeLat")
        val hasLng = s.has("homeLng") && !s.isNull("homeLng")
        if (hasLat && hasLng) {
            homeLat = s.optDouble("homeLat")
            homeLng = s.optDouble("homeLng")
            homeClearedPending = false
            renderHomeBase()
        } else {
            homeLat = null
            homeLng = null
            tvHomeBase.text = "尚未设置家基准"
        }

        etAwayRadius.setText(s.optInt("homeAwayRadiusMeters", -1).takeIf { it > 0 }?.toString() ?: "")
        etStayMove.setText(s.optInt("stayMoveMeters", -1).takeIf { it > 0 }?.toString() ?: "")
        etStayMinutes.setText(s.optInt("homeStayMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        etCallThreshold.setText(s.optInt("callThresholdMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        etPaymentThreshold.setText(s.optDouble("paymentThreshold", -1.0).takeIf { it > 0 }?.toString() ?: "")
        etRecSegments.setText(s.optInt("recordingMaxSegments", -1).takeIf { it > 0 }?.toString() ?: "")
        etRecSegmentMinutes.setText(s.optInt("recordingSegmentMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        swAutoUpload.isChecked = s.optBoolean("recordingAutoUpload", true)

        renderRecHint()
        btnSave.isEnabled = true
    }

    private fun fillDefaults() {
        etAwayRadius.setText(GuardSettingBounds.AWAY_RADIUS_DEFAULT.toString())
        etStayMove.setText(GuardSettingBounds.STAY_MOVE_DEFAULT.toString())
        etStayMinutes.setText(GuardSettingBounds.STAY_MINUTES_DEFAULT.toString())
        etCallThreshold.setText(GuardSettingBounds.CALL_MINUTES_DEFAULT.toString())
        etPaymentThreshold.setText("500")
        etRecSegments.setText(GuardSettingBounds.REC_SEGMENTS_DEFAULT.toString())
        etRecSegmentMinutes.setText(GuardSettingBounds.REC_SEGMENT_MINUTES_DEFAULT.toString())
        swAutoUpload.isChecked = true
        renderRecHint()
        btnSave.isEnabled = true
    }

    private fun renderHomeBase() {
        tvHomeBase.text = "当前基准：${String.format("%.6f", homeLat)}, ${String.format("%.6f", homeLng)}" +
                (if (homeClearedPending) "\n⚠️ 已标记清除，点下方「保存守护设置」后才生效" else "")
    }

    /**
     * 实时换算录音总时长。
     * 照搬老人端设置页的既有做法（`SettingsActivity.updateRecHint`）：
     * 填错当场就能看出，比保存后被静默 clamp 再让用户困惑要好。
     */
    private fun renderRecHint() {
        val seg = etRecSegments.text.toString().trim().toIntOrNull() ?: 0
        val per = etRecSegmentMinutes.text.toString().trim().toIntOrNull() ?: 0
        if (seg == 0 || per == 0) {
            tvRecTotalHint.text = "请填写：段数 1~6，每段 1~10 分钟"
            tvRecTotalHint.setTextColor(UiPrefs.dimColor(this))
            return
        }
        val cs = GuardSettingBounds.recordingSegments(seg)
        val cp = GuardSettingBounds.recordingSegmentMinutes(per)
        val clamped = cs != seg || cp != per
        tvRecTotalHint.text = if (clamped) {
            "超出范围：将按 $cs 段 × $cp 分钟 = ${cs * cp} 分钟保存"
        } else {
            "当前：$seg 段 × $per 分钟，约 ${seg * per} 分钟。弱网下单段越短越不容易上传失败。"
        }
        tvRecTotalHint.setTextColor(
            if (!clamped && per < 8) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
        )
    }

    // ──────────────────────────────────────────
    //  家基准操作
    // ──────────────────────────────────────────

    private fun setHome(lat: Double, lng: Double, msg: String) {
        val vLat = GuardSettingBounds.validLatitude(lat)
        val vLng = GuardSettingBounds.validLongitude(lng)
        if (vLat == null || vLng == null) {
            // 不能像其他数值那样 clamp 到边界：把纬度 999 收敛成 90
            // 会静默把家基准设到北极，界面上看不出任何异常
            Toast.makeText(this, "坐标无效（纬度须 -90~90 且不为 0，经度须 -180~180 且不为 0）",
                Toast.LENGTH_LONG).show()
            return
        }
        homeLat = vLat
        homeLng = vLng
        homeClearedPending = false
        renderHomeBase()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

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

    private fun fetchCurrentLocation() {
        oneShot?.cancel()
        Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show()
        oneShot = OneShotLocation(
            context = this,
            onLocated = { loc -> setHome(loc.latitude, loc.longitude, "已用当前位置，保存后生效") },
            onFailed = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        ).also { it.start() }
    }

    /**
     * 清除家基准。
     *
     * 刻意做成"标记待清除"而不是立即提交：用户在输入框里可能还有没保存的
     * 改动，立即提交会连带把那些一起提交掉，而界面上完全看不出发生了什么。
     */
    private fun onClearHomeTap() {
        if (homeLat == null && !homeClearedPending) {
            Toast.makeText(this, "当前本就未设置家基准", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除家基准")
            .setMessage("清除后老人端会退回「首次定位到的位置」作为家。若老人是在外面时首次打开 App，离家判定会失真。\n\n点「保存守护设置」后生效。")
            .setPositiveButton("标记清除") { _, _ ->
                homeLat = null
                homeLng = null
                homeClearedPending = true
                renderHomeBase()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    private fun onSaveTap() {
        if (saving) {
            Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        val away = etAwayRadius.text.toString().trim().toIntOrNull()
        val stayMove = etStayMove.text.toString().trim().toIntOrNull()
        val stayMin = etStayMinutes.text.toString().trim().toIntOrNull()
        val callMin = etCallThreshold.text.toString().trim().toIntOrNull()
        val payAmt = etPaymentThreshold.text.toString().trim().toDoubleOrNull()
        val seg = etRecSegments.text.toString().trim().toIntOrNull()
        val perMin = etRecSegmentMinutes.text.toString().trim().toIntOrNull()

        if (away == null || stayMove == null || stayMin == null || callMin == null ||
            payAmt == null || seg == null || perMin == null) {
            Toast.makeText(this, "有输入框为空或格式不正确，请检查", Toast.LENGTH_SHORT).show()
            return
        }
        if (payAmt < 1) {
            Toast.makeText(this, "支付预警金额须大于 0", Toast.LENGTH_SHORT).show()
            return
        }

        // 收敛提示：静默 clamp 会让人以为设了 9 段、实际生效 6 段，
        // 然后以为系统有 bug。明确告知会收敛到哪个值。
        val clamped = listOf(
            "离家半径" to (away to GuardSettingBounds.awayRadius(away)),
            "停留判定半径" to (stayMove to GuardSettingBounds.stayMoveMeters(stayMove)),
            "停留时长" to (stayMin to GuardSettingBounds.stayMinutes(stayMin)),
            "通话时长" to (callMin to GuardSettingBounds.callMinutes(callMin)),
            "录音段数" to (seg to GuardSettingBounds.recordingSegments(seg)),
            "单段时长" to (perMin to GuardSettingBounds.recordingSegmentMinutes(perMin))
        ).filter { it.second.first != it.second.second }

        val body = JSONObject().apply {
            put("homeAwayRadiusMeters", GuardSettingBounds.awayRadius(away))
            put("stayMoveMeters", GuardSettingBounds.stayMoveMeters(stayMove))
            put("homeStayMinutes", GuardSettingBounds.stayMinutes(stayMin))
            put("callThresholdMinutes", GuardSettingBounds.callMinutes(callMin))
            put("paymentThreshold", payAmt)
            put("recordingAutoUpload", swAutoUpload.isChecked)
            put("recordingMaxSegments", GuardSettingBounds.recordingSegments(seg))
            put("recordingSegmentMinutes", GuardSettingBounds.recordingSegmentMinutes(perMin))
        }

        val clearHome = homeClearedPending
        if (!clearHome && homeLat != null && homeLng != null) {
            body.put("homeLat", homeLat)
            body.put("homeLng", homeLng)
        }

        saving = true
        btnSave.isEnabled = false

        ApiClient.pushFamilyElderSettings(
            settings = body,
            clearHome = clearHome,
            onSuccess = { merged ->
                saving = false
                btnSave.isEnabled = true
                homeClearedPending = false
                // 服务端 merged 是最终权威：用它回填，而不是用本地 clamp 后的值。
                // 这样客户端与服务端 bounds 万一漂移，界面显示的仍是真实生效值。
                renderSettings(merged)
                val suffix = if (clamped.isEmpty()) "" else {
                    "\n已按范围收敛：" + clamped.joinToString("、") {
                        "${it.first} ${it.second.first} → ${it.second.second}"
                    }
                }
                Toast.makeText(this, "✅ 守护设置已保存并同步到老人手机$suffix",
                    Toast.LENGTH_LONG).show()
            },
            onError = { err ->
                saving = false
                btnSave.isEnabled = true
                // 方向与老人端相反：这里失败 = 云端没改成，老人端仍在按旧参数守护。
                // 不能沿用老人端那句"本机已按新设置运行"。
                AlertDialog.Builder(this)
                    .setTitle("保存失败")
                    .setMessage("原因：$err\n\n这次改动没有保存，老人手机仍在按原来的参数守护。\n\n请检查网络后重试。")
                    .setPositiveButton("重试") { _, _ -> onSaveTap() }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                Toast.makeText(this, "位置权限被拒绝，可用「地图选点」代替", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
