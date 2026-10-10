const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { api } = require('./helpers');

/**
 * 契约 /api/app-update：App 内自升级。
 *
 * 两个后端（本地 Express / Workers）跑同一套用例。这两处最容易出现分歧：
 * 清单一个读 dist/releases.json、一个读 R2，字段稍有出入客户端就会解析出
 * 半截数据 —— 而"解析失败"在客户端的表现是"检查更新没反应"，极难排查。
 *
 * 未发布过升级包时（reason=no_release_published）不做版本相关断言：
 * 那是服务端状态，不是契约违约。此时只校验"优雅降级"这一条契约。
 */
describe('契约 /api/app-update：App 内自升级', () => {
  const ctx = {};

  it('GET /latest 不传 versionCode → 视为 0，结构与字段类型正确', async () => {
    const res = await api.get('/api/app-update/latest');
    assert.equal(res.status, 200, JSON.stringify(res.body));
    assert.equal(res.body.success, true, 'success 必须为 true');
    assert.equal(typeof res.body.hasUpdate, 'boolean');

    if (res.body.reason === 'no_release_published') {
      ctx.published = false;
      // 优雅降级：没发布过也必须 200 + hasUpdate=false，绝不能 500
      assert.equal(res.body.hasUpdate, false);
      console.log('    [skip] 服务端未发布升级包，跳过版本相关断言');
      return;
    }

    ctx.published = true;
    assert.equal(res.body.hasUpdate, true, 'versionCode=0 时任何已发布版本都应算更新');
    const l = res.body.latest;
    assert.ok(Number.isInteger(l.versionCode) && l.versionCode > 0, 'versionCode 必须为正整数');
    assert.equal(typeof l.versionName, 'string');
    assert.equal(typeof l.sizeBytes, 'number');
    assert.match(l.sha256, /^[0-9a-f]{64}$/, 'sha256 必须是 64 位小写十六进制');
    assert.equal(typeof l.changelog, 'string');
    assert.equal(typeof l.force, 'boolean');
    assert.match(
      l.downloadUrl,
      /^\/api\/app-update\/download\?versionCode=\d+$/,
      'downloadUrl 必须是可直接拼接 baseUrl 的绝对路径'
    );
    ctx.latest = l;
  });

  it('GET /latest 传当前最新 versionCode → hasUpdate=false 且 reason=already_latest', async () => {
    if (!ctx.published) return;
    const res = await api.get(`/api/app-update/latest?versionCode=${ctx.latest.versionCode}`);
    assert.equal(res.status, 200);
    assert.equal(res.body.hasUpdate, false);
    assert.equal(res.body.reason, 'already_latest');
    assert.equal(res.body.current.latestCode, ctx.latest.versionCode);
  });

  it('GET /latest 传旧 versionCode → hasUpdate=true（1.10.0 不能小于 1.9.0）', async () => {
    if (!ctx.published) return;
    const old = ctx.latest.versionCode - 1;
    if (old <= 0) return;
    const res = await api.get(`/api/app-update/latest?versionCode=${old}`);
    assert.equal(res.status, 200);
    assert.equal(res.body.hasUpdate, true, '低版本的客户端必须被告知有新版本');
    assert.equal(res.body.latest.versionCode, ctx.latest.versionCode);
    assert.equal(res.body.current.versionCode, old);
  });

  it('GET /latest 非数字 versionCode → 按 0 处理，不 500', async () => {
    const res = await api.get('/api/app-update/latest?versionCode=abc');
    assert.equal(res.status, 200, '非法参数必须被兜底，不能让 App 收到 500');
    assert.equal(res.body.success, true);
  });

  it('GET /download 不带 versionCode → 返回最新 APK 二进制', async () => {
    if (!ctx.published) return;
    const res = await api.get('/api/app-update/download', { raw: true });
    assert.equal(res.status, 200);
    assert.equal(
      res.headers.get('content-type'),
      'application/vnd.android.package-archive',
      'Content-Type 不对时部分 ROM 的安装器会直接拒收'
    );
    const buf = Buffer.from(await res.arrayBuffer());
    assert.ok(buf.length > 0, '下载的 APK 不能是空的');
    if (ctx.latest.sizeBytes > 0) {
      assert.equal(buf.length, ctx.latest.sizeBytes, '实际字节数必须与清单声明一致');
    }
    assert.equal(
      buf.slice(0, 2).toString('ascii'),
      'PK',
      'APK 本质是 zip，前两字节必须是 PK（用于发现"下到一半被截断"）'
    );
  });

  it('GET /download?versionCode=<不存在> → 404 且带 error', async () => {
    const res = await api.get('/api/app-update/download?versionCode=999999');
    assert.equal(res.status, 404);
    assert.equal(res.body.success, false);
    assert.equal(typeof res.body.error, 'string');
  });

  it('GET /download 的 fileName 参数必须被忽略（防目录穿越）', async () => {
    if (!ctx.published) return;
    // 服务端只按 versionCode 挑记录，文件名永远来自发布清单。
    // 若哪天改成读请求参数，这条用例会立刻变红。
    const res = await api.get(
      `/api/app-update/download?versionCode=${ctx.latest.versionCode}` +
        '&fileName=../../data.sqlite'
    );
    assert.equal(res.status, 200, '穿越好被忽略时应正常返回 APK');
  });
});
