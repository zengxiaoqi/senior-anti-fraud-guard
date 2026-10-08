const test = require('node:test');
const assert = require('node:assert');
const { shouldInterrupt, INTERRUPTIBLE_EVENTS } = require('../services/riskAlertPolicy');

test('LOCATION_UPDATE 心跳不弹打断弹窗（原始 bug）', () => {
  // 老人端每次 onLocationChanged 都报一条、severity 恒 LOW。
  // 就是它让子女端每分钟被"长者正处于高危状态 (LOCATION_UPDATE)"糊一次。
  assert.strictEqual(shouldInterrupt('LOCATION_UPDATE', 'LOW'), false);
  assert.strictEqual(shouldInterrupt('LOCATION_UPDATE', 'HIGH'), false);
});

test('心跳/流水类事件一律不打断', () => {
  for (const type of ['CALL_STAT', 'DEVICE_ONLINE', 'GEOFENCE_EXIT', 'GEOFENCE_DWELL', 'LOCATION_RISK']) {
    assert.strictEqual(shouldInterrupt(type, 'HIGH'), false, `${type}/HIGH 应不打断`);
    assert.strictEqual(shouldInterrupt(type, 'LOW'), false, `${type}/LOW 应不打断`);
  }
});

test('GEOFENCE_RECORDING 即便 HIGH 也不打断', () => {
  // 进入敏感地点自动录音是守护系统的正常动作，不是危险信号。
  // 这条最容易被"按 HIGH 一刀切"漏掉 —— 弹红屏只会打断老人正在办的事。
  assert.strictEqual(shouldInterrupt('GEOFENCE_RECORDING', 'HIGH'), false);
});

test('真高危打断：COERCION_RISK / PAYMENT_RISK / CALL_RISK 均为 HIGH', () => {
  for (const type of ['COERCION_RISK', 'PAYMENT_RISK', 'CALL_RISK']) {
    assert.strictEqual(shouldInterrupt(type, 'HIGH'), true, `${type}/HIGH 应打断`);
  }
});

test('白名单事件降级后不打断', () => {
  // CALL_RISK 的 MEDIUM 是"陌生号码 2h 内来电 3 次"这种骚扰频次信号，
  // 拉响警报属于过度反应。
  assert.strictEqual(shouldInterrupt('CALL_RISK', 'MEDIUM'), false);
  assert.strictEqual(shouldInterrupt('CALL_RISK', 'LOW'), false);
});

test('SOS 无视级别一律打断（老人主动按下的求助）', () => {
  for (const sev of ['HIGH', 'MEDIUM', 'LOW', '']) {
    assert.strictEqual(shouldInterrupt('SOS', sev), true, `SOS/${sev} 应打断`);
  }
});

test('大小写与空白不敏感', () => {
  assert.strictEqual(shouldInterrupt('coercion_risk', 'high'), true);
  assert.strictEqual(shouldInterrupt('  PAYMENT_RISK  ', ' HIGH '), true);
});

test('空值与未知类型安全返回 false（不崩、不误弹）', () => {
  assert.strictEqual(shouldInterrupt(undefined, undefined), false);
  assert.strictEqual(shouldInterrupt(null, null), false);
  assert.strictEqual(shouldInterrupt('', ''), false);
  assert.strictEqual(shouldInterrupt('SOMETHING_NEW', 'HIGH'), false);
});

test('未知事件默认不打断 —— 白名单而非黑名单', () => {
  // 老人端新增事件类型时不会自动弹打断。要弹必须显式加进 INTERRUPTIBLE_EVENTS，
  // 这样"漏配"的代价是安静，而不是对着用户狂弹假警报。
  assert.ok(!INTERRUPTIBLE_EVENTS.includes('BRAND_NEW_EVENT'));
});