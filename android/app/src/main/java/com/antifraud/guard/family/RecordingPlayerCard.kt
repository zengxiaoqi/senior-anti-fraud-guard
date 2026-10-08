package com.antifraud.guard.family

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.SeekBar
import android.widget.TextView
import com.antifraud.guard.api.ApiClient
import org.json.JSONObject
import com.antifraud.guard.util.TimeText

/**
 * 单条录音的播放卡片。
 *
 * 播放地址带服务端签名（query token），因为 MediaPlayer 不支持自定义请求头，
 * 无法走 X-Auth-Token。令牌 30 分钟过期，所以播完或超时要重新拉列表换新地址。
 *
 * 同时展示 AI 研判结论与转写文本 —— 子女最关心的不是"有一段录音"，
 * 而是"这段录音里到底发生了什么"。
 */
class RecordingPlayerCard(
    private val ctx: Context,
    private val rec: JSONObject,
    private val onReview: (recordingId: Int, keep: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "RecordingPlayer"
    }

    private var player: MediaPlayer? = null
    private var seekBar: SeekBar? = null
    private var playBtn: TextView? = null
    private var timeLabel: TextView? = null
    private var progressRunnable: Runnable? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 构建卡片视图 */
    fun build(): android.view.View {
        val pad = dp(12)
        val card = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(com.antifraud.guard.R.drawable.bg_card)
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // ── 标题行：编号 + 触发来源 + 研判标签 ──
        val id = rec.optInt("id", 0)
        val reasonLabel = rec.optString("reasonLabel", "录音")
        val header = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "🎙 录音 #$id · $reasonLabel"
            setTextColor(0xFF1E293B.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(26), 1f)
        })
        header.addView(TextView(ctx).apply {
            text = fraudBadgeText()
            setTextColor(fraudBadgeColor())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFFEE2E2.toInt())
            }
        })
        card.addView(header)

        // ── 时间 / 时长 / 大小 / 位置 ──
        val recordedAt = formatTime(rec.optString("recordedAt", ""))
        val durationSec = rec.optInt("durationMs", 0) / 1000
        val sizeKb = rec.optInt("sizeBytes", 0) / 1024
        val address = rec.optString("address", "")
        val meta = buildString {
            append("🕐 $recordedAt · ${durationSec}秒 · ${sizeKb}KB")
            if (address.isNotEmpty()) append("\n📍 $address")
        }
        card.addView(TextView(ctx).apply {
            text = meta
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        // ── AI 研判结论 ──
        val verdict = rec.optString("fraudVerdict", "")
        if (verdict.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = "🤖 AI 研判：$verdict"
                setTextColor(fraudBadgeColor())
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        }

        // ── 命中的诈骗特征标签 ──
        val labels = rec.optJSONArray("fraudLabels")
        if (labels != null && labels.length() > 0) {
            val labelText = (0 until labels.length()).joinToString("、") { labels.optString(it) }
            card.addView(TextView(ctx).apply {
                text = "🏷 命中特征：$labelText"
                setTextColor(0xFF92400E.toInt())
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }

        // ── 转写文本 ──
        card.addView(buildTranscriptView())

        // ── 播放控制行 ──
        card.addView(buildPlayerRow())

        // ── 操作行：保留为证据 / 立即删除 ──
        card.addView(buildActionRow(id))

        // ── 到期清理提示（必须独立成行：挤在横向按钮行里会被截断显示不全）──
        buildRetentionHint()?.let { card.addView(it) }

        return card
    }

    private fun buildTranscriptView(): android.view.View {
        val transcript = rec.optString("transcript", "")
        val status = rec.optString("transcriptStatus", "PENDING")

        return when {
            transcript.isNotEmpty() -> TextView(ctx).apply {
                text = "📝 转写内容：\n$transcript"
                setTextColor(0xFF334155.toInt())
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFF1F5F9.toInt())
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            status == "PENDING" -> TextView(ctx).apply {
                text = "📝 录音转写与 AI 分析中，请稍候…"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
            else -> TextView(ctx).apply {
                val err = rec.optString("transcriptError", "未配置语音转写服务")
                text = "📝 未转写：$err\n可直接点播放收听人工判断。"
                setTextColor(0xFF92400E.toInt())
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            }
        }
    }

    private fun buildPlayerRow(): android.view.View {
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        playBtn = TextView(ctx).apply {
            text = "▶ 播放"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF2563EB.toInt())
            }
            setOnClickListener { togglePlay() }
        }
        row.addView(playBtn)

        seekBar = SeekBar(ctx).apply {
            max = 1000
            visibility = android.view.View.GONE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val p = player
                        if (p != null && p.duration > 0) {
                            p.seekTo(progress * p.duration / 1000)
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(seekBar, android.widget.LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            leftMargin = dp(10)
        })

        timeLabel = TextView(ctx).apply {
            text = ""
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
        }
        row.addView(timeLabel)

        return row
    }

    private fun buildActionRow(id: Int): android.view.View {
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val keep = rec.optBoolean("keepAsEvidence", false)
        val reviewed = rec.optBoolean("reviewedByFamily", false)

        row.addView(TextView(ctx).apply {
            text = if (keep && reviewed) "✅ 已标记为证据" else "⭐ 标记为证据"
            setTextColor(0xFF15803D.toInt())
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFDCFCE7.toInt())
            }
            setOnClickListener {
                onReview(id, !keep)
                Toast2.show(ctx, if (keep) "已取消证据标记" else "已标记为证据，将长期保留")
            }
        })

        row.addView(TextView(ctx).apply {
            text = "🗑 删除"
            setTextColor(0xFFB91C1C.toInt())
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                ApiClient.familyPost("/api/recordings/$id/review",
                    JSONObject().put("keep", false).put("note", "子女端删除该录音"),
                    onSuccess = { releasePlayer(); Toast2.show(ctx, "已请求删除该录音") },
                    onError = { Toast2.show(ctx, "操作失败：$it") })
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) }
        })

        return row
    }

    /**
     * 到期自动清理提示。
     *
     * 必须单独占一整行：之前它被塞进上面的**横向**按钮行里，
     * 横向行放不下整句+ 换行符，文字被压掉一半只露出上半截。
     */
    private fun buildRetentionHint(): android.view.View? {
        val keep = rec.optBoolean("keepAsEvidence", false)
        val reviewed = rec.optBoolean("reviewedByFamily", false)

        // 已标记为证据 / 已人工复核 = 长期保留，不再提示清理时间，
        // 但明确告诉子女"为什么不会被删"，避免误以为系统会自动清掉关键证据。
        if (keep || reviewed) {
            return TextView(ctx).apply {
                text = "🔒 已人工确认，长期保留，不会自动清理"
                setTextColor(0xFF15803D.toInt())
                textSize = 11f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = pill(0xFFDCFCE7.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
        }

        val retention = rec.optString("retentionUntil", "")
        if (retention.isEmpty()) return null

        val remain = remainingText(retention)
        return TextView(ctx).apply {
            text = "🗓 $remain 自动清理 · 到期时间 ${formatTime(retention)}"
            setTextColor(0xFF92400E.toInt())
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill(0xFFFEF3C7.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    /** 剩余清理时间的口语化描述，避免只给一个裸时间戳让人自己算 */
    /** 保留期倒计时：委托给 TimeText，保证全 App 只有一份时区/进位规则 */
    private fun remainingText(iso: String): String = TimeText.remainingText(iso)

    private fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    /**
     * 解析服务端下发的时间为绝对时刻（epoch millis）。
     *
     * retention_until / recorded_at 是 ISO UTC（结尾带 Z），**必须**按 UTC 解析，
     * 否则会被当成本地时间解析，"还剩 N 天" 整体偏 8 小时。
     * 不带时区的串按北京时间解析。
     *
     * 注意：Z 在毫秒之后（...37.000Z），所以要先判 Z 再截小数，不能反过来。
     */

    // ──────────────────────────────────────────
    //  播放控制
    // ──────────────────────────────────────────

    private fun togglePlay() {
        val p = player
        if (p != null) {
            if (p.isPlaying) pause() else resume()
            return
        }
        startPlayback()
    }

    private fun startPlayback() {
        val id = rec.optInt("id", 0)
        val url = "${ApiClient.getBaseUrl()}${rec.optString("streamUrl", "")}"
        if (rec.optString("streamUrl", "").isEmpty()) {
            Toast2.show(ctx, "播放地址已过期，请下拉刷新列表")
            return
        }

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener {
                    seekBar?.visibility = android.view.View.VISIBLE
                    start()
                    updatePlayBtn(true)
                    startProgressTicker()
                }
                setOnCompletionListener {
                    updatePlayBtn(false)
                    stopProgressTicker()
                    seekBar?.progress = 0
                    timeLabel?.text = "已播完"
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "播放失败 what=$what extra=$extra")
                    // 令牌过期是最常见原因，提示刷新
                    Toast2.show(ctx, "播放失败，链接可能已过期，请刷新列表")
                    releasePlayer()
                    true
                }
                prepareAsync()
            }
            playBtn?.text = "⏳ 加载中"
        } catch (e: Exception) {
            Log.e(TAG, "播放异常: ${e.message}")
            Toast2.show(ctx, "无法播放该录音")
            releasePlayer()
        }
    }

    private fun pause() {
        player?.pause()
        updatePlayBtn(false)
        stopProgressTicker()
    }

    private fun resume() {
        player?.start()
        updatePlayBtn(true)
        startProgressTicker()
    }

    private fun updatePlayBtn(playing: Boolean) {
        playBtn?.text = if (playing) "⏸ 暂停" else "▶ 播放"
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        val runnable = object : Runnable {
            override fun run() {
                val p = player ?: return
                if (p.duration > 0) {
                    val pos = p.currentPosition
                    seekBar?.progress = pos * 1000 / p.duration
                    timeLabel?.text = "${formatDuration(pos)} / ${formatDuration(p.duration)}"
                }
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressTicker() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun releasePlayer() {
        stopProgressTicker()
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        seekBar?.visibility = android.view.View.GONE
    }

    /** Fragment 离开时调用，释放播放器避免后台继续播 */
    fun onDetachFromWindow() {
        releasePlayer()
    }

    // ──────────────────────────────────────────
    //  展示辅助
    // ──────────────────────────────────────────

    private fun fraudBadgeText(): String {
        if (rec.optBoolean("keepAsEvidence", false)) return "已保留为证据"
        return when (rec.optString("fraudStatus", "PENDING")) {
            "FRAUD" -> "判定诈骗"
            "SUSPECT" -> "疑似风险"
            "SAFE" -> "未发现诈骗"
            "FAILED" -> "分析失败"
            "ANALYZING" -> "分析中"
            else -> "待分析"
        }
    }

    private fun fraudBadgeColor(): Int = when (rec.optString("fraudStatus", "PENDING")) {
        "FRAUD" -> 0xFFB91C1C.toInt()
        "SUSPECT" -> 0xFF92400E.toInt()
        "SAFE" -> 0xFF15803D.toInt()
        else -> 0xFF64748B.toInt()
    }

    /**
     * 展示服务端时间（统一按北京时间）。
     *
     * 两种来源必须分开处理，否则会差 8 小时：
     * - 带 Z 的 ISO 串（recorded_at / retention_until，库里存的是 UTC）→ 按 UTC 解析再转北京时间
     * - 不带时区的 "YYYY-MM-DD HH:mm:ss"（服务端已转好的北京时间）→ 直接当北京时间
     */
  /** "MM-dd HH:mm"：委托给 TimeText，避免与分组头再出现 8 小时偏差 */
    private fun formatTime(iso: String): String = TimeText.formatShort(iso)

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}

/** 简易 Toast 包装，避免在每个卡片里重复 import Toast */
object Toast2 {
    fun show(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
