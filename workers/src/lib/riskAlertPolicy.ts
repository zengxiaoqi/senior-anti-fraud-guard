/**
 * 风险告警「是否需要子女立刻动手打断」判定（对齐本地 services/riskAlertPolicy.js）。
 *
 * 只有「危险正在发生、子女动手能立刻改变结果」的事件才配弹打断确认框。
 * Android 侧 family/RiskAlertPolicy.kt 与小程序 app.js 镜像了这份规则；
 * 改动请三处同步，否则会出现"服务端说该弹、客户端不弹"的静默不一致。
 */

/** 需要子女立刻动手打断的事件类型（白名单，不是黑名单） */
export const INTERRUPTIBLE_EVENTS = ['SOS', 'PAYMENT_RISK', 'COERCION_RISK', 'CALL_RISK'];

export function shouldInterrupt(eventType: string, severity: string): boolean {
  const type = String(eventType ?? '').trim().toUpperCase();
  if (INTERRUPTIBLE_EVENTS.indexOf(type) === -1) return false;
  // SOS 是老人主动按下的求助，任何级别都必须立刻送到子女眼前，不做级别过滤
  if (type === 'SOS') return true;
  return String(severity ?? '').trim().toUpperCase() === 'HIGH';
}
