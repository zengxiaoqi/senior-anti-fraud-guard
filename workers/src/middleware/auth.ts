/**
 * Hono 鉴权中间件（对齐本地 services/tokenAuth.js 的两个中间件）。
 *
 * 铁律延续：requireBoundElder 必须同时读 :elderId / :id / :userId 三种路径参数名，
 * 拿 undefined 去比对会把合法请求误判 403。
 */
import { createMiddleware } from 'hono/factory';
import { verifyToken, canAccessElder } from '../services/tokenAuth';
import type { Env } from '../env';

export type AppEnv = { Bindings: Env; Variables: { authUserId: number } };

/** 校验登录态：必须在请求头携带有效的 X-Auth-Token */
export const requireFamilyAuth = createMiddleware<AppEnv>(async (c, next) => {
  const token = c.req.header('X-Auth-Token');
  const userId = await verifyToken(c.env, token);
  if (!userId) {
    return c.json({ success: false, error: '登录态无效或已过期，请重新登录' }, 401);
  }
  c.set('authUserId', userId);
  await next();
});

/** 数据归属校验：token 用户本人，或与 :elderId 存在绑定关系 */
export const requireBoundElder = createMiddleware<AppEnv>(async (c, next) => {
  const elderId = c.req.param('elderId') ?? c.req.param('id') ?? c.req.param('userId');
  if (elderId === undefined || elderId === null || elderId === '') {
    return c.json({ success: false, error: '缺少目标用户参数' }, 400);
  }
  const ok = await canAccessElder(c.env, c.get('authUserId'), elderId);
  if (!ok) {
    return c.json({ success: false, error: '无权访问该用户的数据' }, 403);
  }
  await next();
});
