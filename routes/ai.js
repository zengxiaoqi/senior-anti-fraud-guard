const express = require('express');
const router = express.Router();

// 模拟多模态大模型 AI 鉴诈分析接口
router.post('/scan', (req, res) => {
  const { textContent, imageBase64 } = req.body;

  // 算法检测高频虚假宣传/诈骗词汇
  const keywords = ['根治', '磁疗', '买一送一', '养生返利', '高额收益', '解冻基金', '公检法'];
  let matched = [];

  if (textContent) {
    keywords.forEach(k => {
      if (textContent.includes(k)) matched.push(k);
    });
  }

  const isRisk = matched.length > 0;

  const result = {
    risk_level: isRisk ? 'HIGH' : 'SAFE',
    confidence: isRisk ? 0.94 : 0.99,
    matched_keywords: matched,
    analysis_report: isRisk 
      ? `警告：检测到可疑虚假宣传短语 [${matched.join(', ')}]！典型的线下养生讲座诱导与投资骗局，请勿转账付款！`
      : `未检测到已知诈骗模板关键词。但若涉及转账或索要身份证件，请仍保持警惕并咨询家人。`,
    suggested_action: isRisk ? '建议立即离开宣传现场，联系子女。' : '正常阅读'
  };

  res.json({ success: true, data: result });
});

module.exports = router;
