/**
 * 短时效播放签名（对齐本地 services/signToken.js）。
 *
 * 存在理由：Android MediaPlayer 播放 m4a 时不携带自定义请求头，
 * stream 接口没法用 X-Auth-Token 鉴权 → 由已鉴权的 list 接口签发
 * HMAC 令牌拼在 URL 上，30 分钟过期。
 *
 * 与本地的唯一差异：本地密钥是进程内随机（重启即失效），Workers 有多个
 * 隔离实例，随机密钥会导致 A 实例签的令牌在 B 实例验不过 → 必须显式配
 * EVIDENCE_TOKEN_SECRET（wrangler secret put）；未配置时用固定开发密钥，
 * 仅限本地 wrangler dev，生产部署前必须设置。
 */
import { createHmac, timingSafeEqual } from 'node:crypto';
import type { Env } from '../env';

const DEV_FALLBACK_SECRET = 'dev-only-insecure-fallback-secret';
const DEFAULT_TTL_MS = 30 * 60 * 1000;

function secretOf(env: Env): string {
  return env.EVIDENCE_TOKEN_SECRET || DEV_FALLBACK_SECRET;
}

function ttlOf(env: Env): number {
  const n = parseInt(String(env.PLAY_TOKEN_TTL_MS || ''), 10);
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_TTL_MS;
}

function b64url(buf: Buffer): string {
  return buf.toString('base64url');
}

function hmac(secret: string, data: string): string {
  return createHmac('sha256', secret).update(data).digest('base64url');
}

export interface PlayToken {
  token: string;
  expiresAt: number;
}

/** 为指定录音签发播放令牌（payload: {rid, uid, exp}） */
export function sign(env: Env, recordingId: number, userId: number): PlayToken {
  const payload = { rid: Number(recordingId), uid: Number(userId), exp: Date.now() + ttlOf(env) };
  const encoded = b64url(Buffer.from(JSON.stringify(payload), 'utf8'));
  const sig = hmac(secretOf(env), encoded);
  return { token: `${encoded}.${sig}`, expiresAt: payload.exp };
}

export type VerifyResult =
  | { ok: true; payload: { rid: number; uid: number; exp: number } }
  | { ok: false; error: string };

/** 校验播放令牌：签名、rid 匹配、未过期。文案与本地逐字一致（客户端会展示） */
export function verify(env: Env, token: string | undefined | null, recordingId: number | null): VerifyResult {
  if (!token) return { ok: false, error: '缺少播放令牌，请刷新录音列表' };

  const parts = String(token).split('.');
  if (parts.length !== 2) return { ok: false, error: '播放令牌格式错误' };
  const [encoded, sig] = parts;

  const expected = hmac(secretOf(env), encoded);
  const a = Buffer.from(String(sig));
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) {
    return { ok: false, error: '播放令牌签名无效' };
  }

  let payload: { rid?: number; uid?: number; exp?: number };
  try {
    payload = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
  } catch {
    return { ok: false, error: '播放令牌内容无法解析' };
  }

  if (!payload || typeof payload.rid !== 'number' || typeof payload.exp !== 'number') {
    return { ok: false, error: '播放令牌字段缺失' };
  }
  if (Date.now() > payload.exp) {
    return { ok: false, error: '播放令牌已过期，请刷新录音列表' };
  }
  if (recordingId != null && Number(payload.rid) !== Number(recordingId)) {
    return { ok: false, error: '播放令牌与录音不匹配' };
  }
  return { ok: true, payload: payload as { rid: number; uid: number; exp: number } };
}
