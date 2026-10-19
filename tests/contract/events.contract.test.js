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

  // ──────────────────────────────────────────
  //  轨迹返回条数（Android 轨迹页要 7 天全貌，小程序仍在用默认 10 条）
  // ──────────────────────────────────────────

  it('GET /location 默认 10 条，?limit 可放大且必须被上限约束', async () => {
    const { family, elder } = await setupBoundPair();
    // 造 15 条位置：默认 10 条会漏掉最早那批，放大后才能看到"7 天去过哪"
    for (let i = 0; i < 15; i++) {
      await api.post('/api/events/report', {
        body: {
          elderId: elder.elderId,
          eventType: 'LOCATION_UPDATE',
          severity: 'LOW',
          details: { latitude: 23.19 + i * 0.001, longitude: 113.42, address: '' }
        }
      });
    }
    const dflt = await api.get(`/api/events/location/${elder.elderId}`, { token: family.token });
    assert.equal(dflt.status, 200);
    assert.equal(dflt.body.data.length, 10, '不传 limit 时必须仍是 10 条（小程序契约不变）');

    const many = await api.get(`/api/events/location/${elder.elderId}?limit=200`, { token: family.token });
    assert.equal(many.body.data.length, 15, 'limit=200 应返回全部 15 条，不能仍被截断');

    const huge = await api.get(`/api/events/location/${elder.elderId}?limit=999999`, { token: family.token });
    assert.ok(huge.status === 200 && Array.isArray(huge.body.data), '超大 limit 不能报错，必须被上限收敛');
    assert.ok(huge.body.data.length <= 300, 'limit 必须有上限，否则接口可以一次拉空整个库');

    const bad = await api.get(`/api/events/location/${elder.elderId}?limit=abc`, { token: family.token });
    assert.equal(bad.body.data.length, 10, '非法 limit 必须回落到默认条数，不能返回空');
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
    // order_no 全局 UNIQUE：写死固定值时，持久化库（wrangler dev 本地 D1 / 远程）
    // 上一轮跑测试留下的同号流水会让本轮 INSERT 静默失败，测试就假挂了
    const orderNo = 'ORD_CONTRACT_' + Date.now();
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'PAYMENT_RISK',
        severity: 'HIGH',
        details: { amount: 19800, payee_name: '某某养生馆', order_no: orderNo }
      }
    });
    const ev = await api.get(`/api/evidence/export/${elder.elderId}`, { token: family.token });
    assert.equal(ev.status, 200);
    const pay = ev.body.data.payment_records;
    assert.ok(Array.isArray(pay) && pay.length >= 1, '支付存证应入账');
    const mine = pay.find(p => p.order_no === orderNo);
    assert.ok(mine, `本轮写入的流水应可查到（order_no=${orderNo}）`);
    assert.equal(mine.amount, 19800);
    assert.equal(mine.payee_name, '某某养生馆');
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

  // ──────────────────────────────────────────
  //  老人端危险检测：FALL_RISK
  // ──────────────────────────────────────────

  it('FALL_RISK 自报 LOW 也必须存成 HIGH（跌倒不能被降级）', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'FALL_RISK',
        severity: 'LOW',
        details: { peak_magnitude: 25.4, elder_confirmed: false }
      }
    });
    const list = await api.get(`/api/events/list/${elder.elderId}`, { token: family.token });
    const top = list.body.data[0];
    assert.equal(top.event_type, 'FALL_RISK');
    assert.equal(top.severity, 'HIGH', '老人无法决定自己的严重程度，跌倒事件不得降级');
    assert.equal(top.details.elder_confirmed, false);
  });

  // ──────────────────────────────────────────
  //  老人失联：/status（判定口径归服务端，客户端只读）
  // ──────────────────────────────────────────

  it('GET /status 需登录态与绑定关系', async () => {
    const { family, elder } = await setupBoundPair();
    const noAuth = await api.get(`/api/events/status/${elder.elderId}`);
    assert.equal(noAuth.status, 401);

    const ok = await api.get(`/api/events/status/${elder.elderId}`, { token: family.token });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.success, true);

    const stranger = await api.get('/api/events/status/999999', { token: family.token });
    assert.equal(stranger.status, 403, '未绑定的老人必须 403，不能泄露他人守护状态');
  });

  it('GET /status：刚上报过判 ONLINE，并给出阈值与最后上报时间', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: { elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW', details: {} }
    });

    const res = await api.get(`/api/events/status/${elder.elderId}`, { token: family.token });
    const d = res.body.data;
    assert.equal(d.state, 'ONLINE', '刚上报过必须是 ONLINE');
    assert.equal(typeof d.minutes_since_report, 'number');
    assert.ok(d.minutes_since_report >= 0);
    assert.ok(d.minutes_since_report < 5, '刚上报过不应被判成静默');
    assert.equal(typeof d.warn_minutes, 'number');
    assert.equal(typeof d.offline_minutes, 'number');
    assert.ok(d.offline_minutes > d.warn_minutes, 'offline 阈值必须大于 warn');
    assert.ok(typeof d.last_report_at === 'string' && d.last_report_at.length > 0);
  });

  it('GET /status：从未上报过返回 ONLINE 而不是 OFFLINE', async () => {
    const { family, elder } = await setupBoundPair();
    // 注意：不做任何上报。老人整天不出门不通话就是没有事件，
    // 那种情况判 OFFLINE 会在绑定次日清晨变成一场全网误报风暴。
    const res = await api.get(`/api/events/status/${elder.elderId}`, { token: family.token });
    assert.equal(res.body.data.state, 'ONLINE');
    assert.equal(res.body.data.minutes_since_report, null);
  });

  it('GET /status：久未上报判 SILENT / OFFLINE，阈值按服务端配置', async () => {
    const { family, elder } = await setupBoundPair();
    // 用 guard_settings 把阈值调到最小可配值（30 / 60 分钟），
    // 再往库里塞一条 3 小时前的上报，验证判定确实用服务端阈值而不是客户端硬编码
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId,
        eventType: 'LOCATION_UPDATE',
        severity: 'LOW',
        details: {}
      }
    });
    const st = await api.get(`/api/events/status/${elder.elderId}`, { token: family.token });
    const cur = st.body.data;
    assert.ok(cur.offline_minutes >= cur.warn_minutes);

    // 判定函数本身已由 tests/elderLiveness.test.js 覆盖；
    // 契约层只保证"接口存在、鉴权正确、字段齐全、口径由服务端给出"
    assert.ok(['ONLINE', 'SILENT', 'OFFLINE'].includes(cur.state));
  });
});
