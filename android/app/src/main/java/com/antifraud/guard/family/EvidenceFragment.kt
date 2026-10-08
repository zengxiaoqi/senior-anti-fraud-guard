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
import com.antifraud.guard.service.FamilyWebSocketManager
import com.antifraud.guard.util.TimeText
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

    /** 录音卡片集合：离开页面时统一释放播放器，否则会在后台继续出声 */
    private val playerCards = mutableListOf<RecordingPlayerCard>()

    /** 老人端当前是否正在录音（由 WS RECORDING_STATE 维护） */
    private var elderIsRecording = false

    /**
     * 录音开始的时间与原因，用于在横幅上说明「何时开始、为什么开始」。
     *
     * 为什么必须有：只显示"正在录音"，子女无法判断这是老人自己按的求助、
     * 还是系统自动触发的，更不知道老人此刻在哪个地点 ——
     * 真出事了，光看一个红条是没法定性的。
     */
    private var recordingStartedAt = 0L
    private var recordingReason = ""
    private var recordingPlace = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_evidence, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        this.container = view.findViewById(R.id.ll_evidence_container)
        tvEmpty = view.findViewById(R.id.tv_evidence_empty)
        recordingContainer = view.findViewById(R.id.ll_recording_container)
        recordingEmptyView = view.findViewById(R.id.tv_recording_empty)
        packBtn = view.findViewById(R.id.btn_pack_recordings)
        view.findViewById<TextView>(R.id.btn_copy_evidence).setOnClickListener { onCopyTap() }
        view.findViewById<TextView>(R.id.btn_stop_recording).setOnClickListener { onStopRecordingTap() }
        view.findViewById<TextView>(R.id.btn_pack_recordings).setOnClickListener { onPackRecordingsTap() }

        // 录音事件实时刷新：新录音到达、AI 研判出结论、停止指令回执
        FamilyWebSocketManager.setRecordingListener { type, data ->
            when (type) {
"RECORDING_STATE" -> {
   val state = data.optString("state", "")
       val wasRecording = elderIsRecording
   elderIsRecording = (state == "STARTED" || state == "SEGMENT")
     if (elderIsRecording) {
  // 记住起始信息，供横幅展示；只在从"未录"切到"录制"的那一次刷新，
      // 否则每次分段都会把开始时间往后推，导致显示的时长一直是 0
       if (!wasRecording) {
      recordingStartedAt = System.currentTimeMillis()
        }
        recordingReason = data.optString("reason", "")
        recordingPlace = data.optString("place", "")
    } else {
        recordingStartedAt = 0L
       }
    activity?.runOnUiThread { updateRecordingBanner() }
     }
                "RECORDING_UPLOADED", "RECORDING_ANALYZED",
                "RECORDING_REVIEWED", "RECORDING_DELETED" -> {
                    // 清掉节流立刻重拉，让子女第一时间看到新证据
                    lastFetch = 0L
                    activity?.runOnUiThread { fetchRecordings(forceFetch = true) }
                }
            }
        }
        updateRecordingBanner()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 必须释放：MediaPlayer 不释放会在离开页面后继续播放
        playerCards.forEach { it.onDetachFromWindow() }
        playerCards.clear()
        FamilyWebSocketManager.setRecordingListener(null)
    }

    /** 录音横幅：区分来源（老人求助 / 系统自动）、并说明地点与已持续时长 */
    private fun updateRecordingBanner() {
        val v = view ?: return
        val banner = v.findViewById<View>(R.id.ll_recording_banner) ?: return
        val title = v.findViewById<TextView>(R.id.tv_recording_banner_title) ?: return
        val detail = v.findViewById<TextView>(R.id.tv_recording_banner_detail) ?: return

        if (!elderIsRecording) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE

        // 标题区分来源：老人自己按的求助 vs 系统按敏感地点自动触发的存证。
        // 两者严重程度完全不同，混成一个"正在录音"会让子女无法定性。
        title.text = if (recordingReason == "SOS") {
            "🔴 老人按了紧急求助，正在录音"
        } else {
            "🔴 正在自动留存现场记录"
        }

        val why = if (recordingPlace.isNotEmpty()) recordingPlace else "未登记地点"
        val elapsedMin = if (recordingStartedAt > 0L) {
            ((System.currentTimeMillis() - recordingStartedAt) / 60_000L).coerceAtLeast(0L)
        } else -1L

        detail.text = buildString {
            append("地点：")
            append(why)
            if (recordingReason == "SOS") {
                append("\n触发：老人主动按下求助")
            } else {
                append("\n触发：老人进入子女登记的敏感地点后自动开启（用于事后举证）")
            }
            if (elapsedMin >= 0) {
                append("\n已持续 ")
                append(elapsedMin)
                append(" 分钟")
            }
        }
    }

    private fun onStopRecordingTap() {
        FamilyWebSocketManager.sendStopRecording { ok, msg ->
            activity?.runOnUiThread {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (ok) elderIsRecording = false
                updateRecordingBanner()
            }
        }
    }

    /**
     * 一键打包下载报警材料（zip：录音本体 + 逐条证据清单）。
     *
     * 这里用 OkHttp 自行下载而不用系统 DownloadManager，原因是：
     * 本文件 import 了 android.app.* 通配符，会让 DownloadManager 被解析到
     * android.provider 下的同名废弃类而编译失败。改用 OkHttp 顺带也解决了
     * 打包接口需要 X-Auth-Token 头的问题。
     */
    private fun onPackRecordingsTap() {
        if (!GuardConfig.isFamilyBound) {
            Toast.makeText(context, "请先完成亲情绑定", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context ?: return
        val elderId = GuardConfig.boundElderId
        val url = "${ApiClient.getBaseUrl()}/api/recordings/pack/$elderId?scope=all"

        Toast.makeText(ctx, "正在打包录音材料，请稍候…", Toast.LENGTH_SHORT).show()
        packBtn?.isEnabled = false

        // 打包可能耗时较久（录音多时几十 MB），放到后台线程，主线程只负责提示结果
        Thread {
            var ok = false
            var msg = ""
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("X-Auth-Token", GuardConfig.familyToken)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        val err = try {
                            org.json.JSONObject(body?.string() ?: "").optString("error")
                        } catch (e: Exception) { "" }
                        msg = err.ifEmpty { "打包失败（HTTP ${resp.code}）" }
                        return@use
                    }

                    // 存到公共下载目录，子女可直接拿去派出所
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    if (!downloads.exists()) downloads.mkdirs()
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
                        .format(java.util.Date())
                    val outFile = java.io.File(downloads, "反诈录音证据包_${elderId}_${stamp}.zip")
                    body.byteStream().use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    ok = true
                    val kb = outFile.length() / 1024
                    msg = "已保存到「下载」目录：${outFile.name}（${kb}KB）"
                }
            } catch (e: Exception) {
                msg = "下载失败：${e.message}"
            }

            val finalOk = ok
            val finalMsg = msg
            activity?.runOnUiThread {
                packBtn?.isEnabled = true
                Toast.makeText(context, finalMsg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        fetchEvidence()
        fetchRecordings()
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
            onSuccess = { res ->
                // 响应改为完整 JSON：证据包在 data 节点
                val payload = res.optJSONObject("data") ?: JSONObject()
                pkg = payload
                renderPackage(payload)
            },
            onError = {
                Toast.makeText(context, "获取证据材料失败：$it", Toast.LENGTH_SHORT).show()
            })
    }

    // ──────────────────────────────────────────
    //  录音存证
    // ──────────────────────────────────────────

    private var lastRecordingFetch = 0L
    private var recordingContainer: LinearLayout? = null
    private var recordingEmptyView: TextView? = null
    private var packBtn: TextView? = null

    /** 拉取录音列表并渲染（独立于证据包，两者刷新时机不同） */
    private fun fetchRecordings(forceFetch: Boolean = false) {
        if (!GuardConfig.isFamilyBound) return
        val now = System.currentTimeMillis()
        // 首屏与刚被 WS 事件唤醒时不节流，保证子女第一时间看到新录音；
        // 常规 onResume 才做 10s 节流，避免反复切页打接口
        if (!forceFetch && now - lastRecordingFetch < 10_000) return
        lastRecordingFetch = now

        ApiClient.familyGet("/api/recordings/list/${GuardConfig.boundElderId}?group=1",
            onSuccess = { res ->
                val data = res.optJSONObject("data") ?: JSONObject()
                renderRecordings(data)
            },
            onError = { err ->
                // 拉不到要说明原因，否则子女只会看到空列表，以为"没录到"
                val e = view ?: return@familyGet
                if (!e.isShown) return@familyGet
                Toast.makeText(context, "录音列表获取失败：$err", Toast.LENGTH_SHORT).show()
            })
    }

    private fun renderRecordings(data: JSONObject) {
        val root = recordingContainer ?: return
        val empty = recordingEmptyView
        // 先释放上一轮的播放器，避免切换数据时还在播旧的
        playerCards.forEach { it.onDetachFromWindow() }
        playerCards.clear()
        root.removeAllViews()

        val sessions = data.optJSONArray("sessions")
        if (sessions == null || sessions.length() == 0) {
            empty?.visibility = View.VISIBLE
            empty?.text = "暂无录音存证\n\n老人按下紧急求助，或进入你登记的敏感地点时，\n会自动录音并上传到这里。\n\n若老人端提示「录音待发送」，说明还在路上，稍等片刻或下拉刷新。"
            return
        }
        empty?.visibility = View.GONE

        val fraudCount = data.optInt("fraudCount", 0)
        val total = data.optInt("totalRecordings", 0)

        // 统计概览
        root.addView(TextView(context).apply {
            text = "🎙 环境录音存证（共 $total 段" +
                (if (fraudCount > 0) "，其中 $fraudCount 段判定为诈骗）" else "）")
            setTextColor(0xFF1E293B.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(6))
        })

        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            root.addView(buildSessionCard(s))
        }
    }

    /** 一次连续录音 = 一张卡片，内含各分段 */
    private fun buildSessionCard(session: JSONObject): View {
        val ctx = context ?: return View(context)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val reasonLabel = session.optString("reasonLabel", "录音存证")
        val segCount = session.optInt("segmentCount", 1)
        val totalSec = session.optInt("totalDurationMs", 0) / 1000
        // 统一走 TimeText：原来这里是裸字符串 .replace("T"," ").take(16)，
        // 不做时区处理，于是同一张卡片上分组头显示 08:28(UTC)、条目显示 16:28(北京)，
        // 差 8 小时。两条渲染路径行为分叉是事故根因，现已收敛到同一处。
        val startedAt = TimeText.formatFull(session.optString("startedAt", ""))

        val titleColor = when {
            session.optBoolean("isFraud") -> 0xFFB91C1C.toInt()
            session.optBoolean("isSuspect") -> 0xFF92400E.toInt()
            else -> 0xFF1E293B.toInt()
        }
        val flag = when {
            session.optBoolean("isFraud") -> "🚨 检出诈骗对话"
            session.optBoolean("isSuspect") -> "⚠️ 疑似风险"
            else -> "✅ 未发现诈骗"
        }

        card.addView(TextView(ctx).apply {
            text = "$reasonLabel · $segCount 段 · ${totalSec}秒"
            setTextColor(titleColor)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = "$flag｜$startedAt"
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
            setPadding(0, dp(3), 0, dp(4))
        })

        // 各分段的播放卡片
        val recs = session.optJSONArray("recordings")
        if (recs != null) {
            for (i in 0 until recs.length()) {
                val rec = recs.optJSONObject(i) ?: continue
                val pc = RecordingPlayerCard(ctx, rec) { recordingId, keep ->
                    ApiClient.familyPost("/api/recordings/$recordingId/review",
                        JSONObject().put("keep", keep),
                        onSuccess = { lastRecordingFetch = 0L; fetchRecordings() },
                        onError = { Toast.makeText(context, "操作失败：$it", Toast.LENGTH_SHORT).show() })
                }
                playerCards.add(pc)
                card.addView(pc.build())
            }
        }

        return card
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
