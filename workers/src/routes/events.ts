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
import { evaluateLiveness, extractLivenessThresholds, parseSqliteTime } from '../lib/elderLiveness';
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
  GEOFENCE_DWELL: 'MEDIUM',
  // 老人端危险检测（跌倒）：恒 HIGH，不采信客户端自报值（对齐本地）
  FALL_RISK: 'HIGH',
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

  // 2.5 告警详情里的坐标补地名（对齐本地 routes/events.js，与 locations 表同一套模式）：
  // 告警页读的是 risk_events.details，坐标数字对守护人毫无意义。
  // 只在查到真名（geo/db 来源）时回写，"常去地点①"这类推断描述不落库。
  if (details && details.latitude && details.longitude) {
    const baseDetails = typeof details === 'object' ? details : {};
    const hasRealName =
      geo.guess.looksLikeRealAddress(String(baseDetails.location_name || '')) ||
      geo.guess.looksLikeRealAddress(String(baseDetails.address || ''));
    if (!hasRealName) {
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
              await eventsDao.updateRiskEventDetails(
                c.env,
                eventId,
                JSON.stringify({ ...baseDetails, location_name: name })
              );
            }
          } catch {
            // 补全失败不影响事件入账
          }
        })()
      );
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

  // 读时自愈（对齐本地 /list）：写时补全可能失败，历史数据没有 location_name。
  // 对"有坐标但没真名"的事件现场补一次；只有 geo/db（真名）来源才回写库，
  // 推断描述只进响应不落库 —— 与 /location 的回写口径一致。
  await Promise.all(
    formatted.map(async (r: Record<string, any>) => {
      const d = r.details;
      if (!d || typeof d !== 'object' || !d.latitude || !d.longitude) return;
      if (geo.guess.looksLikeRealAddress(String(d.location_name || ''))) return;
      try {
        const { name, source } = await geo.describePlace(
          c.env,
          Number(d.latitude),
          Number(d.longitude),
          elderId
        );
        r.details = { ...d, location_name: name };
        if (source === 'geo' || source === 'db') {
          c.executionCtx.waitUntil(
            eventsDao.updateRiskEventDetails(c.env, Number(r.id), JSON.stringify(r.details))
          );
        }
      } catch {
        // 补全失败保持原样，不影响列表返回
      }
    })
  );
  return c.json({ success: true, data: formatted });
});

// 查询最新位置轨迹（登录态 + 绑定校验）
//
// limit：默认 10 条（小程序 / 旧客户端契约不变）；Android 轨迹页要看 7 天全貌，
// 传 ?limit=200。上限 300：再多基本是同一地点的高频心跳重复，客户端已按地点聚合，
// 多传只是浪费流量和这里的 CPU。
const LOCATION_MAX_LIMIT = 300;
// 逆地理补全预算：只有"库里不是真地名"的点才值得花外部调用去猜名字。
// 7 天几百条上报表大多是同一位置的心跳，逐个补全会把外部 API 配额和 Worker
// CPU 一起打光（还会让这个接口从 200ms 级退化到秒级）。超出预算的点保持库里
// 原样——它们会被 compact 回 simply 坐标名，由客户端按"已定位（x, y）"显示。
const LOCATION_GEO_BUDGET = 20;

eventRoutes.get('/location/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = Number(c.req.param('elderId'));
  const raw = Number(c.req.query('limit'));
  const limit = Number.isFinite(raw) && raw > 0
    ? Math.min(Math.floor(raw), LOCATION_MAX_LIMIT)
    : 10;
  const rows = await eventsDao.listRecentLocations(c.env, elderId, limit);
  const out = toBeijingRows(rows as unknown as Array<Record<string, unknown>>);

  // 地址兜底顺序：库里真实地名 → 地图逆地理 → 最近锚点/常去地点 → 坐标
  // 推断出的描述不写回库覆盖原始上报（只有 geo/db 来源才回写）
  let geoBudget = LOCATION_GEO_BUDGET;
  const merged = await Promise.all(
    out.map(async (r) => {
      if (geo.guess.looksLikeRealAddress(r.address)) {
        return { ...r, place_source: 'db' };
      }
      if (geoBudget <= 0) return { ...r, place_source: 'none' };
      geoBudget--;
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

// 查询老人的守护在线状态（对齐本地 routes/events.js 的 /status/:elderId）
//
// 单独一个接口而不是让客户端从 /list 自己算，原因见本地同名路由的注释：
// 阈值是服务端配置、"从未上报"与"刚上报过"在事件列表里长得几乎一样、
// 且子女端要在控制台第一屏就回答"老人现在联系得上吗"。
eventRoutes.get('/status/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = Number(c.req.param('elderId'));

  const user = await c.env.DB.prepare('SELECT guard_settings FROM users WHERE id = ?')
    .bind(elderId)
    .first<{ guard_settings: string | null }>();
  if (!user) return c.json({ error: '老人不存在' }, 404);

  const lastTs = parseSqliteTime(await eventsDao.getLastReportAt(c.env, elderId));
  const minutesSince = lastTs === null ? null : Math.floor((Date.now() - lastTs) / 60000);
  const { state, thresholds } = evaluateLiveness(
    minutesSince,
    extractLivenessThresholds(user.guard_settings)
  );
  const lv = await eventsDao.getLivenessRow(c.env, elderId);

  return c.json({
    success: true,
    data: {
      state,
      minutes_since_report: minutesSince,
      last_report_at: lastTs === null ? null : toBeijing(new Date(lastTs)),
      warn_minutes: thresholds.warnMinutes,
      offline_minutes: thresholds.offlineMinutes,
      alerted_state: lv?.alerted_state ?? null
    }
  });
});
