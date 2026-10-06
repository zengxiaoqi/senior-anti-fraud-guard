package com.antifraud.guard.family

import android.content.Intent
import android.net.Uri
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

/**
 * 防护指南：常见诈骗套路 / 紧急联系方式（一键拨打）/ 应对步骤
 * 对齐小程序 pages/guide/guide
 */
class GuideFragment : Fragment() {

    private var phoneLoaded = false

    private lateinit var container: LinearLayout
    private var tvElderPhone: TextView? = null

    private data class FraudType(val name: String, val desc: String, val color: String)

    private val fraudTypes = listOf(
        FraudType("冒充公检法", "自称警察/检察官，涉嫌洗钱，要求转账至安全账户", "#EF4444"),
        FraudType("虚假投资理财", "承诺高收益零风险，诱导下载APP或转账", "#F59E0B"),
        FraudType("保健品骗局", "免费体检/专家义诊，夸大疗效诱导购买高价药品", "#8B5CF6"),
        FraudType("冒充熟人借钱", "盗用子女/亲友头像手机号，紧急借钱", "#EC4899"),
        FraudType("中奖/退税诈骗", "通知中奖或退税，要求先交手续费/保证金", "#14B8A6"),
        FraudType("网络贷款诈骗", "低息贷款诱导，要求先交押金/流水费", "#F97316")
    )

    private val guideSteps = listOf(
        "① 保持冷静：遇到紧急情况先深呼吸，不要慌张，给子女打电话确认",
        "② 多方核实：公检法不会电话办案，所有要求转账的都是诈骗",
        "③ 保护信息：不透露银行卡号、密码、短信验证码给任何人",
        "④ 及时报警：发现被骗立即拨打110，保存好转账凭证和聊天记录"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_guide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        this.container = view.findViewById(R.id.ll_guide_container)
        buildContent()
    }

    private fun buildContent() {
        container.removeAllViews()

        addSectionTitle("🛡️ 六大常见诈骗套路")

        fraudTypes.forEach { t ->
            addCard {
                addView(TextView(requireContext()).apply {
                    text = t.name
                    setTextColor(android.graphics.Color.parseColor(t.color))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(requireContext()).apply {
                    text = t.desc
                    setTextColor(0xFF475569.toInt())
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }

        addSectionTitle("🚨 紧急联系方式")
        val contactsCardView = addCard { }
        contactsCardView.addView(TextView(requireContext()).apply {
            setPadding(0, dp(2), 0, dp(2))
            text = "点击「拨打」可直接呼出电话"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        })
        addContactRow(contactsCardView, "🚔 报警电话", "110")
        addContactRow(contactsCardView, "📞 反诈专线", "96110")
        addContactRow(contactsCardView, "🏦 银行客服", "95588")
        // 老人手机（绑定后从服务端拉取）
        contactsCardView.addView(TextView(requireContext()).apply {
            setPadding(0, dp(10), 0, dp(2))
            text = "👴 老人手机"
            setTextColor(0xFF1E293B.toInt())
            textSize = 14f
        })
        tvElderPhone = TextView(requireContext()).apply {
            text = if (GuardConfig.isFamilyBound) "加载中..." else "未绑定老人"
            setTextColor(0xFF3B82F6.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
            setOnClickListener {
                val raw = text.toString()
                val number = if (raw.contains(Regex("\\d{5,}"))) raw.filter { it.isDigit() } else ""
                if (number.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                } else {
                    Toast.makeText(context, "未绑定手机号", Toast.LENGTH_SHORT).show()
                }
            }
        }
        contactsCardView.addView(tvElderPhone)

        addSectionTitle("📖 遇到诈骗怎么办")
        addCard {
            guideSteps.forEach { step ->
                addView(TextView(requireContext()).apply {
                    text = step
                    setTextColor(0xFF475569.toInt())
                    textSize = 13f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, 0, 0, dp(6))
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadElderPhone()
    }

    /** 老人手机号本次会话只拉一次（对齐小程序） */
    private fun loadElderPhone() {
        if (phoneLoaded || !GuardConfig.isFamilyBound) return
        ApiClient.familyGet("/api/auth/user/${GuardConfig.boundElderId}", onSuccess = { data ->
            val phone = data.optString("phone", "")
            if (phone.isNotEmpty() && !phone.startsWith("wx_") && phone != GuardConfig.familyUsername) {
                tvElderPhone?.text = phone
                phoneLoaded = true
            } else {
                tvElderPhone?.text = "未登记手机号"
            }
        }, onError = { tvElderPhone?.text = "获取失败，点击重试" })
    }

    // ── 动态构建 UI 辅助 ──

    private fun addSectionTitle(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(0xFF1E293B.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(10), 0, dp(6))
        })
    }

    private fun addCard(content: LinearLayout.() -> Unit): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.content()
        container.addView(card)
        return card
    }

    private fun addContactRow(card: LinearLayout, name: String, number: String) {
        card.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(requireContext()).apply {
                text = "$name  $number"
                setTextColor(0xFF1E293B.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = "拨打"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setBackgroundResource(R.drawable.bg_btn_primary)
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                }
            })
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
