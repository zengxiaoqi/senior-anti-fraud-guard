package com.antifraud.guard.family

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.antifraud.guard.util.EventLabels
import org.json.JSONObject

/**
 * 防诈告警：老人端风险事件列表 + 紧急远程打断
 * 对齐小程序 pages/index/index
 *
 * 2026-10-10 UI 审查改造：
 *  - 顶部计数总览（高危 / 需留意 / 总数），先回答「有没有要紧事」
 *  - 列表按「今天 / 昨天 / 更早」分组，时间感一眼可得
 *  - 卡片加严重度色条，高危不再淹没在一串同规格卡片里
 *  - 紧急打断从常驻大按钮降级为「说明条 + 二次确认」，降低误触成本
 */
class AlertsFragment : Fragment() {

    private var lastFetch = 0L
    private var events: List<JSONObject> = emptyList()

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCountHigh: TextView
    private lateinit var tvCountMid: TextView
    private lateinit var tvCountTotal: TextView

    /**
     * 列表渲染行：分组头或事件。header 事件为 null。
     *
     * 去重聚合（与控制台 formatOverview 同一套判据 EventLabels.sameKind）：
     * 连续同类型同级别的事件合并成一张卡，count>1 时标题显示「×N」，
     * 点开详情可展开该时段每一条的时间点 —— 列表不再被高频的
     * 位置更新/心跳事件刷成流水账，又不丢失任何一条明细。
     */
    private class Row(val header: String?, val event: JSONObject?) {
        val merged = mutableListOf<JSONObject>()
        val count: Int get() = merged.size.coerceAtLeast(if (event == null) 0 else 1)
    }

    private var rows: List<Row> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_alerts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listView = view.findViewById(R.id.lv_alert_events)
        tvEmpty = view.findViewById(R.id.tv_alerts_empty)
        tvCountHigh = view.findViewById(R.id.tv_count_high)
        tvCountMid = view.findViewById(R.id.tv_count_mid)
        tvCountTotal = view.findViewById(R.id.tv_count_total)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.event != null) showDetail(row)
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
            renderCounts()
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
            rows = buildRows(list)
            renderCounts()
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

    /** 事件列表 → 带分组头的渲染行。按本地日期分组；组内连续同类同级别合并（EventLabels.sameKind） */
    private fun buildRows(list: List<JSONObject>): List<Row> {
        val out = mutableListOf<Row>()
        var lastHeader: String? = null
        var lastEventRow: Row? = null
        for (e in list) {
            val header = dayHeader(e)
            if (header != lastHeader) {
                out.add(Row(header, null))
                lastHeader = header
                lastEventRow = null // 跨天不合并：昨天和今天的同类事件是两件事
            }
            val g = lastEventRow
            if (g != null && g.event != null && EventLabels.sameKind(g.event, e)) {
                g.merged.add(e)
            } else {
                val r = Row(null, e).also { it.merged.add(e) }
                out.add(r)
                lastEventRow = r
            }
        }
        return out
    }

    /** 分组头统一走 EventLabels：老人端风险记录页用的是同一个函数，两端分组语言不会分叉 */
    private fun dayHeader(e: JSONObject): String {
        val millis = EventLabels.parseIsoDate(e.optString("created_at", ""))?.time ?: return ""
        return EventLabels.dayHeader(millis)
    }

    /**
     * 计数总览：今日高危 / 今日需留意 / 本周累计。
     * 口径用"今日"而不是全量：守护人问的是"今天有没有事"，
     * 全量累计只会随使用时间单调增长，很快就失去"要不要现在看"的信号意义。
     */
    private fun renderCounts() {
        val now = System.currentTimeMillis()
        // 今日零点必须按本机时区算：UTC 日的边界在东八区会整体偏 8 小时
        val todayStart = EventLabels.todayStartMillis(now)
        val weekStart = todayStart - 6 * 86_400_000L

        var highToday = 0
        var midToday = 0
        var weekTotal = 0
        for (e in events) {
            val ts = EventLabels.parseIsoDate(e.optString("created_at", ""))?.time ?: continue
            if (ts >= weekStart) weekTotal++
            when {
                ts < todayStart -> {}
                e.optString("severity") == "HIGH" -> highToday++
                e.optString("severity") == "MEDIUM" -> midToday++
            }
        }
        tvCountHigh.text = "高危 $highToday"
        tvCountMid.text = "需留意 $midToday"
        tvCountTotal.text = "本周 $weekTotal"
        // 无告警时计数降灰，有告警才亮色：让「高危 2」成为真正的注意力锚点
        val dim = com.antifraud.guard.util.UiPrefs.dimColor(requireContext())
        tvCountHigh.setTextColor(if (highToday > 0) Color.parseColor("#EF4444") else dim)
        tvCountMid.setTextColor(if (midToday > 0) Color.parseColor("#F59E0B") else dim)
    }

    /**
     * 事件详情。合并卡（count>1）先给"共 N 条"结论再列出每条时间点——
     * 守护人点开聚合卡想知道的是"这堆同类事件都发生在什么时候"，
     * 只显示最新一条的详情会让人误以为只发生了一次。
     */
    private fun showDetail(row: Row) {
        val event = row.event ?: return
        val details = event.optJSONObject("details")
        val pretty = details?.toString(2) ?: event.optString("details", "")
        val merged = row.merged
        val mergedBlock = if (merged.size > 1) {
            val times = merged.take(10).joinToString("\n") {
                "• " + EventLabels.shortLocalTime(it.optString("created_at", ""))
                    .ifEmpty { it.optString("created_at", "").replace("T", " ").take(16) }
            }
            val more = if (merged.size > 10) "\n… 及更早 ${merged.size - 10} 条" else ""
            "\n该时段同类事件共 ${merged.size} 条：\n$times$more\n"
        } else ""
        AlertDialog.Builder(requireContext())
            .setTitle("📝 事件详情")
            .setMessage("""
                类型：${EventLabels.eventName(event.optString("event_type"))}
                级别：${EventLabels.severityName(event.optString("severity"))}
                时间：${event.optString("created_at", "").replace("T", " ").take(19)}
                $mergedBlock
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
        // 二次确认：这个动作会让老人手机当场全屏响铃，误触的心理成本
        // 远大于多点一次的成本；原先点一下直接发，是本页最大的误触风险
        AlertDialog.Builder(requireContext())
            .setTitle("确认紧急打断？")
            .setMessage("老人手机将立即响铃并弹出全屏提醒，用于打断正在进行的可疑通话。\n\n仅在通话中确认到诈骗迹象时使用。")
            .setPositiveButton("确认打断") { _, _ ->
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
            .setNegativeButton("取消", null)
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].header != null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]

            // 分组头
            if (row.header != null) {
                val h = convertView
                    ?: LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_event_header, parent, false)
                h.findViewById<TextView>(R.id.tv_group_header).text = row.header
                return h
            }

            val e = row.event ?: return convertView ?: View(requireContext())
            val v = convertView
                ?: LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_family_event, parent, false)
            // 合并卡标题带 ×N：与控制台概览「位置更新 ×2」同一语言
            v.findViewById<TextView>(R.id.tv_event_type).text =
                EventLabels.eventName(e.optString("event_type")) +
                    if (row.count > 1) " ×${row.count}" else ""
            v.findViewById<TextView>(R.id.tv_event_time).text =
                EventLabels.shortLocalTime(e.optString("created_at", ""))
                    .ifEmpty { e.optString("created_at", "").replace("T", " ").take(16) }
            val sevView = v.findViewById<TextView>(R.id.tv_event_severity)
            val sev = e.optString("severity")
            sevView.text = EventLabels.severityName(sev)
            // 颜色统一走 EventLabels：与控制台/风险记录页同源，杜绝漂移
            sevView.setTextColor(EventLabels.severityColor(sev))
            // 色条与文字同色：扫一眼颜色即可定位高危
            val bar = v.findViewById<View>(R.id.v_sev_bar)
            (bar.background as? GradientDrawable)?.setTint(EventLabels.severityColor(sev))
                ?: run { bar.background.setTint(EventLabels.severityColor(sev)) }
            val details = e.optJSONObject("details")
            v.findViewById<TextView>(R.id.tv_event_details).text =
                // 人话化：优先展示服务端补的 location_name 真名，坐标等原始键值不再平铺
                // （坐标数字守护人看不懂；详情弹窗里仍保留完整 JSON 可查证）
                EventLabels.formatDetails(details).ifEmpty { "（无详情）" }
            return v
        }
    }
}
