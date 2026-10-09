// 录音列表的筛选与分页：纯查询构造，不碰 DB。
//
// 为什么单独抽一层：routes/recordings.js 一 require 就会连带 database/db.js，
// 在测试进程里真的打开 data.sqlite。tests/ 现有用例全是纯函数级、无 DB 依赖，
// 这里必须维持同样的性质 —— 否则跑一次测试就污染生产库。
//
// 为什么分页必须在 SQL 里做：老实现是「LIMIT 200 拉全量 → JS 里 groupBy」，
// JS 分组发生在拿到全部行之后，切页仍然要全量拉回，流量白费。隧道实测吞吐
// 86~102KB/s，这个项目对流量敏感。

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
 * 拼接检索表达式：四个字段拼成一个串再 LIKE 一次。
 *
 * 不用「四段 OR」是因为那样要绑 4 个相同参数，占位符个数与 params 长度
 * 一旦对不上 sqlite3 会静默绑错值。一个参数更不容易出错。
 * COALESCE 是因为 transcript / fraud_verdict 常为 NULL，NULL 参与 || 会让
 * 整行结果变成 NULL，那条录音就永远搜不到。
 */
function buildSearchExpr() {
  return SEARCHABLE.map((c) => `COALESCE(${c}, '')`).join(" || ' ' || ");
}

/**
 * 构造 WHERE 片段。
 *
 * @param {{q?:string, range?:string, level?:string, evidence?:boolean}} filters
 * @param {number} [now] 注入当前时间，便于测试断言
 * @returns {{sql:string, params:Array}} sql 为完整 WHERE 子句（不含 WHERE 关键字），
 *   params 不含 elderId —— elderId 由各 build*Query 统一放在最前面
 */
function buildListWhere(filters, now) {
  const f = filters || {};
  const clauses = [BASE_WHERE];
  const params = [];

  const q = String(f.q == null ? '' : f.q).trim();
  if (q) {
    clauses.push(`(${buildSearchExpr()}) LIKE ? ESCAPE '\\'`);
    params.push('%' + escapeLikePattern(q) + '%');
  }

  const days = RANGE_DAYS[String(f.range || '')];
  if (days) {
    // recorded_at 是客户端上传的 UTC ISO 串，而 created_at 是 DB 生成的空格分隔串，
    // 两种格式在 recordings 表里都存在。裸字符串比较在临界点会错（'T' > ' '），
    // 统一走 julianday() 归一成数值再比，两种格式它都能吃。
    // recorded_at 为 NULL 的行 julianday() 得 NULL，比较为假，自然被排除。
    const at = typeof now === 'number' ? now : Date.now();
    clauses.push('julianday(recorded_at) >= julianday(?)');
    params.push(new Date(at - days * 86400000).toISOString());
  }

  const levelSql = LEVEL_SQL[String(f.level || '')];
  if (levelSql) clauses.push(levelSql);

  if (f.evidence) clauses.push('keep_as_evidence = 1');

  return { sql: clauses.join(' AND '), params };
}

/**
 * 分页参数归一化。非法值一律回落到默认，不抛错 ——
 * 子女端把参数传错时的正确表现是「看到默认列表」，不是白屏。
 */
function normalizeListPaging(query) {
  const q = query || {};
  let limit = parseInt(q.limit, 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = DEFAULT_LIMIT;
  if (limit > MAX_LIMIT) limit = MAX_LIMIT;

  let offset = parseInt(q.offset, 10);
  if (!Number.isFinite(offset) || offset < 0) offset = 0;

  return { limit, offset };
}

/**
 * 第一步：按 session_id 聚合并切页，拿到这一页的会话。
 *
 * 排序用 MAX(id) DESC 而不是 started_at DESC：分段是陆续上传的，
 * MIN(recorded_at) 虽是开始时间，但分批上传时用它排序顺序会错乱。
 * id 单调递增且与上传顺序一致，行为稳定。
 */
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
    params: [elderId, ...where.params, limit, offset],
  };
}

/**
 * 第二步：用第一页拿到的 session_id 取明细。
 *
 * 占位符个数必须与 params 长度严格一致，否则 sqlite3 会静默绑错值 ——
 * 这是本项目「静默失效」类 bug 的典型来源。
 */
function buildDetailQuery(elderId, sessionIds) {
  const ids = Array.isArray(sessionIds) ? sessionIds : [];
  if (ids.length === 0) {
    // 空列表不能拼出 IN ()，SQLite 不接受。返回空结果集而不是整表。
    return {
      sql: 'SELECT * FROM recordings WHERE 1 = 0',
      params: [],
    };
  }
  const holes = ids.map(() => '?').join(',');
  return {
    sql:
      `SELECT * FROM recordings
       WHERE elder_id = ? AND session_id IN (${holes})
       ORDER BY id DESC`,
    params: [elderId, ...sessionIds],
  };
}

/**
 * 会话总数 / 录音总数 / 诈骗数：三个统计各自独立 COUNT，都不带分页。
 *
 * 若沿用「在聚合结果里数」的老做法，翻到第二页时标题上的
 * 「共 17 段，其中 2 段诈骗」会随翻页跳变。
 */
function buildSessionCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(DISTINCT session_id) AS n FROM recordings WHERE ${where.sql}`,
    params: [elderId, ...where.params],
  };
}

function buildRecordingCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql}`,
    params: [elderId, ...where.params],
  };
}

function buildFraudCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql} AND fraud_status = 'FRAUD'`,
    params: [elderId, ...where.params],
  };
}

module.exports = {
  buildListWhere,
  normalizeListPaging,
  buildSessionQuery,
  buildDetailQuery,
  buildSessionCountQuery,
  buildRecordingCountQuery,
  buildFraudCountQuery,
  escapeLikePattern,
  SEARCHABLE,
  RANGE_DAYS,
  LEVEL_SQL,
  DEFAULT_LIMIT,
  MAX_LIMIT,
};
