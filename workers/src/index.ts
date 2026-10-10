/**
 *  Workers 后端入口（P4/P5 阶段）。
 *
 *  路由迁移进度：全部 REST 路由已移植（auth/events/geofence/ai/evidence/recordings）；
 *  WS 推送由 GuardHubDO 承接（P5）。
 *
 *  迁移原则：HTTP/WS 契约与本地后端完全一致（tests/contract 两个后端跑同一套用例）。
 */
import { Hono } from 'hono';
import { notImplemented } from './routes/placeholder';
import { runDbSmoke } from './routes/dbSmoke';
import { authRoutes } from './routes/auth';
import { eventRoutes } from './routes/events';
import { geofenceRoutes } from './routes/geofence';
import { aiRoutes } from './routes/ai';
import { evidenceRoutes } from './routes/evidence';
import { recordingsRoutes } from './routes/recordings';
import { appUpdateRoutes } from './routes/appUpdate';
import { handleScheduled } from './cron/sweeps';
import { GuardHubDO } from './hub/GuardHubDO';
import type { Env } from './env';

export { GuardHubDO };

const app = new Hono<{ Bindings: Env }>();

app.use('/api/*', async (c, next) => {
  // 把 executionCtx 交给库内代码（geo 缓存 waitUntil、后台分析等）
  c.env.ctx = c.executionCtx;
  await next();
});

app.get('/api/health', async (c) => {
  // 健康检查顺带 ping 一次 D1：绑定没配好时第一时间暴露，而不是等第一个业务请求
  let dbOk = false;
  try {
    await c.env.DB.prepare('SELECT 1').first();
    dbOk = true;
  } catch {
    dbOk = false;
  }
  return c.json({
    success: true,
    service: 'workers',
    stage: c.env.SERVICE_STAGE || 'migration',
    db: dbOk ? 'ok' : 'error',
    serverTime: new Date().toISOString()
  });
});

/** P2 数据层自检：POST，只写临时行并立即清理 */
app.post('/api/_smoke/db', (c) => runDbSmoke(c.env));

// ── REST 路由（路径与本地 server.js 挂载点一致）────────────────────
app.route('/api/auth', authRoutes);
app.route('/api/events', eventRoutes);
app.route('/api/geofence', geofenceRoutes);
app.route('/api/ai', aiRoutes);
app.route('/api/evidence', evidenceRoutes);
app.route('/api/recordings', recordingsRoutes);
// App 内自升级：免登录（老人端未登记账号时也必须能升上来）
app.route('/api/app-update', appUpdateRoutes);

app.all('/api/*', (c) => notImplemented(new URL(c.req.url).pathname));

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // WebSocket 升级请求一律转发给 Hub DO。
    // Android 客户端连接的是服务器根路径（wss://host/），不能按路径过滤。
    if (request.headers.get('Upgrade') === 'websocket') {
      const id = env.GUARD_HUB.idFromName('global');
      return env.GUARD_HUB.get(id).fetch(request);
    }
    if (new URL(request.url).pathname.startsWith('/api/')) {
      return app.fetch(request, env, ctx);
    }
    // 非 API 路径回源静态管理页（wrangler assets run_worker_first 模式）
    return env.ASSETS.fetch(request);
  },

  /** Cron Triggers：替代本地后端 recordingCleanup.js 的 setInterval */
  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    await handleScheduled(controller.cron, env, ctx);
  }
};
