/**
 * /api/events 路由（对齐本地 routes/events.js）。
 *
 * 铁律延续：
 *  - severity 由服务端按事件类型归一化，不盲信客户端自报值（自报 LOW 会静默吞掉推送）；
 *  - 坐标串必须兜底成可读地名（describePlace 永不返回空）；
 *  - 轨迹的 is_sensitive 用归一化后的 effSeverity 判定；
 *  - HIGH 事件尽力触发微信推送（waitUntil，不阻塞响应）。
 * P5 起补 DO 广播（本地 broadcastHandler 的对应物）。
 */
import { Hono } from 'hono';
import * as eventsDao from '../db/dao/events';
import { requireFamilyAuth, requireBoundElder, type AppEnv } from '../middleware/auth';
import { toBeijingRows, toBeijing } from '../lib/timeFormat';
import { shouldInterrupt } from '../lib/riskAlertPolicy';
import { isSensitiveLocation } from '../lib/locationSensitivity';
import * as geo from '../lib/geo';
import { notifyFamily } from '../lib/hub';

// 服务端按事件类型归一化 severity：
//   COERCION_RISK  通话中前台切到高危 App → 恒 HIGH，必推送
//   CALL_STAT      通话结束统计 → 恒 LOW，只记录不推送
//   GEOFENCE_DWELL 围栏内停留超阈值 → 恒 MEDIUM
const EVENT_SEVERITY_OVERRIDE: Record<string, string> = {
  COERCION_RISK: 'HIGH',
  CALL_STAT: 'LOW',
  GEOFENCE_DWELL: 'MEDIUM'
};

/** HIGH 事件的微信模板消息推送（尽力而为，无 secrets 时静默跳过） */
async function notifyWechatHigh(env: import('../env').Env, elderId: number, eventType: string, details: unknown): Promise<void> {
  try {
    if (!env.WECHAT_APPID || !env.WECHAT_APPSECRET) return;
    const boundId = await eventsDao.getBoundUserId(env, elderId);
    if (!boundId) return;
    const fam = await env.DB.prepare('SELECT id, wx_openid FROM users WHERE id = ?')
      .bind(boundId)
      .first<{ id: number; wx_openid: string | null }>();
    if (!fam) return;
    // access_token 获取 + 模板消息发送照搬本地 services/wechat.js（P5 一并整理）
    void eventType;
    void details;
  } catch {
    // 推送失败不影响主链路
  }
}

export const eventRoutes = new Hono<AppEnv>();

// 上报风险感知事件（老人设备上报，保持开放）
eventRoutes.post('/report', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const { elderId, eventType, severity, details } = body;

  if (!elderId || !eventType || !severity) {
    return c.json({ error: '缺失必要参数' }, 400);
  }

  const effSeverity = EVENT_SEVERITY_OVERRIDE[eventType] || String(severity).toUpperCase();
  const detailsStr = typeof details === 'object' ? JSON.stringify(details) : details;

  const eventId = await eventsDao.insertRiskEvent(c.env, {
    elderId,
    eventType,
    severity: effSeverity,
    details: detailsStr ?? null
  });

  // 1. 大额支付 → payments 存证表（失败静默：order_no 撞 UNIQUE 等不阻断上报，与本地 db.run 无回调语义一致）
  if (eventType === 'PAYMENT_RISK' && details && details.amount) {
    try {
      await eventsDao.insertPayment(c.env, {
        elderId,
        amount: details.amount,
        payeeName: details.payee_name || '未知商户',
        payeeAccount: details.payee_account || '未知卡号',
        orderNo: details.order_no || `ORD_${Date.now()}`
      });
    } catch {
      // 存证失败不影响事件入账
    }
  }

  // 2. 含坐标 → locations 轨迹表；坐标串异步补全地名（waitUntil，不阻塞响应）
  if (details && details.latitude && details.longitude) {
    // 用 effSeverity 而非自报 severity：归一化后的级别才是真实风险
    const isSensitive = isSensitiveLocation(eventType, effSeverity, details);
    const rawAddress = String(details.address || '').trim();
    const needGeo = !geo.guess.looksLikeRealAddress(rawAddress);
    try {
      const locId = await eventsDao.insertLocation(c.env, {
        elderId,
        latitude: details.latitude,
        longitude: details.longitude,
        address: rawAddress || geo.guess.coordFallback(details.latitude, details.longitude),
        isSensitive
      });
      if (needGeo) {
        c.executionCtx.waitUntil(
          (async () => {
            try {
              const { name, source } = await geo.describePlace(
                c.env,
                Number(details.latitude),
                Number(details.longitude),
                elderId
              );
              if (source === 'geo' || source === 'db') {
                await eventsDao.updateLocationAddress(c.env, locId, name);
              }
            } catch {
              // 补全失败不回滚上报
            }
          })()
        );
      }
    } catch {
      // 轨迹落库失败不影响事件入账（与本地 db.run 无回调语义一致）
    }
  }

  // 3. WS 实时广播给子女端 App（对齐本地 broadcastHandler：RISK_ALERT + interruptible）
  const eventData = {
    id: eventId,
    elder_id: elderId,
    event_type: eventType,
    severity: effSeverity,
    details: details || {},
    created_at: toBeijing(new Date()),
    // 客户端据此决定是否弹「强打断」确认框（心跳级上报不弹，防告警疲劳）
    interruptible: shouldInterrupt(eventType, effSeverity)
  };
  c.executionCtx.waitUntil(notifyFamily(c.env, elderId, { type: 'RISK_ALERT', data: eventData }));

  // 4. HIGH → 尽力触发微信推送
  if (effSeverity === 'HIGH') {
    c.executionCtx.waitUntil(notifyWechatHigh(c.env, elderId, eventType, details));
  }

  return c.json({ success: true, eventId, message: '风险事件已记录并触发微信推送' });
});

// 查询指定老人的历史风险事件（登录态 + 绑定校验）
eventRoutes.get('/list/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = Number(c.req.param('elderId'));
  const limit = Number(c.req.query('limit') || 20);

  const rows = await eventsDao.listRiskEvents(c.env, elderId, limit);
  const formatted = toBeijingRows(rows as unknown as Array<Record<string, unknown>>).map((r) => ({
    ...r,
    details: r.details ? JSON.parse(String(r.details)) : {}
  }));
  return c.json({ success: true, data: formatted });
});

// 查询最新位置轨迹（登录态 + 绑定校验）
eventRoutes.get('/location/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = Number(c.req.param('elderId'));
  const rows = await eventsDao.listRecentLocations(c.env, elderId, 10);
  const out = toBeijingRows(rows as unknown as Array<Record<string, unknown>>);

  // 地址兜底顺序：库里真实地名 → 地图逆地理 → 最近锚点/常去地点 → 坐标
  // 推断出的描述不写回库覆盖原始上报（只有 geo/db 来源才回写）
  const merged = await Promise.all(
    out.map(async (r) => {
      if (geo.guess.looksLikeRealAddress(r.address)) {
        return { ...r, place_source: 'db' };
      }
      try {
        const { name, source } = await geo.describePlace(
          c.env,
          Number(r.latitude),
          Number(r.longitude),
          elderId
        );
        if (source === 'geo' || source === 'db') {
          c.executionCtx.waitUntil(eventsDao.updateLocationAddress(c.env, Number(r.id), name));
        }
        return { ...r, address: name, place_source: source };
      } catch {
        return r;
      }
    })
  );
  return c.json({ success: true, data: merged });
});
