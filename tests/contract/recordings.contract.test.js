const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, setupBoundPair, sampleAudioPath, sha256File } = require('./helpers');

// 用例之间串联：upload 拿到 id 后，list/stream/review/pack/delete 复用同一条记录
const ctx = {};
const BASE = process.env.CONTRACT_BASE_URL || 'http://127.0.0.1:3999';

describe('契约 /api/recordings：录音存证链路', () => {
  it('POST /upload 首次上传 → 落库并返回摘要', async () => {
    const pair = await setupBoundPair();
    ctx.family = pair.family;
    ctx.elder = pair.elder;
    const elder = pair.elder;
    const file = sampleAudioPath();
    ctx.file = file;
    ctx.sha = sha256File(file);
    ctx.sessionId = 'S_CONTRACT_' + Date.now();

    const res = await api.upload(
      '/api/recordings/upload',
      {
        elderId: elder.elderId,
        sessionId: ctx.sessionId,
        segmentIndex: 1,
        reason: 'SOS',
        durationMs: 300000,
        sha256: ctx.sha,
        recordedAt: new Date().toISOString()
      },
      file,
      'contract-sample.m4a'
    );
    assert.equal(res.status, 200, JSON.stringify(res.body));
    assert.equal(res.body.success, true);
    const d = res.body.data;
    assert.ok(Number.isInteger(d.id) && d.id > 0);
    assert.equal(d.duplicated, false);
    assert.match(d.sha256, /^[0-9a-f]{64}$/, 'sha256 应为 64 位小写十六进制');
    assert.equal(d.sha256, ctx.sha);
    assert.ok(d.sizeBytes > 0);
    assert.ok(typeof d.fileName === 'string' && d.fileName.length > 0);
    ctx.id = d.id;
  });

  it('POST /upload 同 sha256 重传 → duplicated=true 且不重复落盘', async () => {
    const res = await api.upload(
      '/api/recordings/upload',
      { elderId: ctx.elder.elderId, sessionId: ctx.sessionId, segmentIndex: 1, reason: 'SOS', sha256: ctx.sha },
      ctx.file,
      'contract-sample.m4a'
    );
    assert.equal(res.status, 200);
    assert.equal(res.body.data.duplicated, true);
    assert.equal(res.body.data.id, ctx.id, '重传应返回同一条记录');
  });

  it('POST /upload 缺 elderId / 不存在的老人 → 400 且不产生孤儿记录', async () => {
    const noId = await api.upload('/api/recordings/upload', { sessionId: 'x' }, ctx.file, 'contract-sample.m4a');
    assert.equal(noId.status, 400);
    assert.equal(noId.body.success, false);

    const ghost = await api.upload('/api/recordings/upload', { elderId: 999999, sessionId: 'x' }, ctx.file, 'contract-sample.m4a');
    assert.equal(ghost.status, 400);
    assert.ok(ghost.body.error.includes('老人账号'), '错误文案应说明老人账号不存在');
  });

  it('GET /list/:elderId 返回完整字段与带签名的 streamUrl', async () => {
    const { family, elder } = ctx;
    const res = await api.get(`/api/recordings/list/${elder.elderId}`, { token: family.token });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.ok(Array.isArray(res.body.data.recordings));
    assert.ok(typeof res.body.data.total === 'number');

    const r = res.body.data.recordings.find((x) => x.id === ctx.id);
    assert.ok(r, '列表应包含刚上传的录音');
    // 契约字段：一律 camelCase 下发
    for (const k of [
      'id', 'sessionId', 'segmentIndex', 'reason', 'reasonLabel', 'fileName', 'durationMs',
      'sizeBytes', 'sha256', 'recordedAt', 'transcriptStatus', 'fraudStatus',
      'keepAsEvidence', 'reviewedByFamily', 'streamUrl', 'streamTokenExpiresAt'
    ]) {
      assert.ok(k in r, `列表字段缺失: ${k}`);
    }
    assert.equal(r.sessionId, ctx.sessionId);
    assert.equal(r.reason, 'SOS');
    assert.equal(r.reasonLabel, '一键紧急求助');
    assert.match(r.streamUrl, /^\/api\/recordings\/stream\/\d+\?token=/, 'streamUrl 必须带签名参数');
    assert.ok(Array.isArray(r.fraudLabels));

    const noAuth = await api.get(`/api/recordings/list/${elder.elderId}`);
    assert.equal(noAuth.status, 401);
  });

  it('GET /list?group=1 按会话聚合；?evidence=1 只留证据', async () => {
    const { family, elder } = ctx;
    const grouped = await api.get(`/api/recordings/list/${elder.elderId}?group=1`, { token: family.token });
    assert.equal(grouped.status, 200);
    assert.ok(Array.isArray(grouped.body.data.sessions));
    assert.ok(typeof grouped.body.data.totalRecordings === 'number');
    assert.ok(typeof grouped.body.data.fraudCount === 'number');
    const s = grouped.body.data.sessions.find((x) => x.sessionId === ctx.sessionId);
    assert.ok(s && s.segmentCount >= 1 && Array.isArray(s.recordings));

    const ev = await api.get(`/api/recordings/list/${elder.elderId}?evidence=1`, { token: family.token });
    assert.equal(ev.status, 200);
    for (const r of ev.body.data.recordings) assert.equal(r.keepAsEvidence, true);
  });

  it('GET /stream/:id?token= 支持整段与 Range 请求；无效签名 401', async () => {
    const { family, elder } = ctx;
    const list = await api.get(`/api/recordings/list/${elder.elderId}`, { token: family.token });
    const r = list.body.data.recordings.find((x) => x.id === ctx.id);
    const url = r.streamUrl;

    const full = await api.get(url, { raw: true });
    assert.equal(full.status, 200);
    assert.match(full.headers.get('content-type') || '', /^audio\//);
    assert.equal(full.headers.get('accept-ranges'), 'bytes');
    const buf = Buffer.from(await full.arrayBuffer());
    assert.equal(buf.length, r.sizeBytes);

    const ranged = await fetch(BASE + url, { headers: { Range: 'bytes=0-99' } });
    assert.equal(ranged.status, 206, 'Range 请求必须返回 206，播放器拖进度条依赖它');
    assert.ok((ranged.headers.get('content-range') || '').startsWith('bytes 0-99/'));

    const bad = await api.get(`/api/recordings/stream/${ctx.id}?token=forged`);
    assert.equal(bad.status, 401);
  });

  it('GET /status/:elderId 返回录音状态与服务器时间', async () => {
    const { family, elder } = ctx;
    const res = await api.get(`/api/recordings/status/${elder.elderId}`, { token: family.token });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.equal(res.body.data.elderId, elder.elderId);
    assert.ok('active' in res.body.data.recording, 'recording 至少要有 active 字段');
    assert.ok(typeof res.body.data.serverTime === 'string');
  });

  it('POST /:id/review 人工复核 → 推翻 AI 判定并长期保留', async () => {
    const { family, elder } = ctx;
    const res = await api.post(`/api/recordings/${ctx.id}/review`, {
      token: family.token,
      body: { keep: true, note: '契约测试复核' }
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.equal(res.body.data.keepAsEvidence, true);

    const list = await api.get(`/api/recordings/list/${elder.elderId}`, { token: family.token });
    const r = list.body.data.recordings.find((x) => x.id === ctx.id);
    assert.equal(r.keepAsEvidence, true);
    assert.equal(r.reviewedByFamily, true);
    assert.equal(r.retentionUntil, null, '复核保留的证据不应再设清理时间');

    const noAuth = await api.post(`/api/recordings/${ctx.id}/review`, { body: { keep: true } });
    assert.equal(noAuth.status, 401);
  });

  it('GET /pack/:elderId 打包返回 zip（含证据清单）', async () => {
    const { family, elder } = ctx;
    const res = await api.get(`/api/recordings/pack/${elder.elderId}`, { token: family.token, raw: true });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /application\/zip/);
    const buf = Buffer.from(await res.arrayBuffer());
    assert.ok(buf.length > 0);
    assert.equal(buf.slice(0, 2).toString('ascii'), 'PK', '必须是 zip 文件');
  });

  it('DELETE /:id 删除记录与文件，重复删除 404', async () => {
    const { family } = ctx;
    const del = await api.del(`/api/recordings/${ctx.id}`, { token: family.token });
    assert.equal(del.status, 200);
    assert.equal(del.body.success, true);
    assert.equal(del.body.data.deleted, true);

    const again = await api.del(`/api/recordings/${ctx.id}`, { token: family.token });
    assert.equal(again.status, 404);
  });

  it('GET /pack/:elderId 无录音 → 404', async () => {
    const { family, elder } = ctx;
    const res = await api.get(`/api/recordings/pack/${elder.elderId}`, { token: family.token });
    assert.equal(res.status, 404);
  });
});
