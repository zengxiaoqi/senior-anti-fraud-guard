package com.antifraud.guard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.db.RiskEvent
import com.antifraud.guard.db.RiskEventDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiskLogActivity : AppCompatActivity() {

    private lateinit var dbHelper: RiskEventDbHelper
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_risk_log)

        dbHelper = RiskEventDbHelper(this)
        listView = findViewById(R.id.lv_risk_events)
        tvEmpty = findViewById(R.id.tv_empty)
        tvCount = findViewById(R.id.tv_count)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            val count = dbHelper.clearAllEvents()
            Toast.makeText(this, "已清除 $count 条记录", Toast.LENGTH_SHORT).show()
            refreshList()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val events = dbHelper.getEvents(200)
        tvCount.text = "共 ${dbHelper.getEventCount()} 条记录"
        if (events.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = RiskAdapter(events)
        }
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

            tvTime.text = dateFormat.format(Date(event.timestamp))
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
        "CALL_RISK" -> "通话风险"
        "PAYMENT_RISK" -> "大额扣款"
        "DEVICE_ONLINE" -> "设备上线"
        "PING_TEST" -> "连接测试"
        else -> type
    }
}
