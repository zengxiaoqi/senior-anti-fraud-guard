const test = require('node:test');
const assert = require('node:assert');
const {
  evaluateLiveness,
  nextAlertState,
  normalizeThresholds,
  parseSqliteTime,
  extractLivenessThresholds,
  LIVENESS_ONLINE,
  LIVENESS_SILENT,
  LIVENESS_OFFLINE,
  DEFAULT_WARN_MINUTES,
  DEFAULT_OFFLINE_MINUTES
} = require('../services/elderLiveness');

test('正常上报周期内判为在线', () => {
  // 老人端定位心跳 10 分钟一条，正常活动下最近一次上报应该在半小时内
  assert.strictEqual(evaluateLiveness(0).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness(10).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness(DEFAULT_WARN_MINUTES - 1).state, LIVENESS_ONLINE);
});

test('超过静默阈值判 SILENT，超过失联阈值判 OFFLINE', () => {
  assert.strictEqual(evaluateLiveness(DEFAULT_WARN_MINUTES).state, LIVENESS_SILENT);
  assert.strictEqual(evaluateLiveness(DEFAULT_OFFLINE_MINUTES - 1).state, LIVENESS_SILENT);
  assert.strictEqual(evaluateLiveness(DEFAULT_OFFLINE_MINUTES).state, LIVENESS_OFFLINE);
  assert.strictEqual(evaluateLiveness(DEFAULT_OFFLINE_MINUTES + 600).state, LIVENESS_OFFLINE);
});

test('从未上报过不判失联（避免新绑定用户被误报）', () => {
  // 老人可能整天不出门、不通话，一天都没有任何事件。
  // 若把 null 直接当"失联"，绑定第二天清晨全网用户都会收到"老人失联"告警。
  assert.strictEqual(evaluateLiveness(null).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness(undefined).state, LIVENESS_ONLINE);
});

test('非法/负数分钟数安全退化为在线', () => {
  // 绝不能让 NaN 参与比较：那会让判定静默退化成"永远在线"，
  // 也就是把整条失联告警线悄悄关掉，而界面上看不出任何异常。
  assert.strictEqual(evaluateLiveness(-5).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness(NaN).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness('abc').state, LIVENESS_ONLINE);
});

test('阈值非法时回退默认，且 offline 恒大于 warn', () => {
  assert.deepStrictEqual(normalizeThresholds(null), {
    warnMinutes: DEFAULT_WARN_MINUTES,
    offlineMinutes: DEFAULT_OFFLINE_MINUTES
  });

  // 用户在设置页填了一组不自洽的值（offline < warn），
  // 必须被夹开而不是照单执行 —— 否则会出现"同一分钟先判 SILENT 再判 OFFLINE"的双告警
  const fixed = normalizeThresholds({ warnMinutes: 120, offlineMinutes: 60 });
  assert.ok(fixed.offlineMinutes > fixed.warnMinutes, 'offline 必须严格大于 warn');

  const equal = normalizeThresholds({ warnMinutes: 90, offlineMinutes: 90 });
  assert.ok(equal.offlineMinutes > equal.warnMinutes, '相等时也要拉开间隔');

  assert.strictEqual(normalizeThresholds({ warnMinutes: 0 }).warnMinutes, DEFAULT_WARN_MINUTES);
  assert.strictEqual(normalizeThresholds({ warnMinutes: 'x' }).warnMinutes, DEFAULT_WARN_MINUTES);
  assert.strictEqual(normalizeThresholds({ warnMinutes: 9999 }).warnMinutes, 240, '超上界要夹住');
  assert.strictEqual(normalizeThresholds({ offlineMinutes: 99999 }).offlineMinutes, 1440);
});

test('自定义阈值生效', () => {
  const t = { warnMinutes: 30, offlineMinutes: 60 };
  assert.strictEqual(evaluateLiveness(29, t).state, LIVENESS_ONLINE);
  assert.strictEqual(evaluateLiveness(30, t).state, LIVENESS_SILENT);
  assert.strictEqual(evaluateLiveness(60, t).state, LIVENESS_OFFLINE);
});

test('告警节流：同一级别只报一次（防子女端被刷屏）', () => {
  // 这是整条链路最容易被写错的地方：扫描每 5 分钟一轮，
  // 不节流的话一条失联会被重复推 12 次/小时。
  assert.deepStrictEqual(nextAlertState(LIVENESS_SILENT, null), {
    shouldAlert: true, eventType: 'ELDER_SILENT', severity: 'MEDIUM'
  });
  assert.deepStrictEqual(nextAlertState(LIVENESS_SILENT, LIVENESS_SILENT), {
    shouldAlert: false, eventType: null, severity: null
  });
  assert.deepStrictEqual(nextAlertState(LIVENESS_OFFLINE, LIVENESS_OFFLINE), {
    shouldAlert: false, eventType: null, severity: null
  });
});

test('告警升级 SILENT→OFFLINE 必须再报一次 HIGH', () => {
  // 情况变严重了，子女必须看到第二次提醒，否则会以为"还是上次那个程度"
  assert.deepStrictEqual(nextAlertState(LIVENESS_OFFLINE, LIVENESS_SILENT), {
    shouldAlert: true, eventType: 'ELDER_OFFLINE', severity: 'HIGH'
  });
});

test('恢复上报后发一条 RECONNECTED 并可再次告警', () => {
  assert.deepStrictEqual(nextAlertState(LIVENESS_ONLINE, LIVENESS_OFFLINE), {
    shouldAlert: true, eventType: 'ELDER_RECONNECTED', severity: 'LOW'
  });
  // 从未告警过就恢复正常 → 不需要发恢复事件（没什么可"恢复"的）
  assert.deepStrictEqual(nextAlertState(LIVENESS_ONLINE, null), {
    shouldAlert: false, eventType: null, severity: null
  });
  // 恢复后节流状态清空，下一轮失联能重新告警
  assert.deepStrictEqual(nextAlertState(LIVENESS_SILENT, null), {
    shouldAlert: true, eventType: 'ELDER_SILENT', severity: 'MEDIUM'
  });
});

test('只有 OFFLINE 会被打断子女，SILENT 不打断（防告警疲劳）', () => {
  // 老人晚上睡觉连续几小时没上报是常态，
  // 按 HIGH 弹窗会让人在深夜被无意义提醒吵醒，之后所有告警都被静音 —— 漏报方向。
  const { shouldInterrupt } = require('../services/riskAlertPolicy');
  assert.strictEqual(shouldInterrupt('ELDER_OFFLINE', 'HIGH'), true);
  assert.strictEqual(shouldInterrupt('ELDER_SILENT', 'MEDIUM'), false);
  assert.strictEqual(shouldInterrupt('ELDER_SILENT', 'HIGH'), false, 'SILENT 即便被误标 HIGH 也不打断');
  assert.strictEqual(shouldInterrupt('ELDER_RECONNECTED', 'LOW'), false);
});

test('FALL_RISK 一律打断子女', () => {
  const { shouldInterrupt } = require('../services/riskAlertPolicy');
  assert.strictEqual(shouldInterrupt('FALL_RISK', 'HIGH'), true);
  assert.strictEqual(
    shouldInterrupt('FALL_RISK', 'LOW'), false,
    '降级情况不打断，但服务端已把 FALL_RISK 强制归一化成 HIGH'
  );
});

test('SQLite 裸时间串按 UTC 解析（否则差 8 小时）', () => {
  // CURRENT_TIMESTAMP 产出 'YYYY-MM-DD HH:MM:SS'，无时区标记。
  // 若被当本地时解析，同一条记录在东八区会算成 8 小时前，
  // 于是"刚上报"被判成"失联 8 小时"。
  const utc = parseSqliteTime('2026-10-10 04:59:00');
  assert.strictEqual(utc, Date.parse('2026-10-10T04:59:00Z'));

  // 已带时区标记的不重复补 Z
  assert.strictEqual(parseSqliteTime('2026-10-10T04:59:00Z'), Date.parse('2026-10-10T04:59:00Z'));
  assert.strictEqual(parseSqliteTime('2026-10-10T04:59:00.123Z'), Date.parse('2026-10-10T04:59:00.123Z'));
  // 带偏移量的也要正确
  assert.strictEqual(parseSqliteTime('2026-10-10 12:59:00+08:00'), Date.parse('2026-10-10T12:59:00+08:00'));
});

test('时间解析失败返回 null 而不是 Invalid Date', () => {
  assert.strictEqual(parseSqliteTime(null), null);
  assert.strictEqual(parseSqliteTime(undefined), null);
  assert.strictEqual(parseSqliteTime(''), null);
  assert.strictEqual(parseSqliteTime('   '), null);
  assert.strictEqual(parseSqliteTime('not-a-date'), null);
});

test('坏 guard_settings 当作未配置（不让一行脏数据关掉整条告警线）', () => {
  assert.strictEqual(extractLivenessThresholds(null), null);
  assert.strictEqual(extractLivenessThresholds('{broken json'), null);
  assert.strictEqual(extractLivenessThresholds('{}'), null);
  assert.strictEqual(extractLivenessThresholds('{"callThresholdMinutes":15}'), null);
  assert.deepStrictEqual(
    extractLivenessThresholds('{"liveness":{"warnMinutes":45,"offlineMinutes":120}}'),
    { warnMinutes: 45, offlineMinutes: 120 }
  );
  // 已是对象时也接受（Worker 侧 D1 读取路径）
  assert.deepStrictEqual(
    extractLivenessThresholds({ liveness: { warnMinutes: 45, offlineMinutes: 120 } }),
    { warnMinutes: 45, offlineMinutes: 120 }
  );
});
