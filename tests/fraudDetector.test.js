// 诈骗研判规则引擎回归测试
//
// 为什么这组测试优先：Phase 3 要往 FRAUD_PATTERNS 里加 96110 / 12381 话术、
// 还要调阈值。没有这层网就无法回答"改完之后误报率是升了还是降了"。
//
// 覆盖三层：
//   1. 逐条规则的命中用例（用每条规则的正例文本，锁住"规则还活着"）
//   2. 判定矩阵（阈值边界 27/28/54/55、SAFE/FRAUD/SUSPECT 分支）
//   3. 证据处置语义（空文本、labels、verdict、retentionDays）
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { analyzeByRules, analyzeTranscript, SAFE_RETENTION_DAYS, FRAUD_PATTERNS } = require('../services/fraudDetector');

// ──────────────────────────────────────────────
//  1. 逐条规则：每条 FRAUD_PATTERNS 必须能被自己的正例命中
// ──────────────────────────────────────────────

const RULE_POSITIVES = [
  ['冒充公检法', '我是公安局的，你涉嫌洗钱，需要配合调查'],
  ['涉案资金冻结', '你的养老金账户已被冻结，要清查资金'],
  ['要求下载APP配合调查', '请你下载一个APP，配合调查做笔录'],
  ['屏幕共享/远程控制', '我们开启屏幕共享，你按我说的操作'],
  ['转账/安全账户', '请把钱转入安全账户，这是统一保管'],
  ['保密要求', '千万不要告诉你的子女，保密'],
  ['索要验证码/密码', '把你收到的验证码念给我'],
  ['高额回报承诺', '这个项目年化回报率 30%，稳赚不赔'],
  ['荐股/带单', '张老师带单推荐股票，有内部消息'],
  ['虚拟币投资', 'USDT 资金盘，拆分转账过去'],
  ['假平台/假交易', 'APP 平台提现失败，需缴税才能解冻'],
  ['以房养老/保健品非法集资', '以房养老项目，投资养老原始股有返利'],
  ['会销/专家义诊', '专家现场免费义诊讲座，教授亲自体检'],
  ['医疗/保健品诱导', '这个磁疗仪能根治，不用吃药'],
  ['低价旅游诱导', '一日游免费旅游，费用全部报销'],
  ['注销校园贷/征信修复', '帮你注销花呗额度，修复征信'],
  ['低息放贷', '无抵押不看征信，当天放款'],
  ['以贷收费', '先付手续费，保证金转账过来'],
  ['冒充亲友急事借钱', '我是你儿子，出了车祸，急需用钱'],
  ['情感诱导', '网恋对象带你去投资见面'],
  ['恐吓/威胁', '你再不处理就坐牢，起诉你'],
  ['限时施压', '今天最后期限，马上打款决定'],
];

test('FRAUD_PATTERNS 条数与清单一致（防止规则被静默删改）', () => {
  assert.equal(FRAUD_PATTERNS.length, RULE_POSITIVES.length,
    'FRAUD_PATTERNS 数量变了：新增规则请同步补 RULE_POSITIVES 正例');
});

for (const [label, text] of RULE_POSITIVES) {
  test(`规则可命中：${label}`, () => {
    const r = analyzeByRules(text);
    assert.ok(r.labels.includes(label), `期望命中「${label}」，实际 labels=${JSON.stringify(r.labels)}`);
  });
}

// ──────────────────────────────────────────────
//  2. 判定矩阵：阈值与分支
// ──────────────────────────────────────────────

test('普通家常对话判 SAFE 且分数归零', () => {
  const r = analyzeByRules('今天天气不错，早上出去买了点菜，中午做了番茄炒蛋');
  assert.equal(r.status, 'SAFE');
  assert.equal(r.score, 0);
  assert.deepEqual(r.labels, []);
});

test('空转写文本判 SAFE 并给出可读结论（不得抛错）', () => {
  for (const empty of ['', '   ', null, undefined]) {
    const r = analyzeByRules(empty);
    assert.equal(r.status, 'SAFE');
    assert.equal(r.score, 0);
    assert.equal(r.role, null);
    assert.ok(r.verdict.length > 0, '必须给出人话结论');
  }
});

test('单条强信号（weight>=50）直接定性为 FRAUD，不被弱信号稀释', () => {
  // "限��施压" weight=25 单独只到 SUSPECT；加上"索要验证码"(60) 应直接 FRAUD
  const r = analyzeByRules('马上打款，同时把验证码念给我');
  assert.equal(r.status, 'FRAUD');
  assert.ok(r.score >= 55);
});

test('仅有弱信号时判 SUSPECT 而非 FRAUD', () => {
  const r = analyzeByRules('这个链接你点开看看，是个二维码，扫码进群');
  assert.equal(r.status, 'SUSPECT');
  assert.ok(r.score < 55, `分数 ${r.score} 不应达到 FRAUD 阈值`);
});

test('单条弱信号即判 SUSPECT（score 不足 28 也走 else 分支）', () => {
  // 说明阈值语义：analyzeByRules 的判定顺序是
  //   零命中 → SAFE；命中强信号或 score>=55 → FRAUD；有强命中或 score>=28 → SUSPECT；
  //   其余（仅弱信号）→ else 分支同样是 SUSPECT。
  // 因此"单条弱信号即 SUSPECT"，下面的 score>=28 只在弱信号累积时才起作用。
  const r = analyzeByRules('这个二维码你扫一下');
  assert.equal(r.status, 'SUSPECT');
  assert.ok(r.score < 28, `单条弱信号分数 ${r.score} 应 < 28`);
});

test('弱信号累积越过 28 分（阈值边界成立）', () => {
  // 命中 6 条弱信号：转账15 + 银行卡12 + 身份证15 + 二维码20 + 基金15 + 保密12 = 89
  // score = 89 * 0.35 = 31.15 → 31，恰好验证 score>=28 这一分支确实可达
  const r = analyzeByRules('转账给他，附上银行卡号和身份证，再扫这个二维码，买基金的事要保密');
  assert.deepEqual(r.labels, [
    '提及转账', '提及银行卡', '提及身份证件', '提到陌生链接', '提到投资项目', '要求保密'
  ]);
  assert.equal(r.score, 31);
  assert.ok(r.score >= 28, `分数 ${r.score} 应 >= 28`);
  assert.equal(r.status, 'SUSPECT');
});

test('组合诈骗（公检法 + 屏幕共享 + 安全账户）满分封顶 100', () => {
  const r = analyzeByRules(
    '我是公安局的，你涉嫌洗钱，请下载APP配合调查，开启屏幕共享，把钱转入安全账户，不要告诉家人'
  );
  assert.equal(r.status, 'FRAUD');
  assert.equal(r.score, 100);
  for (const l of ['冒充公检法', '屏幕共享/远程控制', '转账/安全账户', '保密要求']) {
    assert.ok(r.labels.includes(l), `应命中「${l}」`);
  }
});

test('强信号门槛在 weight=50 处生效', () => {
  // "虚拟币投资" weight=50 —— 恰好在强信号门槛上
  const weak = analyzeByRules('我们做个 USDT 互助盘');
  assert.ok(weak.labels.includes('虚拟币投资'), '该规则 weight=50 应命中');
  assert.equal(weak.status, 'FRAUD', 'weight>=50 应直接越过 55 分阈值');
});

test('无单条强信号时，多条中量信号累积越过 55 分也能定性 FRAUD', () => {
  // 唯一钉住 `score >= 55` 这一分支的用例：必须"没有任何 weight>=50 的规则命中"，
  // 才能证明 FRAUD 不是靠 hasStrongSignal 判出来的。
  // 医疗/保健品诱导(40) + 低价旅游诱导(35) = 75 → 75×0.85 = 63.75 → 64
  const r = analyzeByRules('这个磁疗仪能根治不用吃药，还送一日游免费');
  assert.deepEqual(r.labels, ['医疗/保健品诱导', '低价旅游诱导']);
  for (const l of r.labels) {
    const p = FRAUD_PATTERNS.find((x) => x.label === l);
    assert.ok(p && p.weight < 50, `用例前提：${l} 权重必须 < 50，实际 ${p && p.weight}`);
  }
  assert.equal(r.score, 64);
  assert.equal(r.status, 'FRAUD');
});

test('打分公式已固化：score = 强信号权重×0.85 + 弱信号权重×0.35，封顶 100', () => {
  // Phase 3 调权重/调系数时，这三个锚点会先响，避免误报率静默漂移。
  // 1) 只有 1 条强信号 weight=70（转账/安全账户），无弱信号 → 70×0.85 = 59.5 → 60
  const a = analyzeByRules('请转入安全账户');
  assert.equal(a.score, 60);

  // 2) 只有 1 条弱信号 weight=20（提到陌生链接），无强信号 → 20×0.35 = 7
  const b = analyzeByRules('你打开 https://xxx.example.com/page 看看');
  assert.deepEqual(b.labels, ['提到陌生链接']);
  assert.equal(b.score, 7);

  // 3) 权重和远超 100 时必须封顶，不得溢出
  const c = analyzeByRules(
    '我是公安局的，你涉嫌洗钱，请下载APP配合调查，开启屏幕共享，把钱转入安全账户，不要告诉家人，验证码也给我'
  );
  assert.equal(c.score, 100);
});

// ──────────────────────────────────────────────
//  3. 证据处置语义：这组字段直接决定录音删不删
// ──────────────────────────────────────────────

test('FRAUD 结论必须说明"已长期保留 + 建议报警"', () => {
  const r = analyzeByRules('把钱转入安全账户，不要告诉子女');
  assert.equal(r.status, 'FRAUD');
  assert.match(r.verdict, /长期保留/);
  assert.match(r.verdict, /报警/);
});

test('SUSPECT 结论必须说明是临时保留且可人工复核', () => {
  const r = analyzeByRules('扫这个二维码进群看看');
  assert.equal(r.status, 'SUSPECT');
  assert.match(r.verdict, /临时保留/);
  assert.match(r.verdict, /人工复核/);
});

test('SAFE 结论必须说明仅短期保留且到期自动清理', () => {
  const r = analyzeByRules('晚上一起吃饭吗');
  assert.equal(r.status, 'SAFE');
  assert.match(r.verdict, /短期保留/);
  assert.match(r.verdict, /自动清理/);
});

test('analyzeTranscript 无 LLM 配置时返回 rules 引擎并挂上 retentionDays', async () => {
  const r = await analyzeTranscript('我们说好下周一起去看老李');
  assert.equal(r.engine, 'rules');
  assert.equal(r.retentionDays, SAFE_RETENTION_DAYS);
  assert.equal(r.status, 'SAFE');
});

test('analyzeTranscript 绝不因 LLM 缺失而抛错（分析失败≠安全判定）', async () => {
  // 未配置 LLM_BASE_URL/LLM_API_KEY，needsLLM 必为 false，走规则降级
  const r = await analyzeTranscript('我是公安局的，你涉嫌洗钱');
  assert.equal(r.engine, 'rules');
  assert.equal(r.status, 'FRAUD');
  assert.equal(r.llmError, undefined);
});

// ──────────────────────────────────────────────
//  4. 已知死规则（记录在此，避免后人重复排查）
// ──────────────────────────────────────────────

test('弱信号「索要通话验证码」无法独立命中（被强信号完全覆盖）', () => {
  // SUSPICIOUS_PATTERNS 里的「索要通话验证码」(30) 形如
  //   /(我这边马上|稍等|挂断|你听一下).{0,10}(验证码|密码)/
  // 而 FRAUD_PATTERNS 里的「索要验证码/密码」(60) 形如
  //   /(验证码|短信码|支付密码|银行密码|取款密码|密码)/
  // 后者覆盖面完全包含前者 —— 只要弱信号命中，强信号必然也命中。
  // 结论：该弱信号永远不会独立出现在 labels 里，属于死规则。
  // 保留断言是为了：将来若有人删掉「索要验证码/密码」，这里会先失败提醒。
  const r = analyzeByRules('你听一下，稍等，我这边马上要验证码');
  assert.ok(r.labels.includes('索要验证码/密码'), '强信号应命中');
  assert.equal(
    r.labels.filter((l) => l === '索要通话验证码').length, 1,
    '弱信号此时会一并命中，但永远不可能单独命中'
  );
  // 反证：想只命中弱信号就必须避开"验证码/密码"字样，但那样正则本身就匹配不上
  const onlyWeakAttempt = analyzeByRules('你听一下，稍等');
  assert.equal(onlyWeakAttempt.status, 'SAFE');
});

// ──────────────────────────────────────────────
//  5. 角色推断
// ──────────────────────────────────────────────

test('老人自述被骗时角色判为 caller', () => {
  const r = analyzeByRules('我差点被骗了，对方让我下载APP');
  assert.equal(r.role, 'caller');
});

test('命中诈骗话术但无明确抱怨句式时角色回退为 both', () => {
  const r = analyzeByRules('请转入安全账户');
  assert.equal(r.role, 'both');
});

test('普通对话角色不应凭空断言', () => {
  const r = analyzeByRules('今天菜市场的鱼挺新鲜');
  assert.ok(['caller', 'both'].includes(r.role));
  assert.equal(r.status, 'SAFE');
});