package com.antifraud.guard

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.antifraud.guard.api.ApiClient
import org.json.JSONObject

/**
 * AI 拍照/文字识诈页面
 *  - 输入可疑短信/宣传语，调用后端 /api/ai/scan
 *  - 显示风险等级与分析报告
 */
class AiScanActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_scan)

        val etInput   = findViewById<EditText>(R.id.et_scan_input)
        val btnScan   = findViewById<Button>(R.id.btn_do_scan)
        val tvResult  = findViewById<TextView>(R.id.tv_scan_result)

        btnScan.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入需要鉴别的文字内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvResult.text = "🤖 AI 分析中..."
            btnScan.isEnabled = false

            // 调用后端 AI 识诈接口
            Thread {
                try {
                    val url = java.net.URL("${com.antifraud.guard.config.GuardConfig.serverUrl}/api/ai/scan")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    val payload = JSONObject().apply { put("textContent", text) }
                    val writer = java.io.OutputStreamWriter(conn.outputStream, "UTF-8")
                    writer.write(payload.toString())
                    writer.flush()
                    writer.close()

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(response)
                        val data = json.getJSONObject("data")
                        val level   = data.getString("risk_level")
                        val report  = data.getString("analysis_report")
                        val action  = data.getString("suggested_action")
                        val matched = data.getJSONArray("matched_keywords")

                        runOnUiThread {
                            val icon = if (level == "HIGH") "🔴 高危风险" else "🟢 未检测到风险"
                            tvResult.text = """
━━━━━━━━━━━━━━━━━━━━
$icon
━━━━━━━━━━━━━━━━━━━━

📋 分析报告：
$report

⚡ 命中关键词：${if (matched.length() == 0) "无" else (0 until matched.length()).joinToString("、") { matched.getString(it) }}

💡 建议操作：
$action
━━━━━━━━━━━━━━━━━━━━""".trimIndent()
                            btnScan.isEnabled = true
                        }
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
}
