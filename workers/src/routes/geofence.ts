/**
 * /api/geofence 路由（对齐本地 routes/geofence.js）。
 *
 * 信任模型：
 *  - 老人端免登录拉取（仅启用项与必要字段，不下发 enabled）；
 *  - 子女端增删改需登录态，写操作固定作用于自己绑定的老人（403 而不是 500）。
 * 契约点：radius 夹 [50,2000]、dwell 夹 [0,720]、坐标串名兜底成可读地名、重复删除 404。
 */
import { Hono } from 'hono';
import * as geofenceDao from '../db/dao/geofence';
import { requireFamilyAuth, type AppEnv } from '../middleware/auth';
import { toBeijingRows } from '../lib/timeFormat';
import * as geo from '../lib/geo';
import type { Env } from '../env';

/** 拿登录用户绑定的 elderId；未绑定 403（写操作不接受任意 elderId） */
async function getBoundElderId(env: Env, authUserId: number): Promise<number | null> {
  const row = await env.DB.prepare('SELECT bound_user_id FROM users WHERE id = ?')
    .bind(authUserId)
    .first<{ bound_user_id: number | null }>();
  return row?.bound_user_id ?? null;
}

/**
 * 围栏名称兜底：坐标串/超长截断串改成可读名称（优先锚点/热点推断）。
 * 否则用户看到 "GPS 位置 (28.2" 会以为配置丢了 —— 静默的可理解性故障。
 */
async function normalizeFenceName(env: Env, rawName: unknown, lat: number, lng: number): Promise<string> {
  let s = String(rawName ?? '').trim();
  const coordLike =
    !s ||
    /^(gps\s*位置)?\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]?\s*-?\d+\.?\d*\s*[\)）]?\s*$/i.test(s) ||
    /^gps\s*位置/i.test(s) ||
    /^坐标\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]/i.test(s) ||
    /[\(（]\s*\d+\.\d*\s*$/.test(s); // 被截断的半个坐标串
  if (coordLike) return geo.guessPlace(env, lat, lng, null);
  return s.length > 30 ? s.substring(0, 30) : s;
}

export const geofenceRoutes = new Hono<AppEnv>();

// 老人端拉取本账号启用的围栏（免登录，仅必要字段）
geofenceRoutes.get('/elder/:elderId', async (c) => {
  const elderId = Number(c.req.param('elderId'));
  const rows = await geofenceDao.listEnabledForElder(c.env, elderId);
  return c.json({ success: true, data: rows });
});

// 子女端查看围栏配置（含停用项；越权 403）
geofenceRoutes.get('/list/:elderId', requireFamilyAuth, async (c) => {
  const elderId = Number(c.req.param('elderId'));
  const boundId = await getBoundElderId(c.env, c.get('authUserId'));
  if (!boundId || String(boundId) !== String(elderId)) {
    return c.json({ success: false, error: '无权访问该用户的围栏配置' }, 403);
  }

  const rows = await geofenceDao.listAllForElder(c.env, elderId);
  const out = toBeijingRows(rows as unknown as Array<Record<string, unknown>>);
  // 历史围栏名可能是坐标串，实时兜底成真实地名；只有地图真名才写回库
  const merged = await Promise.all(
    out.map(async (r) => {
      if (geo.guess.looksLikeRealAddress(r.name)) {
        return { ...r, place_source: 'db' };
      }
      try {
        const { name, source } = await geo.describePlace(
          c.env,
          Number(r.latitude),
          Number(r.longitude),
          elderId
        );
        if (source === 'geo') {
          c.executionCtx.waitUntil(
            c.env.DB.prepare(
              `UPDATE geofences SET name = ? WHERE id = ? AND (name LIKE 'GPS%' OR name LIKE '坐标%' OR name LIKE '%附近 (%')`
            )
              .bind(name, r.id)
              .run()
          );
        }
        return { ...r, name, place_source: source };
      } catch {
        return r;
      }
    })
  );
  return c.json({ success: true, data: merged });
});

// 子女端新增敏感地点
geofenceRoutes.post('/add', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const { name, latitude, longitude, radius, dwellMinutes } = body || {};
  const lat = parseFloat(latitude);
  const lng = parseFloat(longitude);
  const r = parseInt(radius, 10);
  // 1-8 停留告警阈值（分钟）：0/缺省 = 只录音不额外告警，上限 720
  const dwell = isNaN(parseInt(dwellMinutes, 10)) ? 0 : Math.min(Math.max(parseInt(dwellMinutes, 10), 0), 720);

  if (!name || String(name).trim().length === 0) {
    return c.json({ success: false, error: '请填写地点名称' }, 400);
  }
  if (isNaN(lat) || lat < -90 || lat > 90 || isNaN(lng) || lng < -180 || lng > 180) {
    return c.json({ success: false, error: '经纬度无效' }, 400);
  }

  const elderId = await getBoundElderId(c.env, c.get('authUserId'));
  if (!elderId) {
    return c.json({ success: false, error: '尚未绑定守护对象，无法配置敏感地点' }, 403);
  }

  const finalName = await normalizeFenceName(c.env, name, lat, lng);
  const id = await geofenceDao.insertFence(c.env, {
    elderId,
    name: finalName,
    latitude: lat,
    longitude: lng,
    radius: isNaN(r) ? 200 : Math.min(Math.max(r, 50), 2000),
    dwellMinutes: dwell
  });
  return c.json({
    success: true,
    id,
    name: finalName,
    message: '敏感地点已登记，老人进入后自动开启环境录音存证'
  });
});

// 子女端更新围栏（字段级缺省沿用原值）
geofenceRoutes.post('/update', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const { id, name, radius, enabled, dwellMinutes } = body || {};
  if (!id) return c.json({ success: false, error: '缺失围栏 id' }, 400);

  const elderId = await getBoundElderId(c.env, c.get('authUserId'));
  if (!elderId) {
    return c.json({ success: false, error: '尚未绑定守护对象，无法配置敏感地点' }, 403);
  }

  const row = await geofenceDao.getFence(c.env, Number(id), elderId);
  if (!row) return c.json({ success: false, error: '围栏不存在' }, 404);

  const newName = name !== undefined && String(name).trim() ? String(name).trim() : row.name;
  const newRadius =
    radius !== undefined && !isNaN(parseInt(radius, 10))
      ? Math.min(Math.max(parseInt(radius, 10), 50), 2000)
      : row.radius;
  const newEnabled = enabled !== undefined ? (enabled ? 1 : 0) : row.enabled;
  const newDwell =
    dwellMinutes !== undefined && !isNaN(parseInt(dwellMinutes, 10))
      ? Math.min(Math.max(parseInt(dwellMinutes, 10), 0), 720)
      : row.dwell_minutes || 0;

  await geofenceDao.updateFence(c.env, Number(id), {
    name: newName,
    radius: newRadius,
    enabled: newEnabled,
    dwellMinutes: newDwell
  });
  return c.json({ success: true, message: '围栏配置已更新' });
});

// 子女端删除围栏
geofenceRoutes.post('/delete', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const id = body && body.id;
  if (!id) return c.json({ success: false, error: '缺失围栏 id' }, 400);

  const elderId = await getBoundElderId(c.env, c.get('authUserId'));
  if (!elderId) {
    return c.json({ success: false, error: '尚未绑定守护对象，无法配置敏感地点' }, 403);
  }

  const changes = await geofenceDao.deleteFence(c.env, Number(id), elderId);
  if (changes === 0) return c.json({ success: false, error: '围栏不存在' }, 404);
  return c.json({ success: true, message: '敏感地点已删除' });
});
