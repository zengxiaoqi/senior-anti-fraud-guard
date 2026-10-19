/**
 * 微信订阅消息推送 —— 小程序高危告警的离线触达兜底。
 *
 * 为什么需要：小程序切后台约 5 秒即被挂起，WebSocket 随之断开。
 * 此前 GuardHubDO.notifyBoundFamily 对离线子女是"静默丢弃"——
 * 老人真出事时（SOS/跌倒/失联），子女大概率不在小程序前台，告警等于没送达到人。
 * 订阅消息是微信官方允许的"小程序不在线也能通知用户"的通道（一次性订阅：一次授权一条）。
 *
 * 使用前提（缺任何一项都自动降级为 no-op，不影响主流程）：
 *  1. 在微信公众平台 → 订阅消息里选用一个模板，把模板 ID 配到
 *     wrangler secret：`wrangler secret put WECHAT_SUBSCRIBE_TMPL_ID`
 *  2. 当前使用的模板（2026-10-18 选用，编号 39041「秒杀倒计时提醒」，
 *     场景说明已改为"老人风险告警"）：
 *       thing1 = 事件（活动名称位）
 *       time2  = 发生时间（活动时间位）
 *       thing3 = 处置建议（温馨提示位）
 *     若日后换模板，改下面 ALERT_DATA_FIELDS 与 buildAlertData 即可
 *  3. 子女端在用户点按时调 wx.requestSubscribeMessage 攒授权额度
 *     （见小程序 app.js requestAlertSubscription，每次授权 = 可下发一条）
 *
 * 调用点：GuardHubDO.notifyBoundFamily —— WS 不在线且 payload 是可打断的
 * RISK_ALERT 时触发。这里刻意不做重试队列：订阅消息有时效性，迟到的告警
 * 会误导子女（以为老人"现在"出事），送不出去就该靠 INTERRUPT_ACK 的
 * "请直接电话联系老人"兜底文案补位。
 */
import type { Env } from '../env';

/** access_token 有效期 7200s，提前 1 小时刷新，避免临界点 401 */
const TOKEN_TTL_MARGIN_MS = 60 * 60 * 1000;

/** 同一 (family, eventType) 的最小下发间隔：presenceSweep 周期性扫失联，不节流会刷屏 */
const THROTTLE_MS = 5 * 60 * 1000;

let cachedToken: { value: string; expiresAt: number } | null = null;

/** 事件类型 → 订阅消息里的"事件"文案（与 Android EventLabels 同源口径，去掉 emoji 防占宽） */
const EVENT_LABELS: Record<string, string> = {
  'SOS': '老人按下紧急求助！',
  'FALL_RISK': '检测到老人疑似跌倒',
  'ELDER_OFFLINE': '老人手机已失联',
  'ELDER_SILENT': '老人长时间无上报',
  'PAYMENT_RISK': '发生大额支付预警',
  'CALL_RISK': '检测到异常通话',
  'COERCION_RISK': '通话中疑似被诱导操作'
};

const EVENT_ADVICE: Record<string, string> = {
  'SOS': '请立即回电老人确认安危',
  'FALL_RISK': '请立即回电确认是否受伤',
  'ELDER_OFFLINE': '请尽快电话联系老人',
  'ELDER_SILENT': '请尽快电话联系老人',
  'PAYMENT_RISK': '请核实是否本人操作',
  'CALL_RISK': '请回电提醒老人防骗',
  'COERCION_RISK': '请立即打断并回电核实'
};

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

/** 北京时间裸串（服务端统一格式），转订阅消息 time 字段的"yyyy-MM-dd HH:mm" */
function formatBeijing(raw: string): string {
  const m = String(raw || '').match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? `${m[1]} ${m[2]}` : truncate(String(raw || ''), 16);
}

async function getAccessToken(env: Env): Promise<string | null> {
  if (!env.WECHAT_APPID || !env.WECHAT_APPSECRET) return null;
  if (cachedToken && Date.now() < cachedToken.expiresAt) return cachedToken.value;
  try {
    const url =
      `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential` +
      `&appid=${env.WECHAT_APPID}&secret=${env.WECHAT_APPSECRET}`;
    const res = await fetch(url);
    const json = (await res.json()) as {
      access_token?: string; expires_in?: number; errcode?: number; errmsg?: string;
    };
    if (!json.access_token) {
      console.warn('[wxSubscribe] 获取 access_token 失败:', json.errcode, json.errmsg);
      return null;
    }
    cachedToken = {
      value: json.access_token,
      expiresAt: Date.now() + ((json.expires_in || 7200) * 1000) - TOKEN_TTL_MARGIN_MS
    };
    return cachedToken.value;
  } catch (e) {
    console.warn('[wxSubscribe] 获取 access_token 异常:', e);
    return null;
  }
}

/**
 * 高危告警订阅消息下发（fire-and-forget，调用方无需 await 结果）。
 * 未配模板 / 用户无微信登录态（openid 为空）/ 未订阅(43101) 都静默跳过。
 */
export async function sendAlertSubscribe(
  env: Env,
  familyUserId: number,
  alert: { eventType: string; createdAt: string }
): Promise<void> {
  if (!env.WECHAT_SUBSCRIBE_TMPL_ID) return; // 模板未配置 = 功能整体关闭

  try {
    const row = await env.DB.prepare('SELECT wx_openid FROM users WHERE id = ?')
      .bind(familyUserId)
      .first<{ wx_openid: string | null }>();
    const openid = (row?.wx_openid || '').trim();
    if (!openid) return; // 账密注册的 App 子女端没有 openid，发不了

    const token = await getAccessToken(env);
    if (!token) return;

    const eventType = String(alert.eventType || '').trim();
    // 模板 39041 字段位：thing1=事件标题位「活动名称」，time2=时间位「活动时间」，
    // thing3=建议位「温馨提示」。thing 类微信侧限 20 字符（超限 errcode 47003）
    const data: Record<string, { value: string }> = {
      thing1: { value: truncate(EVENT_LABELS[eventType] || `风险事件 ${eventType}`, 20) },
      time2: { value: formatBeijing(alert.createdAt) },
      thing3: { value: truncate(EVENT_ADVICE[eventType] || '请尽快联系老人核实情况', 20) }
    };

    const res = await fetch(
      `https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=${encodeURIComponent(token)}`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          touser: openid,
          template_id: env.WECHAT_SUBSCRIBE_TMPL_ID,
          page: 'pages/index/index',
          data
        })
      }
    );
    const json = (await res.json()) as { errcode?: number; errmsg?: string };
    const code = json.errcode || 0;
    // 43101 = 用户未订阅/订阅额度已用完：一次性订阅的正常消耗态，不告警不重试
    if (code !== 0 && code !== 43101) {
      // 40001/42001 = token 失效：清缓存让下次重新取
      if (code === 40001 || code === 42001) cachedToken = null;
      console.warn('[wxSubscribe] 订阅消息下发失败:', code, json.errmsg);
    }
  } catch (e) {
    // 推送失败绝不影响告警主流程
    console.warn('[wxSubscribe] 下发异常:', e);
  }
}
