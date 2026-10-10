/**
 * 录音内容诈骗研判（对齐本地 services/fraudDetector.js）。
 *
 * 证据从严：FRAUD 长期保留；SUSPECT 临时保留；SAFE 短期缓冲后自动清理。
 * 两级判定：规则引擎（纯本地、可复现，主依据）+ LLM 复核（规则判 SAFE 且文本较长时兜底）。
 * SAFE 不是立即删除——老人可能正在被骗中，判错了就找不回来。
 */
import type { Env } from '../env';

interface Pattern {
  label: string;
  weight: number;
  re: RegExp;
}

/** 明确诈骗话术特征：命中即高危 */
const FRAUD_PATTERNS: Pattern[] = [
  { label: '冒充公检法', weight: 60, re: /(公安局|公安|检察院|法院|民警).{0,12}(涉嫌|洗钱|犯罪|通缉|传唤)/ },
  { label: '涉案资金冻结', weight: 55, re: /(资金|账户|养老金|存款).{0,8}(冻结|解冻|清查|监管)/ },
  { label: '要求下载APP配合调查', weight: 50, re: /(下载|安装|打开).{0,10}(APP|app|软件|客户端).{0,15}(核实|调查|配合|做笔录|操作)/ },
  { label: '屏幕共享/远程控制', weight: 65, re: /(屏幕共享|共享屏幕|远程控制|远程协助|投屏|双向屏幕)/ },
  { label: '转账/安全账户', weight: 70, re: /(安全账户|指定账户|资金归集|统一保管|转入.{0,6}(账户|卡))/ },
  { label: '保密要求', weight: 35, re: /(不要|别|禁止).{0,6}(告诉|告知|说给|联系).{0,6}(家人|子女|亲属|儿子|女儿|朋友)/ },
  { label: '索要验证码/密码', weight: 60, re: /(验证码|短信码|支付密码|银行密码|取款密码|密码)/ },
  { label: '高额回报承诺', weight: 55, re: /(稳赚|保本|高收益|零风险|翻倍|年化|回报率).{0,15}(\d|[百分之%])/ },
  { label: '荐股/带单', weight: 55, re: /(老师|导师|专家|操盘手|分析师).{0,10}(带单|荐股|推荐股票|内部消息|免费.{0,4}股)/ },
  { label: '虚拟币投资', weight: 50, re: /(数字货币|虚拟币|USDT|泰拉顿|资金盘|互助盘|拆分)/ },
  { label: '假平台/假交易', weight: 50, re: /(平台|交易所|APP|网站).{0,10}(提现|充值|投资).{0,10}(失败|卡住|需.{0,4}税|解冻)/ },
  { label: '以房养老/保健品非法集资', weight: 50, re: /(以房养老|高息|返利|养老钱|投资养老|原始股|原始基金份额)/ },
  { label: '会销/专家义诊', weight: 40, re: /(专家|名医|教授).{0,8}(现场|免费).{0,8}(讲座|会诊|体检|治疗|义诊)/ },
  { label: '医疗/保健品诱导', weight: 40, re: /(根治|包好|特效|偏方|磁疗|理疗|能停药|不用吃药)/ },
  { label: '低价旅游诱导', weight: 35, re: /(旅游|游玩|一日游).{0,10}(免费|优惠|特价|报销)/ },
  { label: '注销校园贷/征信修复', weight: 55, re: /(注销|清空|修复).{0,8}(校园贷|花呗|白条|征信|贷款|额度)/ },
  { label: '低息放贷', weight: 40, re: /(无抵押|不看征信|秒批|当天放款|黑户可贷|包装资料)/ },
  { label: '以贷收费', weight: 45, re: /(保证金|解冻金|手续费|服务费).{0,6}(转账|先付|交)/ },
  { label: '冒充亲友急事借钱', weight: 60, re: /(我是).{0,6}(你儿子|你女儿|你孙子|你朋友).{0,15}(急|出事了|住院|车祸|借钱|要钱)/ },
  { label: '情感诱导', weight: 30, re: /(网恋|谈恋爱|相亲).{0,10}(见面|投资|带你|带你做)/ },
  { label: '恐吓/威胁', weight: 40, re: /(坐牢|判刑|起诉你|曝光你|你家人有危险|立刻.{0,4}(处理|关闭))/ },
  { label: '限时施压', weight: 25, re: /(今天|马上|立刻|限时|最后).{0,6}(打款|转账|付款|决定|要不)/ }
];

/** 弱信号：单独出现不构成诈骗，但会加权 */
const SUSPICIOUS_PATTERNS: Pattern[] = [
  { label: '提及转账', weight: 15, re: /(转账|汇款|打款|付款|微信转|支付宝转)/ },
  { label: '提及银行卡', weight: 12, re: /(银行卡|储蓄卡|信用卡|卡号|开户行)/ },
  { label: '提及身份证件', weight: 15, re: /(身份证|社保卡|户口本|护照|人脸|正反面)/ },
  { label: '提到陌生链接', weight: 20, re: /(https?:\/\/|www\.|下载链接|二维码|小程序|公众号)/ },
  { label: '提到投资项目', weight: 15, re: /(投资|理财|基金|股票|股权|原始股|认购)/ },
  { label: '要求保密', weight: 12, re: /(保密|别告诉|不要跟|先别说是)/ },
  { label: '催促行动', weight: 10, re: /(快点|抓紧|马上|赶紧|越快越好)/ },
  { label: '索要通话验证码', weight: 30, re: /(我这边马上|稍等|挂断|你听一下).{0,10}(验证码|密码)/ }
];

export function safeRetentionDays(env: Env): number {
  const n = parseInt(String(env.RECORDING_SAFE_RETENTION_DAYS || ''), 10);
  return Number.isFinite(n) && n > 0 ? n : 14;
}

/** 从转写文本推断诈骗方的角色 */
function detectRole(text: string): string {
  const calleeFraud = FRAUD_PATTERNS.some((p) => p.re.test(text));
  const victimComplains = /(他|她|对方|他们|那个人|这个).{0,8}(骗|骗我|让我|要钱|让我转|让我按|让我下载)/.test(text);
  const selfIsVictim = /(我|咱们).{0,6}(被骗|上当|差点|险些)/.test(text);

  if (calleeFraud && victimComplains) return 'caller';
  if (calleeFraud) return 'both';
  if (selfIsVictim) return 'caller';
  return 'both';
}

function buildVerdict(
  status: string,
  fraudHits: Pattern[],
  suspHits: Pattern[],
  score: number,
  retentionDays: number
): string {
  if (status === 'FRAUD') {
    return `判定为诈骗对话（置信分 ${Math.round(score)}）。命中诈骗话术特征：${fraudHits
      .map((h) => h.label)
      .join('、')}。已作为证据长期保留，建议尽快核对扣款并考虑报警。`;
  }
  if (status === 'SUSPECT') {
    const parts: string[] = [];
    if (fraudHits.length) parts.push(`弱化诈骗话术：${fraudHits.map((h) => h.label).join('、')}`);
    if (suspHits.length) parts.push(`可疑行为线索：${suspHits.map((s) => s.label).join('、')}`);
    return `判定为疑似风险对话（置信分 ${Math.round(score)}）。${parts.join('；')}。` +
      `证据临时保留${retentionDays}天，子女端可人工复核后改为长期保留。`;
  }
  return '未发现诈骗话术特征，判定为普通对话。按策略仅短期保留，到期自动清理；' +
    '若子女端认为判定有误，可在到期前人工标记为证据。';
}

export interface Analysis {
  status: 'FRAUD' | 'SUSPECT' | 'SAFE';
  score: number;
  labels: string[];
  role: string | null;
  verdict: string;
  engine?: string;
  retentionDays?: number;
  llmError?: string;
  llmSummary?: string;
}

/** 规则引擎主判定 */
export function analyzeByRules(text: string, retentionDays: number): Analysis {
  const clean = String(text || '').trim();
  if (!clean) {
    return {
      status: 'SAFE',
      score: 0,
      labels: [],
      role: null,
      verdict: '转写文本为空，无法判定内容，暂按非诈骗处理（短期保留，子女端可人工复核）'
    };
  }

  const fraudHits = FRAUD_PATTERNS.filter((p) => p.re.test(clean));
  const suspHits = SUSPICIOUS_PATTERNS.filter((p) => p.re.test(clean));
  const fraudWeight = fraudHits.reduce((n, p) => n + p.weight, 0);
  const suspWeight = suspHits.reduce((n, p) => n + p.weight, 0);

  let score = Math.min(100, fraudWeight * 0.85 + suspWeight * 0.35);
  // 强特征直接越过阈值，避免"高收益+截图+项目"这种组合被权重稀释
  const hasStrongSignal = fraudHits.some((h) => h.weight >= 50);

  let status: Analysis['status'];
  if (fraudHits.length === 0 && suspHits.length === 0) {
    status = 'SAFE';
    score = 0;
  } else if (hasStrongSignal || score >= 55) {
    status = 'FRAUD';
  } else {
    status = 'SUSPECT';
  }

  return {
    status,
    score: Math.round(score),
    labels: [...fraudHits.map((h) => h.label), ...suspHits.map((s) => s.label)],
    role: detectRole(clean),
    verdict: buildVerdict(status, fraudHits, suspHits, score, retentionDays)
  };
}

function buildLLMPrompt(text: string, context: { place_name?: string }): string {
  const place = context.place_name ? `录音场景：${context.place_name}。` : '';
  return [
    '你是电信诈骗与养老诈骗的审查专家。下面是一段通话录音的转写文本，',
    '说话人可能包含老人本人和诈骗方。请判断是否存在诈骗，并只返回 JSON。',
    '',
    `${place}转写文本：`,
    '"""',
    String(text).slice(0, 4000),
    '"""',
    '',
    '返回格式（严格 JSON，不要多余文字）：',
    '{"isFraud":true或false,"confidence":0到100的整数,"labels":["特征标签"],"summary":"一句话结论","advice":"给子女的行动建议"}',
    '',
    '判定要点：涉及转账/验证码/屏幕共享/冒充公检法/高额回报/带单荐股/以房养老/低息放贷等，',
    '只要对方在诱导老人转账或提供敏感信息，即为 true。'
  ].join('\n');
}

async function callLLM(env: Env, prompt: string, maxTokens: number): Promise<string> {
  const baseUrl = String(env.LLM_BASE_URL).replace(/\/+$/, '');
  const timeoutMs = parseInt(String(env.LLM_TIMEOUT_MS || ''), 10) || 45000;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error('LLM 请求超时')), timeoutMs);
  try {
    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: 'POST',
      signal: ctrl.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${env.LLM_API_KEY}`
      },
      body: JSON.stringify({
        model: env.LLM_MODEL || 'gpt-4o-mini',
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.1,
        max_tokens: maxTokens
      })
    });
    const raw = await res.text();
    if (!res.ok) throw new Error(`LLM 返回 HTTP ${res.status}：${raw.slice(0, 200)}`);
    const json = JSON.parse(raw) as { choices?: Array<{ message?: { content?: string } }> };
    const content = json.choices?.[0]?.message?.content;
    if (!content) throw new Error('LLM 返回内容为空');
    return String(content);
  } finally {
    clearTimeout(timer);
  }
}

function parseLLMVerdict(reply: string, fallback: Analysis): Analysis {
  const text = String(reply || '').trim();
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('LLM 未返回可解析的 JSON');
  const obj = JSON.parse(text.slice(start, end + 1)) as {
    isFraud?: boolean;
    confidence?: number;
    labels?: unknown;
    summary?: string;
    advice?: string;
  };
  const isFraud = !!obj.isFraud;
  const conf = Math.max(0, Math.min(100, parseInt(String(obj.confidence ?? (isFraud ? 60 : 10)), 10) || 0));

  // LLM 定性为诈骗但自身置信度不高时，降级为 SUSPECT 走临时保留，
  // 避免模型一次误判就让文件被长期钉在服务器上。
  const status: Analysis['status'] = isFraud ? (conf >= 70 ? 'FRAUD' : 'SUSPECT') : 'SAFE';

  return {
    status,
    score: conf,
    labels: Array.isArray(obj.labels) ? obj.labels.slice(0, 12).map(String) : [],
    role: fallback.role,
    verdict: `${obj.summary || (isFraud ? '大模型判定存在诈骗' : '大模型判定未发现诈骗')}。` +
      `建议：${obj.advice || (isFraud ? '立即联系老人核实并考虑报警' : '保持常规关注')}`,
    llmSummary: obj.summary || ''
  };
}

/**
 * 综合研判入口：规则为主，LLM 仅在规则结论为 SAFE 且文本明显偏长时兜底复核。
 * 理由：SAFE 是要清理文件的结论，必须比 FRAUD 更谨慎。
 */
export async function analyzeTranscript(
  env: Env,
  text: string,
  context: { place_name?: string } = {}
): Promise<Analysis> {
  const retentionDays = safeRetentionDays(env);
  const ruleResult = analyzeByRules(text, retentionDays);
  ruleResult.retentionDays = retentionDays;

  const needsLLM =
    env.LLM_BASE_URL &&
    env.LLM_API_KEY &&
    ruleResult.status === 'SAFE' &&
    String(text || '').length >= 40;

  if (!needsLLM) {
    return { ...ruleResult, engine: 'rules' };
  }

  try {
    const reply = await callLLM(env, buildLLMPrompt(text, context), 500);
    const parsed = parseLLMVerdict(reply, ruleResult);
    return { ...parsed, engine: 'rules+llm' };
  } catch (e) {
    // LLM 不可用不是致命错误，规则结论照常生效
    return { ...ruleResult, engine: 'rules', llmError: (e as Error).message };
  }
}
