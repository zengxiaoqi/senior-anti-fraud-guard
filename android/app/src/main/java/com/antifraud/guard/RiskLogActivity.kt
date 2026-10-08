package com.antifraud.guard

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.db.RiskEvent
import com.antifraud.guard.db.RiskEventDbHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiskLogActivity : AppCompatActivity() {

    private lateinit var dbHelper: RiskEventDbHelper
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView

    private lateinit var filterAll: Button
    private lateinit var filterPayment: Button
    private lateinit var filterCall: Button
    private lateinit var filterSos: Button
    private lateinit var filterLocation: Button

    private var currentFilter: String? = null // null means ALL
    private var displayedEvents: List<RiskEvent> = emptyList()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_risk_log)

        dbHelper = RiskEventDbHelper(this)
        listView = findViewById(R.id.lv_risk_events)
        tvEmpty = findViewById(R.id.tv_empty)
        tvCount = findViewById(R.id.tv_count)

        filterAll = findViewById(R.id.filter_all)
        filterPayment = findViewById(R.id.filter_payment)
        filterCall = findViewById(R.id.filter_call)
        filterSos = findViewById(R.id.filter_sos)
        filterLocation = findViewById(R.id.filter_location)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认清除所有记录？")
                .setMessage("清除后本地所有风险日志将无法恢复，建议先点击【导出】备份。")
                .setPositiveButton("确认清除") { _, _ ->
                    val count = dbHelper.clearAllEvents()
                    Toast.makeText(this, "已清除 $count 条记录", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btn_export_text).setOnClickListener {
            exportCurrentLogsToClipboard()
        }

        setupFilters()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in displayedEvents.indices) {
                showEventDetailDialog(displayedEvents[position])
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun setupFilters() {
        val filterButtons = listOf(filterAll, filterPayment, filterCall, filterSos, filterLocation)

        fun updateButtonStyles(activeBtn: Button) {
            for (btn in filterButtons) {
                if (btn == activeBtn) {
                    btn.setBackgroundColor(0xFF3B82F6.toInt())
                    btn.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    btn.setBackgroundColor(0xFF1E293B.toInt())
                    btn.setTextColor(0xFF94A3B8.toInt())
                }
            }
        }

        filterAll.setOnClickListener {
            currentFilter = null
            updateButtonStyles(filterAll)
            refreshList()
        }
        filterPayment.setOnClickListener {
            currentFilter = "PAYMENT_RISK"
            updateButtonStyles(filterPayment)
            refreshList()
        }
        filterCall.setOnClickListener {
            currentFilter = "CALL_RISK"
            updateButtonStyles(filterCall)
            refreshList()
        }
        filterSos.setOnClickListener {
            currentFilter = "SOS_OR_INTERRUPT"
            updateButtonStyles(filterSos)
            refreshList()
        }
        filterLocation.setOnClickListener {
            currentFilter = "LOCATION"
            updateButtonStyles(filterLocation)
            refreshList()
        }
    }

    private fun refreshList() {
        val allEvents = dbHelper.getEvents(300)

        displayedEvents = when (currentFilter) {
            "PAYMENT_RISK" -> allEvents.filter { it.eventType == "PAYMENT_RISK" }
            "CALL_RISK" -> allEvents.filter { it.eventType == "CALL_RISK" }
            "SOS_OR_INTERRUPT" -> allEvents.filter { it.eventType == "SOS" || it.eventType == "REMOTE_INTERRUPT" }
            "LOCATION" -> allEvents.filter { it.eventType == "LOCATION_RISK" || it.eventType == "LOCATION_UPDATE" }
            else -> allEvents
        }

        tvCount.text = "共 ${displayedEvents.size} 条记录（总库 ${allEvents.size} 条）"

        if (displayedEvents.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = RiskAdapter(displayedEvents)
        }
    }

    private fun showEventDetailDialog(event: RiskEvent) {
        val timeStr = dateFormat.format(Date(event.timestamp))
        val typeName = getEventTypeName(event.eventType)

        val prettyDetails = try {
            JSONObject(event.details).toString(2)
        } catch (e: Exception) {
            event.details
        }

        val msg = """
【类型】$typeName
【级别】${event.severity}
【时间】$timeStr
【详情参数】
$prettyDetails
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📝 事件记录详情")
            .setMessage(msg)
            .setPositiveButton("复制详情") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("EventDetail", msg))
                Toast.makeText(this, "详情文本已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportCurrentLogsToClipboard() {
        if (displayedEvents.isEmpty()) {
            Toast.makeText(this, "暂无记录可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("═════════════════════════════════════\n")
        sb.append("长者防诈亲情守护系统 - 风险存证记录导出\n")
        sb.append("导出时间：").append(dateFormat.format(Date())).append("\n")
        sb.append("记录总数：").append(displayedEvents.size).append(" 条\n")
        sb.append("═════════════════════════════════════\n\n")

        for ((idx, ev) in displayedEvents.withIndex()) {
            sb.append("[").append(idx + 1).append("] ")
                .append(dateFormat.format(Date(ev.timestamp))).append(" | ")
                .append(getEventTypeName(ev.eventType)).append(" (")
                .append(ev.severity).append(")\n")
                .append("详情：").append(ev.details).append("\n\n")
        }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AntiFraudLogs", sb.toString()))
        Toast.makeText(this, "✅ 已成功导出 ${displayedEvents.size} 条记录至剪贴板！可直接粘贴发给子女或民警。", Toast.LENGTH_LONG).show()
    }

    private inner class RiskAdapter(private val events: List<RiskEvent>) : BaseAdapter() {
        override fun getCount() = events.size
        override fun getItem(position: Int) = events[position]
        override fun getItemId(position: Int) = events[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@RiskLogActivity)
                .inflate(R.layout.item_risk_event, parent, false)
            val event = events[position]

            val tvTime = view.findViewById<TextView>(R.id.tv_event_time)
            val tvType = view.findViewById<TextView>(R.id.tv_event_type)
            val tvSeverity = view.findViewById<TextView>(R.id.tv_event_severity)
            val tvDetails = view.findViewById<TextView>(R.id.tv_event_details)

            tvTime.text = shortDateFormat.format(Date(event.timestamp))
            tvType.text = getEventTypeName(event.eventType)
            tvSeverity.text = when (event.severity) {
                "HIGH" -> "高危"
                "MEDIUM" -> "中危"
                else -> "低危"
            }
            tvSeverity.setTextColor(when (event.severity) {
                "HIGH" -> 0xFFEF4444.toInt()
                "MEDIUM" -> 0xFFF59E0B.toInt()
                else -> 0xFF10B981.toInt()
            })

            tvDetails.text = event.details.take(120)

            return view
        }
    }

    private fun getEventTypeName(type: String): String = when (type) {
        "CALL_RISK" -> "📞 通话风险"
        "COERCION_RISK" -> "🚨 通话中被诱导操作"
        "CALL_STAT" -> "📞 通话记录"
        "GEOFENCE_DWELL" -> "⏱ 敏感地点停留过久"
        "PAYMENT_RISK" -> "💳 大额扣款"
        "SOS" -> "🆘 一键紧急求助"
        "REMOTE_INTERRUPT" -> "🚨 子女远程强打断"
        "LOCATION_RISK" -> "📍 敏感地点长时间停留"
        "LOCATION_UPDATE" -> "📍 位置更新"
        "DEVICE_ONLINE" -> "🟢 设备上线"
        "PING_TEST" -> "🔍 连接测试"
        else -> type
    }
}
