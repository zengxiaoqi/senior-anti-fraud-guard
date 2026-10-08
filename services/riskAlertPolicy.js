/**
 * 风险告警的「是否需要子女立刻动手打断」判定（单一权威）。
 *
 * ## 为什么必须有这道闸
 * /api/events/report 对**每一条**事件都无条件广播 RISK_ALERT，而子女端收到就弹
 * 「⚠️ 收到紧急防诈预警 / 长者正处于高危状态 (LOCATION_UPDATE) / 是否立即发起远程打断？」。
 *
 * 老人端的定位上报是**心跳级**的：LocationGuardService 每次 onLocationChanged 都报一条
 * LOCATION_UPDATE，severity 写死 LOW（`if (isAway) "LOW" else "LOW"`）。
 * 结果就是子女端被每分钟一次的"高危状态"弹窗糊满屏幕。
 *
 * 这不只是吵。真正该弹的时刻（COERCION_RISK / PAYMENT_RISK）到来时，子女的注意力
 * 正被这些噪声弹窗占着 —— 这是最典型的**告警疲劳**，而且是漏报方向的。
 *
 * ## 判定标准：打断能改变结果，才值得打断
 * 只有「危险正在发生、子女动手能立刻改变结果」的事件才配弹打断确认框：
 *   - SOS           老人自己按了求助
 *   - PAYMENT_RISK  大额扣款正在/已经发生
 *   - COERCION_RISK 通话中被诱导屏幕共享或转账
 *   - CALL_RISK     通话中的时长预警（CallScreeningGuardService 实时监测）
 *
 * 明确**不**打断的类型（哪怕它是 HIGH）：
 *   - GEOFENCE_RECORDING 进入敏感地点自动录音 —— 这是守护系统的**正常动作**，不是危险信号。
 *     弹红屏打断只会打断老人正在办的事，还徒增恐慌。这条最容易被"一刀切按 HIGH 过滤"漏掉。
 *   - LOCATION_RISK / GEOFENCE_DWELL（MEDIUM）—— "在陌生地点停留 40 分钟"该做的是
 *     打个电话问一声，不是拉响警报。
 *   - LOCATION_UPDATE / CALL_STAT / GEOFENCE_EXIT / DEVICE_ONLINE —— 心跳与流水，只入列表。
 *
 * Android 侧 family/RiskAlertPolicy.kt 与小程序 app.js 都镜像了这份规则；
 * 改动请三处同步，否则会出现"服务端说该弹、客户端不弹"的静默不一致。
 */

/** 需要子女立刻动手打断的事件类型（白名单，不是黑名单） */
const INTERRUPTIBLE_EVENTS = ['SOS', 'PAYMENT_RISK', 'COERCION_RISK', 'CALL_RISK'];

/**
 * 判定一条风险事件是否值得在子女端弹出「强打断」确认框。
 *
 * @param {string} eventType 事件类型，如 COERCION_RISK / LOCATION_UPDATE
 * @param {string} severity  服务端归一化后的级别：HIGH / MEDIUM / LOW
 * @returns {boolean}
 */
function shouldInterrupt(eventType, severity) {
  const type = String(eventType == null ? '' : eventType).trim().toUpperCase();
  if (INTERRUPTIBLE_EVENTS.indexOf(type) === -1) return false;
  // SOS 是老人主动按下的求助，任何级别都必须立刻送到子女眼前，不做级别过滤
  if (type === 'SOS') return true;
  return String(severity == null ? '' : severity).trim().toUpperCase() === 'HIGH';
}

module.exports = { INTERRUPTIBLE_EVENTS, shouldInterrupt };