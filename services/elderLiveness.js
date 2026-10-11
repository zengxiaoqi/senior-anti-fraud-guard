/**
 * 老人「失联」的判定与告警节流（单一权威，纯函数，无IO）。
 *
 * ## 为什么必须有这条线
 * 现在子女端能看到的"老人状态"完全来自 risk_events / locations 两张表，
 * 也就是说：**老人端一停报，子女端就什么都不知道了**。
 *
 * 而恰恰是老人出事的那一刻最需要子女知道：
 *   - 摔倒在角落够不着手机 → 不上报（这是设计使然，不是故障）
 *   - 手机没电 / 被摔坏 / 进了水 → 不上报
 *   - 被电信诈骗团伙带走手机 → 主动停报
 *
 * 老人端的正常上报周期是 10 分钟（LocationGuardService 心跳），
 * 加上通话/支付事件，所以「超过 2 小时一条都没有」本身就是异常信号，
 * 而这个异常**只能在服务端被主动发现** —— 老人端自己没法上报自己没上报。
 *
 * ## 为什么要分级而不是一刀切
 * 老人晚上睡觉、不出门、关机充电，很可能连续几小时没有任何上报。
 * 一刀切按 HIGH 弹窗，第一个晚上全网用户的子女都会被"老人失联"糊醒，
 * 之后所有人都会把这个告警当成噪声 —— 这就是告警疲劳，且是漏报方向。
 *
 * 所以分两级，语义完全不同：
 *   SILENT  超过 warnMinutes（默认 90 分钟）无上报 → MEDIUM，只入列表 + 控制台标黄。
 *           这是"有点不对，值得留意"，**不打断**。
 *   OFFLINE 超过 offlineMinutes（默认 240 分钟）无上报 → HIGH，推送 + WS 广播。
 *           这是"老人联系不上了"，子女该打电话/上门，**要打断**。
 *
 * 两级都**只告警一次**，直到老人重新上报为止（见 [nextAlertState]）。
 * 不做节流的定时器每 5 分钟就会重复告警一次，子女端直接被同一条消息刷屏。
 *
 * ## 恢复必须显式落一条事件
 * 老人重新上报后除了清除失联状态，还要发一条 ELDER_RECONNECTED（LOW）。
 * 原因：子女点开控制台看到"2 小时失联"，第一反应是"现在好了吗"，
 * 没有这条事件就只能靠猜时间。
 *
 * Android / 小程序 / 服务端三处判定口径必须一致，改一处就要改三处。
 */

/** 失联状态机的四个状态 */
const LIVENESS_ONLINE = 'ONLINE';
const LIVENESS_SILENT = 'SILENT';
const LIVENESS_OFFLINE = 'OFFLINE';

/** 失联判定阈值（分钟）。可被 guard_settings 覆盖，未配置时用这些默认值。 */
const DEFAULT_WARN_MINUTES = 90;
const DEFAULT_OFFLINE_MINUTES = 240;

/**
 * 阈值归一化 + 相互约束。
 *
 * offline 必须严格大于 warn，否则会出现"刚判 SILENT 同一分钟又判 OFFLINE"
 * 的一次性双告警。两个阈值相等或反超时，把 offline 抬到 warn + 30。
 * 这样配置页即使让用户填了一组不自洽的值，也不会产生噪声。
 */
function normalizeThresholds(raw) {
  const src = raw || {};
  let warn = Number(src.warnMinutes);
  let offline = Number(src.offlineMinutes);

  if (!Number.isFinite(warn) || warn <= 0) warn = DEFAULT_WARN_MINUTES;
  if (!Number.isFinite(offline) || offline <= 0) offline = DEFAULT_OFFLINE_MINUTES;

  warn = Math.min(240, Math.max(30, Math.round(warn)));
  offline = Math.min(1440, Math.max(60, Math.round(offline)));
  if (offline <= warn) offline = Math.min(1440, warn + 30);

  return { warnMinutes: warn, offlineMinutes: offline };
}

/**
 * 由"最后一次上报距今多少分钟"推导当前应处于的失联状态。
 *
 * @param {number|null} minutesSinceLastReport 距最后一次上报的分钟数；null = 从未上报过
 * @param {object} [rawThresholds] warnMinutes / offlineMinutes（可来自 guard_settings）
 * @returns {{state: string, minutes: number|null, thresholds: {warnMinutes: number, offlineMinutes: number}}}
 */
function evaluateLiveness(minutesSinceLastReport, rawThresholds) {
  const thresholds = normalizeThresholds(rawThresholds);

  // 从未上报过：不能立刻判 OFFLINE。
  // 新绑定的老人可能一天都没触发过任何事件（没出门、没通话），
  // 那样会在绑定第二天清晨就推一条"老人失联"，是纯粹的误报。
  // 统一按"已经静默了阈值时长"处理，给用户一个完整的观察窗口。
  if (minutesSinceLastReport === null || minutesSinceLastReport === undefined) {
    return { state: LIVENESS_ONLINE, minutes: null, thresholds };
  }

  const minutes = Number(minutesSinceLastReport);
  if (!Number.isFinite(minutes) || minutes < 0) {
    return { state: LIVENESS_ONLINE, minutes: 0, thresholds };
  }
  if (minutes >= thresholds.offlineMinutes) return { state: LIVENESS_OFFLINE, minutes, thresholds };
  if (minutes >= thresholds.warnMinutes) return { state: LIVENESS_SILENT, minutes, thresholds };
  return { state: LIVENESS_ONLINE, minutes, thresholds };
}

/**
 * 告警节流：算出本次扫描该发什么事件。
 *
 * 这是整条链路里最容易写错的地方 —— 判定"该不该报"和判定"现在是几级"
 * 是两件事：
 *   - SILENT → OFFLINE 是升级，必须再报一次 HIGH（子女需要知道情况变严重了）；
 *   - SILENT → SILENT / OFFLINE → OFFLINE 什么都不发（否则每 5 分钟刷屏）。
 *
 * @param {string} currentState 本次扫描算出的状态（evaluateLiveness 的结果）
 * @param {string|null} alertedState 上一次**已经告警过**的状态；null = 从未告警（在线）
 * @returns {{shouldAlert: boolean, eventType: string|null, severity: string|null}}
 */
function nextAlertState(currentState, alertedState) {
  const current = String(currentState || LIVENESS_ONLINE);
  const alerted = alertedState ? String(alertedState) : null;

  if (current === LIVENESS_ONLINE) {
    // 已经告警过、现在又正常了 → 发一条恢复事件，让子女知道"已经好了"
    return alerted
      ? { shouldAlert: true, eventType: 'ELDER_RECONNECTED', severity: 'LOW' }
      : { shouldAlert: false, eventType: null, severity: null };
  }

  if (current === LIVENESS_SILENT) {
    return alerted === LIVENESS_SILENT
      ? { shouldAlert: false, eventType: null, severity: null }
      : { shouldAlert: true, eventType: 'ELDER_SILENT', severity: 'MEDIUM' };
  }

  if (current === LIVENESS_OFFLINE) {
    // OFFLINE 之前是 SILENT 也要再报：情况升级了，子女必须看到第二次提醒
    return alerted === LIVENESS_OFFLINE
      ? { shouldAlert: false, eventType: null, severity: null }
      : { shouldAlert: true, eventType: 'ELDER_OFFLINE', severity: 'HIGH' };
  }

  return { shouldAlert: false, eventType: null, severity: null };
}

/**
 * 阈值对应的老人端可读说明，用于写入事件 details 与设置页提示。
 */
function describeThresholds(thresholds) {
  const t = normalizeThresholds(thresholds);
  return `超过 ${t.warnMinutes} 分钟无上报视为长时间静默，超过 ${t.offlineMinutes} 分钟视为失联`;
}

/**
 * 从 users.guard_settings 的 JSON 里取出失联阈值子对象。
 *
 * 解析失败一律当"没配置"（用默认值）：一行坏 JSON 不该让失联检测整体失效，
 * 那等于把整条告警线静默关掉 —— 而用户界面上看不出任何异常。
 */
function extractLivenessThresholds(guardSettingsRaw) {
  try {
    if (!guardSettingsRaw) return null;
    const parsed = typeof guardSettingsRaw === 'string' ? JSON.parse(guardSettingsRaw) : guardSettingsRaw;
    const lv = parsed && typeof parsed === 'object' ? parsed.liveness : null;
    return lv && typeof lv === 'object' ? lv : null;
  } catch {
    return null;
  }
}

/**
 * 解析库里的时间戳 → 毫秒。解析失败返回 null（绝不用 Invalid Date 参与运算，
 * 那会让 minutesSince 变成 NaN，判定静默退化成"永远在线"）。
 *
 * SQLite / D1 的 CURRENT_TIMESTAMP 是 UTC 裸串（'YYYY-MM-DD HH:MM:SS'），
 * 没有时区标记。而 JS 的 new Date('2026-10-10 04:59:00') 在部分引擎上按**本地时**解析，
 * 于是同样一条记录在不同机器上差 8 小时 —— 这正是项目笔记里"时间显示"那条铁律的来源。
 * 所以这里统一补 Z：当原文没有任何时区信息时，按 UTC 解释。
 */
function parseSqliteTime(raw) {
  if (raw === null || raw === undefined) return null;
  const s = String(raw).trim();
  if (!s) return null;
  const hasZone = /[Zz]$/.test(s) || /[+-]\d{2}:?\d{2}$/.test(s);
  const normalized = hasZone ? s.replace(' ', 'T') : `${s.replace(' ', 'T')}Z`;
  const ms = new Date(normalized).getTime();
  return Number.isFinite(ms) ? ms : null;
}

module.exports = {
  LIVENESS_ONLINE,
  LIVENESS_SILENT,
  LIVENESS_OFFLINE,
  DEFAULT_WARN_MINUTES,
  DEFAULT_OFFLINE_MINUTES,
  normalizeThresholds,
  evaluateLiveness,
  nextAlertState,
  describeThresholds,
  extractLivenessThresholds,
  parseSqliteTime
};
