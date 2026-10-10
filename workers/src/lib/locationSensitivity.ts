/**
 * 轨迹点「敏感」判定（对齐本地 services/locationSensitivity.js）。
 *
 * 心跳类事件（LOCATION_UPDATE 等）走 details.is_away_from_home；
 * 非位置类维持按 severity 推导。上报方没带 is_away_from_home 时
 * 退回按 severity 判定 —— 宁可标橙也不漏标。
 */

/** 走「离家判定」而不是「按 severity 判定」的事件类型 */
export const LOCATION_DRIVEN_EVENTS = [
  'LOCATION_UPDATE',
  'LOCATION_RISK',
  'GEOFENCE_RECORDING',
  'GEOFENCE_EXIT'
];

/** 宽松地把各种形态的真值解析成布尔；解析不出来返回 null（区分「明确不在家」和「不知道」） */
export function toBoolLoose(raw: unknown): boolean | null {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1') return true;
    if (s === 'false' || s === '0' || s === '') return false;
  }
  return null;
}

export function isSensitiveLocation(eventType: string, severity: string, details: Record<string, unknown> | null): 0 | 1 {
  const sev = String(severity ?? '').trim().toUpperCase();
  const isSevere = sev === 'HIGH' || sev === 'MEDIUM';

  if (LOCATION_DRIVEN_EVENTS.indexOf(String(eventType ?? '').trim().toUpperCase()) !== -1) {
    const away = toBoolLoose(details && details.is_away_from_home);
    return (away === null ? isSevere : away) ? 1 : 0;
  }

  return isSevere ? 1 : 0;
}
