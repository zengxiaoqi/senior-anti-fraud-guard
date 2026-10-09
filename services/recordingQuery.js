// 录音列表的筛选与分页：纯查询构造，不碰 DB。
//
// 为什么单独抽一层：routes/recordings.js 一 require 就会连带 database/db.js，
// 在测试进程里真的打开 data.sqlite。tests/ 的用例必须是纯函数级、不碰真实库
// （tests/recordingQuery.test.js 里的集成用例也只用 sqlite3 的 :memory:）——
// 否则跑一次测试就污染生产库。
//
// 为什么分页必须在 SQL 里做：老实现是「LIMIT 200 拉全量 → JS 里 groupBy」，
// JS 分组发生在拿到全部行之后，切页仍然要全量拉回，流量白费。隧道实测吞吐
// 86~102KB/s，这个项目对流量敏感。

// 注意：RANGE_DAYS / LEVEL_SQL 这两个表必须用 Object.hasOwn 收口查表，不能裸取值。
// 普通对象字面量会命中 Object.prototype 的继承键 ——
// RANGE_DAYS['constructor'] 拿到的是 Object 构造函数，
// 乘法得到 NaN 后 new Date(NaN).toISOString() 直接抛异常；
// LEVEL_SQL['constructor'] 则会把函数源码拼进 SQL。
// 这些值从 query 串直通进来（?range=constructor），不收口就是 500。
// 任何拿这类表反查用户输入的地方都要照这个写法收口。
// 路由回显 appliedFilters 时也必须走 resolveRange/resolveLevel，不要裸查表。
/** 时间范围 → 天数。未列出的值一律按 all 处理。 */
const RANGE_DAYS = { '7d': 7, '30d': 30 };

/**
 * 风险等级 → SQL 片段。
 *
 * 判定口径必须在服务端定死，客户端不得各自解释。
 * PENDING / ANALYZING（研判在途）刻意不进任何一档：单列一个「待研判」
 * 对子女没有决策价值，只会让人以为那些录音有问题。
 */
const LEVEL_SQL = {
  fraud: "fraud_status = 'FRAUD'",
  suspect: "fraud_status IN ('SUSPECT','FAILED')",
  safe: "fraud_status = 'SAFE'",
  untranscribed: "(transcript_status IN ('SKIPPED','FAILED') OR transcript IS NULL)",
};

/** 会被全文检索的文本字段：转写内容、录音地点、触发来源、AI 结论。 */
const SEARCHABLE = ['transcript', 'place_name', 'reason', 'fraud_verdict'];

const DEFAULT_LIMIT = 3;
const MAX_LIMIT = 20;
const BASE_WHERE = 'elder_id = ?';

/**
 * 安全地把用户的 range 取值归一成合法 key。
 *
 * 不能用 `RANGE_DAYS[key] ? key : 'all'` —— 继承来的 Object 构造函数是
 * truthy，`RANGE_DAYS['constructor']` 会返回 'constructor'，
 * 于是服务端没施加这个筛选、回包却说「已筛选」，客户端渲染出一个
 * 没生效的筛选 chip。这种「服务端状态与客户端显示不一致」比报错更难查。
 */
function resolveRange(key) {
  const k = String(key == null ? '' : key);
  return Object.hasOwn(RANGE_DAYS, k) ? k : 'all';
}

/** 同 resolveRange，风险等级版本。level=all 自身不在表里，也要回 'all'。 */
function resolveLevel(key) {
  const k = String(key == null ? '' : key);
  return Object.hasOwn(LEVEL_SQL, k) ? k : 'all';
}

/**
 * 给 WHERE 片段的参数前置 elderId。
 *
 * 为什么要有这个函数：buildListWhere 的 params 里刻意不含 elderId
 * （它的 `elder_id = ?` 留给调用方填）。如果调用方写成
 * `params: [...where.params, elderId]`，elder_id 就会绑到搜索 pattern
 * 或时间下界上 —— SQLite 类型宽松不报错，查询静默返回 0 行，
 * 子女端只看到空列表，控制台无任何痕迹。极端情况下筛选值恰为数字串时
 * 可能匹配到别的 elder，属于越权读。
 *
 * 顺序只在这里写一遍，调用方没有机会写反。
 */
function withElder(where, elderId) {
  return [elderId, ...where.params];
}

/**
 * 把用户输入里的 LIKE 通配符转义。
 *
 * 不转义的话，子女搜一个「50%」就变成匹配全部 —— 这不是小问题：
 * 检索结果突然从 3 条变成几百条，他会以为系统出故障。
 * 必须配合 SQL 里的 ESCAPE '\' 一起用。
 */
function escapeLikePattern(raw) {
  return String(raw).replace(/[\\%_]/g, (ch) => '\\' + ch);
}

/**
 * 构造 WHERE 片段。
 *
 * @param {{q?:string, range?:string, level?:string, evidence?:boolean}} filters
 * @param {number} [now] 毫秒时间戳（测试注入用；非 number 时按当前时间算）
 * @returns {{sql:string, params:Array}} sql 为完整 WHERE 子句（不含 WHERE 关键字），
 *   params 不含 elderId —— 由 withElder 统一前置
 */
function buildListWhere(filters, now) {
  const f = filters || {};
  const clauses = [BASE_WHERE];
  const params = [];

  const q = String(f.q == null ? '' : f.q).trim();
  if (q) {
    const like = '%' + escapeLikePattern(q) + '%';
    clauses.push('(' + SEARCHABLE.map((c) => `${c} LIKE ? ESCAPE '\\'`).join(' OR ') + ')');
    // 一个 like 值要重复 SEARCHABLE.length 次，顺序与占位符一致
    for (let i = 0; i < SEARCHABLE.length; i++) params.push(like);
  }

  const rangeKey = resolveRange(f.range);
  const days = Object.hasOwn(RANGE_DAYS, rangeKey) ? RANGE_DAYS[rangeKey] : undefined;
  if (days) {
    // recorded_at 是 UTC ISO 串（客户端上传），与 DB 生成的 created_at 空格格式不同。
    // 统一用 julianday() 归一到数值再比 —— 两种格式它都能吃。
    // 实测三种格式 SQLite 均正常解析（都得到同一个 julian day）：
    //   '2026-10-08T12:40:37Z' / '2026-10-08T12:40:37.000Z' / '2026-10-08 12:40:37'
    // 所以绝不能简化成裸字符串比较 —— 混合格式下会静默漏掉一部分录音。
    //
    // COALESCE 给 0，被 '>= 正数' 排除，等价于 IS NOT NULL 过滤，
    // 但写成单子句少一条 NULL 处理路径。
    const at = typeof now === 'number' ? now : Date.now();
    clauses.push('COALESCE(julianday(recorded_at), 0) >= julianday(?)');
    params.push(new Date(at - days * 86400000).toISOString());
  }

  const levelKey = resolveLevel(f.level);
  const levelSql = Object.hasOwn(LEVEL_SQL, levelKey) ? LEVEL_SQL[levelKey] : undefined;
  if (levelSql) clauses.push(levelSql);

  if (f.evidence) clauses.push('keep_as_evidence = 1');

  return { sql: clauses.join(' AND '), params };
}

/**
 * 分页参数归一化。非法值一律回落到默认，不抛错 ——
 * 子女端把参数传错时的正确表现是「看到默认列表」，不是白屏。
 *
 * 但要留日志：静默回落本身是好的，**静默无声**不是。「客户端想要 100 条被夹到 20」
 * 和「参数名写错了」这两种情况，如果完全不出声，排查时会白查很久。
 *
 * 只在「传了但非法」时告警：完全没传（首屏默认 3 条）是正常路径，
 * 每次请求都刷两行 warn，真正的参数问题反而会被淹掉。
 *
 * 副作用说明：本函数唯一的副作用是 console.warn（日志不是状态变更），
 * 返回值仍是纯函数式的，不读写任何状态。
 */
function normalizeListPaging(query) {
  const q = query || {};
  const given = (v) => v != null && v !== '';

  let limit = parseInt(q.limit, 10);
  if (!Number.isFinite(limit) || limit <= 0) {
    if (given(q.limit)) console.warn('[recordings] limit 参数非法，回落默认:', q.limit);
    limit = DEFAULT_LIMIT;
  } else if (limit > MAX_LIMIT) {
    console.warn(`[recordings] limit=${limit} 超过上限，夹到 ${MAX_LIMIT}`);
    limit = MAX_LIMIT;
  }

  let offset = parseInt(q.offset, 10);
  if (!Number.isFinite(offset) || offset < 0) {
    if (given(q.offset)) console.warn('[recordings] offset 参数非法，归零:', q.offset);
    offset = 0;
  }

  return { limit, offset };
}

/** 第一步：按 session_id 聚合并切页，拿到这一页的会话。 */
function buildSessionQuery(elderId, where, limit, offset) {
  return {
    sql:
      `SELECT session_id,
              COUNT(*)         AS segment_count,
              SUM(duration_ms) AS total_duration_ms,
              MIN(recorded_at) AS started_at,
              MAX(id)          AS last_id
       FROM recordings
       WHERE ${where.sql}
       GROUP BY session_id
       ORDER BY last_id DESC
       LIMIT ? OFFSET ?`,
    params: [...withElder(where, elderId), limit, offset],
  };
}

/**
 * 第二步：用第一页拿到的 session_id 取明细。
 *
 * 占位符个数必须与 params 长度严格一致，否则 sqlite3 会静默绑错值 ——
 * 这是本项目「静默失效」类 bug 的典型来源。
 *
 * 空列表返回空结果集：SQLite 不接受 `IN ()`，拼出来是语法错误，
 * 而「这一页没有会话」是正常路径（比如筛得太狠），不该打 500。
 */
function buildSessionDetailQuery(elderId, sessionIds) {
  const ids = Array.isArray(sessionIds) ? sessionIds : [];
  if (ids.length === 0) {
    return { sql: 'SELECT * FROM recordings WHERE 1 = 0', params: [] };
  }
  const holes = ids.map(() => '?').join(',');
  return {
    sql:
      `SELECT * FROM recordings
       WHERE elder_id = ? AND session_id IN (${holes})
       ORDER BY id DESC`,
    params: [elderId, ...ids],
  };
}

/** 会话总数（筛选后，不受 limit/offset 影响）。 */
function buildSessionCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(DISTINCT session_id) AS n FROM recordings WHERE ${where.sql}`,
    params: withElder(where, elderId),
  };
}

/** 录音总数（筛选后，不受 limit/offset 影响）。 */
function buildRecordingCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql}`,
    params: withElder(where, elderId),
  };
}

/** 检出诈骗的录音数（筛选后，不受 limit/offset 影响）。 */
function buildFraudCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql} AND fraud_status = 'FRAUD'`,
    params: withElder(where, elderId),
  };
}

module.exports = {
  buildListWhere,
  normalizeListPaging,
  buildSessionQuery,
  buildSessionDetailQuery,
  buildSessionCountQuery,
  buildRecordingCountQuery,
  buildFraudCountQuery,
  escapeLikePattern,
  resolveRange,
  resolveLevel,
  withElder,
  RANGE_DAYS,
  LEVEL_SQL,
  SEARCHABLE,
  DEFAULT_LIMIT,
  MAX_LIMIT,
};
