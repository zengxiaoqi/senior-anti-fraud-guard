const test = require('node:test');
const assert = require('node:assert');
const {
  GUARD_SETTING_BOUNDS,
  sanitizeGuardSettings,
  extractHomeCleared,
  FAMILY_WRITABLE_SETTINGS,
  pickFamilyWritable,
  mergeFamilySettings
} = require('../services/guardSettingsPolicy');

test('白名单放行全部守护规则字段', () => {
  const expected = [
    'callThresholdMinutes', 'paymentThreshold',
    'recordingMaxSegments', 'recordingSegmentMinutes', 'recordingAutoUpload',
    'homeLat', 'homeLng',
    'homeAwayRadiusMeters', 'stayMoveMeters', 'homeStayMinutes'
  ];
  assert.deepStrictEqual(FAMILY_WRITABLE_SETTINGS.slice().sort(), expected.slice().sort());
});

test('信任号码白名单对子女端关闭（信任链是诈骗话术利用的东西）', () => {
  const out = pickFamilyWritable({ trustedCallNumbersJson: '["13800138000"]' });
  assert.strictEqual(out.trustedCallNumbersJson, undefined);
});

test('高危应用规则白名单对子女端关闭（改了会静默缩小告警面）', () => {
  const out = pickFamilyWritable({ highRiskPackages: '[{"pkg":"com.a","level":"HIGH"}]' });
  assert.strictEqual(out.highRiskPackages, undefined);
});

test('白名单外的未知字段一律丢弃', () => {
  const out = pickFamilyWritable({ callThresholdMinutes: 20, evilKey: 'rm -rf /' });
  assert.strictEqual(out.callThresholdMinutes, 20);
  assert.strictEqual(out.evilKey, undefined);
});

test('白名单外的字段不会因为值合法而被放行', () => {
  // 反向断言：确保实现是白名单而非黑名单
  const out = pickFamilyWritable({ guard_settings: '{"x":1}', role: 'elder' });
  assert.deepStrictEqual(Object.keys(out), []);
});

test('pickFamilyWritable 不修改入参', () => {
  const input = { callThresholdMinutes: 20 };
  pickFamilyWritable(input);
  assert.deepStrictEqual(input, { callThresholdMinutes: 20 });
});

test('合并是增量的：未下发的字段保留旧值', () => {
  const merged = mergeFamilySettings(
    { callThresholdMinutes: 15, homeLat: 28.2, homeLng: 112.9 },
    { callThresholdMinutes: 25 },
    false
  );
  assert.strictEqual(merged.callThresholdMinutes, 25);
  assert.strictEqual(merged.homeLat, 28.2, '未下发的家基准必须保留，不能被整体覆盖冲掉');
});

test('homeCleared 删除两个家坐标键', () => {
  const merged = mergeFamilySettings(
    { callThresholdMinutes: 15, homeLat: 28.2, homeLng: 112.9 },
    {},
    true
  );
  assert.strictEqual('homeLat' in merged, false);
  assert.strictEqual('homeLng' in merged, false);
  assert.strictEqual(merged.callThresholdMinutes, 15, '清除家基准不应牵连其他字段');
});

test('homeCleared 优先于同请求内的坐标（清除必须最后生效）', () => {
  // 客户端不并发发这两个，但服务端行为不应依赖 Object.assign 的键序
  const merged = mergeFamilySettings(
    { homeLat: 1, homeLng: 2 },
    { homeLat: 28.2, homeLng: 112.9 },
    true
  );
  assert.strictEqual('homeLat' in merged, false);
  assert.strictEqual('homeLng' in merged, false);
});

test('合并不修改两个入参对象', () => {
  const stored = { callThresholdMinutes: 15 };
  const incoming = { callThresholdMinutes: 25 };
  mergeFamilySettings(stored, incoming, false);
  assert.strictEqual(stored.callThresholdMinutes, 15);
  assert.strictEqual(incoming.callThresholdMinutes, 25);
});

test('merged 返回新对象，不与 stored 共用引用', () => {
  const stored = { callThresholdMinutes: 15 };
  const merged = mergeFamilySettings(stored, {}, false);
  merged.callThresholdMinutes = 99;
  assert.strictEqual(stored.callThresholdMinutes, 15, '改 merged 不能污染库里已存的配置');
});

test('越界数值被收敛到边界内', () => {
  const clean = sanitizeGuardSettings({ homeStayMinutes: 99999, homeAwayRadiusMeters: 1 });
  assert.strictEqual(clean.homeStayMinutes, 240);
  assert.strictEqual(clean.homeAwayRadiusMeters, 100);
});

test('布尔型字段只接受布尔值', () => {
  assert.strictEqual(sanitizeGuardSettings({ recordingAutoUpload: true }).recordingAutoUpload, true);
  assert.strictEqual(sanitizeGuardSettings({ recordingAutoUpload: 'yes' }).recordingAutoUpload, undefined);
});

test('NaN 输入不写进配置（Math.min 与字符串比较会静默产出 NaN）', () => {
  const clean = sanitizeGuardSettings({ homeStayMinutes: 'abc', paymentThreshold: null });
  assert.strictEqual(clean.homeStayMinutes, undefined, '非数字字符串必须被丢弃而不是变成 NaN');
  assert.strictEqual(clean.paymentThreshold, undefined);
});

test('homeLat/homeLng 没有默认值，不会被塞进 0', () => {
  const clean = sanitizeGuardSettings({ homeLat: 28.2, homeLng: 112.9 });
  assert.strictEqual(clean.homeLat, 28.2);
  assert.strictEqual(clean.homeLng, 112.9);
  // 缺一个就不写另一个的默认值，避免半对坐标被当成有效家基准
  const half = sanitizeGuardSettings({ homeLat: 28.2 });
  assert.strictEqual(half.homeLat, 28.2);
  assert.strictEqual(half.homeLng, undefined);
});

test('trust 与 highRisk 字符串字段原样透传给策略层（白名单在下一道关卡过滤）', () => {
  const clean = sanitizeGuardSettings({ trustedCallNumbersJson: '["13800138000"]' });
  assert.strictEqual(clean.trustedCallNumbersJson, '["13800138000"]');
  assert.strictEqual(pickFamilyWritable(clean).trustedCallNumbersJson, undefined,
    '透传归 sanitize，拦截归白名单 —— 两道关卡职责不同');
});

test('字符串字段长度超限或格式不对时丢弃', () => {
  assert.strictEqual(
    sanitizeGuardSettings({ trustedCallNumbersJson: 'x'.repeat(20001) }).trustedCallNumbersJson,
    undefined
  );
  assert.strictEqual(
    sanitizeGuardSettings({ highRiskPackages: 'not-json' }).highRiskPackages,
    undefined
  );
});

test('extractHomeCleared 只认严格布尔 true', () => {
  assert.strictEqual(extractHomeCleared({ homeCleared: true }), true);
  assert.strictEqual(extractHomeCleared({ homeCleared: 'true' }), false);
  assert.strictEqual(extractHomeCleared({}), false);
  assert.strictEqual(extractHomeCleared(null), false);
  assert.strictEqual(extractHomeCleared('string'), false);
});

test('bounds 覆盖了全部数值型守护字段（新增字段必须补 bounds）', () => {
  // 新增可配置字段时若忘了写 bounds，sanitize 会静默丢掉它，
  // 表现为"设置永远不生效"。这条测试把这种遗忘提前到 CI 阶段。
  const expected = [
    'callThresholdMinutes', 'paymentThreshold',
    'recordingMaxSegments', 'recordingAutoUpload',
    'homeAwayRadiusMeters', 'stayMoveMeters', 'homeStayMinutes',
    'homeLat', 'homeLng'
  ];
  assert.deepStrictEqual(Object.keys(GUARD_SETTING_BOUNDS).sort(), expected.slice().sort());
});
