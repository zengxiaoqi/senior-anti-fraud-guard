/**
 * /api/ai 路由（对齐本地 routes/ai.js）。
 * 多模态大模型鉴诈分析：关键词命中判定（纯本地规则，无外部调用）。
 */
import { Hono } from 'hono';
import { requireFamilyAuth, type AppEnv } from '../middleware/auth';

export const aiRoutes = new Hono<AppEnv>();

aiRoutes.post('/scan', requireFamilyAuth, async (c) => {
  const { textContent } = await c.req.json().catch(() => ({}) as any);

  // 算法检测高频虚假宣传/诈骗词汇
  const keywords = ['根治', '磁疗', '买一送一', '养生返利', '高额收益', '解冻基金', '公检法'];
  const matched: string[] = [];

  if (textContent) {
    for (const k of keywords) {
      if (String(textContent).includes(k)) matched.push(k);
    }
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

  return c.json({ success: true, data: result });
});
