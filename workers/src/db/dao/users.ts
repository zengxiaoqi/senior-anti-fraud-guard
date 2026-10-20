/**
 * users 表 DAO。
 *
 * 移植原则：SQL 语义逐条对齐本地 routes/auth.js，尤其是这几条"踩过坑"的约定：
 *  - 手机号唯一性要同时比 mobile 和 elder 的 phone（`mobile = ? OR (role='elder' AND phone = ?)`），
 *    因为老人端把真实号码存在 phone 列、子女端存在 mobile 列；
 *  - 换号找回必须用 COALESCE(NULLIF(?,''), name)，空姓名沿用服务端原值；
 *  - 绑定是双向的，两个 UPDATE 必须同时成功（D1 用 batch 保证一次提交）。
 */
import type { Env } from '../../env';
import type { UserRow } from '../types';
import * as bindings from './bindings';

export async function findUserById(env: Env, id: number): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(id).first<UserRow>();
}

export async function findFamilyForLogin(env: Env, identifier: string): Promise<UserRow | null> {
  return env.DB.prepare(
    'SELECT * FROM users WHERE (phone = ? OR mobile = ?) AND password_hash IS NOT NULL'
  )
    .bind(identifier, identifier)
    .first<UserRow>();
}

export async function findByBindCode(env: Env, bindCode: string): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE bind_code = ?').bind(bindCode).first<UserRow>();
}

export async function usernameExists(env: Env, username: string): Promise<boolean> {
  const row = await env.DB.prepare('SELECT id FROM users WHERE phone = ?').bind(username).first<{ id: number }>();
  return !!row;
}

/** 手机号是否被其他账号占用。excludeId 用于"改自己资料"时排除自身 */
export async function mobileTaken(env: Env, mobile: string, excludeId?: number): Promise<boolean> {
  const sql = excludeId
    ? "SELECT id FROM users WHERE (mobile = ? OR (role = 'elder' AND phone = ?)) AND id != ? LIMIT 1"
    : "SELECT id FROM users WHERE (mobile = ? OR (role = 'elder' AND phone = ?)) LIMIT 1";
  const stmt = excludeId
    ? env.DB.prepare(sql).bind(mobile, mobile, excludeId)
    : env.DB.prepare(sql).bind(mobile, mobile);
  const row = await stmt.first<{ id: number }>();
  return !!row;
}

/** 按手机号找 elder 账号（卸载重装 / 换机 / 换号找回都靠它） */
export async function findElderByPhone(env: Env, phone: string): Promise<UserRow | null> {
  return env.DB.prepare("SELECT id, bind_code FROM users WHERE role = 'elder' AND (phone = ? OR mobile = ?) LIMIT 1")
    .bind(phone, phone)
    .first<UserRow>();
}

/** 按手机号找 elder 完整行（子女端手机号绑定用：回执 boundUser 需要 name/phone 等全字段） */
export async function findElderByPhoneFull(env: Env, phone: string): Promise<UserRow | null> {
  return env.DB.prepare("SELECT * FROM users WHERE role = 'elder' AND (phone = ? OR mobile = ?) LIMIT 1")
    .bind(phone, phone)
    .first<UserRow>();
}

export async function findElderById(env: Env, elderId: number): Promise<UserRow | null> {
  return env.DB.prepare("SELECT * FROM users WHERE id = ? AND role = 'elder'").bind(elderId).first<UserRow>();
}

export async function createFamily(
  env: Env,
  args: { username: string; mobile: string; name: string; passwordHash: string; bindCode: string }
): Promise<number> {
  const row = await env.DB.prepare(
    'INSERT INTO users (role, name, phone, mobile, bind_code, password_hash) VALUES (?, ?, ?, ?, ?, ?) RETURNING id'
  )
    .bind('family', args.name, args.username, args.mobile, args.bindCode, args.passwordHash)
    .first<{ id: number }>();
  if (!row) throw new Error('创建子女账号失败');
  return row.id;
}

export async function createElder(
  env: Env,
  args: { name: string; phone: string; bindCode: string }
): Promise<number> {
  const row = await env.DB.prepare(
    "INSERT INTO users (role, name, phone, bind_code) VALUES ('elder', ?, ?, ?) RETURNING id"
  )
    .bind(args.name, args.phone, args.bindCode)
    .first<{ id: number }>();
  if (!row) throw new Error('创建老人账号失败');
  return row.id;
}

/** 更新老人资料：姓名留空时沿用服务端原值 */
export async function updateElderProfile(env: Env, elderId: number, name: string, phone: string): Promise<void> {
  await env.DB.prepare("UPDATE users SET name = COALESCE(NULLIF(?, ''), name), phone = ? WHERE id = ?")
    .bind(name, phone, elderId)
    .run();
}

/**
 * 换号找回：只改号，bound_user_id / bind_code / 历史记录一律不动。
 * mobile 置空是本地既有行为（elder 的真实号统一存在 phone 列）。
 */
export async function applyPhoneChange(env: Env, elderId: number, name: string, newPhone: string): Promise<void> {
  await env.DB.prepare("UPDATE users SET name = COALESCE(NULLIF(?, ''), name), phone = ?, mobile = NULL WHERE id = ?")
    .bind(name, newPhone, elderId)
    .run();
}

export async function updateMobile(env: Env, userId: number, mobile: string): Promise<boolean> {
  const res = await env.DB.prepare('UPDATE users SET mobile = ? WHERE id = ?').bind(mobile, userId).run();
  return (res.meta?.changes ?? 0) > 0;
}

/** 改密/重置密码共用：写入新的 salt:hash（调用方负责先验证旧凭据） */
export async function updatePasswordHash(env: Env, userId: number, passwordHash: string): Promise<void> {
  await env.DB.prepare('UPDATE users SET password_hash = ? WHERE id = ?').bind(passwordHash, userId).run();
}

/**
 * 忘记密码重置用：账号（用户名或手机号任一标识）+ 绑定手机号双重匹配。
 * 只匹配 password_hash 非空的账密账号 —— 微信建号没有可登录的用户名，不在范围内。
 */
export async function findFamilyForPasswordReset(
  env: Env,
  identifier: string,
  mobile: string
): Promise<{ id: number } | null> {
  return env.DB.prepare(
    'SELECT id FROM users WHERE (phone = ? OR mobile = ?) AND mobile = ? AND password_hash IS NOT NULL LIMIT 1'
  )
    .bind(identifier, identifier, mobile)
    .first<{ id: number }>();
}

export async function updateBindCode(env: Env, elderId: number, bindCode: string): Promise<boolean> {
  const res = await env.DB.prepare('UPDATE users SET bind_code = ? WHERE id = ?').bind(bindCode, elderId).run();
  return (res.meta?.changes ?? 0) > 0;
}

export async function bindCodeTaken(env: Env, bindCode: string, excludeId: number): Promise<boolean> {
  const row = await env.DB.prepare('SELECT id FROM users WHERE bind_code = ? AND id != ? LIMIT 1')
    .bind(bindCode, excludeId)
    .first<{ id: number }>();
  return !!row;
}

export async function setGuardSettings(env: Env, elderId: number, settings: Record<string, unknown>): Promise<void> {
  await env.DB.prepare('UPDATE users SET guard_settings = ? WHERE id = ?')
    .bind(JSON.stringify(settings), elderId)
    .run();
}

export async function getGuardSettings(env: Env, elderId: number): Promise<string | null> {
  const row = await env.DB.prepare("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'")
    .bind(elderId)
    .first<{ guard_settings: string | null }>();
  return row?.guard_settings ?? null;
}

/**
 * 老人 + 主守护人（boundFamily 兼容字段来源）。
 * 多人绑定后从 bindings 取全量守护人（主守护人在前），第一条即主守护人。
 */
export async function getElderWithFamily(
  env: Env,
  elderId: number
): Promise<{
  elder: UserRow;
  family: { id: number; name: string; phone: string; mobile: string | null; isPrimary: boolean } | null;
  families: { id: number; name: string; phone: string; mobile: string | null; isPrimary: boolean }[];
} | null> {
  const elder = await env.DB.prepare(
    'SELECT id, name, phone, bind_code, bound_user_id, guard_settings FROM users WHERE id = ?'
  )
    .bind(elderId)
    .first<UserRow>();

  if (!elder) return null;

  const famRows = await bindings.listFamiliesOfElder(env, elderId);
  const families = famRows.map((f) => ({
    id: f.id,
    name: f.name,
    // phone 在子女端存的是用户名，真实号码在 mobile；统一抹平让客户端只认一个字段
    phone: f.mobile || f.phone,
    mobile: f.mobile,
    isPrimary: !!f.is_primary
  }));
  return { elder, family: families.length ? families[0] : null, families };
}

/** /api/auth/user/:id 用：带绑定人姓名手机号 + display_phone（mobile 优先） */
export async function getUserWithBound(env: Env, userId: number) {
  return env.DB.prepare(
    `SELECT u.*, b.name as bound_name, b.phone as bound_phone,
            CASE WHEN u.mobile IS NOT NULL AND u.mobile != '' THEN u.mobile
                 WHEN u.phone IS NOT NULL AND u.phone != '' THEN u.phone
                 ELSE '' END as display_phone
     FROM users u LEFT JOIN users b ON u.bound_user_id = b.id
     WHERE u.id = ?`
  )
    .bind(userId)
    .first<Record<string, unknown>>();
}

/** 生成 6 位不冲突的绑定码 */
export async function generateUniqueBindCode(env: Env): Promise<string> {
  for (let i = 0; i < 20; i++) {
    const code = String(Math.floor(100000 + Math.random() * 900000));
    const row = await env.DB.prepare('SELECT id FROM users WHERE bind_code = ? LIMIT 1')
      .bind(code)
      .first<{ id: number }>();
    if (!row) return code;
  }
  throw new Error('生成绑定码失败：重试次数耗尽');
}
