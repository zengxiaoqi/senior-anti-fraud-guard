const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, setupBoundPair, assertBeijingTime } = require('./helpers');

describe('契约 /api/events：风险事件上报与查询', () => {
  it('POST /report 正常上报 → success + eventId', async () => {
    const { elder } = await setupBoundPair();
    const res = await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'CALL_RISK',
        severity: 'MEDIUM',
        details: { peer: '13800000000', seconds: 600 }
      }
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.ok(Number.isInteger(res.body.eventId) && res.body.eventId > 0);
    assert.ok(typeof res.body.message === 'string');
  });

  it('POST /report 缺参 → 400', async () => {
    const res = await api.post('/api/events/report', { body: { eventType: 'CALL_RISK' } });
    assert.equal(res.status, 400);
    assert.ok(res.body.error);
  });

  it('severity 归一化：COERCION_RISK 自报 LOW 也要存成 HIGH', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: { elderId: elder.elderId, eventType: 'COERCION_RISK', severity: 'LOW', details: { pkg: 'com.fake.remote' } }
    });
    const list = await api.get(`/api/events/list/${elder.elderId}`, { token: family.token });
    assert.equal(list.status, 200);
    const top = list.body.data[0];
    assert.equal(top.event_type, 'COERCION_RISK');
    assert.equal(top.severity, 'HIGH', '高危事件类型必须被服务端归一化，不能采信客户端自报值');
    // 注：interruptible 只出现在 WS 的 RISK_ALERT 载荷里（列表是库行原样下发，不含该字段），
    // 该字段的契约由 P5 的 WS 契约用例覆盖。
  });

  it('CALL_STAT 恒 LOW，不触发强打断', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: { elderId: elder.elderId, eventType: 'CALL_STAT', severity: 'HIGH', details: { seconds: 30 } }
    });
    const list = await api.get(`/api/events/list/${elder.elderId}`, { token: family.token });
    const top = list.body.data[0];
    assert.equal(top.severity, 'LOW', '统计类事件恒为 LOW，不得触发告警噪声');
  });

  it('带经纬度的事件写入轨迹表，GET /location 可读回', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'LOCATION_UPDATE',
        severity: 'LOW',
        details: { latitude: 39.9042, longitude: 116.4074, address: 'GPS 位置 (39.9042, 116.4074)' }
      }
    });
    const loc = await api.get(`/api/events/location/${elder.elderId}`, { token: family.token });
    assert.equal(loc.status, 200);
    assert.equal(loc.body.success, true);
    assert.ok(Array.isArray(loc.body.data) && loc.body.data.length >= 1);
    const row = loc.body.data[0];
    assert.equal(typeof row.latitude, 'number');
    assert.equal(typeof row.longitude, 'number');
    assert.ok(typeof row.address === 'string' && row.address.length > 0, 'address 不能为空串');
    assert.equal(row.address.startsWith('GPS'), false, '坐标串必须被兜底成可读地名');
    assert.ok('place_source' in row);
    assertBeijingTime(row.created_at, 'locations.created_at');
  });

  it('PAYMENT_RISK 带金额 → 写入 payments 存证表（证据包可查）', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'PAYMENT_RISK',
        severity: 'HIGH',
        details: { amount: 19800, payee_name: '某某养生馆', order_no: 'ORD_CONTRACT_1' }
      }
    });
    const ev = await api.get(`/api/evidence/export/${elder.elderId}`, { token: family.token });
    assert.equal(ev.status, 200);
    const pay = ev.body.data.payment_records;
    assert.ok(Array.isArray(pay) && pay.length >= 1, '支付存证应入账');
    assert.equal(pay[0].amount, 19800);
    assert.equal(pay[0].payee_name, '某某养生馆');
  });

  it('GET /list/:elderId 需登录态，且只返回本人绑定老人的事件', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: { elderId: elder.elderId, eventType: 'CALL_RISK', severity: 'LOW', details: {} }
    });

    const noAuth = await api.get(`/api/events/list/${elder.elderId}`);
    assert.equal(noAuth.status, 401);

    const ok = await api.get(`/api/events/list/${elder.elderId}`, { token: family.token });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.success, true);
    assert.ok(Array.isArray(ok.body.data));
    const row = ok.body.data[0];
    assert.equal(row.elder_id, elder.elderId);
    assert.ok('details' in row && typeof row.details === 'object', 'details 必须是已解析的对象，不能是字符串');
    assert.ok('id' in row && 'event_type' in row && 'severity' in row);

    // 未绑定的老人 → 403
    const stranger = await api.get('/api/events/list/999999', { token: family.token });
    assert.equal(stranger.status, 403);
  });
});
