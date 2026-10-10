/**
 * D1 版登录态鉴权（替代本地 services/tokenAuth.js 的内存 Map）。
 *
 * 为什么进 D1 而不是 KV：KV 最终一致（写后立刻读可能读到旧值），登录后马上带 token
 * 请求会出现偶发 401；D1 是强一致单写，语义与本地 Map 最接近。
 *
 * 语义对齐点：
 *  - token 24 字节随机 hex，TTL 7 天；
 *  - 过期 token 校验时顺手删掉（本地 Map 的 delete 行为）；
 *  - requireBoundElder 的三态参数名兼容（:elderId/:id/:userId）在 Hono 中间件层做，
 *    这里只提供数据访问。
 */
import type { Env } from '../env';

const TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 天，与本地一致

/** 生成 48 位随机 hex token（等价 crypto.randomBytes(24).toString('hex')） */
function randomToken(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** 签发登录态。返回 token 字符串 */
export async function issueToken(env: Env, userId: number): Promise<string> {
  const token = randomToken();
  // created_at 用毫秒时间戳整数（本地 Map 存的 Date.now() 同语义）
  await env.DB.prepare('INSERT INTO auth_tokens (token, user_id, created_at) VALUES (?, ?, ?)')
    .bind(token, userId, Date.now())
    .run();
  return token;
}

/** 校验登录态，返回 userId；无效/过期返回 null（过期顺手清理，防表膨胀） */
export async function verifyToken(env: Env, token: string | undefined | null): Promise<number | null> {
  if (!token) return null;
  const row = await env.DB.prepare('SELECT user_id, created_at FROM auth_tokens WHERE token = ?')
    .bind(token)
    .first<{ user_id: number; created_at: number }>();
  if (!row) return null;
  if (Date.now() - row.created_at > TOKEN_TTL_MS) {
    await env.DB.prepare('DELETE FROM auth_tokens WHERE token = ?').bind(token).run();
    return null;
  }
  return row.user_id;
}

/** 主动登出 */
export async function revokeToken(env: Env, token: string): Promise<void> {
  await env.DB.prepare('DELETE FROM auth_tokens WHERE token = ?').bind(token).run();
}

/** 数据归属校验：本人或已绑定守护对象。对齐 requireBoundElder 的字符串化比较 */
export async function canAccessElder(env: Env, authUserId: number, elderId: string | number): Promise<boolean> {
  if (String(authUserId) === String(elderId)) return true;
  const row = await env.DB.prepare('SELECT bound_user_id FROM users WHERE id = ?')
    .bind(authUserId)
    .first<{ bound_user_id: number | null }>();
  return row?.bound_user_id != null && String(row.bound_user_id) === String(elderId);
}

/** cron 清理：删掉全部过期 token（替代本地 Map 超过 5000 条时的惰性清理） */
export async function purgeExpiredTokens(env: Env): Promise<number> {
  const res = await env.DB.prepare('DELETE FROM auth_tokens WHERE created_at <= ?')
    .bind(Date.now() - TOKEN_TTL_MS)
    .run();
  return res.meta.changes ?? 0;
}
