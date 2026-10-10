/**
 * 地点名称推断（对齐本地 services/placeGuess.js，纯本地计算、零外部依赖）。
 *
 * 三层降级里最坏情况的兜底：就算没配地图 key，界面上也永远不会只剩一串数字。
 *   2. 最近锚点继承 —— 附近有用户手填名的地点/历史地名，就借用它
 *   3. 停留热点聚类 —— 按停留时长/次数聚类，给"常去地点①/②"
 */

const R = 6371000; // 地球半径（米）

function toRad(d: number): number {
  return (d * Math.PI) / 180;
}

/** Haversine 距离（米） */
export function distanceMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** 判断是否已经是可读地名（不是坐标串） */
export function looksLikeRealAddress(addr: unknown): boolean {
  if (!addr) return false;
  const s = String(addr).trim();
  if (!s) return false;
  if (/^gps\s*位置/i.test(s)) return false;
  if (/^坐标\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]/i.test(s)) return false;
  if (/[\(（]?\s*\d+\.\d+\s*[,，]\s*\d+\.\d+\s*[\)）]?\s*$/.test(s)) return false;
  return s.length > 1;
}

/** 从一个地址串里剥掉坐标尾巴："XX 附近 (28.27, 113.06)" → "XX" */
export function stripCoordTail(addr: unknown): string {
  if (!addr) return '';
  return String(addr)
    .replace(/[\(（]\s*-?\d+\.\d+\s*[,，]\s*-?\d+\.\d+\s*[\)）]\s*$/, '')
    .replace(/\s*附近\s*$/, '')
    .replace(/^\s*坐标\s*[:：]?\s*/, '')
    .trim();
}

export interface Anchor {
  name: string;
  lat: number;
  lng: number;
}

/**
 * 最近锚点继承。判定保守：只在"确实很近"（默认 300 米）时才继承，
 * 否则会出现"老人去了趟机场，结果显示家附近的银行"这种误导性标签。
 */
export function inheritFromAnchor(
  lat: number,
  lng: number,
  anchors: Anchor[] | null,
  opts: { maxDist?: number } = {}
): { name: string; distance: number; anchor: string } | null {
  const maxDist = opts.maxDist != null ? opts.maxDist : 300;
  const list = (anchors ?? []).filter(
    (a) =>
      a &&
      looksLikeRealAddress(a.name) &&
      Number.isFinite(a.lat) &&
      Number.isFinite(a.lng) &&
      a.lat !== 0 &&
      a.lng !== 0
  );
  if (!list.length) return null;

  let best: Anchor | null = null;
  let bestDist = Infinity;
  for (const a of list) {
    const d = distanceMeters(lat, lng, a.lat, a.lng);
    if (d < bestDist) {
      bestDist = d;
      best = a;
    }
  }
  if (!best || bestDist > maxDist) return null;

  const clean = stripCoordTail(best.name);
  if (!clean) return null;
  return { name: clean, distance: Math.round(bestDist), anchor: clean };
}

/** 把借来的锚点描述成"XX 附近"这种相对位置表述 */
export function describeInherited(hit: { name: string; distance: number } | null): string {
  if (!hit) return '';
  const d = hit.distance;
  if (d <= 30) return `${hit.name}附近`;
  if (d <= 150) return `${hit.name}约${d}米内`;
  return `${hit.name}约${(d / 100) * 10 >= 10 ? Math.round(d / 100) * 100 : Math.round(d / 50) * 50}米`;
}

export interface HotspotCluster {
  lat: number;
  lng: number;
  count: number;
  rank?: number;
  firstAt: number | null;
  lastAt: number | null;
}

/**
 * 停留热点聚类。老人活动范围极小，把高频停留区编号命名成「常去地点①」。
 * 单链聚类：老点数量不大（几十~几百），O(n²) 完全够用。
 */
export function clusterHotspots(
  rows: Array<Record<string, unknown>> | null,
  opts: { radius?: number; minCount?: number } = {}
): Map<string, HotspotCluster> {
  const radius = opts.radius != null ? opts.radius : 120;
  const minCount = opts.minCount != null ? opts.minCount : 2;

  const pts = (rows ?? [])
    .map((r) => {
      if (!r) return null;
      const lat = Number(r.lat != null ? r.lat : r.latitude);
      const lng = Number(r.lng != null ? r.lng : r.longitude);
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) return null;
      return { lat, lng, createdAt: (r.createdAt || r.created_at || null) as string | null };
    })
    .filter((p): p is { lat: number; lng: number; createdAt: string | null } => !!p);
  if (!pts.length) return new Map();

  const clusters: Array<HotspotCluster & { points: unknown[] }> = [];
  for (const p of pts) {
    let hit: (typeof clusters)[number] | null = null;
    for (const c of clusters) {
      if (distanceMeters(p.lat, p.lng, c.lat, c.lng) <= radius) {
        hit = c;
        break;
      }
    }
    if (hit) {
      const n = hit.count;
      hit.lat = (hit.lat * n + p.lat) / (n + 1);
      hit.lng = (hit.lng * n + p.lng) / (n + 1);
      hit.count = n + 1;
      hit.points.push(p);
      const t = p.createdAt ? new Date(String(p.createdAt).replace(' ', 'T') + 'Z').getTime() : null;
      if (t && !isNaN(t)) {
        if (!hit.firstAt || t < hit.firstAt) hit.firstAt = t;
        if (!hit.lastAt || t > hit.lastAt) hit.lastAt = t;
      }
    } else {
      clusters.push({ lat: p.lat, lng: p.lng, count: 1, firstAt: null, lastAt: null, points: [p] });
    }
  }

  const valid = clusters.filter((c) => c.count >= minCount);
  valid.sort((a, b) => b.count - a.count);
  valid.forEach((c, i) => {
    c.rank = i + 1;
  });
  return new Map(valid.map((c) => [`${c.lat.toFixed(4)},${c.lng.toFixed(4)}`, c]));
}

/** 找这个点落在哪个热点簇里 */
export function findCluster(
  lat: number,
  lng: number,
  hotspotMap: Map<string, HotspotCluster> | null,
  radius = 200
): { cluster: HotspotCluster; distance: number } | null {
  if (!hotspotMap || !hotspotMap.size) return null;
  let best: HotspotCluster | null = null;
  let bestDist = Infinity;
  for (const c of hotspotMap.values()) {
    const d = distanceMeters(lat, lng, c.lat, c.lng);
    if (d < bestDist) {
      bestDist = d;
      best = c;
    }
  }
  if (!best || bestDist > radius) return null;
  return { cluster: best, distance: Math.round(bestDist) };
}

/** 坐标兜底文案：比"未知位置"有信息量 */
export function coordFallback(lat: unknown, lng: unknown): string {
  return `坐标 ${Number(lat).toFixed(4)}, ${Number(lng).toFixed(4)}`;
}
