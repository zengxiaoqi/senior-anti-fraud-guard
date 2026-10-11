package com.antifraud.guard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.antifraud.guard.config.GuardConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * AI 拍照/文字识诈页面（2026-10-10 改版）。
 *
 * 旧版两个问题（UI 审查结论）：
 *  1. 首页按钮承诺"拍照识诈"，本页却只有文本框 —— 宣传单恰恰是老人最需要
 *     拍下来鉴别的场景，承诺的能力没兑现。现在补上：拍照/相册 → ML Kit 中文
 *     OCR **离线**识别（bundled 模型，不依赖 GMS，照片不出手机），提取的文字
 *     填进文本框供老人核对后再上传鉴别。
 *  2. 结果用 monospace 代码风字体、没有第一眼结论。现在结论结构化：
 *     红/绿横幅一眼定调，报告正文加大字号去掉等宽。
 *
 * 实现说明：
 *  - 拍照用 ActivityResultContracts.TakePicture + FileProvider（cache/scan/，
 *    file_paths.xml 已放行），不需要 CAMERA 权限（交给系统相机 App）。
 *  - 相册用 GetContent，同样不需要存储权限。
 *  - OCR 失败（模糊/无文字/模型异常）一律 Toast 提示并引导手输，绝不静默。
 */
class AiScanActivity : BaseActivity() {

    private lateinit var etInput: EditText
    private lateinit var btnScan: Button
    private lateinit var tvResult: TextView
    private lateinit var llVerdict: LinearLayout
    private lateinit var tvVerdictTitle: TextView
    private lateinit var tvVerdictDetail: TextView

    /** 拍照输出文件（App 私有 cache，FileProvider 授权给相机 App 写入） */
    private val photoFile: File by lazy {
        val dir = File(cacheDir, "scan").apply { mkdirs() }
        File(dir, "scan_capture.jpg")
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) recognizeTextFromUri(FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile))
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) recognizeTextFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_scan)

        etInput        = findViewById(R.id.et_scan_input)
        btnScan        = findViewById(R.id.btn_do_scan)
        tvResult       = findViewById(R.id.tv_scan_result)
        llVerdict      = findViewById(R.id.ll_verdict)
        tvVerdictTitle = findViewById(R.id.tv_verdict_title)
        tvVerdictDetail = findViewById(R.id.tv_verdict_detail)

        findViewById<View>(R.id.btn_photo).setOnClickListener { takePicture.launch(Uri.fromFile(photoFile)) }
        findViewById<View>(R.id.btn_gallery).setOnClickListener { pickImage.launch("image/*") }

        btnScan.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请拍照、选图或输入需要鉴别的文字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvResult.visibility = View.VISIBLE
            tvResult.text = "🤖 AI 分析中..."
            llVerdict.visibility = View.GONE
            btnScan.isEnabled = false

            // 网络请求沿用旧版的裸线程实现（本页无协程基础设施，改动最小）
            Thread {
                try {
                    val url = java.net.URL("${GuardConfig.serverUrl}/api/ai/scan")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000

                    val payload = JSONObject().apply { put("textContent", text) }
                    val writer = java.io.OutputStreamWriter(conn.outputStream, "UTF-8")
                    writer.write(payload.toString())
                    writer.flush()
                    writer.close()

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(response)
                        // 统一走 opt 系列：旧版 getString 遇缺字段直接 JSONException，
                        // 表现成"永远分析失败"，用户无从知道原因
                        val data = json.optJSONObject("data") ?: JSONObject()
                        val level   = data.optString("risk_level", "UNKNOWN")
                        val conf    = data.optDouble("confidence", 0.0)
                        val report  = data.optString("analysis_report", "（服务端未返回分析报告）")
                        val action  = data.optString("suggested_action", "")
                        val matched = data.optJSONArray("matched_keywords")

                        runOnUiThread { renderResult(level, conf, report, action, matched) }
                    } else {
                        runOnUiThread {
                            tvResult.text = "❌ 分析失败（HTTP ${conn.responseCode}），请检查网络连接。"
                            btnScan.isEnabled = true
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    runOnUiThread {
                        tvResult.text = "❌ 连接后端失败：${e.localizedMessage}"
                        btnScan.isEnabled = true
                    }
                }
            }.start()
        }
    }

    /**
     * OCR：离线识别图片中的中文，提取文字填进输入框供核对。
     * 识别是异步的；fromFilePath 的 IOException 在主线程捕获，识别失败走 onError。
     */
    private fun recognizeTextFromUri(uri: Uri) {
        Toast.makeText(this, "🔍 正在识别图片文字...", Toast.LENGTH_SHORT).show()
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: IOException) {
            Toast.makeText(this, "❌ 读取图片失败，请重试或直接输入文字", Toast.LENGTH_LONG).show()
            return
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "图片里没有识别到文字，请拍清楚一些或直接输入", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                etInput.setText(text)
                etInput.setSelection(text.length)
                Toast.makeText(this, "✅ 已识别 ${text.length} 字，核对无误后点「开始鉴别」", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "❌ 文字识别失败：${it.localizedMessage ?: "请直接输入文字"}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * 结构化渲染结果：红/绿结论横幅 + 报告正文。
     * 结论色用饱和实底 + 白字（状态色，深浅主题都可辨识，不进主题体系）。
     */
    private fun renderResult(level: String, confidence: Double, report: String, action: String, matched: org.json.JSONArray?) {
        btnScan.isEnabled = true
        tvResult.visibility = View.VISIBLE

        val (bg, title, detailColor) = when (level) {
            "HIGH" -> Triple(0xFFDC2626.toInt(), "🚨 很可能是诈骗，请勿转账", 0xFFFFE4E4.toInt())
            "SAFE" -> Triple(0xFF065F46.toInt(), "✅ 未检出已知诈骗话术", 0xFFD1FAE5.toInt())
            else   -> Triple(0xFF334155.toInt(), "⚠️ 暂时无法判断，请谨慎对待", 0xFFE2E8F0.toInt())
        }
        llVerdict.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = 14f * resources.displayMetrics.density
        }
        tvVerdictTitle.text = title
        tvVerdictDetail.text = buildString {
            append("AI 置信度 ${(confidence * 100).toInt()}%")
            if (action.isNotEmpty()) append(" · $action")
        }
        tvVerdictDetail.setTextColor(detailColor)
        llVerdict.visibility = View.VISIBLE

        tvResult.text = buildString {
            append("📋 分析报告：").append("\n").append(report).append("\n\n")
            val kw = matched
            if (kw != null && kw.length() > 0) {
                append("⚡ 命中可疑词：")
                append((0 until kw.length()).joinToString("、") { kw.optString(it) })
                append("\n\n")
            }
            if (level == "HIGH") {
                append("💡 记住三句话：不转账、不透露验证码、先打给子女核实。")
            } else {
                append("💡 若对方提到转账、汇款或索要验证码，请立即停止并告知子女。")
            }
        }
    }
}
