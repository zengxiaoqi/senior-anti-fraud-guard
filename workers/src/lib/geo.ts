/**
 * 逆地理编码 + 地点推断（对齐本地 services/regeo.js）。
 *
 * 与本地的差异（Workers 环境适配）：
 *  - http/https 模块 → fetch（5s 超时用 AbortSignal.timeout）；
 *  - 坐标缓存内存 Map → CACHE KV（1 天 TTL，多隔离实例共享）；
 *  - 配置从环境变量来（wrangler vars / secrets）：GEO_PROVIDER / AMAP_WEB_KEY / TENCENT_MAP_KEY；
 *  - 无 key 时所有查询直接走本地推断（锚点继承 + 热点聚类），零外部请求。
 *
 * 关键语义原样保留：
 *  - describePlace 永不返回空值，最坏给坐标兜底；
 *  - 推断出的"常去地点①/XX附近"是描述而非真名，不写回库覆盖原始上报。
 */
import * as guess from './placeGuess';
import type { Env } from '../env';

const TIMEOUT_MS = 5000;

// ── WGS-84 → GCJ-02 纠偏（与 GeoConverter.kt 同算法） ──────────────
const PI = Math.PI;
const A = 6378245.0;
const EE = 0.00669342162296594323;

function outOfChina(lat: number, lng: number): boolean {
  return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
}

function transformLat(x: number, y: number): number {
  let ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2) / 3;
  ret += ((20 * Math.sin(y * PI) + 40 * Math.sin((y / 3) * PI)) * 2) / 3;
  ret += ((160 * Math.sin((y / 12) * PI) + 320 * Math.sin((y * PI) / 30)) * 2) / 3;
  return ret;
}

function transformLng(x: number, y: number): number {
  let ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2) / 3;
  ret += ((20 * Math.sin((x / 12) * PI) + 40 * Math.sin((x / 30) * PI)) * 2) / 3;
  ret += ((150 * Math.sin((x / 6) * PI) + 300 * Math.sin((x / 15) * PI)) * 2) / 3;
  return ret;
}

export function wgs2gcj(wgsLat: number, wgsLng: number): { lat: number; lng: number } {
  if (outOfChina(wgsLat, wgsLng)) return { lat: wgsLat, lng: wgsLng };
  let dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0);
  let dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0);
  const radLat = (wgsLat / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((A * (1 - EE)) / (magic * sqrtMagic)) * PI);
  dLng = (dLng * 180.0) / ((A / sqrtMagic) * Math.cos(radLat) * PI);
  return { lat: wgsLat + dLat, lng: wgsLng + dLng };
}

// ── Provider 配置与缓存 ────────────────────────────────────────────

export function providerName(env: Env): string {
  const provider = (env.GEO_PROVIDER || '').toUpperCase();
  const amapKey = env.AMAP_WEB_KEY || '';
  const tencentKey = env.TENCENT_MAP_KEY || '';
  if (provider === 'NONE') return 'none';
  if (provider === 'TENCENT') return tencentKey ? 'tencent' : 'none(缺 TENCENT_MAP_KEY)';
  if (provider === 'AMAP') return amapKey ? 'amap' : 'none(缺 AMAP_WEB_KEY)';
  if (amapKey) return 'amap';
  if (tencentKey) return 'tencent';
  return 'none(未配置地图 key)';
}

function isEnabled(env: Env): boolean {
  const p = providerName(env);
  return p.startsWith('amap') || p.startsWith('tencent');
}

function cacheKeyOf(lat: number, lng: number): string {
  return `geo:${lat.toFixed(4)},${lng.toFixed(4)}`;
}

// ── 高德 / 腾讯 regeo ──────────────────────────────────────────────

function pickAmapAddress(re: Record<string, any>): string {
  if (!re) return '';
  const comp = re.addressComponent || {};
  const poi = re.poi || {};
  if (poi.name && String(poi.name).trim()) {
    const loc = poi.address || poi.adname || '';
    return String(poi.name).trim() + (loc ? `（${String(loc).trim()}）` : '');
  }
  const parts: string[] = [];
  const township = Array.isArray(comp.township) ? comp.township.join('') : comp.township || '';
  if (comp.streetNumber && comp.streetNumber.length) {
    parts.push(`${township}${comp.streetNumber.street || ''}${comp.streetNumber.number || ''}`);
  }
  if (!parts.length && comp.neighborhood && comp.neighborhood.name) {
    parts.push(comp.neighborhood.name);
  }
  if (!parts.length && township) parts.push(township);
  if (!parts.length && comp.district) parts.push(comp.district);
  if (!parts.length && comp.city) parts.push(comp.city);

  const formatted = re.formatted_address || '';
  if (parts.length && formatted && formatted.indexOf(parts[0]) === -1) {
    return `${parts[0]} 附近`;
  }
  return formatted || parts[0] || '';
}

async function reverseAmap(env: Env, lat: number, lng: number): Promise<string> {
  const g = wgs2gcj(lat, lng);
  const url =
    `https://restapi.amap.com/v3/geocode/regeo?key=${encodeURIComponent(env.AMAP_WEB_KEY!)}` +
    `&location=${g.lng.toFixed(6)},${g.lat.toFixed(6)}&extensions=base`;
  const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json: any = await res.json();
  if (json.status !== '1') throw new Error(`高德返回异常: ${json.info || json.infocode}`);
  return pickAmapAddress(json.regeocode);
}

async function reverseTencent(env: Env, lat: number, lng: number): Promise<string> {
  const g = wgs2gcj(lat, lng);
  const url =
    `https://apis.map.qq.com/ws/geocoder/v1/?key=${encodeURIComponent(env.TENCENT_MAP_KEY!)}` +
    `&location=${g.lat.toFixed(6)},${g.lng.toFixed(6)}`;
  const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json: any = await res.json();
  if (json.status !== 0) throw new Error(`腾讯返回异常: ${json.message || json.status}`);
  const r = json.result || {};
  return r.address || r.title || (r.formatted_addresses && r.formatted_addresses[0]) || '';
}

/**
 * 同步取地名（异步签名）：命中 KV 缓存直接返回，否则返回坐标兜底（**永不返回空**）。
 */
export async function reverseGeocode(env: Env, lat: number, lng: number): Promise<string> {
  const la = parseFloat(String(lat));
  const ln = parseFloat(String(lng));
  if (isNaN(la) || isNaN(ln) || la === 0 || ln === 0) return guess.coordFallback(lat, lng);
  if (!isEnabled(env)) return guess.coordFallback(la, ln);

  const key = cacheKeyOf(la, ln);
  try {
    const cached = await env.CACHE.get(key);
    if (cached) return cached;
  } catch {
    // 缓存不可用不阻断主链路
  }

  try {
    const useTencent = providerName(env) === 'tencent';
    const name = useTencent ? await reverseTencent(env, la, ln) : await reverseAmap(env, la, ln);
    if (name && name.trim()) {
      // 写缓存失败无所谓，下次多查一次而已
      env.ctx?.waitUntil?.(env.CACHE.put(key, name.trim(), { expirationTtl: 86400 }));
      return name.trim();
    }
  } catch {
    // 地图服务不可用不该影响主链路，落坐标兜底
  }
  return guess.coordFallback(la, ln);
}

// ── 本地推断（锚点 + 热点，D1 查询） ───────────────────────────────

interface Anchor extends guess.Anchor {
  source: string;
}

/** 收集"地点锚点"：子女端主动填过的围栏名 + 历史真实地名（最可信的地点名来源） */
async function loadAnchors(env: Env, elderId: number | null): Promise<Anchor[]> {
  const eid = elderId ?? null;
  const out: Anchor[] = [];
  const fences = await env.DB.prepare(
    'SELECT name, latitude, longitude FROM geofences WHERE (? IS NULL OR elder_id = ?)'
  )
    .bind(eid, eid)
    .all<{ name: string; latitude: number; longitude: number }>();
  for (const g of fences.results ?? []) {
    out.push({ name: g.name, lat: g.latitude, lng: g.longitude, source: 'db' });
  }
  const locs = await env.DB.prepare(
    `SELECT DISTINCT address, latitude, longitude FROM locations
     WHERE address IS NOT NULL AND address NOT LIKE 'GPS%'
       AND address NOT LIKE '坐标%' AND address <> ''
       AND (? IS NULL OR elder_id = ?)`
  )
    .bind(eid, eid)
    .all<{ address: string; latitude: number; longitude: number }>();
  for (const l of locs.results ?? []) {
    out.push({ name: l.address, lat: l.latitude, lng: l.longitude, source: 'db' });
  }
  return out;
}

/** 构建（或复用 KV 缓存的）热点簇 + 锚点集合 */
async function buildContext(env: Env, elderId: number | null): Promise<{ map: Map<string, guess.HotspotCluster>; anchors: Anchor[] }> {
  const eid = elderId ?? null;
  const cacheKey = `geo:ctx:${eid ?? 'all'}`;
  try {
    const cached = (await env.CACHE.get(cacheKey, 'json')) as {
      at?: number;
      map?: Record<string, guess.HotspotCluster>;
      anchors?: Anchor[];
    } | null;
    if (cached && cached.at && Date.now() - cached.at < 5 * 60 * 1000) {
      return {
        map: new Map(Object.entries(cached.map ?? {})),
        anchors: cached.anchors ?? []
      };
    }
  } catch {
    // 缓存不可用就现算
  }

  const locs = await env.DB.prepare(
    'SELECT latitude, longitude, created_at FROM locations WHERE (? IS NULL OR elder_id = ?) ORDER BY id DESC LIMIT 500'
  )
    .bind(eid, eid)
    .all<Record<string, unknown>>();
  const map = guess.clusterHotspots(locs.results ?? [], { radius: 120, minCount: 2 });
  const anchors = await loadAnchors(env, eid);

  // Map → plain object 才能进 KV json
  const plainMap: Record<string, guess.HotspotCluster> = {};
  for (const [k, v] of map) plainMap[k] = v;
  env.ctx?.waitUntil?.(
    env.CACHE.put(cacheKey, JSON.stringify({ at: Date.now(), map: plainMap, anchors }), { expirationTtl: 300 })
  );
  return { map, anchors };
}

/**
 * 地点推断（最终兜底）：地图查不到时，用本地关系给出可认的称呼。
 * 返回 "锚点名附近" / "常去地点①（N 次上报）" / "常去地点② 约300米" / "坐标 x, y"
 */
export async function guessPlace(env: Env, lat: number, lng: number, elderId: number | null): Promise<string> {
  const la = parseFloat(String(lat));
  const ln = parseFloat(String(lng));
  if (isNaN(la) || isNaN(ln) || la === 0 || ln === 0) return guess.coordFallback(lat, lng);

  let ctx: Awaited<ReturnType<typeof buildContext>>;
  try {
    ctx = await buildContext(env, elderId);
  } catch {
    return guess.coordFallback(la, ln);
  }

  const hit = guess.inheritFromAnchor(la, ln, ctx.anchors, { maxDist: 300 });
  if (hit) return guess.describeInherited(hit);

  const cl = guess.findCluster(la, ln, ctx.map, 200);
  if (cl) {
    const ordinal = '①②③④⑤⑥⑦⑧⑨⑩'[(cl.cluster.rank ?? 1) - 1] || String(cl.cluster.rank);
    return cl.distance <= 150
      ? `常去地点${ordinal}（累计 ${cl.cluster.count} 次上报）`
      : `常去地点${ordinal} 约${Math.round(cl.distance / 50) * 50}米`;
  }

  return guess.coordFallback(la, ln);
}

/**
 * 地点描述（含来源标记），供接口出口使用。
 * source: geo | anchor | hotspot | coord
 */
export async function describePlace(
  env: Env,
  lat: number,
  lng: number,
  elderId: number | null
): Promise<{ name: string; source: string }> {
  const la = parseFloat(String(lat));
  const ln = parseFloat(String(lng));
  // 1) 优先地图地名（没配 key 时 reverseGeocode 直接返坐标兜底）
  const name = await reverseGeocode(env, la, ln);
  if (name && !/^坐标\s/.test(name)) return { name, source: 'geo' };

  // 2) 本地关系推断
  const g = await guessPlace(env, la, ln, elderId);
  const source = /^坐标\s/.test(g) ? 'coord' : /附近|约\d+米内/.test(g) ? 'anchor' : 'hotspot';
  return { name: g, source };
}

export { guess };
