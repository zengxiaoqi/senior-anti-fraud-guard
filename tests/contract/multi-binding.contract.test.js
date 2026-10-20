const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, randMobile, registerFamily, registerElder } = require('./helpers');

/**
 * 多人绑定契约用例（docs/multi-binding-design.md）：
 * 一个子女可绑多个老人、一个老人可被多个子女绑。
 * 兼容铁律：旧回执字段（boundUser / boundFamily）必须恒存在且语义不变。
 */
describe('契约 /api/auth：多人绑定', () => {
  it('追加式绑定：子女绑两位老人 → boundUsers 长度 2，两位都可访问', async () => {
    const fam = await registerFamily();
    const e1 = await registerElder();
    const e2 = await registerElder();

    const b1 = await api.post('/api/auth/bind', { body: { bindCode: e1.bindCode }, token: fam.token });
    assert.equal(b1.status, 200);
    assert.equal(b1.body.boundUser.id, e1.elderId, 'boundUser 兼容字段=本次绑定的老人');
    assert.equal(b1.body.boundList.length, 1);

    const b2 = await api.post('/api/auth/bind', { body: { bindCode: e2.bindCode }, token: fam.token });
    assert.equal(b2.status, 200, '第二位老人必须能绑上（旧覆盖式会冲掉第一位）');
    assert.equal(b2.body.boundList.length, 2, 'boundList 应包含两位老人');

    // 登录回执：boundUsers 全量、boundUser=最早绑定
    const login = await api.post('/api/auth/login', {
      body: { username: fam.username, password: 'test123456' }
    });
    assert.equal(login.status, 200);
    assert.equal(login.body.data.boundUsers.length, 2);
    assert.equal(login.body.data.boundUser.id, e1.elderId, 'boundUser 仍=最早绑定的老人');
    assert.equal(login.body.data.boundUsers[0].id, e1.elderId);

    // 两位老人的数据都允许访问（requireBoundElder 走 bindings）
    const u1 = await api.get(`/api/auth/user/${e1.elderId}`, { token: fam.token });
    const u2 = await api.get(`/api/auth/user/${e2.elderId}`, { token: fam.token });
    assert.equal(u1.status, 200);
    assert.equal(u2.status, 200);
  });

  it('幂等：重复绑同一对 → 200 不新增；bindCode 路径限定 elder（子女绑定码不可绑）', async () => {
    const fam = await registerFamily();
    const e1 = await registerElder();
    await api.post('/api/auth/bind', { body: { bindCode: e1.bindCode }, token: fam.token });

    const again = await api.post('/api/auth/bind', { body: { bindCode: e1.bindCode }, token: fam.token });
    assert.equal(again.status, 200);
    assert.match(again.body.message, /已在/);
    assert.equal(again.body.boundList.length, 1, '重复绑定不得产生第二条');

    // 子女的绑定码不能被另一个子女拿去当"绑定凭据"绑到 family 上
    const fam2 = await registerFamily();
    const bad = await api.post('/api/auth/bind', { body: { bindCode: fam.bindCode }, token: fam2.token });
    assert.equal(bad.status, 404, 'family 的绑定码不可作为 elder 绑定凭据');
  });

  it('一个老人被两个子女绑定 → 老人回执 boundFamilies=2 且主守护人在前，双方都可访问', async () => {
    const fam1 = await registerFamily();
    const fam2 = await registerFamily();
    const elder = await registerElder();
    const code = elder.bindCode;

    await api.post('/api/auth/bind', { body: { bindCode: code }, token: fam1.token });
    const b2 = await api.post('/api/auth/bind', { body: { bindCode: code }, token: fam2.token });
    assert.equal(b2.status, 200, '第二个子女必须能绑上同一老人');

    const state = await api.post('/api/auth/elder-register', {
      body: { elderId: elder.elderId, name: '', phone: elder.mobile }
    });
    assert.equal(state.status, 200);
    assert.equal(state.body.boundFamilies.length, 2, '老人端应看到两位守护人');
    assert.equal(state.body.boundFamily.id, fam1.userId, 'boundFamily 兼容字段=主守护人（先绑者）');
    assert.equal(state.body.boundFamilies[0].isPrimary, true);
    assert.equal(state.body.boundFamilies[1].isPrimary, false);

    // 两位子女都可访问该老人数据
    const u1 = await api.get(`/api/auth/user/${elder.elderId}`, { token: fam1.token });
    const u2 = await api.get(`/api/auth/user/${elder.elderId}`, { token: fam2.token });
    assert.equal(u1.status, 200);
    assert.equal(u2.status, 200);
  });

  it('解绑语义：只解除自己那一条；主守护人解绑后主守护人轮换给先绑的其余守护人', async () => {
    const fam1 = await registerFamily();
    const fam2 = await registerFamily();
    const elder = await registerElder();
    const code = elder.bindCode;

    await api.post('/api/auth/bind', { body: { bindCode: code }, token: fam1.token });
    await api.post('/api/auth/bind', { body: { bindCode: code }, token: fam2.token });

    // fam1（主守护人）解绑 → 老人守护人轮换给 fam2
    const un1 = await api.post('/api/auth/unbind', { body: { elderId: elder.elderId }, token: fam1.token });
    assert.equal(un1.status, 200);

    const state = await api.post('/api/auth/elder-register', {
      body: { elderId: elder.elderId, name: '', phone: elder.mobile }
    });
    assert.equal(state.body.boundFamilies.length, 1, 'fam1 解绑后只剩 fam2');
    assert.equal(state.body.boundFamily.id, fam2.userId, '主守护人应轮换给 fam2');

    // fam1 已无权访问；fam2 仍可访问
    const u1 = await api.get(`/api/auth/user/${elder.elderId}`, { token: fam1.token });
    const u2 = await api.get(`/api/auth/user/${elder.elderId}`, { token: fam2.token });
    assert.equal(u1.status, 403);
    assert.equal(u2.status, 200);

    // fam2 也解绑 → 老人回到未绑定态（boundFamily 显式 null）
    const un2 = await api.post('/api/auth/unbind', { body: { elderId: elder.elderId }, token: fam2.token });
    assert.equal(un2.status, 200);
    const state2 = await api.post('/api/auth/elder-register', {
      body: { elderId: elder.elderId, name: '', phone: elder.mobile }
    });
    assert.equal(state2.body.boundFamily, null);
    assert.equal(state2.body.boundFamilies.length, 0);
  });

  it('解绑缺省 elderId：单绑定兼容不传参；多绑定必须显式指定否则 400', async () => {
    const fam = await registerFamily();
    const e1 = await registerElder();
    await api.post('/api/auth/bind', { body: { bindCode: e1.bindCode }, token: fam.token });

    // 另绑一位制造多绑定态
    const e2 = await registerElder();
    await api.post('/api/auth/bind', { body: { bindCode: e2.bindCode }, token: fam.token });

    const ambiguous = await api.post('/api/auth/unbind', { body: {}, token: fam.token });
    assert.equal(ambiguous.status, 400, '多绑定时缺省 elderId 必须 400，不能静默替用户选择');

    // 解绑一位后剩单绑定，缺省参数恢复可用
    await api.post('/api/auth/unbind', { body: { elderId: e1.elderId }, token: fam.token });
    const ok = await api.post('/api/auth/unbind', { body: {}, token: fam.token });
    assert.equal(ok.status, 200);
  });

  it('越权：未绑定子女访问他人老人数据 → 403', async () => {
    const fam = await registerFamily();
    const elder = await registerElder();
    const res = await api.get(`/api/auth/user/${elder.elderId}`, { token: fam.token });
    assert.equal(res.status, 403);
  });

  it('容量上限：第 6 位老人 409；解绑一位后可再绑', async () => {
    const fam = await registerFamily();
    const elders = [];
    for (let i = 0; i < 5; i++) {
      const e = await registerElder();
      elders.push(e);
      const b = await api.post('/api/auth/bind', { body: { bindCode: e.res.body.bindCode }, token: fam.token });
      assert.equal(b.status, 200, `第 ${i + 1} 位应绑上`);
    }
    const sixth = await registerElder();
    const over = await api.post('/api/auth/bind', {
      body: { bindCode: sixth.bindCode }, token: fam.token
    });
    assert.equal(over.status, 409, '超过 5 位上限应 409');
    assert.match(over.body.error, /上限/);

    // 腾位后可再绑
    await api.post('/api/auth/unbind', { body: { elderId: elders[0].elderId }, token: fam.token });
    const retry = await api.post('/api/auth/bind', {
      body: { bindCode: sixth.bindCode }, token: fam.token
    });
    assert.equal(retry.status, 200);
  });

  it('GET /my-elders 返回全量绑定列表（含失联状态摘要）', async () => {
    const fam = await registerFamily();
    const e1 = await registerElder();
    const e2 = await registerElder();
    await api.post('/api/auth/bind', { body: { bindCode: e1.bindCode }, token: fam.token });
    await api.post('/api/auth/bind', { body: { bindCode: e2.bindCode }, token: fam.token });

    const res = await api.get('/api/auth/my-elders', { token: fam.token });
    assert.equal(res.status, 200);
    assert.equal(res.body.data.length, 2);
    for (const row of res.body.data) {
      assert.ok(row.id > 0);
      assert.ok(typeof row.name === 'string');
      assert.equal(typeof row.isPrimary, 'boolean');
      // 失联摘要字段必须存在（可为 null：从未上报）
      assert.ok('livenessState' in row && 'lastReportAt' in row);
    }
  });

  it('老人端登记回执：guardSettings 恒存在 + 未绑定三态显式空（多人绑定版回归）', async () => {
    const elder = await registerElder();
    const state = elder.res.body;
    assert.equal('boundFamily' in state, true);
    assert.equal(state.boundFamily, null);
    assert.deepEqual(state.boundFamilies, []);
    assert.ok('guardSettings' in state);
  });
});
