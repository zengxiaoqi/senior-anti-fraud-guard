/**
 * geofences 表 DAO。语义对齐本地 routes/geofence.js：
 *  - radius 夹取 [50,2000]、dwell 夹取 [0,720] 在路由层做，DAO 存最终值；
 *  - 老人端只拉启用项（enabled=1）且只返回必要字段；
 *  - update 是"字段级缺省沿用原值"，由路由先读旧行再拼 UPDATE，DAO 提供按行更新。
 */
import type { Env } from '../../env';
import type { GeofenceRow } from '../types';

/** 老人端拉取本账号启用的围栏（免登录路径使用，只给必要字段） */
export async function listEnabledForElder(
  env: Env,
  elderId: number
): Promise<Array<Pick<GeofenceRow, 'id' | 'name' | 'latitude' | 'longitude' | 'radius' | 'dwell_minutes'>>> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, latitude, longitude, radius, dwell_minutes FROM geofences
     WHERE elder_id = ? AND enabled = 1`
  )
    .bind(elderId)
    .all<GeofenceRow>();
  return (results ?? []).map((r) => ({
    id: r.id,
    name: r.name,
    latitude: r.latitude,
    longitude: r.longitude,
    radius: r.radius,
    dwell_minutes: r.dwell_minutes
  }));
}

/** 子女端查看围栏配置（含停用项） */
export async function listAllForElder(env: Env, elderId: number): Promise<GeofenceRow[]> {
  const { results } = await env.DB.prepare(
    'SELECT * FROM geofences WHERE elder_id = ? ORDER BY id DESC'
  )
    .bind(elderId)
    .all<GeofenceRow>();
  return results ?? [];
}

export async function getFence(env: Env, id: number, elderId: number): Promise<GeofenceRow | null> {
  return env.DB.prepare('SELECT * FROM geofences WHERE id = ? AND elder_id = ?')
    .bind(id, elderId)
    .first<GeofenceRow>();
}

export async function insertFence(
  env: Env,
  args: { elderId: number; name: string; latitude: number; longitude: number; radius: number; dwellMinutes: number }
): Promise<number> {
  const res = await env.DB.prepare(
    'INSERT INTO geofences (elder_id, name, latitude, longitude, radius, dwell_minutes) VALUES (?, ?, ?, ?, ?, ?)'
  )
    .bind(args.elderId, args.name, args.latitude, args.longitude, args.radius, args.dwellMinutes)
    .run();
  return res.meta.last_row_id;
}

export async function updateFence(
  env: Env,
  id: number,
  fields: { name: string; radius: number; enabled: number; dwellMinutes: number }
): Promise<void> {
  await env.DB.prepare('UPDATE geofences SET name = ?, radius = ?, enabled = ?, dwell_minutes = ? WHERE id = ?')
    .bind(fields.name, fields.radius, fields.enabled, fields.dwellMinutes, id)
    .run();
}

export async function deleteFence(env: Env, id: number, elderId: number): Promise<number> {
  const res = await env.DB.prepare('DELETE FROM geofences WHERE id = ? AND elder_id = ?')
    .bind(id, elderId)
    .run();
  return res.meta.changes ?? 0;
}
