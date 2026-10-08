const test = require('node:test');
const assert = require('node:assert');
const { isSensitiveLocation, toBoolLoose } = require('../services/locationSensitivity');

test('原始 bug：老人离家在外，每次上报的 is_sensitive 却是 0', () => {
  // LOCATION_UPDATE 恒 LOW，老逻辑只看 severity → 地图上的"敏感位置"高亮从不触发。
  // 子女看不出老人到底在不在家，只看到颜色永远一样。
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: true }), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: false }), 0);
});

test('心跳事件在家的点不标敏感', () => {
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: false }), 0);
  assert.strictEqual(isSensitiveLocation('GEOFENCE_EXIT', 'LOW', { is_away_from_home: false }), 0);
});

test('心跳事件不因 severity 被升格 —— 不制造新噪声', () => {
  // 老人离家 + 长时间停留本身就是 MEDIUM，但不能把每一条心跳都变敏感，
  // 否则地图整条轨迹全橙，等于没有信息量。以离家为准是稳定的语义。
  assert.strictEqual(isSensitiveLocation('LOCATION_RISK', 'MEDIUM', { is_away_from_home: false }), 0);
});

test('老版本老人端不带 is_away_from_home 时退回按 severity（宁可多标不漏标）', () => {
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', {}), 0);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'HIGH', {}), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'MEDIUM', {}), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', null), 0);
});

test('非位置类事件维持"按 severity 推导"不变', () => {
  assert.strictEqual(isSensitiveLocation('PAYMENT_RISK', 'HIGH', { is_away_from_home: true }), 1);
  assert.strictEqual(isSensitiveLocation('CALL_RISK', 'HIGH', { is_away_from_home: false }), 1);
  assert.strictEqual(isSensitiveLocation('SOS', 'HIGH', {}), 1);
  assert.strictEqual(isSensitiveLocation('CALL_STAT', 'LOW', { is_away_from_home: true }), 0);
  assert.strictEqual(isSensitiveLocation('DEVICE_ONLINE', 'LOW', {}), 0);
});

test('非位置类事件忽略 is_away_from_home —— 没有位置就不能凭"离家"标敏感', () => {
  assert.strictEqual(isSensitiveLocation('PAYMENT_RISK', 'LOW', { is_away_from_home: true }), 0);
});

test('is_away_from_home 的各种序列化形态都能解析', () => {
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: true }), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: 'true' }), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: 1 }), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: '1' }), 1);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'HIGH', { is_away_from_home: false }), 0);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'HIGH', { is_away_from_home: 'false' }), 0);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'HIGH', { is_away_from_home: '0' }), 0);
  assert.strictEqual(isSensitiveLocation('LOCATION_UPDATE', 'HIGH', { is_away_from_home: '' }), 0);
});

test('无法解析的值返回 null（区分"明确在家"与"不知道"）', () => {
  assert.strictEqual(toBoolLoose(true), true);
  assert.strictEqual(toBoolLoose(false), false);
  assert.strictEqual(toBoolLoose('TRUE'), true);
  assert.strictEqual(toBoolLoose(''), false);
  assert.strictEqual(toBoolLoose('maybe'), null);
  assert.strictEqual(toBoolLoose(undefined), null);
  assert.strictEqual(toBoolLoose(null), null);
  assert.strictEqual(toBoolLoose(2), null);
});

test('大小写与空白不敏感', () => {
  assert.strictEqual(isSensitiveLocation('  location_update  ', ' low ', { is_away_from_home: true }), 1);
});

test('返回值严格是 0/1（SQLite 整型列，不能是 true/false）', () => {
  const a = isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: true });
  const b = isSensitiveLocation('LOCATION_UPDATE', 'LOW', { is_away_from_home: false });
  assert.strictEqual(a, 1);
  assert.strictEqual(b, 0);
  assert.strictEqual(typeof a, 'number');
  assert.strictEqual(typeof b, 'number');
});