const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, setupBoundPair } = require('./helpers');

describe('契约 /api/geofence：敏感地点围栏', () => {
  it('子女端 add → 老人端 GET /elder/:elderId 立即可见（仅启用项）', async () => {
    const { family, elder } = await setupBoundPair();

    const add = await api.post('/api/geofence/add', {
      token: family.token,
      body: { name: '契约测试·老年活动中心', latitude: 28.2732, longitude: 113.0623, radius: 300, dwellMinutes: 10 }
    });
    assert.equal(add.status, 200);
    assert.equal(add.body.success, true);
    assert.ok(Number.isInteger(add.body.id) && add.body.id > 0, '应返回围栏 id');

    const elderView = await api.get(`/api/geofence/elder/${elder.elderId}`);
    assert.equal(elderView.status, 200);
    assert.equal(elderView.body.success, true);
    const rows = elderView.body.data;
    assert.ok(Array.isArray(rows));
    const mine = rows.find((r) => r.id === add.body.id);
    assert.ok(mine, '老人端应能看到新围栏');
    // 老人端只拿必要字段：不应下发 enabled 之外的内部字段
    for (const k of ['name', 'latitude', 'longitude', 'radius', 'dwell_minutes']) {
      assert.ok(k in mine, `老人端字段缺失: ${k}`);
    }
    assert.equal('enabled' in mine, false, '老人端列表不下发 enabled 字段');
  });

  it('add 参数校验：缺名称 / 非法经纬度 → 400', async () => {
    const { family } = await setupBoundPair();
    const noName = await api.post('/api/geofence/add', {
      token: family.token,
      body: { latitude: 28.2, longitude: 113.0 }
    });
    assert.equal(noName.status, 400);

    const badLat = await api.post('/api/geofence/add', {
      token: family.token,
      body: { name: 'x', latitude: 999, longitude: 113.0 }
    });
    assert.equal(badLat.status, 400);

    const noAuth = await api.post('/api/geofence/add', { body: { name: 'x', latitude: 1, longitude: 1 } });
    assert.equal(noAuth.status, 401);
  });

  it('radius 被夹到 [50,2000]，dwellMinutes 上限 720', async () => {
    const { family, elder } = await setupBoundPair();
    const add = await api.post('/api/geofence/add', {
      token: family.token,
      body: { name: '夹取测试点', latitude: 28.1, longitude: 113.1, radius: 99999, dwellMinutes: 99999 }
    });
    assert.equal(add.status, 200);
    const list = await api.get(`/api/geofence/list/${elder.elderId}`, { token: family.token });
    const row = list.body.data.find((r) => r.id === add.body.id);
    assert.equal(row.radius, 2000, 'radius 超上限应夹到 2000');
    assert.equal(row.dwell_minutes, 720, 'dwellMinutes 超上限应夹到 720');
  });

  it('未绑定守护对象时 add → 403（不是 500）', async () => {
    const fam = await api.post('/api/auth/register', {
      body: { username: 'u' + Date.now(), password: 'test123456', phone: '139' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0') }
    });
    const res = await api.post('/api/geofence/add', {
      token: fam.body.data.token,
      body: { name: '无绑定', latitude: 1, longitude: 1 }
    });
    assert.equal(res.status, 403);
    assert.equal(res.body.success, false);
  });

  it('GET /list/:elderId 越权 → 403；无 token → 401', async () => {
    const { family, elder } = await setupBoundPair();
    const ok = await api.get(`/api/geofence/list/${elder.elderId}`, { token: family.token });
    assert.equal(ok.status, 200);
    assert.ok(Array.isArray(ok.body.data));

    const noAuth = await api.get(`/api/geofence/list/${elder.elderId}`);
    assert.equal(noAuth.status, 401);

    const other = await api.get('/api/geofence/list/999999', { token: family.token });
    assert.equal(other.status, 403);
  });

  it('update → 改半径/停用；delete → 删除，重复删除 404', async () => {
    const { family, elder } = await setupBoundPair();
    const add = await api.post('/api/geofence/add', {
      token: family.token,
      body: { name: '待更新点', latitude: 28.3, longitude: 113.3, radius: 200 }
    });
    const id = add.body.id;

    const upd = await api.post('/api/geofence/update', {
      token: family.token,
      body: { id, radius: 500, enabled: false }
    });
    assert.equal(upd.status, 200);
    assert.equal(upd.body.success, true);

    const elderView = await api.get(`/api/geofence/elder/${elder.elderId}`);
    assert.equal(
      elderView.body.data.some((r) => r.id === id),
      false,
      '停用后老人端不应再拉到该围栏'
    );

    const noId = await api.post('/api/geofence/update', { token: family.token, body: { radius: 100 } });
    assert.equal(noId.status, 400);

    const del = await api.post('/api/geofence/delete', { token: family.token, body: { id } });
    assert.equal(del.status, 200);
    assert.equal(del.body.success, true);

    const delAgain = await api.post('/api/geofence/delete', { token: family.token, body: { id } });
    assert.equal(delAgain.status, 404);
  });
});
