package com.antifraud.guard.family

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager
import org.json.JSONObject

/**
 * 防诈告警：老人端风险事件列表 + 紧急远程打断
 * 对齐小程序 pages/index/index
 */
class AlertsFragment : Fragment() {

    private var lastFetch = 0L
    private var events: List<JSONObject> = emptyList()

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_alerts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listView = view.findViewById(R.id.lv_alert_events)
        tvEmpty = view.findViewById(R.id.tv_alerts_empty)
        tvSummary = view.findViewById(R.id.tv_alerts_summary)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in events.indices) showDetail(events[position])
        }

        view.findViewById<TextView>(R.id.btn_interrupt).setOnClickListener { onInterruptTap() }
    }

    override fun onResume() {
        super.onResume()
        // 必须 force：防诈告警的价值全在时效。用户反复切进来看的就是
        // "有没有新告警"，而这恰恰是被 30s 节流挡掉的场景 ——
        // 切走再切回看到 30s 前的旧列表，界面上却没有任何"数据已过期"的提示。
        // 与 DashboardFragment.fetchGeofenceSummary 的处理方式一致。
        fetchEvents(force = true)
    }

    /** @param force true = 无条件重新拉取（onResume 走这条）；false 供将来手动刷新用 */
    private fun fetchEvents(force: Boolean = false) {
        if (!GuardConfig.isFamilyBound) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "尚未绑定老人\n请先到「控制台」完成亲情绑定"
            listView.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < 30_000) return
        lastFetch = now

        ApiClient.familyGet("/api/events/list/${GuardConfig.boundElderId}", onSuccess = { data ->
            val list = mutableListOf<JSONObject>()
            val arr = data.optJSONArray("data")
            if (arr != null) for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(it) }
            }
            events = list
            tvSummary.text = "共 ${events.size} 条记录（数据实时同步自老人端）"
            if (events.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "✅ 暂无风险事件，老人一切正常"
                listView.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                listView.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
            }
        }, onError = {
            Toast.makeText(context, "获取风险事件失败：$it", Toast.LENGTH_SHORT).show()
        })
    }

    private fun showDetail(event: JSONObject) {
        val details = event.optJSONObject("details")
        val pretty = details?.toString(2) ?: event.optString("details", "")
        AlertDialog.Builder(requireContext())
            .setTitle("📝 事件详情")
            .setMessage("""
                类型：${eventName(event.optString("event_type"))}
                级别：${severityName(event.optString("severity"))}
                时间：${event.optString("created_at", "").replace("T", " ").take(19)}

                $pretty
            """.trimIndent())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun onInterruptTap() {
        if (!GuardConfig.isFamilyBound) {
            Toast.makeText(context, "请先绑定老人", Toast.LENGTH_SHORT).show()
            return
        }
        FamilyWebSocketManager.sendRemoteInterrupt(
            "App 强打断：子女提醒您立即终止当前异常通话！"
        ) { ok, msg ->
            Toast.makeText(
                context,
                if (ok) "⚡ 已触发远程打断" else "触发失败：$msg",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = events.size
        override fun getItem(position: Int) = events[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_family_event, parent, false)
            val e = events[position]
            v.findViewById<TextView>(R.id.tv_event_type).text =
                eventName(e.optString("event_type"))
            v.findViewById<TextView>(R.id.tv_event_time).text =
                e.optString("created_at", "").replace("T", " ").take(16)
            val sevView = v.findViewById<TextView>(R.id.tv_event_severity)
            val sev = e.optString("severity")
            sevView.text = severityName(sev)
            sevView.setTextColor(
                when (sev) {
                    "HIGH" -> 0xFFEF4444.toInt()
                    "MEDIUM" -> 0xFFF59E0B.toInt()
                    else -> 0xFF10B981.toInt()
                }
            )
            val details = e.optJSONObject("details")
            val detailStr = if (details != null) {
                details.keys().asSequence().joinToString("；") { k ->
                    "$k: ${details.opt(k)}"
                }
            } else e.optString("details", "")
            v.findViewById<TextView>(R.id.tv_event_details).text =
                detailStr.ifEmpty { "（无详情）" }
            return v
        }
    }

    private fun eventName(type: String) = when (type) {
        "CALL_RISK" -> "📞 通话风险"
        "COERCION_RISK" -> "🚨 通话中被诱导操作"
        "CALL_STAT" -> "📞 通话记录"
        "GEOFENCE_DWELL" -> "⏱ 敏感地点停留过久"
        "PAYMENT_RISK" -> "💳 大额扣款"
        "SOS" -> "🆘 一键紧急求助"
        "REMOTE_INTERRUPT" -> "🚨 子女远程强打断"
        "LOCATION_RISK" -> "📍 敏感地点长时间停留"
        "GEOFENCE_RECORDING" -> "📍 进入敏感地点（自动录音存证）"
        "GEOFENCE_EXIT" -> "📍 离开敏感地点（录音停止）"
        "LOCATION_UPDATE" -> "📍 位置更新"
        "DEVICE_ONLINE" -> "🟢 设备上线"
        else -> type
    }

    private fun severityName(sev: String) = when (sev) {
        "HIGH" -> "高危"
        "MEDIUM" -> "中危"
        else -> "低危"
    }
}
