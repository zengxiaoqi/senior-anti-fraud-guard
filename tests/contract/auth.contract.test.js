const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api, randMobile, randUsername, registerFamily, registerElder, setupBoundPair, assertBeijingTime } = require('./helpers');

describe('契约 /api/auth：账号与亲情绑定', () => {
  it('POST /register 正常注册 → 返回 userId / bindCode / token', async () => {
    const username = randUsername();
    const mobile = randMobile();
    const res = await api.post('/api/auth/register', {
      body: { username, password: 'test123456', phone: mobile, name: '契约守护人' }
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    const d = res.body.data;
    assert.ok(Number.isInteger(d.userId) && d.userId > 0, 'userId 应为正整数');
    assert.match(String(d.bindCode), /^\d{6}$/, 'bindCode 应为 6 位数字');
    assert.equal(d.mobile, mobile);
    assert.equal(d.mobileMissing, false);
    assert.equal(d.boundUser, null, '新注册账号无绑定对象');
    assert.ok(typeof d.token === 'string' && d.token.length >= 32, '应签发 token');
  });

  it('POST /register 参数校验：弱密码 / 非法用户名 / 非法手机号 / 缺参', async () => {
    const bad = [
      [{ username: 'ab', password: 'test123456', phone: randMobile() }, '用户名过短'],
      [{ username: randUsername(), password: '123', phone: randMobile() }, '密码过短'],
      [{ username: randUsername(), password: 'test123456', phone: '12345' }, '手机号非法'],
      [{ username: randUsername() }, '缺 password/phone']
    ];
    for (const [body, label] of bad) {
      const res = await api.post('/api/auth/register', { body });
      assert.equal(res.status, 400, `${label} 应 400`);
      assert.ok(typeof res.body.error === 'string' && res.body.error.length > 0, `${label} 应有 error 文案`);
    }
  });

  it('POST /register 重复用户名 → 409', async () => {
    const username = randUsername();
    const first = await api.post('/api/auth/register', {
      body: { username, password: 'test123456', phone: randMobile() }
    });
    assert.equal(first.status, 200);
    const dup = await api.post('/api/auth/register', {
      body: { username, password: 'test123456', phone: randMobile() }
    });
    assert.equal(dup.status, 409);
  });

  it('POST /login 用户名/手机号任一登录，错误密码 401', async () => {
    const username = randUsername();
    const mobile = randMobile();
    await api.post('/api/auth/register', { body: { username, password: 'test123456', phone: mobile } });

    const byName = await api.post('/api/auth/login', { body: { username, password: 'test123456' } });
    assert.equal(byName.status, 200);
    assert.equal(byName.body.data.userId > 0, true);
    assert.equal(byName.body.data.mobile, mobile);
    assert.equal(byName.body.data.mobileMissing, false);
    assert.ok(byName.body.data.token);

    const byMobile = await api.post('/api/auth/login', { body: { username: mobile, password: 'test123456' } });
    assert.equal(byMobile.status, 200, '手机号应可作为登录标识');

    const wrong = await api.post('/api/auth/login', { body: { username, password: 'wrong-pass' } });
    assert.equal(wrong.status, 401);

    const missing = await api.post('/api/auth/login', { body: { username } });
    assert.equal(missing.status, 400);
  });

  it('POST /elder-register 首次激活 → 返回完整账号状态（boundFamily 显式 null）', async () => {
    const mobile = randMobile();
    const res = await api.post('/api/auth/elder-register', {
      body: { elderId: 0, name: '契约老人A', phone: mobile }
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.ok(res.body.elderId > 0, '应返回 elderId');
    assert.match(String(res.body.bindCode), /^\d{6}$/);
    assert.equal(res.body.name, '契约老人A');
    assert.equal(res.body.phone, mobile);
    // 三态约定：未绑定必须是显式 null，不能是字段缺失
    assert.equal('boundFamily' in res.body, true, 'boundFamily 字段必须存在');
    assert.equal(res.body.boundFamily, null);
    assert.equal('guardSettings' in res.body, true, 'guardSettings 字段必须存在');
  });

  it('POST /elder-register 同号再激活 → 账号找回（recoveredExistingAccount）', async () => {
    const mobile = randMobile();
    const first = await api.post('/api/auth/elder-register', { body: { elderId: 0, name: '契约老人B', phone: mobile } });
    assert.equal(first.status, 200);
    const elderId = first.body.elderId;

    // 姓名留空也要能找回，并沿用服务端原姓名（换机/重装场景）
    const again = await api.post('/api/auth/elder-register', { body: { elderId: 0, name: '', phone: mobile } });
    assert.equal(again.status, 200);
    assert.equal(again.body.elderId, elderId, '必须复用同一 elderId，不能新建');
    assert.equal(again.body.recoveredExistingAccount, true);
    assert.equal(again.body.name, '契约老人B', '空姓名应沿用服务端原姓名');
  });

  it('POST /elder-register 带 previousPhone → 换号找回同一 elderId', async () => {
    const oldMobile = randMobile();
    const newMobile = randMobile();
    const first = await api.post('/api/auth/elder-register', { body: { elderId: 0, name: '换号老人', phone: oldMobile } });
    assert.equal(first.status, 200);
    const elderId = first.body.elderId;

    const changed = await api.post('/api/auth/elder-register', {
      body: { elderId: 0, name: '', phone: newMobile, previousPhone: oldMobile }
    });
    assert.equal(changed.status, 200);
    assert.equal(changed.body.elderId, elderId, '换号必须保住同一 elderId');
    assert.equal(changed.body.recoveredByPhoneChange, true);
    assert.equal(changed.body.phone, newMobile);
  });

  it('POST /elder-register 手机号非法 → 400', async () => {
    const res = await api.post('/api/auth/elder-register', { body: { elderId: 0, name: 'x', phone: '139' } });
    assert.equal(res.status, 400);
    assert.ok(res.body.error.includes('手机号'));
  });

  it('POST /bind 双向绑定 → /user/:id 可见 boundFamily', async () => {
    const fam = await registerFamily();
    const token = fam.res.body.data.token;
    const elder = await registerElder();
    const elderId = elder.res.body.elderId;

    const bind = await api.post('/api/auth/bind', { body: { bindCode: elder.res.body.bindCode }, token });
    assert.equal(bind.status, 200);
    assert.equal(bind.body.success, true);
    assert.equal(bind.body.boundUser.id, elderId);

    const user = await api.get(`/api/auth/user/${elderId}`, { token });
    assert.equal(user.status, 200);
    assert.equal(user.body.success, true);
    assert.equal(user.body.data.id, elderId);
    assert.equal(user.body.data.bound_name, '契约测试守护人');
    // NULL 必须抹平成空串，不能让 Android optString 读出 "null"
    for (const k of ['mobile', 'display_phone']) {
      assert.notEqual(user.body.data[k], null, `${k} 不应为 JSON null`);
    }

    const missingCode = await api.post('/api/auth/bind', { body: {}, token });
    assert.equal(missingCode.status, 400);
  });

  it('POST /bind 手机号绑定 → 同样双向绑定，错误文案与绑定码路径区分', async () => {
    const fam = await registerFamily();
    const token = fam.res.body.data.token;
    const elder = await registerElder();
    const elderId = elder.res.body.elderId;

    // 正常路径：老人登记手机号
    const bind = await api.post('/api/auth/bind', { body: { phone: elder.mobile }, token });
    assert.equal(bind.status, 200);
    assert.equal(bind.body.success, true);
    assert.equal(bind.body.boundUser.id, elderId);
    assert.equal(bind.body.boundUser.name, '契约测试老人', '手机号绑定回执同样要带全 boundUser');

    // 全角数字也要能绑（中文输入法高频坑）
    const fam2 = await registerFamily();
    const elder2 = await registerElder();
    const fullWidth = elder2.mobile
      .split('')
      .map((d) => String.fromCharCode(d.charCodeAt(0) + 0xFEE0))
      .join('');
    const bind2 = await api.post('/api/auth/bind', { body: { phone: fullWidth }, token: fam2.res.body.data.token });
    assert.equal(bind2.status, 200);
    assert.equal(bind2.body.boundUser.id, elder2.res.body.elderId);

    // 未登记的手机号 → 404，文案必须是"手机号"语义，不能与绑定码报错混淆
    const notFound = await api.post('/api/auth/bind', { body: { phone: randMobile() }, token });
    assert.equal(notFound.status, 404);
    assert.ok(notFound.body.error.includes('手机号'), `应提示手机号语义，实际: ${notFound.body.error}`);

    // 格式非法 → 400
    const bad = await api.post('/api/auth/bind', { body: { phone: '123' }, token });
    assert.equal(bad.status, 400);
  });

  it('GET /user/:id 无 token → 401', async () => {
    const { elder } = await setupBoundPair();
    const res = await api.get(`/api/auth/user/${elder.elderId}`);
    assert.equal(res.status, 401);
    assert.ok(typeof res.body.error === 'string');
  });

  it('POST /unbind 解除绑定 → elder-register 回执 boundFamily 回到 null', async () => {
    const { family, elder } = await setupBoundPair();
    const unbind = await api.post('/api/auth/unbind', { token: family.token });
    assert.equal(unbind.status, 200);
    assert.equal(unbind.body.success, true);

    const state = await api.post('/api/auth/elder-register', { body: { elderId: 0, name: '', phone: elder.mobile } });
    assert.equal(state.status, 200);
    assert.equal(state.body.boundFamily, null, '解绑后回执必须回到显式 null');
  });

  it('POST /profile-mobile 补录与校验', async () => {
    const fam = await registerFamily();
    const token = fam.res.body.data.token;
    const mobile = randMobile();

    const ok = await api.post('/api/auth/profile-mobile', { body: { phone: mobile }, token });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.success, true);
    assert.equal(ok.body.mobile, mobile);

    const bad = await api.post('/api/auth/profile-mobile', { body: { phone: '123' }, token });
    assert.equal(bad.status, 400);

    const noAuth = await api.post('/api/auth/profile-mobile', { body: { phone: randMobile() } });
    assert.equal(noAuth.status, 401);
  });

  it('POST /profile-mobile 不能占用老人端登记的手机号（elder 真实号在 phone 列）', async () => {
    const fam = await registerFamily();
    const token = fam.res.body.data.token;
    // elder 账号的真实号存 phone 列（mobile 为 NULL），查重必须一并比对，
    // 否则回拨号码被设成老人自己的号，老人端"联系子女"会拨给自己
    const elderMobile = randMobile();
    const reg = await api.post('/api/auth/elder-register', {
      body: { elderId: 0, name: '查重老人', phone: elderMobile }
    });
    assert.equal(reg.status, 200);

    const dup = await api.post('/api/auth/profile-mobile', { body: { phone: elderMobile }, token });
    assert.equal(dup.status, 409);
    assert.ok(
      String(dup.body.error).includes('老人'),
      `文案应提示这是老人的号码，实际: ${dup.body.error}`
    );
  });

  it('GET/POST /elder-settings 云端同步：增量合并 + 范围夹取', async () => {
    const { elder } = await setupBoundPair();
    const elderId = elder.elderId;

    const empty = await api.get(`/api/auth/elder-settings?elderId=${elderId}`);
    assert.equal(empty.status, 200);
    assert.equal(empty.body.success, true);
    assert.equal(empty.body.settings, null, '未配置时应为 null');

    const save1 = await api.post('/api/auth/elder-settings', {
      body: { elderId, settings: { callThresholdMinutes: 20, recordingMaxSegments: 3 } }
    });
    assert.equal(save1.status, 200);
    assert.equal(save1.body.settings.callThresholdMinutes, 20);

    // 增量合并：第二项不能冲掉第一项
    const save2 = await api.post('/api/auth/elder-settings', {
      body: { elderId, settings: { paymentThreshold: 800 } }
    });
    assert.equal(save2.status, 200);
    assert.equal(save2.body.settings.callThresholdMinutes, 20, '已有项必须保留');
    assert.equal(save2.body.settings.paymentThreshold, 800);

    // 范围夹取：超上限值被夹到 240，负数被夹到 1
    const clamped = await api.post('/api/auth/elder-settings', {
      body: { elderId, settings: { callThresholdMinutes: 99999 } }
    });
    assert.equal(clamped.body.settings.callThresholdMinutes, 240);

    const reread = await api.get(`/api/auth/elder-settings?elderId=${elderId}`);
    assert.equal(reread.body.settings.paymentThreshold, 800);
    assert.equal(reread.body.settings.callThresholdMinutes, 240);

    const bad = await api.post('/api/auth/elder-settings', { body: { elderId, settings: {} } });
    assert.equal(bad.status, 400, '空配置应 400');

    const noId = await api.get('/api/auth/elder-settings');
    assert.equal(noId.status, 400);
  });

  it('GET/POST /elder-settings/family/:elderId 子女端读写：鉴权 + 白名单 + 清除家基准', async () => {
    const { family, elder } = await setupBoundPair();
    const elderId = elder.elderId;
    const token = family.token;

    // 未登录 → 401
    const noAuth = await api.get(`/api/auth/elder-settings/family/${elderId}`);
    assert.equal(noAuth.status, 401, '无 token 读取应 401');

    // 无关子女（未绑定该老人）→ 403
    const stranger = await registerFamily();
    const strangerToken = stranger.res.body.data.token;
    const forbidden = await api.get(`/api/auth/elder-settings/family/${elderId}`, { token: strangerToken });
    assert.equal(forbidden.status, 403, '未绑定关系读取应 403');

    // 绑定子女读取：未配置 → settings null
    const empty = await api.get(`/api/auth/elder-settings/family/${elderId}`, { token });
    assert.equal(empty.status, 200);
    assert.equal(empty.body.settings, null);

    // 保存：白名单字段生效，白名单外（trustedCallNumbersJson / highRiskPackages）被丢弃
    const save = await api.post(`/api/auth/elder-settings/family/${elderId}`, {
      token,
      body: {
        settings: {
          homeLat: 28.228, homeLng: 112.938,
          homeAwayRadiusMeters: 99999,
          callThresholdMinutes: 20,
          fallFreefallMinMs: 120,
          trustedCallNumbersJson: '["13800138000"]',
          highRiskPackages: '[{"pkg":"com.a","level":"HIGH"}]'
        },
        elderId: 99999
      }
    });
    assert.equal(save.status, 200);
    const s = save.body.settings;
    assert.equal(s.homeLat, 28.228);
    assert.equal(s.homeAwayRadiusMeters, 5000, '越界半径应收敛到上限');
    assert.equal(s.callThresholdMinutes, 20);
    assert.equal(s.fallFreefallMinMs, 120, '跌倒灵敏度在子女端可写范围内');
    assert.equal(s.trustedCallNumbersJson, undefined, '信任号码对子女端必须关闭');
    assert.equal(s.highRiskPackages, undefined, '高危应用规则对子女端必须关闭');
    assert.equal(s.homeLng, 112.938);

    // 跌倒灵敏度越界收敛（100~400）：低于下限的值会被"随手一丢"误触发，不能放行
    const fallClamp = await api.post(`/api/auth/elder-settings/family/${elderId}`, {
      token,
      body: { settings: { fallFreefallMinMs: 50 } }
    });
    assert.equal(fallClamp.status, 200);
    assert.equal(fallClamp.body.settings.fallFreefallMinMs, 100, '越界灵敏度应收敛到下限');

    // body 里的 elderId 不能改写目标老人（99999 账号不存在也不影响本请求）
    const reread = await api.get(`/api/auth/elder-settings/family/${elderId}`, { token });
    assert.equal(reread.body.settings.homeLat, 28.228, '保存应落在路径参数指定的老人身上');

    // 增量合并：新保存不能冲掉旧字段
    const save2 = await api.post(`/api/auth/elder-settings/family/${elderId}`, {
      token,
      body: { settings: { paymentThreshold: 800 } }
    });
    assert.equal(save2.status, 200);
    assert.equal(save2.body.settings.homeLat, 28.228, '未下发的家基准必须保留');
    assert.equal(save2.body.settings.paymentThreshold, 800);

    // homeCleared 清除家基准，其余字段不受牵连
    const clear = await api.post(`/api/auth/elder-settings/family/${elderId}`, {
      token,
      body: { settings: { homeCleared: true } }
    });
    assert.equal(clear.status, 200);
    assert.equal('homeLat' in clear.body.settings, false, 'homeCleared 应删除 homeLat');
    assert.equal('homeLng' in clear.body.settings, false, 'homeCleared 应删除 homeLng');
    assert.equal(clear.body.settings.paymentThreshold, 800, '清除家基准不应牵连其他字段');

    // 空配置且无清除指令 → 400
    const bad = await api.post(`/api/auth/elder-settings/family/${elderId}`, {
      token,
      body: { settings: { evilKey: 'x' } }
    });
    assert.equal(bad.status, 400);

    // 白名单只进不出：保存后服务端允许再读，老人端通道也能读到
    const viaElder = await api.get(`/api/auth/elder-settings?elderId=${elderId}`);
    assert.equal(viaElder.body.settings.callThresholdMinutes, 20, '老人端拉取应看到子女端写入的值');
  });

  it('POST /elder-bind-code 查询与刷新绑定码', async () => {
    const { elder } = await setupBoundPair();
    const elderId = elder.elderId;

    const query = await api.post('/api/auth/elder-bind-code', { body: { elderId } });
    assert.equal(query.status, 200);
    assert.match(String(query.body.bindCode), /^\d{6}$/);

    // 绑定码必须随机生成：生产库已有真实账号，写死的码（如 654321）会撞已有账号 409
    const code = String(Math.floor(100000 + Math.random() * 900000));
    const refresh = await api.post('/api/auth/elder-bind-code', { body: { elderId, bindCode: code } });
    assert.equal(refresh.status, 200, `随机码 ${code} 撞了已有绑定码（概率事件，重跑即可）`);
    assert.equal(refresh.body.bindCode, code);

    const invalid = await api.post('/api/auth/elder-bind-code', { body: { elderId, bindCode: '12' } });
    assert.equal(invalid.status, 400);
  });

  it('POST /change-password 旧密码验证 + 改后新密码可登录', async () => {
    const username = randUsername();
    const mobile = randMobile();
    const fam = await api.post('/api/auth/register', {
      body: { username, password: 'test123456', phone: mobile }
    });
    const token = fam.body.data.token;

    // 缺参 / 弱新密码
    assert.equal((await api.post('/api/auth/change-password', { token, body: {} })).status, 400);
    assert.equal((await api.post('/api/auth/change-password', { token, body: { oldPassword: 'test123456', newPassword: '123' } })).status, 400);
    // 无 token
    assert.equal((await api.post('/api/auth/change-password', { body: { oldPassword: 'test123456', newPassword: 'newpass123' } })).status, 401);
    // 旧密码错误
    const wrong = await api.post('/api/auth/change-password', {
      token, body: { oldPassword: 'wrong-old', newPassword: 'newpass123' }
    });
    assert.equal(wrong.status, 401);
    assert.ok(wrong.body.error.includes('旧密码'));

    // 正确改密
    const ok = await api.post('/api/auth/change-password', {
      token, body: { oldPassword: 'test123456', newPassword: 'newpass123' }
    });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.success, true);

    // 旧密码登录必须失败、新密码登录必须成功
    assert.equal((await api.post('/api/auth/login', { body: { username, password: 'test123456' } })).status, 401);
    const relogin = await api.post('/api/auth/login', { body: { username, password: 'newpass123' } });
    assert.equal(relogin.status, 200);
    assert.ok(relogin.body.data.token);
  });

  it('POST /reset-password 账号+绑定手机号双重匹配后重置', async () => {
    const username = randUsername();
    const mobile = randMobile();
    await api.post('/api/auth/register', {
      body: { username, password: 'test123456', phone: mobile }
    });

    // 缺参 / 手机号格式非法 / 弱密码
    assert.equal((await api.post('/api/auth/reset-password', { body: { username, phone: mobile } })).status, 400);
    assert.equal((await api.post('/api/auth/reset-password', { body: { username, phone: '123', newPassword: 'newpass123' } })).status, 400);
    assert.equal((await api.post('/api/auth/reset-password', { body: { username, phone: mobile, newPassword: '123' } })).status, 400);

    // 手机号与账号不匹配（用错别人的号）
    const mismatch = await api.post('/api/auth/reset-password', {
      body: { username, phone: randMobile(), newPassword: 'newpass123' }
    });
    assert.equal(mismatch.status, 404);
    assert.ok(mismatch.body.error.includes('不匹配'));

    // 用户名 + 绑定手机号匹配 → 重置成功 → 新密码可登录
    const ok = await api.post('/api/auth/reset-password', {
      body: { username, phone: mobile, newPassword: 'newpass123' }
    });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.success, true);
    assert.equal((await api.post('/api/auth/login', { body: { username, password: 'newpass123' } })).status, 200);

    // 手机号作为账号标识也能重置（与登录的标识规则一致）
    const byMobile = await api.post('/api/auth/reset-password', {
      body: { username: mobile, phone: mobile, newPassword: 'newpass456' }
    });
    assert.equal(byMobile.status, 200);
    assert.equal((await api.post('/api/auth/login', { body: { username: mobile, password: 'newpass456' } })).status, 200);
  });

  it('事件时间字段为北京时间字符串（不带 Z）', async () => {
    const { family, elder } = await setupBoundPair();
    await api.post('/api/events/report', {
      body: { elderId: elder.elderId, eventType: 'CALL_RISK', severity: 'MEDIUM', details: { note: 'time-check' } }
    });
    const list = await api.get(`/api/events/list/${elder.elderId}`, { token: family.token });
    assert.equal(list.status, 200);
    const row = list.body.data[0];
    assert.equal(typeof row.created_at, 'string');
    assert.equal(row.created_at.endsWith('Z'), false, 'created_at 不应是 UTC Z 串');
    assertBeijingTime(row.created_at, 'events.created_at');
  });
});
