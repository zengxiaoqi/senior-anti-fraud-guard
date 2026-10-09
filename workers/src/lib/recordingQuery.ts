/**
 * 录音列表的筛选与分页：纯查询构造，不碰 DB。（移植自 services/recordingQuery.js）
 *
 * 为什么单独抽一层：SQL 构造与参数拼装是「双写」两端最容易漂移的地方，
 * Express 与 Workers 共用同一套口径（level 判定、LIKE 转义、分页归一），
 * 两端行为不一致时子女端会看到两套列表。
 *
 * 为什么分页必须在 SQL 里做：老实现是「LIMIT 200 拉全量 → JS 里 groupBy」，
 * JS 分组发生在拿到全部行之后，切页仍然要全量拉回，流量白费。隧道实测吞吐
 * 86~102KB/s，这个项目对流量敏感。
 */

// 注意：RANGE_DAYS / LEVEL_SQL 这两个表必须用 Object.hasOwn 收口查表，不能裸取值。
// 普通对象字面量会命中 Object.prototype 的继承键 ——
// RANGE_DAYS['constructor'] 拿到的是 Object 构造函数，
// 乘法得到 NaN 后 new Date(NaN).toISOString() 直接抛异常；
// LEVEL_SQL['constructor'] 则会把函数源码拼进 SQL。
// 这些值从 query 串直通进来（?range=constructor），不收口就是 500。
// 路由回显 appliedFilters 时必须走 resolveRange/resolveLevel，不要裸查表。

/** 时间范围 → 天数。未列出的值一律按 all 处理。 */
export const RANGE_DAYS: Record<string, number> = { '7d': 7, '30d': 30 };

/**
 * 风险等级 → SQL 片段。
 *
 * 判定口径必须在服务端定死，客户端不得各自解释。
 * PENDING / ANALYZING（研判在途）刻意不进任何一档：单列一个「待研判」
 * 对子女没有决策价值，只会让人以为那些录音有问题。
 */
export const LEVEL_SQL: Record<string, string> = {
  fraud: "fraud_status = 'FRAUD'",
  suspect: "fraud_status IN ('SUSPECT','FAILED')",
  safe: "fraud_status = 'SAFE'",
  untranscribed: "(transcript_status IN ('SKIPPED','FAILED') OR transcript IS NULL)"
};

/** 会被全文检索的文本字段：转写内容、录音地点、触发来源、AI 结论。 */
export const SEARCHABLE = ['transcript', 'place_name', 'reason', 'fraud_verdict'];

export const DEFAULT_LIMIT = 3;
export const MAX_LIMIT = 20;
const BASE_WHERE = 'elder_id = ?';

export function resolveRange(key: unknown): string {
  const k = String(key == null ? '' : key);
  return Object.hasOwn(RANGE_DAYS, k) ? k : 'all';
}

export function resolveLevel(key: unknown): string {
  const k = String(key == null ? '' : key);
  return Object.hasOwn(LEVEL_SQL, k) ? k : 'all';
}

/**
 * 给 WHERE 片段的参数前置 elderId。
 *
 * buildListWhere 的 params 里刻意不含 elderId（它的 `elder_id = ?` 留给调用方
 * 填）。如果调用方把 elderId 追加在后面，elder_id 就会绑到搜索 pattern 或时间
 * 下界上 —— SQLite 类型宽松不报错，查询静默返回 0 行。顺序只在这里写一遍。
 */
export function withElder(where: Where, elderId: number): unknown[] {
  return [elderId, ...where.params];
}

/**
 * 把用户输入里的 LIKE 通配符转义。
 * 不转义的话，子女搜一个「50%」就变成匹配全部 —— 检索结果突然从 3 条变成
 * 几百条，他会以为系统出故障。必须配合 SQL 里的 ESCAPE '\' 一起用。
 */
export function escapeLikePattern(raw: unknown): string {
  return String(raw).replace(/[\\%_]/g, (ch) => '\\' + ch);
}

export interface Where {
  sql: string;
  params: unknown[];
}

export interface ListFilters {
  q?: string;
  range?: string;
  level?: string;
  evidence?: boolean;
}

/**
 * 构造 WHERE 片段。
 *
 * @param filters 筛选条件
 * @param now 毫秒时间戳（测试注入用；缺省按当前时间算）
 * @returns sql 为完整 WHERE 子句（不含 WHERE 关键字），params 不含 elderId
 */
export function buildListWhere(filters: ListFilters | null, now?: number): Where {
  const f = filters || {};
  const clauses: string[] = [BASE_WHERE];
  const params: unknown[] = [];

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
    // 统一用 julianday() 归一到数值再比 —— 两种格式它都能吃，裸字符串比较在
    // 临界点会错（'T' > ' '）。COALESCE 给 0 被排除，等价 IS NOT NULL 过滤。
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

export interface Paging {
  limit: number;
  offset: number;
}

/**
 * 分页参数归一化。非法值一律回落到默认，不抛错 ——
 * 子女端把参数传错时的正确表现是「看到默认列表」，不是白屏。
 */
export function normalizeListPaging(query: Record<string, unknown> | null): Paging {
  const q = query || {};
  let limit = parseInt(String(q.limit ?? ''), 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = DEFAULT_LIMIT;
  if (limit > MAX_LIMIT) limit = MAX_LIMIT;

  let offset = parseInt(String(q.offset ?? ''), 10);
  if (!Number.isFinite(offset) || offset < 0) offset = 0;

  return { limit, offset };
}

/** 第一步：按 session_id 聚合并切页，拿到这一页的会话。 */
export function buildSessionQuery(elderId: number, where: Where, limit: number, offset: number) {
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
    params: [...withElder(where, elderId), limit, offset]
  };
}

/**
 * 第二步：用第一页拿到的 session_id 取明细。
 * 空列表返回空结果集：SQLite 不接受 `IN ()`，拼出来是语法错误。
 */
export function buildSessionDetailQuery(elderId: number, sessionIds: string[]) {
  const ids = Array.isArray(sessionIds) ? sessionIds : [];
  if (ids.length === 0) {
    return { sql: 'SELECT * FROM recordings WHERE 1 = 0', params: [] as unknown[] };
  }
  const holes = ids.map(() => '?').join(',');
  return {
    sql:
      `SELECT * FROM recordings
       WHERE elder_id = ? AND session_id IN (${holes})
       ORDER BY id DESC`,
    params: [elderId, ...ids] as unknown[]
  };
}

/** 会话总数（筛选后，不受 limit/offset 影响）。 */
export function buildSessionCountQuery(elderId: number, where: Where) {
  return {
    sql: `SELECT COUNT(DISTINCT session_id) AS n FROM recordings WHERE ${where.sql}`,
    params: withElder(where, elderId) as unknown[]
  };
}

/** 录音总数（筛选后，不受 limit/offset 影响）。 */
export function buildRecordingCountQuery(elderId: number, where: Where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql}`,
    params: withElder(where, elderId) as unknown[]
  };
}

/** 检出诈骗的录音数（筛选后，不受 limit/offset 影响）。 */
export function buildFraudCountQuery(elderId: number, where: Where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql} AND fraud_status = 'FRAUD'`,
    params: withElder(where, elderId) as unknown[]
  };
}
