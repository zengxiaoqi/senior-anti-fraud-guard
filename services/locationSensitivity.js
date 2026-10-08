/**
 * 轨迹点的「敏感」判定 —— 写入 locations.is_sensitive 的唯一依据。
 *
 * ## 为什么要单独抽出来
 * 以前是一行内联：`severity === 'HIGH' || severity === 'MEDIUM' ? 1 : 0`。
 * 问题是它对**心跳事件**永远返回 0：老人端每次定位回调都报一条 LOCATION_UPDATE，
 * severity 恒 LOW，于是 locations 里每一行的 is_sensitive 都是 0。
 * 而小程序地图正是靠这个字段做橙色高亮（map.wxml 的 is_sensitive 三元）——
 * 结果就是"敏感位置标记"这条链路从来没生效过，子女在地图上看不出
 * "老人这次的点是不是在外面"。
 *
 * 现场一度以为是老人没出过门：db 里 LOCATION_UPDATE 的 is_sensitive 全部为 0，
 * 而老人实际一直在外面活动。字段存在、没人报错、界面只是"颜色永远一样"，
 * 是典型的静默可理解性故障。
 *
 * ## 判定规则
 * 两类事件的语义完全不同，不能用同一把尺子：
 *
 *   - **心跳**（LOCATION_UPDATE / LOCATION_RISK / GEOFENCE_RECORDING / GEOFENCE_EXIT）
 *     走 `details.is_away_from_home`：老人出了门就是敏感位置，语义直白，
 *     且不需要动 severity（心跳保持 LOW 才是对的，改成 MEDIUM 会让每次出门的
 *     每一条心跳都升级，反过来污染微信推送与告警弹窗）。
 *   - **非位置类**事件（支付、通话、SOS…）维持"按 severity 推导"不变。
 *
 * 心跳事件上报方没带 is_away_from_home 时（老版本老人端 App），
 * 退回按 severity 判定，而不是一律当 0 —— 宁可标橙也不漏标。
 */

/** 走「离家判定」而不是「按 severity 判定」的事件类型 */
const LOCATION_DRIVEN_EVENTS = [
  'LOCATION_UPDATE',
  'LOCATION_RISK',
  'GEOFENCE_RECORDING',
  'GEOFENCE_EXIT'
];

/**
 * 宽松地把各种形态的真值解析成布尔。
 * 老人端可能传 true/false，也可能是字符串 "true" / 1 / "1"（JSON 序列化差异）。
 * 解析不出来时返回 null，让调用方能区分「明确不在家」和「不知道」。
 */
function toBoolLoose(raw) {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1') return true;
    if (s === 'false' || s === '0' || s === '') return false;
  }
  return null;
}

/**
 * @param {string} eventType 事件类型
 * @param {string} severity  服务端归一化后的级别（必须传 effSeverity，不是客户端自报值）
 * @param {object} details  事件详情
 * @returns {0|1} 写入 locations.is_sensitive
 */
function isSensitiveLocation(eventType, severity, details) {
  const sev = String(severity == null ? '' : severity).trim().toUpperCase();
  const isSevere = sev === 'HIGH' || sev === 'MEDIUM';

  if (LOCATION_DRIVEN_EVENTS.indexOf(String(eventType || '').trim().toUpperCase()) !== -1) {
    const away = toBoolLoose(details && details.is_away_from_home);
    // 上报方给了明确答案就用它；没给（老版本 App）则退回按 severity，宁可多标
    return (away === null ? isSevere : away) ? 1 : 0;
  }

  return isSevere ? 1 : 0;
}

module.exports = { LOCATION_DRIVEN_EVENTS, isSensitiveLocation, toBoolLoose };