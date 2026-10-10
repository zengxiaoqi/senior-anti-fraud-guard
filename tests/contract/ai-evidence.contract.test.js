const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, setupBoundPair } = require('./helpers');

describe('契约 /api/ai 与 /api/evidence', () => {
  it('POST /api/ai/scan 命中诈骗关键词 → HIGH', async () => {
    const { family } = await setupBoundPair();
    const res = await api.post('/api/ai/scan', {
      token: family.token,
      body: { textContent: '本产品根治高血压，买一送一，高额收益' }
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    const d = res.body.data;
    assert.equal(d.risk_level, 'HIGH');
    assert.ok(typeof d.confidence === 'number');
    assert.ok(Array.isArray(d.matched_keywords));
    assert.ok(d.matched_keywords.includes('根治'));
    assert.ok(typeof d.analysis_report === 'string' && d.analysis_report.length > 0);
    assert.ok(typeof d.suggested_action === 'string');
  });

  it('POST /api/ai/scan 无关键词 → SAFE；无 token → 401', async () => {
    const { family } = await setupBoundPair();
    const safe = await api.post('/api/ai/scan', { token: family.token, body: { textContent: '今天天气不错' } });
    assert.equal(safe.status, 200);
    assert.equal(safe.body.data.risk_level, 'SAFE');
    assert.deepEqual(safe.body.data.matched_keywords, []);

    const noAuth = await api.post('/api/ai/scan', { body: { textContent: '根治' } });
    assert.equal(noAuth.status, 401);
  });

  it('GET /evidence/export/:elderId 证据包结构完整', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: {
        elderId: elder.elderId, eventType: 'CALL_RISK', severity: 'HIGH',
        details: { peer: '13900002222', seconds: 900 }
      }
    });

    const res = await api.get(`/api/evidence/export/${elder.elderId}`, { token: family.token });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    const d = res.body.data;

    assert.ok(d.metadata, '缺少 metadata');
    assert.match(d.metadata.checksum, /^[0-9a-f]{64}$/, 'checksum 必须是可复算的 SHA-256，不能是 HASH_时间戳');
    assert.equal(d.metadata.algorithm, 'SHA-256');
    assert.ok(typeof d.metadata.generated_at === 'string');
    assert.ok(typeof d.metadata.generated_at_beijing === 'string');
    assert.ok(typeof d.metadata.title === 'string');

    assert.ok(d.elder_info, '缺少 elder_info');
    assert.equal(d.elder_info.name, '契约测试老人');
    assert.ok(typeof d.elder_info.phone === 'string' && d.elder_info.phone.length > 0);
    assert.ok(typeof d.elder_info.guardian_name === 'string');
    assert.ok(typeof d.elder_info.guardian_phone === 'string');

    assert.ok(Array.isArray(d.payment_records), 'payment_records 应为数组');
    assert.ok(Array.isArray(d.location_logs), 'location_logs 应为数组');
    assert.ok(Array.isArray(d.suspicious_calls), 'suspicious_calls 应为数组');
    assert.ok(d.audio_recordings, '缺少 audio_recordings');
    assert.equal(typeof d.audio_recordings.total, 'number');
    assert.equal(typeof d.audio_recordings.fraudCount, 'number');
    assert.equal(typeof d.audio_recordings.keptAsEvidence, 'number');
    assert.ok(Array.isArray(d.audio_recordings.items));

    // CALL_RISK 事件应进入 suspicious_calls，且 details 已解析为对象
    const call = d.suspicious_calls[0];
    assert.ok(call, 'CALL_RISK 事件应出现在 suspicious_calls');
    assert.equal(typeof call.details, 'object');
    assert.equal(call.details.peer, '13900002222');

    const noAuth = await api.get(`/api/evidence/export/${elder.elderId}`);
    assert.equal(noAuth.status, 401);
  });
});
