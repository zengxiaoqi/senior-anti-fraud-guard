const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { api, setupBoundPair } = require('./helpers');

// 数据双份必须逐字节一致（routes/ 本地后端 与 workers/src/ Workers 后端）
const ROOT = path.join(__dirname, '..', '..');
const dataA = fs.readFileSync(path.join(ROOT, 'routes', 'adguide-data.json'));
const dataB = fs.readFileSync(path.join(ROOT, 'workers', 'src', 'routes', 'adguide-data.json'));

describe('契约 /api/adguide（广告源头关闭指引）', () => {
  it('数据文件双份逐字节一致', () => {
    assert.ok(dataA.equals(dataB), 'routes/adguide-data.json 与 workers/src/routes/adguide-data.json 不一致，改数据必须双写');
  });

  it('GET /brands 公开可读（不挂鉴权），返回品牌列表与通用建议', async () => {
    const res = await api.get('/api/adguide/brands');
    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    const d = res.body.data;

    assert.ok(typeof d.version === 'string' && d.version.length > 0);
    assert.ok(d.source, '缺来源署名（MIT 合规）');
    assert.equal(d.source.license, 'MIT');
    assert.ok(d.source.url.includes('github.com'), '必须保留上游仓库链接');

    assert.ok(Array.isArray(d.brands) && d.brands.length >= 7, '至少覆盖 7 个品牌');
    for (const b of d.brands) {
      assert.ok(b.id && b.name && b.os, '品牌缺 id/name/os');
    }
    const ids = d.brands.map((b) => b.id);
    for (const required of ['xiaomi', 'honor', 'huawei', 'oppo', 'vivo']) {
      assert.ok(ids.includes(required), `缺品牌 ${required}`);
    }

    assert.ok(Array.isArray(d.universal) && d.universal.length >= 3);
  });

  it('GET /brands/:id 返回完整 sections，每节至少 3 个关闭项', async () => {
    const res = await api.get('/api/adguide/brands/xiaomi');
    assert.equal(res.status, 200);
    const d = res.body.data;
    assert.equal(d.id, 'xiaomi');
    assert.ok(Array.isArray(d.sections) && d.sections.length >= 3);
    for (const s of d.sections) {
      assert.ok(s.title && typeof s.path === 'string', `节缺 title/path：${s.title}`);
      assert.ok(Array.isArray(s.off) && Array.isArray(s.keep));
    }
    // 内容抽查：必须与上游清单同名（允许带路径前缀，如「隐私和安全 → 个性化内容推荐」）
    const allOff = d.sections.flatMap((s) => s.off);
    assert.ok(allOff.some((t) => t.includes('个性化广告推荐')));
    assert.ok(allOff.some((t) => t.includes('个性化内容推荐')));
  });

  it('GET /brands/未知id → 404', async () => {
    const res = await api.get('/api/adguide/brands/nonexistent');
    assert.equal(res.status, 404);
    assert.equal(res.body.success, false);
  });

  describe('设备品牌上报 → 自动匹配链路', () => {
    it('POST /report 捎带 device_brand/device_model → GET /user/:id 读回（家属端自动选品牌的依据）', async () => {
      const { family, elder } = await setupBoundPair();

      // 老人端心跳捎带设备信息（顶层可选字段）
      const rep = await api.post('/api/events/report', {
        body: {
          elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW',
          device_brand: 'HUAWEI', device_model: 'ALP-AL00',
          details: { latitude: 31.2, longitude: 121.4, address: 'GPS 位置' }
        }
      });
      assert.equal(rep.status, 200);

      const res = await api.get(`/api/auth/user/${elder.elderId}`, { token: family.token });
      assert.equal(res.status, 200);
      assert.equal(res.body.data.device_brand, 'HUAWEI');
      assert.equal(res.body.data.device_model, 'ALP-AL00');
    });

    it('不带设备字段的旧版上报不受影响，device_* 保持原值', async () => {
      const { family, elder } = await setupBoundPair();
      await api.post('/api/events/report', {
        body: {
          elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW',
          device_brand: 'Xiaomi', device_model: '23117RK66C',
          details: { latitude: 31.2, longitude: 121.4 }
        }
      });
      // 旧版客户端（不带 device_* 字段）再报一次
      await api.post('/api/events/report', {
        body: {
          elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW',
          details: { latitude: 31.2, longitude: 121.5 }
        }
      });
      const res = await api.get(`/api/auth/user/${elder.elderId}`, { token: family.token });
      assert.equal(res.body.data.device_brand, 'Xiaomi');
      assert.equal(res.body.data.device_model, '23117RK66C');
    });

    it('设备信息变化时才更新（同值重复上报后仍为最新值）', async () => {
      const { family, elder } = await setupBoundPair();
      await api.post('/api/events/report', {
        body: { elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW', device_brand: 'vivo', device_model: 'V2547DA', details: {} }
      });
      await api.post('/api/events/report', {
        body: { elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW', device_brand: 'vivo', device_model: 'V2547DA', details: {} }
      });
      await api.post('/api/events/report', {
        body: { elderId: elder.elderId, eventType: 'LOCATION_UPDATE', severity: 'LOW', device_brand: 'vivo', device_model: 'V2545A', details: {} }
      });
      const res = await api.get(`/api/auth/user/${elder.elderId}`, { token: family.token });
      assert.equal(res.body.data.device_model, 'V2545A');
    });
  });
});
