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
