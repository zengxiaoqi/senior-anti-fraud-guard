/**
 * events 相关三张表（risk_events / payments / locations）的 DAO。
 *
 * 语义对齐本地 routes/events.js：
 *  - severity 归一化在路由层做（EVENT_SEVERITY_OVERRIDE），DAO 只存最终值；
 *  - PAYMENT_RISK 时同时写 payments，带坐标时同时写 locations（两次写入独立，一侧失败不回滚另一侧，
 *    与本地 db.run 回调行为一致）；
 *  - created_at 用 D1 的 DEFAULT CURRENT_TIMESTAMP（与本地 SQLite 相同的 UTC 裸串格式，
 *    客户端双格式兼容逻辑已覆盖，见项目笔记"时间显示"铁律）。
 */
import type { Env } from '../../env';
import type { RiskEventRow, PaymentRow, LocationRow } from '../types';

export async function insertRiskEvent(
  env: Env,
  args: { elderId: number; eventType: string; severity: string; details: string | null }
): Promise<number> {
  const res = await env.DB.prepare(
    'INSERT INTO risk_events (elder_id, event_type, severity, details) VALUES (?, ?, ?, ?)'
  )
    .bind(args.elderId, args.eventType, args.severity, args.details)
    .run();
  return res.meta.last_row_id;
}

export async function insertPayment(
  env: Env,
  args: {
    elderId: number;
    amount: number;
    payeeName: string;
    payeeAccount: string;
    orderNo: string;
  }
): Promise<void> {
  await env.DB.prepare(
    'INSERT INTO payments (elder_id, amount, payee_name, payee_account, order_no) VALUES (?, ?, ?, ?, ?)'
  )
    .bind(args.elderId, args.amount, args.payeeName, args.payeeAccount, args.orderNo)
    .run();
}

export async function insertLocation(
  env: Env,
  args: { elderId: number; latitude: number; longitude: number; address: string; isSensitive: number }
): Promise<number> {
  const res = await env.DB.prepare(
    'INSERT INTO locations (elder_id, latitude, longitude, address, is_sensitive) VALUES (?, ?, ?, ?, ?)'
  )
    .bind(args.elderId, args.latitude, args.longitude, args.address, args.isSensitive)
    .run();
  return res.meta.last_row_id;
}

/** 异步补全地名后回写（对应本地 geo.enrichLocation 的 UPDATE） */
export async function updateLocationAddress(env: Env, id: number, address: string): Promise<void> {
  await env.DB.prepare('UPDATE locations SET address = ? WHERE id = ?').bind(address, id).run();
}

/** 告警详情坐标补地名后回写（对齐本地 UPDATE risk_events SET details） */
export async function updateRiskEventDetails(env: Env, id: number, details: string): Promise<void> {
  await env.DB.prepare('UPDATE risk_events SET details = ? WHERE id = ?').bind(details, id).run();
}

export async function listRiskEvents(env: Env, elderId: number, limit: number): Promise<RiskEventRow[]> {
  const { results } = await env.DB.prepare(
    'SELECT * FROM risk_events WHERE elder_id = ? ORDER BY id DESC LIMIT ?'
  )
    .bind(elderId, limit)
    .all<RiskEventRow>();
  return results ?? [];
}

export async function listRecentLocations(env: Env, elderId: number, limit = 10): Promise<LocationRow[]> {
  const { results } = await env.DB.prepare(
    'SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT ?'
  )
    .bind(elderId, limit)
    .all<LocationRow>();
  return results ?? [];
}

export async function listPayments(env: Env, elderId: number, limit = 50): Promise<PaymentRow[]> {
  const { results } = await env.DB.prepare(
    'SELECT * FROM payments WHERE elder_id = ? ORDER BY id DESC LIMIT ?'
  )
    .bind(elderId, limit)
    .all<PaymentRow>();
  return results ?? [];
}

/** HIGH 事件推送前查守护人（本地 events.js 的两次 db.get 合一） */
export async function getBoundUserId(env: Env, userId: number): Promise<number | null> {
  const row = await env.DB.prepare('SELECT bound_user_id FROM users WHERE id = ?')
    .bind(userId)
    .first<{ bound_user_id: number | null }>();
  return row?.bound_user_id ?? null;
}

/**
 * 最后一次**老人端**上报的时间戳（事件 / 轨迹 / 录音三表取最大）。
 *
 * 为什么要三张表：老人如果整段时间只是在屋里录音（围栏录音）而没有位置回调，
 * 只看 risk_events 会误判失联 —— 而那恰恰是"守护系统在工作"的时刻。
 *
 * ## 关键：必须排除失联告警自身
 * ELDER_SILENT / ELDER_OFFLINE / ELDER_RECONNECTED 是**服务端自己造的行**，
 * 不是老人端的上报。若不排除，失联扫描会在每一轮里（间隔见 wrangler.jsonc 的 cron）
 * 「判失联 → 写入告警 → 下一轮把这条告警当成"刚上报过" → 判已恢复 →
 * 再写入恢复事件 → 再下一轮又判失联」地无限横跳，而老人从未上线。
 * 告警自己成了"活着的证据"，这是这条链路最容易踩的坑。
 *
 * 失联扫描与 /status 接口共用这一条，保证"判状态"和"对外显示"口径一致。
 *
 * ## 为什么每条分支都写 ORDER BY created_at DESC LIMIT 1，而不是 MAX(created_at)
 * MAX() 只有在"聚合列后面没有任何其他过滤"时才能走 SQLite 的 min/max 优化
 * （一次索引端点查找，只读 1 行）。risk_events 这条带 event_type NOT IN 过滤，
 * 优化会失效，退化成"扫完该老人的全部索引条目"——每天几百轮扫描 × 几万行
 * 就是这么把 D1 免费额度吃光的（2026-10-11 配额打爆事故）。
 * 显式写成倒序取 1 条后，三条分支都变成"索引上倒着走一格就停"，
 * 无论表长多大都只读常数行。语义与 MAX 完全等价（NULL 在 DESC 里排最后，
 * 与 MAX 忽略 NULL 一致）。
 *
 * 注意：每条分支必须写成**独立标量子查询**。写成
 * `SELECT ... ORDER BY created_at DESC LIMIT 1 UNION ALL SELECT ...`
 * 是错的 —— 复合 SELECT 上的 ORDER BY/LIMIT 作用于整个 UNION，
 * 结果只剩一行、LIMIT 1 砍掉的是另外两条分支的数据（实测返回 null）。
 */
export async function getLastReportAt(env: Env, elderId: number): Promise<string | null> {
  const row = await env.DB.prepare(
    `SELECT MAX(ts) AS last_ts FROM (
       SELECT (SELECT created_at FROM risk_events
                WHERE elder_id = ?1
                  AND event_type NOT IN ('ELDER_SILENT', 'ELDER_OFFLINE', 'ELDER_RECONNECTED')
                ORDER BY created_at DESC LIMIT 1) AS ts
       UNION ALL
       SELECT (SELECT created_at FROM locations WHERE elder_id = ?1 ORDER BY created_at DESC LIMIT 1)
       UNION ALL
       SELECT (SELECT created_at FROM recordings WHERE elder_id = ?1 ORDER BY created_at DESC LIMIT 1)
     )`
  )
    .bind(elderId)
    .first<{ last_ts: string | null }>();
  return row?.last_ts ?? null;
}

/** 读取某老人的失联状态行（不存在时返回 null，调用方按"从未扫描过"处理） */
export async function getLivenessRow(
  env: Env,
  elderId: number
): Promise<{ elder_id: number; state: string | null; alerted_state: string | null; last_report_at: string | null } | null> {
  return await env.DB.prepare('SELECT * FROM elder_liveness WHERE elder_id = ?')
    .bind(elderId)
    .first<{ elder_id: number; state: string | null; alerted_state: string | null; last_report_at: string | null }>();
}

/**
 * 写入失联状态（upsert）。
 *
 * last_state_change_at 用 CASE 保留旧值：状态没变时不要刷新这个时间戳，
 * 它表示"状态变成现在这样是什么时候"，被每轮扫描刷新就失去意义了。
 */
export async function upsertLiveness(
  env: Env,
  args: {
    elderId: number;
    state: string;
    alertedState: string | null;
    lastReportAt: string | null;
    nowIso: string;
  }
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO elder_liveness (elder_id, state, alerted_state, last_report_at, last_state_change_at, updated_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?5)
     ON CONFLICT(elder_id) DO UPDATE SET
       state = excluded.state,
       alerted_state = excluded.alerted_state,
       last_report_at = excluded.last_report_at,
       last_state_change_at = CASE WHEN elder_liveness.state IS excluded.state
                                   THEN elder_liveness.last_state_change_at
                                   ELSE excluded.last_state_change_at END,
       updated_at = excluded.updated_at`
  )
    .bind(args.elderId, args.state, args.alertedState, args.lastReportAt, args.nowIso)
    .run();
}
