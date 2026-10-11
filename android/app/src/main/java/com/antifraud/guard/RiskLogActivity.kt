package com.antifraud.guard

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.antifraud.guard.db.RiskEvent
import com.antifraud.guard.db.RiskEventDbHelper
import com.antifraud.guard.util.EventLabels
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 老人端「我的风险记录」：本机风险事件库的可读视图。
 *
 * 2026-10-11 改版（参照子女端 AlertsFragment 的呈现规则，docs/ui-guidelines.md §4）：
 *  - **顶部计数总览**：今天要紧 / 留意 / 近 7 天。老人打开页面的第一个问题永远是
 *    "有没有要紧事"，而不是"一共有几条"——旧版只有一行"共 N 条记录（总库 N 条）"，
 *    把这两个问题混成一句灰字，等于没回答。
 *  - **按「今天 / 昨天 / M月d日」分组**：几十条同规格卡片的时间感，只能靠分组给。
 *  - **连续同类同级合并成「×N」**：位置更新、通话记录是高频事件，不合并就会把
 *    真正的高危挤到看不见的地方（判据 EventLabels.sameKind，与子女端同一套）。
 *  - **详情人话化**：列表行不再贴 details 原始 JSON。老人以前看到的是
 *    {"amount":19800,"source":"com.eg.android.AlipayGphone"}，现在看到"金额 19800 元"。
 *  - **详情弹窗给"该怎么办"**：只告诉老人"发生过高危事件"没有用，他要的是下一步。
 *
 * 文案与颜色一律走 EventLabels / ?attr（AGENT.MD 铁律 11），本页不自建事件名映射。
 */
class RiskLogActivity : BaseActivity() {

    private lateinit var dbHelper: RiskEventDbHelper
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvCountHigh: TextView
    private lateinit var tvCountMid: TextView
    private lateinit var tvCountWeek: TextView

    /** 筛选 chip：key=null 表示全部；判据集中在 [matchesFilter]，增删 chip 只动这里 */
    private val filterChips = mutableListOf<Pair<Button, String?>>()
    private var currentFilter: String? = null

    private var allEvents: List<RiskEvent> = emptyList()
    private var filteredEvents: List<RiskEvent> = emptyList()
    private var rows: List<Row> = emptyList()

    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 列表渲染行：分组头或事件。header 非 null 时是分组头。merged 装被合并进来的同类事件 */
    private class Row(val header: String?, val event: RiskEvent?) {
        val merged = mutableListOf<RiskEvent>()
        val count: Int get() = merged.size.coerceAtLeast(if (event == null) 0 else 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_risk_log)

        dbHelper = RiskEventDbHelper(this)
        listView = findViewById(R.id.lv_risk_events)
        tvEmpty = findViewById(R.id.tv_empty)
        tvCount = findViewById(R.id.tv_count)
        tvCountHigh = findViewById(R.id.tv_count_high)
        tvCountMid = findViewById(R.id.tv_count_mid)
        tvCountWeek = findViewById(R.id.tv_count_week)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        // ⋮ 溢出菜单：清除记录收进这里（2026-10-10 改版）。
        // 旧版"清除"红色实心按钮与"导出"并排在顶栏，误触即删光维权存证；
        // 收进菜单 + 二次确认后，破坏性操作与常规操作彻底分离。
        findViewById<TextView>(R.id.btn_more).setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(this, anchor)
            popup.menu.add("🗑 清除本机全部记录")
            popup.menu.add("本机共 ${dbHelper.getEventCount()} 条记录").isEnabled = false
            popup.setOnMenuItemClickListener { item ->
                if (item.title.toString().startsWith("🗑")) confirmClear()
                true
            }
            popup.show()
        }

        findViewById<TextView>(R.id.btn_export_text).setOnClickListener {
            exportCurrentLogsToClipboard()
        }

        setupFilters()

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.event != null) showEventDetail(row)
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // 老人端记录是本地库，服务在后台仍会写入（跌倒、扣款、打断都写本地），
        // 回前台必须重读一次，否则看到的会是进页面那一刻的快照。
        refreshList()
    }

    /** 清除全部记录（⋮ 菜单入口，始终二次确认；提示先导出备份） */
    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("确认清除所有记录？")
            .setMessage("清除后本机所有风险日志将无法恢复，建议先点击【导出】备份。")
            .setPositiveButton("确认清除") { _, _ ->
                val count = dbHelper.clearAllEvents()
                Toast.makeText(this, "已清除 $count 条记录", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupFilters() {
        val specs = listOf(
            R.id.filter_all to null,
            R.id.filter_high to "HIGH",
            R.id.filter_payment to "PAYMENT",
            R.id.filter_call to "CALL",
            R.id.filter_sos to "SOS",
            R.id.filter_location to "LOCATION",
            R.id.filter_fall to "FALL"
        )
        for ((id, key) in specs) {
            val btn = findViewById<Button>(id)
            filterChips.add(btn to key)
            btn.setOnClickListener {
                currentFilter = key
                updateChipStyles()
                refreshList()
            }
        }
        updateChipStyles()
    }

    private fun updateChipStyles() {
        for ((btn, key) in filterChips) {
            val active = key == currentFilter
            // 用 backgroundTintList 而不是 setBackgroundColor：
            // 后者会把 Material 圆角 shape 打回直角（审查发现的视觉毛边）
            btn.backgroundTintList = ColorStateList.valueOf(
                if (active) 0xFF3B82F6.toInt() else UiPrefs.cardColor(this)
            )
            btn.setTextColor(if (active) 0xFFFFFFFF.toInt() else UiPrefs.dimColor(this))
        }
    }

    private fun matchesFilter(e: RiskEvent): Boolean = when (currentFilter) {
        null -> true
        "HIGH" -> e.severity == "HIGH"
        "PAYMENT" -> e.eventType == "PAYMENT_RISK"
        "CALL" -> e.eventType == "CALL_RISK" || e.eventType == "COERCION_RISK" || e.eventType == "CALL_STAT"
        "SOS" -> e.eventType == "SOS" || e.eventType == "REMOTE_INTERRUPT"
        "LOCATION" -> e.eventType.startsWith("LOCATION") || e.eventType.startsWith("GEOFENCE")
        "FALL" -> e.eventType == "FALL_RISK"
        else -> true
    }

    private fun refreshList() {
        allEvents = dbHelper.getEvents(500)
        filteredEvents = allEvents.filter { matchesFilter(it) }
        rows = buildRows(filteredEvents)
        renderCounts()

        tvCount.text = if (currentFilter == null) {
            "本机共保存 ${allEvents.size} 条记录"
        } else {
            "本机共保存 ${allEvents.size} 条记录，当前筛出 ${filteredEvents.size} 条"
        }

        if (rows.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = emptyText()
            listView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            listView.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    /** 空状态要说话（ui-guidelines §6）：告诉老人"没有"是好事，而不是留一片空白 */
    private fun emptyText(): String = when (currentFilter) {
        null -> "还没有任何风险记录\n守护开启后，可疑通话、大额扣款、跌倒等都会记在这里，您和子女都能查看"
        "HIGH" -> "最近没有要紧的风险记录，一切正常"
        "PAYMENT" -> "没有大额扣款记录"
        "CALL" -> "没有通话风险记录"
        "SOS" -> "没有求助与打断记录"
        "LOCATION" -> "没有位置相关记录"
        "FALL" -> "没有跌倒相关记录"
        else -> "没有符合条件的记录"
    }

    /** 事件列表 → 带分组头的渲染行。按本地日期分组；组内连续同类同级合并 */
    private fun buildRows(list: List<RiskEvent>): List<Row> {
        val out = mutableListOf<Row>()
        var lastHeader: String? = null
        var lastEventRow: Row? = null
        for (e in list) {
            val header = EventLabels.dayHeader(e.timestamp)
            if (header != lastHeader) {
                out.add(Row(header, null))
                lastHeader = header
                lastEventRow = null // 跨天不合并：昨天和今天的同类事件是两件事
            }
            val g = lastEventRow
            if (g != null && g.event != null &&
                EventLabels.sameKind(g.event.eventType, g.event.severity, e.eventType, e.severity)
            ) {
                g.merged.add(e)
            } else {
                val r = Row(null, e).also { it.merged.add(e) }
                out.add(r)
                lastEventRow = r
            }
        }
        return out
    }

    /**
     * 计数总览：今日口径，不是全量累计 —— 全量只会随使用时间单调增长，
     * 很快就失去"要不要现在看"的信号意义。无记录时降灰，有记录才亮状态色。
     */
    private fun renderCounts() {
        val todayStart = EventLabels.todayStartMillis()
        val weekStart = todayStart - 6 * 86_400_000L

        var highToday = 0
        var midToday = 0
        var weekTotal = 0
        for (e in allEvents) {
            if (e.timestamp >= weekStart) weekTotal++
            if (e.timestamp < todayStart) continue
            when (e.severity) {
                "HIGH" -> highToday++
                "MEDIUM" -> midToday++
            }
        }
        tvCountHigh.text = "今天要紧 $highToday"
        tvCountMid.text = "留意 $midToday"
        tvCountWeek.text = "近 7 天 $weekTotal 条"
        val dim = UiPrefs.dimColor(this)
        tvCountHigh.setTextColor(if (highToday > 0) EventLabels.severityColor("HIGH") else dim)
        tvCountMid.setTextColor(if (midToday > 0) EventLabels.severityColor("MEDIUM") else dim)
    }

    /**
     * 记录详情。合并卡（count>1）先给"共 N 条"结论再列出每条时间点——
     * 只显示最新一条会让人误以为只发生了一次。
     * 末尾附原始 JSON 是刻意的：它是维权/查证时唯一有证据价值的东西，
     * 但明确标注"看不懂可以不管"，不让技术信息喧宾夺主。
     */
    private fun showEventDetail(row: Row) {
        val event = row.event ?: return
        val details = detailsOf(event)
        val summary = EventLabels.formatDetails(details, forElder = true)
        val advice = EventLabels.elderAdvice(event.eventType)

        val mergedBlock = if (row.merged.size > 1) {
            val times = row.merged.take(10).joinToString("\n") {
                "• ${EventLabels.shortLocalTime(it.timestamp)}"
            }
            val more = if (row.merged.size > 10) "\n… 及更早 ${row.merged.size - 10} 条" else ""
            "\n这类记录共 ${row.merged.size} 条：\n$times$more\n"
        } else ""

        val raw = event.details.trim()
        val rawBlock = if (raw.isEmpty() || raw == "{}") "" else
            "\n—— 以下为技术信息，看不懂可以不管 ——\n${raw.take(400)}"

        val msg = buildString {
            append(EventLabels.eventName(event.eventType))
            append("（").append(EventLabels.severityName(event.severity)).append("）\n")
            append(fullDateFormat.format(Date(event.timestamp)))
            if (summary.isNotEmpty()) append("\n\n").append(summary)
            if (advice.isNotEmpty()) append("\n\n💡 该怎么办\n").append(advice)
            append(mergedBlock)
            append(rawBlock)
        }

        AlertDialog.Builder(this)
            .setTitle("记录详情")
            .setMessage(msg)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("EventDetail", msg))
                Toast.makeText(this, "详情已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 导出：给子女或民警看的维权材料，所以**人话摘要与原始记录都要有**——
     * 只有人话失真、只有原始数据对方看不懂。
     */
    private fun exportCurrentLogsToClipboard() {
        // 导出基于筛选后的**全量**事件，不是列表里的代表事件 ——
        // 合并成「×N」只是显示层的聚合，导出漏掉被合并的记录等于丢证据
        val exported = filteredEvents
        if (exported.isEmpty()) {
            Toast.makeText(this, "暂无记录可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("═════════════════════════════════════\n")
        sb.append("长者防诈亲情守护系统 - 风险存证记录导出\n")
        sb.append("导出时间：").append(fullDateFormat.format(Date())).append("\n")
        sb.append("记录条数：").append(exported.size).append(" 条\n")
        sb.append("═════════════════════════════════════\n\n")

        for ((idx, ev) in exported.withIndex()) {
            sb.append("[").append(idx + 1).append("] ")
                .append(fullDateFormat.format(Date(ev.timestamp))).append(" | ")
                .append(EventLabels.eventName(ev.eventType)).append("（")
                .append(EventLabels.severityName(ev.severity)).append("）\n")
            val summary = EventLabels.formatDetails(detailsOf(ev), forElder = true)
            if (summary.isNotEmpty()) sb.append("说明：").append(summary).append("\n")
            if (ev.details.isNotBlank()) sb.append("原始记录：").append(ev.details).append("\n")
            sb.append("\n")
        }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AntiFraudLogs", sb.toString()))
        Toast.makeText(this, "已导出 ${exported.size} 条记录至剪贴板，可直接粘贴发给子女或民警。", Toast.LENGTH_LONG).show()
    }

    private fun detailsOf(e: RiskEvent): JSONObject? = try {
        if (e.details.isBlank()) null else JSONObject(e.details)
    } catch (t: Throwable) {
        null
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].header != null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]

            if (row.header != null) {
                val h = convertView
                    ?: LayoutInflater.from(this@RiskLogActivity)
                        .inflate(R.layout.item_event_header, parent, false)
                h.findViewById<TextView>(R.id.tv_group_header).text = row.header
                return h
            }

            val e = row.event ?: return convertView ?: View(this@RiskLogActivity)
            val v = convertView
                ?: LayoutInflater.from(this@RiskLogActivity)
                    .inflate(R.layout.item_risk_event, parent, false)

            // 合并卡标题带 ×N：与子女端「位置更新 ×2」同一语言
            v.findViewById<TextView>(R.id.tv_event_type).text =
                EventLabels.eventName(e.eventType) + if (row.count > 1) " ×${row.count}" else ""
            v.findViewById<TextView>(R.id.tv_event_time).text =
                EventLabels.shortLocalTime(e.timestamp)

            val sevView = v.findViewById<TextView>(R.id.tv_event_severity)
            sevView.text = EventLabels.severityName(e.severity)
            sevView.setTextColor(EventLabels.severityColor(e.severity))
            // 色条与文字同色：扫一眼颜色即可定位要紧事
            v.findViewById<View>(R.id.v_sev_bar).backgroundTintList =
                ColorStateList.valueOf(EventLabels.severityColor(e.severity))

            val summary = EventLabels.formatDetails(detailsOf(e), forElder = true)
            v.findViewById<TextView>(R.id.tv_event_details).text =
                summary.ifEmpty { "（没有更详细的说明）" }
            return v
        }
    }
}
