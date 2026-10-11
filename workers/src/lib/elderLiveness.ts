/**
 * 老人失联判定的 Workers 镜像（对齐本地 services/elderLiveness.js）。
 *
 * 判定口径、阈值归一化、节流规则三处必须与本地版逐字对齐，
 * 否则会出现"本地后端不告警、Workers 告警"（或反之）的静默不一致。
 * 纯函数，不碰 D1 / DO，便于单测。
 */

export const LIVENESS_ONLINE = 'ONLINE';
export const LIVENESS_SILENT = 'SILENT';
export const LIVENESS_OFFLINE = 'OFFLINE';

export const DEFAULT_WARN_MINUTES = 90;
export const DEFAULT_OFFLINE_MINUTES = 240;

export function normalizeThresholds(
  raw?: { warnMinutes?: unknown; offlineMinutes?: unknown } | null
): { warnMinutes: number; offlineMinutes: number } {
  const src = (raw || {}) as { warnMinutes?: unknown; offlineMinutes?: unknown };
  let warn = Number(src.warnMinutes);
  let offline = Number(src.offlineMinutes);

  if (!Number.isFinite(warn) || warn <= 0) warn = DEFAULT_WARN_MINUTES;
  if (!Number.isFinite(offline) || offline <= 0) offline = DEFAULT_OFFLINE_MINUTES;

  warn = Math.min(240, Math.max(30, Math.round(warn)));
  offline = Math.min(1440, Math.max(60, Math.round(offline)));
  if (offline <= warn) offline = Math.min(1440, warn + 30);

  return { warnMinutes: warn, offlineMinutes: offline };
}

export function evaluateLiveness(
  minutesSinceLastReport: number | null | undefined,
  rawThresholds?: { warnMinutes?: unknown; offlineMinutes?: unknown } | null
): {
  state: string;
  minutes: number | null;
  thresholds: { warnMinutes: number; offlineMinutes: number };
} {
  const thresholds = normalizeThresholds(rawThresholds);

  // 从未上报过 → 按在线处理（给新绑定用户一个完整观察窗口，避免误报）
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

export function nextAlertState(
  currentState: string,
  alertedState: string | null
): { shouldAlert: boolean; eventType: string | null; severity: string | null } {
  const current = String(currentState || LIVENESS_ONLINE);
  const alerted = alertedState ? String(alertedState) : null;

  if (current === LIVENESS_ONLINE) {
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
    return alerted === LIVENESS_OFFLINE
      ? { shouldAlert: false, eventType: null, severity: null }
      : { shouldAlert: true, eventType: 'ELDER_OFFLINE', severity: 'HIGH' };
  }

  return { shouldAlert: false, eventType: null, severity: null };
}

export function describeThresholds(thresholds?: {
  warnMinutes?: unknown;
  offlineMinutes?: unknown;
} | null): string {
  const t = normalizeThresholds(thresholds);
  return `超过 ${t.warnMinutes} 分钟无上报视为长时间静默，超过 ${t.offlineMinutes} 分钟视为失联`;
}

/** 从 users.guard_settings 的 JSON 里取失联阈值；坏数据一律当"没配置"（对齐本地版） */
export function extractLivenessThresholds(
  guardSettingsRaw: string | Record<string, unknown> | null | undefined
): { warnMinutes?: unknown; offlineMinutes?: unknown } | null {
  try {
    if (!guardSettingsRaw) return null;
    const parsed = typeof guardSettingsRaw === 'string' ? JSON.parse(guardSettingsRaw) : guardSettingsRaw;
    const lv = parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>).liveness : null;
    return lv && typeof lv === 'object' ? (lv as { warnMinutes?: unknown; offlineMinutes?: unknown }) : null;
  } catch {
    return null;
  }
}

/**
 * 解析库里的时间戳 → 毫秒。解析失败返回 null。
 *
 * SQLite / D1 的 CURRENT_TIMESTAMP 是 UTC 裸串且无时区标记，
 * 而 new Date('2026-10-10 04:59:00') 在不同引擎上按本地时解析，会差 8 小时。
 * 所以原文没有时区信息时统一补 Z，按 UTC 解释（对齐本地版与项目"时间"铁律）。
 */
export function parseSqliteTime(raw: string | null | undefined): number | null {
  if (raw === null || raw === undefined) return null;
  const s = String(raw).trim();
  if (!s) return null;
  const hasZone = /[Zz]$/.test(s) || /[+-]\d{2}:?\d{2}$/.test(s);
  const normalized = hasZone ? s.replace(' ', 'T') : `${s.replace(' ', 'T')}Z`;
  const ms = new Date(normalized).getTime();
  return Number.isFinite(ms) ? ms : null;
}
