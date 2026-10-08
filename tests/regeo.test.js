// 地点推断回归测试（regeo + placeGuess）
//
// 为什么重要：regeo 的核心承诺是"**永不返回空**"。
// 一旦返回空，子女端控制台会出现一片"未知位置"，
// 用户无法判断风险，等于整个位置模块失去意义（见 regeo.js:9）。
// 同时"没配地图 key 也能跑"是这套降级链存在的前提。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const regeo = require('../services/regeo');
const guess = require('../services/placeGuess');

// 未配置任何地图 key（测试进程不带 .env）
const NO_MAP_KEY = !process.env.AMAP_WEB_KEY && !process.env.AMAP_KEY && !process.env.TENCENT_MAP_KEY;

// ──────────────────────────────────────────────
//  1. 降级链第 0 层：坐标兜底
// ──────────────────────────────────────────────

test('coordFallback 输出格式固定为"坐标 lat, lng"（4 位小数）', () => {
  assert.equal(regeo.coordFallback(28.27381, 113.06162), '坐标 28.2738, 113.0616');
  assert.equal(regeo.coordFallback('28', '113'), '坐标 28.0000, 113.0000');
  assert.match(regeo.coordFallback(-33.86882, 151.20929), /^坐标 -33\.8688, 151\.2093$/);
});

test('非法/零值坐标一律走 coordFallback，绝不返回空串', () => {
  const bad = [[0, 0], [null, null], ['abc', 'def'], [undefined, 113], ['', '']];
  for (const [la, ln] of bad) {
    assert.equal(regeo.coordFallback(la, ln), regeo.coordFallback(la, ln)); // 不抛错
  }
  assert.ok(regeo.coordFallback(0, 0).length > 0);
});

// ──────────────────────────────────────────────
//  2. is_real_address 判定
// ──────────────────────────────────────────────

test('looksLikeRealAddress 能区分真实地名与坐标串', () => {
  const real = ['益丰大药房（门牌号）', '金鹰花园', '银行网点', '芙蓉区'];
  const fake = ['GPS 位置 (28.2738, 113.0616)', '坐标 28.2739, 113.0616', '', '  ', null, undefined, 'x'];
  for (const a of real) assert.equal(regeo.looksLikeRealAddress(a), true, `应判真实：${a}`);
  for (const a of fake) assert.equal(regeo.looksLikeRealAddress(a), false, `应判坐标串：${a}`);
});

test('placeGuess 的同名函数判定一致（两处实现应保持同步）', () => {
  for (const a of ['益丰大药房', 'GPS 位置 (1.0, 2.0)', '坐标 1.0000, 2.0000']) {
    assert.equal(
      guess.looksLikeRealAddress(a), regeo.looksLikeRealAddress(a),
      `两处对「${a}」判定不一致`
    );
  }
});

test('stripCoordTail 能剥掉坐标尾巴与"附近"后缀', () => {
  assert.equal(guess.stripCoordTail('益丰大药房附近 (28.2739, 113.0616)'), '益丰大药房');
  assert.equal(guess.stripCoordTail('金鹰花园 附近'), '金鹰花园');
  assert.equal(guess.stripCoordTail('坐标 28.2739, 113.0616'), '28.2739, 113.0616');
  assert.equal(guess.stripCoordTail(null), '');
});

// ──────────────────────────────────────────────
//  3. 距离计算
// ──────────────────────────────────────────────

test('distanceMeters 与理论值一致（1e-3 度 ≈ 111.19m）', () => {
  assert.ok(Math.abs(guess.distanceMeters(0, 0, 0.001, 0) - 111.19) < 0.05);
  assert.ok(Math.abs(guess.distanceMeters(28.2739, 113.0616, 28.2749, 113.0616) - 111.19) < 0.05);
  assert.equal(guess.distanceMeters(28.2739, 113.0616, 28.2739, 113.0616), 0);
});

test('经度距离在高纬度处显著收缩（北京 vs 赤道）', () => {
  const equator = guess.distanceMeters(0, 0, 0, 0.001);
  const beijing = guess.distanceMeters(39.9, 116.4, 39.9, 116.401);
  // 北京纬度 cos(39.9°) ≈ 0.767，所以经度 1e-3 度只走 85.3m
  assert.ok(beijing < equator * 0.8, `北京 ${beijing}m 应明显小于赤道 ${equator}m`);
  assert.ok(Math.abs(beijing - 111.19 * Math.cos(39.9 * Math.PI / 180)) < 0.05);
});

// ──────────────────────────────────────────────
//  4. 降级链第 2 层：锚点继承
// ──────────────────────────────────────────────

test('锚点在 300m 内则继承其名称', () => {
  const anchors = [{ name: '老妈养生馆', lat: 28.2739, lng: 113.0616 }];
  // 北偏约 0.0005 度 ≈ 55m
  const hit = guess.inheritFromAnchor(28.2744, 113.0616, anchors, { maxDist: 300 });
  assert.ok(hit);
  assert.equal(hit.name, '老妈养生馆');
  assert.ok(hit.distance > 30 && hit.distance < 80, `距离 ${hit.distance}m 超出预期`);
});

test('锚点超出 300m 不继承（避免"去了机场显示家附近的银行"式误导）', () => {
  const anchors = [{ name: '家', lat: 28.2739, lng: 113.0616 }];
  // 北偏 0.01 度 ≈ 1113m，远超阈值
  assert.equal(guess.inheritFromAnchor(28.2839, 113.0616, anchors, { maxDist: 300 }), null);
});

test('锚点若本身是坐标串则不参与继承（防止自举出坐标标签）', () => {
  const anchors = [{ name: 'GPS 位置 (28.2739, 113.0616)', lat: 28.2739, lng: 113.0616 }];
  assert.equal(guess.inheritFromAnchor(28.2740, 113.0616, anchors, { maxDist: 300 }), null);
});

test('已知约束：名字带坐标尾巴的锚点不会进入继承（stripCoordTail 对此不可达）', () => {
  // inheritFromAnchor 先用 looksLikeRealAddress 过滤锚点（placeGuess.js:60-64），
  // 而该函数会拒绝一切"以坐标对结尾"的字符串，于是
  // stripCoordTail 里专门剥坐标尾巴的那段分支对锚点继承路径**永远不会执行**。
  // 它目前只在剥"附近"后缀时起作用。保留断言是为了：若将来调整过滤顺序，这里会先失败。
  const coordTailed = { name: '老妈养生馆 附近 (28.2739, 113.0616)', lat: 28.2739, lng: 113.0616 };
  assert.equal(guess.inheritFromAnchor(28.2740, 113.0616, [coordTailed], { maxDist: 300 }), null);

  // 但只要坐标尾巴被去掉，同样的锚点就能正常继承
  const clean = { name: '老妈养生馆 附近', lat: 28.2739, lng: 113.0616 };
  const hit = guess.inheritFromAnchor(28.2740, 113.0616, [clean], { maxDist: 300 });
  assert.ok(hit);
  assert.equal(hit.name, '老妈养生馆', '继承时会剥掉"附近"后缀');
});

test('已知约束：单字锚点名（长度 1）被 looksLikeRealAddress 拒绝', () => {
  // looksLikeRealAddress 末尾要求 s.length > 1，因此"店""家"这类单字地名无法作为锚点。
  const hit = guess.inheritFromAnchor(28.2740, 113.0616, [{ name: '家', lat: 28.2739, lng: 113.0616 }], { maxDist: 300 });
  assert.equal(hit, null, '单字锚点当前不可用');
  // 两字即可
  const ok = guess.inheritFromAnchor(28.2740, 113.0616, [{ name: '老家', lat: 28.2739, lng: 113.0616 }], { maxDist: 300 });
  assert.equal(ok.name, '老家');
});

test('多条锚点时选中最近的，而不是第一个', () => {
  const anchors = [
    { name: '远店', lat: 28.2800, lng: 113.0616 },
    { name: '近店', lat: 28.2740, lng: 113.0616 },
  ];
  const hit = guess.inheritFromAnchor(28.2741, 113.0616, anchors, { maxDist: 300 });
  assert.equal(hit.name, '近店');
});

test('describeInherited 按距离分三档表述', () => {
  assert.equal(guess.describeInherited({ name: '养生馆', distance: 10 }), '养生馆附近');
  assert.equal(guess.describeInherited({ name: '养生馆', distance: 100 }), '养生馆约100米内');
  assert.match(guess.describeInherited({ name: '养生馆', distance: 260 }), /^养生馆约\d+米$/);
  assert.equal(guess.describeInherited(null), '');
});

// ──────────────────────────────────────────────
//  5. 降级链第 3 层：停留热点聚类
// ──────────────────────────────────────────────

test('邻近点聚成同一簇并按次数降序编号', () => {
  const rows = [];
  for (let i = 0; i < 5; i++) rows.push({ lat: 28.2739 + i * 0.0002, lng: 113.0616 });
  for (let i = 0; i < 2; i++) rows.push({ lat: 28.2900 + i * 0.0002, lng: 113.0800 });

  const map = guess.clusterHotspots(rows, { radius: 120, minCount: 2 });
  assert.equal(map.size, 2);
  const ranks = [...map.values()].map((c) => c.rank).sort();
  assert.deepEqual(ranks, [1, 2]);
  const top = [...map.values()].find((c) => c.rank === 1);
  assert.equal(top.count, 5, '次数最多的簇应排第 1');
});

test('minCount 过滤掉只出现一次的孤立点', () => {
  const rows = [{ lat: 28.2739, lng: 113.0616 }, { lat: 28.2900, lng: 113.0800 }];
  assert.equal(guess.clusterHotspots(rows, { radius: 120, minCount: 2 }).size, 0);
  assert.equal(guess.clusterHotspots(rows, { radius: 120, minCount: 1 }).size, 2);
});

test('clusterHotspots 兼容数据库行字段名并拒绝 0/非法点', () => {
  const rows = [
    { latitude: 28.2739, longitude: 113.0616 },
    { latitude: 28.2740, longitude: 113.0616 },
    { latitude: 0, longitude: 0 },
    { latitude: 'abc', longitude: 'def' },
    null,
    undefined,
  ];
  const map = guess.clusterHotspots(rows, { radius: 120, minCount: 2 });
  assert.equal(map.size, 1);
  assert.equal([...map.values()][0].count, 2, '只有两个有效点');
});

test('clusterHotspots 空输入返回空 Map', () => {
  assert.equal(guess.clusterHotspots([]).size, 0);
  assert.equal(guess.clusterHotspots(null).size, 0);
});

test('findCluster 按半径判定归属，簇外返回 null', () => {
  const rows = [{ lat: 28.2739, lng: 113.0616 }, { lat: 28.2740, lng: 113.0616 }];
  const map = guess.clusterHotspots(rows, { radius: 120, minCount: 2 });
  assert.ok(guess.findCluster(28.2740, 113.0616, map, 200));
  assert.equal(guess.findCluster(30, 120, map, 200), null);
  assert.equal(guess.findCluster(28.2740, 113.0616, new Map(), 200), null);
});

// ──────────────────────────────────────────────
//  6. regeo 对外接口：无 key 时必须全部降级且不崩
// ──────────────────────────────────────────────

test('providerName 在无 key 时明确报 none 而不是含糊值', () => {
  const p = regeo.providerName();
  assert.equal(typeof p, 'string');
  assert.ok(p.length > 0);
  if (NO_MAP_KEY) assert.ok(p.startsWith('none'), `无 key 时应为 none，实际 ${p}`);
});

test('cacheKey 按 1e-4 度网格量化，GPS 微抖动命中同一格', () => {
  // 注意：实现用的是 toFixed(4)，是"四舍五入"而非截断，
  // 因此取点时要避开恰好落在 x.xxxx5 的进位边界上。
  assert.equal(regeo.cacheKey(28.27381, 113.06161), regeo.cacheKey(28.27384, 113.06164));
  // 约 111m（整整 1e-3 度）必须落到不同格
  assert.notEqual(regeo.cacheKey(28.27381, 113.06161), regeo.cacheKey(28.28381, 113.06161));
  assert.equal(regeo.cacheKey(28.27386, 113.06162), '28.2739,113.0616');
});

test('reverseGeocode 无 key 时直接返回坐标兜底，不发网络请求', async () => {
  if (!NO_MAP_KEY) return; // 有 key 的环境不做此断言
  const name = await regeo.reverseGeocode(28.2739, 113.0616);
  assert.equal(name, '坐标 28.2739, 113.0616');
});

test('reverseGeocode 对 0/NaN 坐标返回兜底而非抛错', async () => {
  for (const [la, ln] of [[0, 0], ['abc', 'def'], [null, null]]) {
    const r = await regeo.reverseGeocode(la, ln);
    assert.ok(r && r.length > 0, `${la},${ln} 应返回兜底文案`);
  }
});

test('guessPlace 无 db 时降级为坐标兜底', async () => {
  const r = await regeo.guessPlace(28.2739, 113.0616, null, 101);
  assert.match(r, /^坐标\s/);
});

test('guessPlace 有 db 但无任何数据时仍降级为坐标兜底', async () => {
  const emptyDb = { all: (q, p, cb) => cb(null, []) };
  const r = await regeo.guessPlace(28.2739, 113.0616, emptyDb, 102);
  assert.match(r, /^坐标\s/);
});

const fenceDb = (name) => ({
  all(query, params, cb) {
    if (/FROM geofences/i.test(query)) {
      return cb(null, [{ name, latitude: 28.2739, longitude: 113.0616 }]);
    }
    return cb(null, []);
  },
});

test('有用户登记围栏时 guessPlace 借其名称（覆盖"老人去了机场显示家附近"）', async () => {
  // 距锚点约 55m
  const r = await regeo.guessPlace(28.2744, 113.0616, fenceDb('老妈养生馆'), 201);
  assert.match(r, /^老妈养生馆/);
});

test('已知行为：guessPlace 按 elderId 缓存 5 分钟，新登记的围栏最多 5 分钟后才生效', async () => {
  // regeo.js:299-320 的 HOTSPOT_TTL_MS = 5min，缓存键含 elderId。
  // 后果：子女端刚登记一个新围栏，老人下一次位置上报可能仍显示旧的"常去地点①"。
  // 前端/运营需要知道这个延迟；要立即生效得改成调 buildContext(db, elderId, force=true)。
  const first = await regeo.guessPlace(28.2740, 113.0616, fenceDb('旧店'), 777);
  assert.match(first, /^旧店/);

  const cached = await regeo.guessPlace(28.2740, 113.0616, fenceDb('新店'), 777);
  assert.match(cached, /^旧店/, '5 分钟内仍走缓存，看不到新围栏');

  // 换一个 elderId 即重算，验证缓存确实是按 elderId 隔离的
  const other = await regeo.guessPlace(28.2740, 113.0616, fenceDb('新店'), 778);
  assert.match(other, /^新店/);
});

test('围栏锚点很老远时不借用，退回坐标兜底', async () => {
  const r = await regeo.guessPlace(40, 116, fenceDb('老妈养生馆'), 301);
  assert.match(r, /^坐标\s/);
});

test('describePlace 标注来源，便于前端区分"真实地名"与"推断地名"', async () => {
  const r = await regeo.describePlace(28.2740, 113.0616, fenceDb('老妈养生馆'), 401);
  assert.ok(['geo', 'anchor', 'hotspot', 'coord'].includes(r.source), `非法 source: ${r.source}`);
  assert.ok(r.name.length > 0);
});

test('describePlace 无任何数据时 source=coord', async () => {
  if (!NO_MAP_KEY) return;
  const emptyDb = { all: (q, p, cb) => cb(null, []) };
  const r = await regeo.describePlace(28.2739, 113.0616, emptyDb, 501);
  assert.equal(r.source, 'coord');
  assert.match(r.name, /^坐标\s/);
});

test('热点缓存不串号：不同 elderId 必须各自重算（防止"常去地点①"编号跳号）', async () => {
  const a = await regeo.guessPlace(28.2740, 113.0616, fenceDb('一号店'), 611);
  assert.match(a, /^一号店/);
  const b = await regeo.guessPlace(28.2740, 113.0616, fenceDb('二号店'), 612);
  assert.match(b, /^二号店/, '换 elderId 后必须重新查库，不能命中他人缓存');
});