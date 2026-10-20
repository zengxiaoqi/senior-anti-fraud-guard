/**
 * bindings 多对多绑定 DAO（对齐本地 services/bindings.js，逐语义移植）。
 *
 * 与本地版的差异：D1 没有交互式事务，多步写入用 batch 一次提交；
 * bound_user_id 派生列的重算统一用「子查询全量重算」语句（幂等、无需先读后写），
 * 天然规避了 D1 上读-改-写的竞态。
 *
 * 语义约定（与本地完全一致，改一侧必须同步另一侧 + 契约测试）：
 *  - 绑定追加式，重复绑同一对幂等；is_primary 每老人至多一位，无主时最早 ACTIVE 顶上；
 *  - 解绑软删（status='REMOVED'），重绑恢复；
 *  - elder.bound_user_id = 主守护人（无主则最早绑定）；family.bound_user_id = 最早绑定老人；
 *  - 容量上限：每老人 ≤5 守护人、每子女 ≤5 老人。
 */
import type { Env } from '../../env';

export const MAX_FAMILIES_PER_ELDER = 5;
export const MAX_ELDERS_PER_FAMILY = 5;

export interface BindingFamilyRow {
  id: number;
  name: string;
  phone: string;
  mobile: string | null;
  wx_openid: string | null;
  is_primary: number;
}

export interface BindingElderRow {
  id: number;
  name: string;
  phone: string;
  bind_code: string;
  is_primary: number;
  created_at: string;
}

export async function countFamiliesOfElder(env: Env, elderId: number): Promise<number> {
  const row = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM bindings WHERE elder_id = ? AND status = 'ACTIVE'"
  )
    .bind(elderId)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

export async function countEldersOfFamily(env: Env, familyId: number): Promise<number> {
  const row = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM bindings WHERE family_id = ? AND status = 'ACTIVE'"
  )
    .bind(familyId)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/** 子女与老人之间是否存在 ACTIVE 绑定（鉴权用，一行判定） */
export async function isBound(env: Env, familyId: number, elderId: number | string): Promise<boolean> {
  const row = await env.DB.prepare(
    "SELECT 1 AS ok FROM bindings WHERE family_id = ? AND elder_id = ? AND status = 'ACTIVE' LIMIT 1"
  )
    .bind(familyId, Number(elderId))
    .first<{ ok: number }>();
  return !!row?.ok;
}

/** 某老人的全部 ACTIVE 守护人（主守护人在前），推送 fanout / 老人端展示用 */
export async function listFamiliesOfElder(env: Env, elderId: number): Promise<BindingFamilyRow[]> {
  const { results } = await env.DB.prepare(
    `SELECT f.id, f.name, f.phone, f.mobile, f.wx_openid, b.is_primary
       FROM bindings b JOIN users f ON f.id = b.family_id
      WHERE b.elder_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.is_primary DESC, b.id ASC`
  )
    .bind(elderId)
    .all<BindingFamilyRow>();
  return results ?? [];
}

/** 某子女的全部 ACTIVE 老人（最早的在前），登录回执 / my-elders 用 */
export async function listEldersOfFamily(env: Env, familyId: number): Promise<BindingElderRow[]> {
  const { results } = await env.DB.prepare(
    `SELECT e.id, e.name, e.phone, e.bind_code, b.is_primary, b.created_at
       FROM bindings b JOIN users e ON e.id = b.elder_id
      WHERE b.family_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.id ASC`
  )
    .bind(familyId)
    .all<BindingElderRow>();
  return results ?? [];
}

/** elder.bound_user_id 全量重算：主守护人优先，无主则最早绑定（幂等，单语句） */
const RESYNC_ELDER_SQL = `UPDATE users SET bound_user_id = COALESCE(
    (SELECT family_id FROM bindings WHERE elder_id = users.id AND status = 'ACTIVE' AND is_primary = 1 LIMIT 1),
    (SELECT family_id FROM bindings WHERE elder_id = users.id AND status = 'ACTIVE' ORDER BY id LIMIT 1)
  ) WHERE role = 'elder' AND id = ?`;

/** family.bound_user_id 全量重算：最早绑定老人（无绑定则置 NULL） */
const RESYNC_FAMILY_SQL = `UPDATE users SET bound_user_id = (
    SELECT elder_id FROM bindings WHERE family_id = users.id AND status = 'ACTIVE' ORDER BY id LIMIT 1
  ) WHERE id = ?`;

/** is_primary 全量重算：该老人最早 ACTIVE 的绑定者成为主（幂等，单语句） */
const RESYNC_PRIMARY_SQL = `UPDATE bindings SET is_primary = CASE
    WHEN id = (SELECT MIN(id) FROM bindings WHERE elder_id = ?1 AND status = 'ACTIVE') THEN 1
    ELSE 0 END
  WHERE elder_id = ?1 AND status = 'ACTIVE'`;

/**
 * 追加一条绑定（幂等）。返回 { already, isPrimary }。
 * 写入（INSERT 或软删行恢复）+ is_primary 重算 + 双方 bound_user_id 重算，一个 batch 提交。
 */
export async function addBinding(
  env: Env,
  elderId: number,
  familyId: number
): Promise<{ already: boolean; isPrimary: boolean } | null> {
  const existing = await env.DB.prepare(
    'SELECT id, status, is_primary FROM bindings WHERE elder_id = ? AND family_id = ?'
  )
    .bind(elderId, familyId)
    .first<{ id: number; status: string; is_primary: number }>();

  // 幂等：已绑定直接返回，绝不动其他绑定行
  if (existing && existing.status === 'ACTIVE') {
    return { already: true, isPrimary: !!existing.is_primary };
  }

  // 无主老人 → 本次绑定者成为主（重算语句会把这个事实算出来，这里只需决定写入方式）
  const stmts = existing
    ? [env.DB.prepare("UPDATE bindings SET status = 'ACTIVE' WHERE id = ?").bind(existing.id)]
    : [
        env.DB.prepare(
          "INSERT INTO bindings (elder_id, family_id, is_primary, status) VALUES (?, ?, 0, 'ACTIVE')"
        ).bind(elderId, familyId)
      ];
  // 写入后统一重算 primary 与派生列（INSERT 的 is_primary=0 会被重算语句纠正为 1——若它是最早的）
  stmts.push(
    env.DB.prepare(RESYNC_PRIMARY_SQL).bind(elderId),
    env.DB.prepare(RESYNC_ELDER_SQL).bind(elderId),
    env.DB.prepare(RESYNC_FAMILY_SQL).bind(familyId)
  );

  const results = await env.DB.batch(stmts);
  if (!results.every((r) => r.success)) return null;

  // 回读主守护人标记（重算后该家庭是否为主）
  const row = await env.DB.prepare(
    "SELECT is_primary FROM bindings WHERE elder_id = ? AND family_id = ? AND status = 'ACTIVE'"
  )
    .bind(elderId, familyId)
    .first<{ is_primary: number }>();
  return { already: false, isPrimary: !!row?.is_primary };
}

/**
 * 解除一条绑定（软删）+ 主守护人轮换 + 双方派生列重算。
 * 只解除 (familyId, elderId) 这一对，不影响其他绑定。
 */
export async function removeBinding(env: Env, elderId: number, familyId: number): Promise<boolean> {
  const stmts = [
    env.DB.prepare(
      "UPDATE bindings SET status = 'REMOVED' WHERE elder_id = ? AND family_id = ? AND status = 'ACTIVE'"
    ).bind(elderId, familyId),
    env.DB.prepare(RESYNC_PRIMARY_SQL).bind(elderId),
    env.DB.prepare(RESYNC_ELDER_SQL).bind(elderId),
    env.DB.prepare(RESYNC_FAMILY_SQL).bind(familyId)
  ];
  const results = await env.DB.batch(stmts);
  return results.every((r) => r.success);
}

/**
 * 把某账号的全部 ACTIVE 绑定整体转移给另一账号（wx-link 微信占位号 → App 账号）。
 * 同 elder 去重靠 UNIQUE + OR IGNORE；转移行一律非主，最后按 elder 全量重算主与派生列。
 */
export async function migrateBindings(env: Env, fromUserId: number, toUserId: number): Promise<boolean> {
  const stmts = [
    env.DB.prepare(
      `INSERT OR IGNORE INTO bindings (elder_id, family_id, is_primary, status, created_at)
       SELECT elder_id, ?1, 0, 'ACTIVE', created_at FROM bindings
        WHERE family_id = ?2 AND status = 'ACTIVE'`
    ).bind(toUserId, fromUserId),
    // 占位号的绑定行整体删除（含复制时撞 UNIQUE 的重复对）
    env.DB.prepare('DELETE FROM bindings WHERE family_id = ?').bind(fromUserId)
  ];

  // 目标账号绑定的老人全量重算 primary + elder 派生列 + 目标账号自己的派生列
  const elders = await env.DB.prepare(
    "SELECT DISTINCT elder_id FROM bindings WHERE family_id = ? AND status = 'ACTIVE'"
  )
    .bind(toUserId)
    .all<{ elder_id: number }>();
  for (const { elder_id } of elders.results ?? []) {
    stmts.push(
      env.DB.prepare(RESYNC_PRIMARY_SQL).bind(elder_id),
      env.DB.prepare(RESYNC_ELDER_SQL).bind(elder_id)
    );
  }
  stmts.push(env.DB.prepare(RESYNC_FAMILY_SQL).bind(toUserId));

  const results = await env.DB.batch(stmts);
  return results.every((r) => r.success);
}
