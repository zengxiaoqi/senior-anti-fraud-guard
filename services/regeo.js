// 逆地理编码：WGS-84/GCJ-02 坐标 → 人类可读地名
//
// 为什么必须有这个：老人端上报的 address 只是 "GPS 位置 (28.2738, 113.0616)"
// 这种坐标串。子女端控制台、轨迹列表拿到的全是数字，人根本没法判断"这是哪"。
// 反诈场景里地点本身就是关键信息 —— "在保健品店门口"和"在银行门口"风险完全不同，
// 看不到地名等于这个功能没有意义。
//
// 关键设计：
//  - 同步接口 reverseGeocode()：拿不到地名时**返回坐标串本身**，绝不让调用方拿到空值
//  - 异步补全 enrichLocation()：写入库后异步查、查到再 UPDATE，接口不阻塞
//  - 坐标缓存：老人常年待在同一个点（家里、店里），按 ~11m 网格缓存，避免重复请求
//  - Provider 可切换：AMAP（高德）/ TENCENT（腾讯）/ NONE

const https = require('https');
const http = require('http');
const { URL } = require('url');

const PROVIDER = (process.env.GEO_PROVIDER || '').toUpperCase();
const AMAP_KEY = process.env.AMAP_WEB_KEY || process.env.AMAP_KEY || '';
const TENCENT_KEY = process.env.TENCENT_MAP_KEY || '';

const TIMEOUT_MS = parseInt(process.env.GEO_TIMEOUT_MS || '5000', 10);

// ── 坐标缓存 ──
// key 用网格化坐标（约 0.0001° ≈ 11m），老人在同一地点反复上报时命中缓存，
// 既省配额又避免同一地点因为微小 GPS 抖动查出不同名字。
const CACHE = new Map();
const CACHE_MAX = 5000;

function cacheKey(lat, lng) {
  return `${lat.toFixed(4)},${lng.toFixed(4)}`;
}

function cacheGet(key) {
  const hit = CACHE.get(key);
  if (!hit) return null;
  // 1 天后过期，避免营业场所改名后长期显示旧名
  if (Date.now() - hit.at > 24 * 3600 * 1000) {
    CACHE.delete(key);
    return null;
  }
  return hit.name;
}

function cacheSet(key, name) {
  if (CACHE.size >= CACHE_MAX) {
    // 简单淘汰最早的 1/4，避免 Map 无限增长
    const drop = Math.floor(CACHE_MAX / 4);
    let i = 0;
    for (const k of CACHE.keys()) {
      CACHE.delete(k);
      if (++i >= drop) break;
    }
  }
  CACHE.set(key, { name, at: Date.now() });
}

/** 坐标兜底文案：查不到地名时用，至少比"未知位置"有信息量 */
function coordFallback(lat, lng) {
  return `坐标 ${Number(lat).toFixed(4)}, ${Number(lng).toFixed(4)}`;
}

/** 判断是否已经是可读地名（不是坐标串） */
function looksLikeRealAddress(addr) {
  if (!addr) return false;
  const s = String(addr).trim();
  if (!s) return false;
  // 坐标串特征：以数字+逗号结尾、或含 GPS 字样
  if (/^gps\s*位置/i.test(s)) return false;
  if (/[\(（]?\s*\d+\.\d+\s*[,，]\s*\d+\.\d+\s*[\)）]?\s*$/.test(s)) return false;
  return s.length > 1;
}

function httpGetJson(urlStr) {
  return new Promise((resolve, reject) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch (e) {
      return reject(new Error('非法 URL'));
    }
    const mod = u.protocol === 'http:' ? http : https;
    const req = mod.get(urlStr, { timeout: TIMEOUT_MS }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode}`));
      }
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', (c) => {
        buf += c;
        if (buf.length > 100000) req.destroy(); // 防御异常大响应
      });
      res.on('end', () => {
        try {
          resolve(JSON.parse(buf));
        } catch (e) {
          reject(new Error('响应非合法 JSON'));
        }
      });
    });
    req.on('timeout', () => req.destroy(new Error('逆地理编码超时')));
    req.on('error', reject);
  });
}

/** 从高德 regeo 结果里拼出可读地址 */
function pickAmapAddress(re) {
  if (!re) return '';
  const comp = re.addressComponent || {};
  const poi = re.poi || {};
  // POI 名最直观（如"益丰大药房"），优先作为地名
  if (poi.name && String(poi.name).trim()) {
    const loc = poi.address || poi.adname || '';
    // 高德 regeo 的 poi.address 常是门牌号，补成"POI名（门牌）"更好认
    return String(poi.name).trim() + (loc ? `（${String(loc).trim()}）` : '');
  }
  const parts = [];
  const township = Array.isArray(comp.township) ? comp.township.join('') : (comp.township || '');
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

async function reverseAmap(lat, lng) {
  // 高德 regeo 要求 GCJ-02。老人端给的是 WGS-84，必须先纠偏，否则会偏到隔壁街道。
  const g = wgs2gcj(lat, lng);
  const url = `https://restapi.amap.com/v3/geocode/regeo?key=${encodeURIComponent(AMAP_KEY)}` +
    `&location=${g.lng.toFixed(6)},${g.lat.toFixed(6)}&extensions=base`;
  const json = await httpGetJson(url);
  if (json.status !== '1') {
    throw new Error(`高德返回异常: ${json.info || json.infocode}`);
  }
  return pickAmapAddress(json.regeocode);
}

async function reverseTencent(lat, lng) {
  const g = wgs2gcj(lat, lng);
  const url = `https://apis.map.qq.com/ws/geocoder/v1/?key=${encodeURIComponent(TENCENT_KEY)}` +
    `&location=${g.lat.toFixed(6)},${g.lng.toFixed(6)}`;
  const json = await httpGetJson(url);
  if (json.status !== 0) {
    throw new Error(`腾讯返回异常: ${json.message || json.status}`);
  }
  const r = json.result || {};
  return r.address || r.title || r.formatted_addresses?.[0] || '';
}

/* ---------- WGS-84 → GCJ-02 纠偏（与 GeoConverter.kt 同算法） ---------- */
const PI = Math.PI;
const A = 6378245.0;
const EE = 0.00669342162296594323;

function outOfChina(lat, lng) {
  return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
}
function transformLat(x, y) {
  let ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  ret += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
  ret += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
  return ret;
}
function transformLng(x, y) {
  let ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  ret += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
  ret += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
  return ret;
}
function wgs2gcjLatLng(lat, lng) {
  if (outOfChina(lat, lng)) return [lat, lng];
  let dLat = transformLat(lng - 105.0, lat - 35.0);
  let dLng = transformLng(lng - 105.0, lat - 35.0);
  const radLat = lat / 180.0 * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
  dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
  return [lat + dLat, lng + dLng];
}
function wgs2gcj(wgsLat, wgsLng) {
  const [lat, lng] = wgs2gcjLatLng(wgsLat, wgsLng);
  return { lat, lng };
}

/** 当前是否可用（用于启动日志与健康检查） */
function providerName() {
  if (PROVIDER === 'NONE') return 'none';
  if (PROVIDER === 'TENCENT') return TENCENT_KEY ? 'tencent' : 'none(缺 TENCENT_MAP_KEY)';
  if (PROVIDER === 'AMAP') return AMAP_KEY ? 'amap' : 'none(缺 AMAP_WEB_KEY)';
  // 未显式指定：有高德 key 就用高德，否则腾讯，否则降级
  if (AMAP_KEY) return 'amap';
  if (TENCENT_KEY) return 'tencent';
  return 'none(未配置地图 key)';
}

function isEnabled() {
  return providerName().startsWith('amap') || providerName().startsWith('tencent');
}

/**
 * 同步取地名：命中缓存直接返回，否则返回坐标兜底（**永不返回空**）。
 * 用于接口出口实时拼装 —— 不能让一个外部 HTTP 请求阻塞用户看列表。
 */
async function reverseGeocode(lat, lng) {
  const la = parseFloat(lat), ln = parseFloat(lng);
  if (isNaN(la) || isNaN(ln) || la === 0 || ln === 0) return coordFallback(lat, lng);
  if (!isEnabled()) return coordFallback(la, ln);

  const key = cacheKey(la, ln);
  const cached = cacheGet(key);
  if (cached) return cached;

  try {
    const name = PROVIDER === 'TENCENT' && !AMAP_KEY
      ? await reverseTencent(la, ln)
      : await reverseAmap(la, ln);
    if (name && name.trim()) {
      cacheSet(key, name.trim());
      return name.trim();
    }
  } catch (err) {
    // 失败只记一次简短日志，不抛出：地图服务不可用不该影响主链路
    console.warn(`[geo] 逆地理编码失败 (${la.toFixed(4)}, ${ln.toFixed(4)}): ${err.message}`);
  }
  return coordFallback(la, ln);
}

/**
 * 异步补全：写入库之后调，查到地名再 UPDATE 那一行。
 * 不 await、不阻塞接口 —— 老人端上报位置必须秒回。
 */
function enrichLocation(rowId, lat, lng, db) {
  const la = parseFloat(lat), ln = parseFloat(lng);
  if (isNaN(la) || isNaN(ln) || !isEnabled()) return Promise.resolve(null);
  reverseGeocode(la, ln).then((name) => {
    if (!name || name === coordFallback(la, ln)) return; // 没查到就别覆盖
    db.run(
      'UPDATE locations SET address = ? WHERE id = ? AND (address IS NULL OR address LIKE \'GPS%\' OR address LIKE \'坐标%\')',
      [name, rowId],
      function () { if (this.changes > 0) console.log(`📍 地点已补全 #${rowId}: ${name}`); }
    );
  }).catch(() => { /* 已由 reverseGeocode 内部兜底 */ });
}

/* ============================================================
 *  无 key 也能用的地点推断（从 regeo 挪过来作为最终兜底）
 * ============================================================ */

const guess = require('./placeGuess');

// 热点簇缓存：轨迹数据量小，但每次请求都重算一遍聚类也没必要
let hotspotCache = { at: 0, map: new Map(), anchors: [] };
const HOTSPOT_TTL_MS = 5 * 60 * 1000;

/**
 * 从库里收集"地点锚点"。
 *
 * 锚点 = 子女端/老人端**主动填过**的地点名（登记的围栏、被识别出的历史地址）。
 * 这是最可信的地名来源 —— 因为是人在"知道这是哪"的前提下填的，
 * 比任何机器猜测都可靠。借过来给附近的坐标点用，比展示一串数字强得多。
 */
function loadAnchors(db, elderId) {
  const eid = elderId || null;
  return new Promise((resolve) => {
    const result = [];
    db.all(`SELECT name, latitude, longitude FROM geofences
            WHERE (? IS NULL OR elder_id = ?)`, [eid, eid],
      (e1, gs) => {
        (gs || []).forEach(g => result.push({ name: g.name, lat: g.latitude, lng: g.longitude }));
        db.all(`SELECT DISTINCT address, latitude, longitude FROM locations
                WHERE address IS NOT NULL AND address NOT LIKE 'GPS%'
                  AND address NOT LIKE '坐标%' AND address <> ''
                  AND (? IS NULL OR elder_id = ?)`,
          [eid, eid],
          (e2, ls) => {
            (ls || []).forEach(l => result.push({ name: l.address, lat: l.latitude, lng: l.longitude }));
            resolve(result);
          });
      });
  });
}

/** 构建（或复用缓存的）热点簇 + 锚点集合 */
function buildContext(db, elderId, force) {
  const now = Date.now();
  // elderId 必须严格相等才算命中缓存：串到别的老人的数据会让"常去地点①"
  // 变成全局排名，界面上出现编号跳号（这个坑实际踩过一次）
  const sameElder = hotspotCache.elderId === (elderId || null);
  if (!force && sameElder && now - hotspotCache.at < HOTSPOT_TTL_MS) {
    return Promise.resolve(hotspotCache);
  }
  const eid = elderId || null;
  return new Promise((resolve) => {
    db.all(`SELECT latitude, longitude, created_at FROM locations
            WHERE (? IS NULL OR elder_id = ?) ORDER BY id DESC LIMIT 500`,
      [eid, eid],
      (e, rows) => {
        const map = guess.clusterHotspots(rows || [], { radius: 120, minCount: 2 });
        loadAnchors(db, eid).then((anchors) => {
          hotspotCache = { at: now, map, anchors, elderId: eid };
          resolve(hotspotCache);
        });
      });
  });
}

/**
 * 地点推断（最终兜底）：地图查不到时，用本地关系给出可认的称呼。
 *
 * 返回形如：
 *   "某某养生体验馆附近"        ← 附近有用户命名的锚点
 *   "常去地点①（51 次上报）"     ← 落在高频停留簇里
 *   "常去地点② 约300 米"         ← 靠近高频簇但在簇外
 *   "坐标 28.2739, 113.0616"     ← 实在什么都没有
 */
async function guessPlace(lat, lng, db, elderId) {
  const la = parseFloat(lat), ln = parseFloat(lng);
  if (isNaN(la) || isNaN(ln) || la === 0 || ln === 0) return coordFallback(lat, lng);
  if (!db) return coordFallback(la, ln);

  let ctx;
  try {
    ctx = await buildContext(db, elderId, false);
  } catch (e) {
    return coordFallback(la, ln);
  }

  const hit = guess.inheritFromAnchor(la, ln, ctx.anchors, { maxDist: 300 });
  if (hit) return guess.describeInherited(hit);

  const cl = guess.findCluster(la, ln, ctx.map, 200);
  if (cl) {
    const ordinal = '①②③④⑤⑥⑦⑧⑨⑩'[cl.cluster.rank - 1] || String(cl.cluster.rank);
    return cl.distance <= 150
      ? `常去地点${ordinal}（累计 ${cl.cluster.count} 次上报）`
      : `常去地点${ordinal} 约${Math.round(cl.distance / 50) * 50}米`;
  }

  return coordFallback(la, ln);
}

/**
 * 地点描述（含来源标记），供接口出口使用。
 * @returns {name, source} source: geo | anchor | hotspot | coord
 */
async function describePlace(lat, lng, db, elderId) {
  const la = parseFloat(lat), ln = parseFloat(lng);
  // 1) 优先地图地名（高德/腾讯；没配 key 时 reverseGeocode 直接返坐标兜底）
  const name = await reverseGeocode(la, ln);
  if (name && !/^坐标\s/.test(name)) return { name, source: 'geo' };

  // 2) 本地关系推断
  const g = await guessPlace(la, ln, db, elderId);
  const source = /^坐标\s/.test(g) ? 'coord'
    : (/附近|约\d+米内/.test(g)) ? 'anchor'
    : 'hotspot';
  return { name: g, source };
}

module.exports = {
  reverseGeocode,
  enrichLocation,
  guessPlace,
  describePlace,
  providerName,
  isEnabled,
  looksLikeRealAddress,
  coordFallback,
  cacheKey,
};