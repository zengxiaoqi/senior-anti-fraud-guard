// 地点名称推断：即使拿不到地图地名，也要给用户一个"认得出来"的称呼
//
// 背景：真实反诈场景里最需要的不是精确到门牌号的地址，而是
//   "老人常去的那家养生馆""家附近""昨天去过的银行"
// 这些**相对位置关系**。让用户读一串坐标毫无意义，也判断不了风险。
//
// 三层降级（越靠前越具体）：
//   1. 地图地名        —— 高德/腾讯逆地理编码（需 key，最精确）
//   2. 最近锚点继承    —— 附近有用户手填名的地点/历史地名，就借用它
//   3. 停留热点聚类    —— 按停留时长/次数聚类，给"常去地点①/②"
//
// 第 2、3 层**不需要任何外部 key**，纯本地算。这是本文件的价值所在：
// 就算没配地图 key，界面上也永远不会只剩一串数字。

const R = 6371000; // 地球半径（米）

function toRad(d) { return d * Math.PI / 180; }

/** Haversine 距离（米） */
function distanceMeters(lat1, lng1, lat2, lng2) {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** 判断是否已经是可读地名（不是坐标串） */
function looksLikeRealAddress(addr) {
  if (!addr) return false;
  const s = String(addr).trim();
  if (!s) return false;
  if (/^gps\s*位置/i.test(s)) return false;
  if (/^坐标\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]/i.test(s)) return false;
  if (/[\(（]?\s*\d+\.\d+\s*[,，]\s*\d+\.\d+\s*[\)）]?\s*$/.test(s)) return false;
  return s.length > 1;
}

/** 从一个地址串里剥掉坐标尾巴："XX 附近 (28.27, 113.06)" → "XX" */
function stripCoordTail(addr) {
  if (!addr) return '';
  return String(addr)
    .replace(/[\(（]\s*-?\d+\.\d+\s*[,，]\s*-?\d+\.\d+\s*[\)）]\s*$/, '')
    .replace(/\s*附近\s*$/, '')
    .replace(/^\s*坐标\s*[:：]?\s*/, '')
    .trim();
}

/**
 * 最近锚点继承。
 *
 * 数据来源：用户自己手填过的地点（geofences.name、locations 里非坐标类地址）。
 * 这些是**最可信的地点名** —— 因为是子女端在"我知道这是哪"的前提下填的。
 *
 * 借用的判定要保守：只在"确实很近"时才继承，否则会出现
 * "老人去了趟机场，结果显示家附近的银行"这种误导性标签。
 */
function inheritFromAnchor(lat, lng, anchors, opts = {}) {
  const maxDist = opts.maxDist != null ? opts.maxDist : 300; // 默认 300 米
  const list = (anchors || []).filter(a =>
    a && looksLikeRealAddress(a.name) &&
    Number.isFinite(a.lat) && Number.isFinite(a.lng) &&
    a.lat !== 0 && a.lng !== 0
  );
  if (!list.length) return null;

  let best = null, bestDist = Infinity;
  for (const a of list) {
    const d = distanceMeters(lat, lng, a.lat, a.lng);
    if (d < bestDist) { bestDist = d; best = a; }
  }
  if (!best || bestDist > maxDist) return null;

  const clean = stripCoordTail(best.name);
  if (!clean) return null;
  return {
    name: clean,
    distance: Math.round(bestDist),
    anchor: clean,
  };
}

/** 把借来的锚点描述成"XX 附近"这种相对位置表述 */
function describeInherited(hit) {
  if (!hit) return '';
  const d = hit.distance;
  if (d <= 30) return `${hit.name}附近`;
  if (d <= 150) return `${hit.name}约${d}米内`;
  return `${hit.name}约${(d / 100) * 10 >= 10 ? Math.round(d / 100) * 100 : Math.round(d / 50) * 50}米`;
}

/**
 * 停留热点聚类。
 *
 * 老人活动范围极小（实测 62 个不同坐标点里绝大多数挤在 2~3 个区域）。
 * 把高频停留区编号命名成「常去地点①」，用户一看就知道"这是他常去的地方"，
 * 比坐标有信息量得多 —— 反诈要判断的正是"他常去这些地方"。
 *
 * @param rows [{lat,lng,createdAt}]（或数据库行，兼容 latitude/longitude 字段名）
 * @returns Map<clusterKey, {lat,lng,count,rank,firstAt,lastAt}>
 */
function clusterHotspots(rows, opts = {}) {
  const radius = opts.radius != null ? opts.radius : 120; // 米
  const minCount = opts.minCount != null ? opts.minCount : 2;

  const pts = (rows || [])
    .map(r => {
      if (!r) return null;
      // 兼容 {lat,lng} 与数据库行 {latitude,longitude}
      const lat = Number(r.lat != null ? r.lat : r.latitude);
      const lng = Number(r.lng != null ? r.lng : r.longitude);
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0 || lng === 0) return null;
      return { lat, lng, createdAt: r.createdAt || r.created_at || null };
    })
    .filter(Boolean);
  if (!pts.length) return new Map();

  // 单链聚类：老点数量不大（几十~几百），O(n²) 完全够用
  const clusters = [];
  for (const p of pts) {
    let hit = null;
    for (const c of clusters) {
      if (distanceMeters(p.lat, p.lng, c.lat, c.lng) <= radius) { hit = c; break; }
    }
    if (hit) {
      // 用增量平均更新中心，避免中心被单个离群点拉偏
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
      clusters.push({
        lat: p.lat, lng: p.lng, count: 1, firstAt: null, lastAt: null,
        points: [p],
      });
    }
  }

  const valid = clusters.filter(c => c.count >= minCount);
  valid.sort((a, b) => b.count - a.count);
  valid.forEach((c, i) => { c.rank = i + 1; });
  return new Map(valid.map(c => [clusterKey(c), c]));
}

function clusterKey(c) {
  return `${c.lat.toFixed(4)},${c.lng.toFixed(4)}`;
}

/** 找这个点落在哪个热点簇里 */
function findCluster(lat, lng, hotspotMap, radius = 200) {
  if (!hotspotMap || !hotspotMap.size) return null;
  let best = null, bestDist = Infinity;
  for (const c of hotspotMap.values()) {
    const d = distanceMeters(lat, lng, c.lat, c.lng);
    if (d < bestDist) { bestDist = d; best = c; }
  }
  if (!best || bestDist > radius) return null;
  return { cluster: best, distance: Math.round(bestDist) };
}

/** 坐标兜底文案：比"未知位置"有信息量 */
function coordFallback(lat, lng) {
  return `坐标 ${Number(lat).toFixed(4)}, ${Number(lng).toFixed(4)}`;
}

module.exports = {
  distanceMeters,
  looksLikeRealAddress,
  stripCoordTail,
  inheritFromAnchor,
  describeInherited,
  clusterHotspots,
  findCluster,
  coordFallback,
};