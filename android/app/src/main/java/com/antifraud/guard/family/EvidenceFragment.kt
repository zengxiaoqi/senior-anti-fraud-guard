package com.antifraud.guard.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject

/**
 * 维权证据：一键导出《反诈报案维权证据包》结构化展示 + 复制
 * 对齐小程序 pages/evidence/evidence
 */
class EvidenceFragment : Fragment() {

    private var lastFetch = 0L
    private var pkg: JSONObject? = null

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_evidence, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        this.container = view.findViewById(R.id.ll_evidence_container)
        tvEmpty = view.findViewById(R.id.tv_evidence_empty)
        view.findViewById<TextView>(R.id.btn_copy_evidence).setOnClickListener { onCopyTap() }
    }

    override fun onResume() {
        super.onResume()
        fetchEvidence()
    }

    /** 30s 节流（对齐小程序） */
    private fun fetchEvidence() {
        if (!GuardConfig.isFamilyBound) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "尚未绑定老人\n请先到「控制台」完成亲情绑定"
            container.removeAllViews()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30_000) return
        lastFetch = now

        ApiClient.familyGet("/api/evidence/export/${GuardConfig.boundElderId}",
            onSuccess = { data ->
                pkg = data
                renderPackage(data)
            },
            onError = {
                Toast.makeText(context, "获取证据材料失败：$it", Toast.LENGTH_SHORT).show()
            })
    }

    private fun renderPackage(p: JSONObject) {
        container.removeAllViews()
        if (p.length() == 0) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE

        // 元数据
        val meta = p.optJSONObject("metadata")
        if (meta != null) {
            addSection("📦 ${meta.optString("title", "证据包")}", listOf(
                "生成时间：${meta.optString("generated_at", "").replace("T", " ").take(19)}",
                "系统版本：${meta.optString("system_version", "")}",
                "校验码：${meta.optString("checksum", "")}"
            ))
        }

        // 老人信息
        val elder = p.optJSONObject("elder_info")
        if (elder != null) {
            addSection("👤 守护对象信息", listOf(
                "姓名：${elder.optString("name", "--")}",
                "电话：${elder.optString("phone", "--")}",
                "监护人：${elder.optString("guardian_name", "--")}",
                "监护人电话：${elder.optString("guardian_phone", "--")}"
            ))
        }

        // 扣款流水
        val payments = p.optJSONArray("payment_records")
        if (payments != null && payments.length() > 0) {
            val lines = mutableListOf<String>()
            for (i in 0 until payments.length()) {
                val pay = payments.optJSONObject(i) ?: continue
                val time = pay.optString("created_at", "").replace("T", " ").take(16)
                lines.add("💰 ¥${pay.optDouble("amount", 0.0)} → ${pay.optString("payee_name", "未知商户")}")
                lines.add("   卡号：${pay.optString("payee_account", "--")}｜单号：${pay.optString("order_no", "--")}")
                lines.add("   时间：$time")
            }
            addSection("💳 可疑扣款流水（${payments.length()} 笔）", lines)
        } else {
            addSection("💳 可疑扣款流水", listOf("（无记录）"))
        }

        // 可疑通话
        val calls = p.optJSONArray("suspicious_calls")
        if (calls != null && calls.length() > 0) {
            val lines = mutableListOf<String>()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val time = call.optString("created_at", "").replace("T", " ").take(16)
                lines.add("📞 ${call.optString("severity", "LOW")} 级通话风险 @ $time")
                val details = call.optJSONObject("details")
                details?.keys()?.asSequence()?.forEach { k ->
                    lines.add("   $k: ${details.opt(k)}")
                }
            }
            addSection("☎️ 可疑通话记录（${calls.length()} 条）", lines)
        } else {
            addSection("☎️ 可疑通话记录", listOf("（无记录）"))
        }

        // 位置轨迹
        val locs = p.optJSONArray("location_logs")
        if (locs != null && locs.length() > 0) {
            val lines = mutableListOf<String>()
            for (i in 0 until locs.length()) {
                val loc = locs.optJSONObject(i) ?: continue
                val time = loc.optString("created_at", "").replace("T", " ").take(16)
                lines.add("📍 ${loc.optString("address", "未知位置")} @ $time")
            }
            addSection("🗺️ 近期位置轨迹（${locs.length()} 条）", lines)
        } else {
            addSection("🗺️ 近期位置轨迹", listOf("（无记录）"))
        }
    }

    /** 动态添加一个卡片区块 */
    private fun addSection(title: String, lines: List<String>) {
        val ctx = context ?: return
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        card.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFF1E293B.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        lines.forEach { line ->
            card.addView(TextView(ctx).apply {
                text = line
                setTextColor(0xFF475569.toInt())
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }

        container.addView(card)
    }

    private fun onCopyTap() {
        val p = pkg
        if (p == null) {
            Toast.makeText(context, "材料未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("EvidencePackage", p.toString(2)))
        Toast.makeText(context, "✅ 证据链文本已复制，可直接粘贴给民警", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
