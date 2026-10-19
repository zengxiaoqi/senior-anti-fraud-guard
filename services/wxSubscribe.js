/**
 * 微信订阅消息推送 —— 小程序高危告警的离线触达兜底（本地 Express 版）。
 *
 * 与 workers/src/lib/wxSubscribe.ts 同源双写，行为必须保持一致：
 *  - 只对可打断的高危 RISK_ALERT 且子女 WS 离线时下发；
 *  - 未配 WECHAT_APPID/WECHAT_APPSECRET/WECHAT_SUBSCRIBE_TMPL_ID 时整体 no-op；
 *  - 同一 (family, eventType) 5 分钟节流（失联扫描是周期性的，不节流会刷屏）。
 *
 * 调用点：server.js 的 notifyBoundFamily / eventsRoutes 广播回调，
 * 在"WS 不在线或发送失败"分支触发。
 */

const TOKEN_TTL_MARGIN_MS = 60 * 60 * 1000;
const THROTTLE_MS = 5 * 60 * 1000;

let cachedToken = null;
const lastSentAt = new Map();

const EVENT_LABELS = {
  'SOS': '老人按下紧急求助！',
  'FALL_RISK': '检测到老人疑似跌倒',
  'ELDER_OFFLINE': '老人手机已失联',
  'ELDER_SILENT': '老人长时间无上报',
  'PAYMENT_RISK': '发生大额支付预警',
  'CALL_RISK': '检测到异常通话',
  'COERCION_RISK': '通话中疑似被诱导操作'
};

const EVENT_ADVICE = {
  'SOS': '请立即回电老人确认安危',
  'FALL_RISK': '请立即回电确认是否受伤',
  'ELDER_OFFLINE': '请尽快电话联系老人',
  'ELDER_SILENT': '请尽快电话联系老人',
  'PAYMENT_RISK': '请核实是否本人操作',
  'CALL_RISK': '请回电提醒老人防骗',
  'COERCION_RISK': '请立即打断并回电核实'
};

function truncate(s, n) {
  s = String(s || '');
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function formatBeijing(raw) {
  const m = String(raw || '').match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? `${m[1]} ${m[2]}` : truncate(String(raw || ''), 16);
}

function configured() {
  return !!(process.env.WECHAT_APPID && process.env.WECHAT_APPSECRET && process.env.WECHAT_SUBSCRIBE_TMPL_ID);
}

async function getAccessToken() {
  if (cachedToken && Date.now() < cachedToken.expiresAt) return cachedToken.value;
  try {
    const url =
      `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential` +
      `&appid=${process.env.WECHAT_APPID}&secret=${process.env.WECHAT_APPSECRET}`;
    const res = await fetch(url);
    const json = await res.json();
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
    console.warn('[wxSubscribe] 获取 access_token 异常:', e.message);
    return null;
  }
}

/**
 * 高危告警订阅消息下发（fire-and-forget）。
 * @param {object} db  database/db 实例（查 family 用户的 wx_openid）
 * @param {number} familyUserId 子女用户 ID
 * @param {{eventType: string, createdAt: string}} alert
 */
async function sendAlertSubscribe(db, familyUserId, alert) {
  if (!configured()) return;

  try {
    db.get('SELECT wx_openid FROM users WHERE id = ?', [familyUserId], async (err, row) => {
      const openid = (row && row.wx_openid || '').trim();
      if (err || !openid) return; // 账密注册的 App 子女端没有 openid，发不了

      const token = await getAccessToken();
      if (!token) return;

      const eventType = String(alert.eventType || '').trim();
      // 与 workers/src/lib/wxSubscribe.ts 保持同一映射（模板 39041）：
      // thing1=事件「活动名称位」/ time2=时间「活动时间位」/ thing3=建议「温馨提示位」
      const data = {
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
            template_id: process.env.WECHAT_SUBSCRIBE_TMPL_ID,
            page: 'pages/index/index',
            data
          })
        }
      );
      const json = await res.json();
      const code = json.errcode || 0;
      // 43101 = 未订阅/额度用完：一次性订阅的正常消耗态，不告警不重试
      if (code !== 0 && code !== 43101) {
        if (code === 40001 || code === 42001) cachedToken = null;
        console.warn('[wxSubscribe] 订阅消息下发失败:', code, json.errmsg);
      }
    });
  } catch (e) {
    // 推送失败绝不影响告警主流程
    console.warn('[wxSubscribe] 下发异常:', e.message);
  }
}

/** 判定 + 节流 + 下发。payload 不符合"可打断高危 RISK_ALERT"时静默跳过 */
function maybeSubscribeAlert(db, familyUserId, payload) {
  try {
    if (!configured()) return;
    const p = payload || {};
    if (p.type !== 'RISK_ALERT') return;
    const d = p.data || {};
    if (!d.interruptible) return;

    const key = `${familyUserId}:${d.event_type || ''}`;
    const last = lastSentAt.get(key) || 0;
    if (Date.now() - last < THROTTLE_MS) return;
    lastSentAt.set(key, Date.now());

    sendAlertSubscribe(db, familyUserId, {
      eventType: String(d.event_type || ''),
      createdAt: String(d.created_at || new Date().toISOString())
    });
  } catch (e) {
    console.warn('[wxSubscribe] maybeSubscribeAlert 异常:', e.message);
  }
}

module.exports = { maybeSubscribeAlert };
