/**
 * 老人失联扫描器（本地后端）：定时检查每个老人的最后一次上报时间。
 *
 * 对齐 Workers 的 src/cron/presenceSweep.ts —— 两边判定口径走同一份
 * services/elderLiveness.js 纯函数，差异只在"怎么拿到老人列表"和"怎么广播"。
 *
 * ## 为什么需要它
 * 老人端一旦停止上报（摔倒在够不着手机的地方 / 没电 / 被带走），
 * 子女端目前**完全无感** —— 因为子女端看到的一切都来自 risk_events 与 locations。
 * 这个扫描器是唯一能在"老人没有发出任何信号"时还做出判断的地方。
 *
 * ## 三条必须守住的规则
 *  1. **只扫描有子女绑定的老人**。没绑定的老人没有告警对象，
 *     给他生成告警事件只会污染他自己的事件列表。
 *  2. **只处理 guardEnabled 的老人**。用户在 App 里关掉守护开关 = 明确表示
 *     "我不需要被监测"，此时失联告警是越权。
 *  3. **节流**（nextAlertState）。同一级别只报一次，恢复上报后才重置。
 *     这是防"子女端被同一条失联告警刷屏"的唯一机制。
 *
 * 定时器用 setInterval（本地后端是长驻进程）；Workers 侧没有长驻进程，
 * 走 Cron Triggers，见 workers/src/cron/presenceSweep.ts。
 */
const db = require('../database/db');
const {
  LIVENESS_ONLINE,
  evaluateLiveness,
  nextAlertState,
  describeThresholds,
  extractLivenessThresholds,
  parseSqliteTime
} = require('./elderLiveness');

/**
 * 扫描间隔（毫秒）。默认 30 分钟：阈值是 90 分钟静默 / 240 分钟失联，
 * 30 分钟的发现延迟对告警链路足够，同时大幅压低 D1 行读取量。
 * 可用环境变量 PRESENCE_SCAN_MINUTES 覆盖（合法 5~240），
 * 与 Workers 侧的 SCAN_INTERVAL_MINUTES / KV 覆盖口径一致，别让两端口径漂移。
 */
const SCAN_INTERVAL_MIN = (() => {
  const raw = Number(process.env.PRESENCE_SCAN_MINUTES);
  const v = Number.isFinite(raw) && raw > 0 ? Math.round(raw) : 30;
  return Math.min(Math.max(v, 5), 240);
})();
const SCAN_INTERVAL_MS = SCAN_INTERVAL_MIN * 60 * 1000;

/**
 * 取"最后一次**老人端**上报"的时间戳（毫秒）。
 *
 * 为什么要取三张表的最大值而不是只看 risk_events：
 *  - locations  表：定位心跳（LOCATION_UPDATE 是事件，但纯位置上报路径也写这里）
 *  - risk_events 表：通话/支付/围栏事件
 *  - recordings  表：录音上传（录音存证链路独立于事件上报）
 * 老人如果整段时间只是在屋里录音（围栏录音）而没有位置回调，
 * 只看 risk_events 会误判失联 —— 那正是"守护系统在工作"的时刻。
 *
 * ## 关键：必须排除失联告警自身
 * 这个扫描器把 ELDER_SILENT / ELDER_OFFLINE / ELDER_RECONNECTED 写进 risk_events，
 * 而这些是**服务端自己造的行**，不是老人端的上报。若不排除：
 *   第 1 轮：5 小时没上报 → 写入 ELDER_OFFLINE
 *   第 2 轮：last_report_at 变成了"刚才"（因为上一轮那条告警）→ 判定 ONLINE
 *            → 写入 ELDER_RECONNECTED
 *   第 3 轮：又是 5 小时前 → 再写 ELDER_OFFLINE
 * 于是每 5 分钟在"失联 ↔ 已恢复"之间反复横跳，
 * 而老人从头到尾**一次都没上线过**。
 * 这是本条链路最容易踩的坑：告警自己成了"活着的证据"。
 *
 * 时间戳解析统一走 elderLiveness.parseSqliteTime（与 /status 接口同一份）。
 */
const SELF_GENERATED_EVENTS = ['ELDER_SILENT', 'ELDER_OFFLINE', 'ELDER_RECONNECTED'];

// 每条分支写成 "倒序取 1 条" 而不是 MAX(created_at)：
// 带 event_type 过滤时 MAX 会退化成扫完该老人全部历史行，失联扫描每轮都跑，
// 行数一涨就把 D1 免费额度吃光（2026-10-11 配额打爆事故）。倒序 + LIMIT 1
// 让每条分支都只读常数行，语义与 MAX 等价。
// 注意每条分支都得是**独立标量子查询**：复合 SELECT 上的 ORDER BY/LIMIT
// 作用于整个 UNION，写在分支里会被当成"给整个 UNION 排序并只取 1 行"，
// 实测直接返回 null。
const LAST_REPORT_SQL = `
  SELECT MAX(ts) AS last_ts FROM (
    SELECT (SELECT created_at FROM risk_events
             WHERE elder_id = ? AND event_type NOT IN ('ELDER_SILENT', 'ELDER_OFFLINE', 'ELDER_RECONNECTED')
             ORDER BY created_at DESC LIMIT 1) AS ts
    UNION ALL
    SELECT (SELECT created_at FROM locations WHERE elder_id = ? ORDER BY created_at DESC LIMIT 1)
    UNION ALL
    SELECT (SELECT created_at FROM recordings WHERE elder_id = ? ORDER BY created_at DESC LIMIT 1)
  )
`;

/**
 * 判定单个老人并落库 + 必要时生成事件。抽出来是为了能单测。
 *
 * @param {object} deps  { db, thresholdsByElder, onAlert }
 *        thresholdsByElder: Map<elderId, {warnMinutes?, offlineMinutes?}>（本轮预取，见 runOnce）
 * @param {object} row   老人行（id / name）
 * @param {object|null} livenessRow 现有 elder_liveness 行
 * @param {number} nowMs
 * @returns {Promise<{changed: boolean, eventType: string|null}>}
 */
async function evaluateOne(deps, row, livenessRow, nowMs) {
  const { db: database, thresholdsByElder, onAlert } = deps;
  const elderId = row.id;

  const lastTs = await new Promise((resolve) => {
    database.get(LAST_REPORT_SQL, [elderId, elderId, elderId], (err, r) => {
      if (err) {
        console.error('[失联扫描] 查询最后上报时间失败:', err.message);
        resolve(null);
        return;
      }
      resolve(parseSqliteTime(r && r.last_ts));
    });
  });

  // 从未上报过：状态记 ONLINE、不告警（evaluateLiveness 内已处理）
  const minutesSince = lastTs === null ? null : Math.floor((nowMs - lastTs) / 60000);
  const rawSettings = thresholdsByElder ? thresholdsByElder.get(elderId) : null;
  const { state, thresholds } = evaluateLiveness(minutesSince, rawSettings);

  const previousAlerted = (livenessRow && livenessRow.alerted_state) || null;
  const decision = nextAlertState(state, previousAlerted);

  // 状态没变且没新告警 → 不写库。扫描每 5 分钟一次，
  // 无条件 UPDATE 会把 updated_at 刷成"刚刚"，这个字段就再也说明不了任何事。
  const stateUnchanged = (livenessRow && livenessRow.state) === state;
  const alertedUnchanged = (livenessRow && (livenessRow.alerted_state || null)) === previousAlerted;
  if (stateUnchanged && alertedUnchanged && !decision.shouldAlert) {
    return { changed: false, eventType: null };
  }

  const nowIso = new Date(nowMs).toISOString().replace('T', ' ').slice(0, 19);
  const nextAlerted = decision.shouldAlert && decision.eventType !== 'ELDER_RECONNECTED'
    ? state
    : (decision.eventType === 'ELDER_RECONNECTED' ? null : previousAlerted);

  await new Promise((resolve) => {
    database.run(
      `INSERT INTO elder_liveness (elder_id, state, alerted_state, last_report_at, last_state_change_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(elder_id) DO UPDATE SET
         state = excluded.state,
         alerted_state = excluded.alerted_state,
         last_report_at = excluded.last_report_at,
         last_state_change_at = CASE WHEN elder_liveness.state IS excluded.state
                                     THEN elder_liveness.last_state_change_at
                                     ELSE excluded.last_state_change_at END,
         updated_at = excluded.updated_at`,
      [elderId, state, nextAlerted, lastTs ? new Date(lastTs).toISOString().replace('T', ' ').slice(0, 19) : null, nowIso, nowIso],
      (err) => {
        if (err) console.error('[失联扫描] 写入状态失败:', err.message);
        resolve();
      }
    );
  });

  if (decision.shouldAlert) {
    const details = {
      state,
      minutes_since_report: minutesSince,
      warn_minutes: thresholds.warnMinutes,
      offline_minutes: thresholds.offlineMinutes,
      threshold_desc: describeThresholds(thresholds),
      event_desc: describeEvent(state, minutesSince, thresholds)
    };
    await new Promise((resolve) => {
      database.run(
        `INSERT INTO risk_events (elder_id, event_type, severity, details) VALUES (?, ?, ?, ?)`,
        [elderId, decision.eventType, decision.severity, JSON.stringify(details)],
        (err) => {
          if (err) {
            console.error('[失联扫描] 写入事件失败:', err.message);
            resolve();
            return;
          }
          // 生成事件后立刻广播给子女端，不等下一轮轮询
          try {
            if (typeof onAlert === 'function') {
              onAlert(elderId, decision.eventType, decision.severity, details);
            }
          } catch (e) {
            console.error('[失联扫描] 广播失败:', e.message);
          }
          resolve();
        }
      );
    });
  }

  return { changed: true, eventType: decision.eventType };
}

function describeEvent(state, minutes, thresholds) {
  if (state === LIVENESS_ONLINE) {
    return `老人已重新上报，守护恢复正常（此前中断约 ${minutes ?? 0} 分钟）`;
  }
  const human = (m) => {
    const v = Number(m) || 0;
    if (v < 60) return `${v} 分钟`;
    const h = Math.floor(v / 60);
    const rest = v % 60;
    return rest ? `${h} 小时 ${rest} 分钟` : `${h} 小时`;
  };
  if (state === 'SILENT') {
    return `老人已 ${human(minutes)} 没有任何上报，超过 ${thresholds.warnMinutes} 分钟静默阈值，请留意`;
  }
  return `老人已 ${human(minutes)} 没有任何上报，超过 ${thresholds.offlineMinutes} 分钟失联阈值，请立即电话或上门核实`;
}

/**
 * 执行一轮扫描。
 *
 * @param {object} deps { onAlert }
 * @returns {Promise<{scanned: number, alerted: string[]}>}
 */
async function runOnce(deps) {
  const { onAlert } = deps;
  const nowMs = Date.now();

  // 规则 1：只扫「有绑定子女」的 elder。
  // 注意守护开关（GuardConfig.guardEnabled）是**本机**状态，服务端无从得知，
  // 所以服务端侧唯一能判的"要不要监测"就是"有没有绑定子女"。
  // 连这一条都没有的老人没有告警对象，给他记状态只会污染他自己的数据。
  //
  // 同时把 guard_settings 一起带出来：本轮一次性解析成 Map，
  // 而不是逐个老人再查一次库（判定是同步的，没法在循环里 await 单条查询）。
  const rows = await new Promise((resolve) => {
    db.all(
      `SELECT u.id AS id, u.name AS name, u.bound_user_id AS bound_user_id,
              u.guard_settings AS guard_settings
         FROM users u
        WHERE u.role = 'elder' AND u.bound_user_id IS NOT NULL`,
      [],
      (err, r) => {
        if (err) {
          console.error('[失联扫描] 读取老人列表失败:', err.message);
          resolve([]);
          return;
        }
        resolve(r || []);
      }
    );
  });

  const thresholdsByElder = new Map();
  for (const row of rows) {
    const lv = extractLivenessThresholds(row.guard_settings);
    if (lv) thresholdsByElder.set(row.id, lv);
  }

  const alerted = [];
  for (const row of rows) {
    try {
      const livenessRow = await new Promise((resolve) => {
        db.get('SELECT * FROM elder_liveness WHERE elder_id = ?', [row.id], (err, r) => {
          if (err) {
            console.error('[失联扫描] 读取状态失败:', err.message);
            resolve(null);
            return;
          }
          resolve(r || null);
        });
      });
      const res = await evaluateOne({ db, thresholdsByElder, onAlert }, row, livenessRow, nowMs);
      if (res.eventType) alerted.push(`${row.name || row.id}:${res.eventType}`);
    } catch (e) {
      // 单个老人失败绝不能中断整轮扫描
      console.error('[失联扫描] 单个老人判定异常:', e && e.message);
    }
  }

  if (alerted.length) console.log(`[失联扫描] 本轮生成告警：${alerted.join('、')}`);
  return { scanned: rows.length, alerted };
}

/**
 * 从 guard_settings JSON 里取出失联阈值。
 * 实现复用 services/elderLiveness 的 extractLivenessThresholds（顶部已 require，
 * 与 /status 接口同一份），避免"扫描器认一种格式、接口认另一种格式"。
 */

let timer = null;

/**
 * 启动定时扫描（server.js 启动时调用）。
 * 立即先跑一轮，避免"服务刚起来要等 5 分钟才判第一次失联"。
 */
function startScheduler(deps) {
  if (timer) return;
  const tick = () => {
    runOnce(deps).catch((e) => console.error('[失联扫描] 轮次异常:', e && e.message));
  };
  tick();
  timer = setInterval(tick, SCAN_INTERVAL_MS);
  // 不阻止进程退出（本地后端由计划任务前台持有）
  if (timer.unref) timer.unref();
  console.log(`⏱️  老人失联扫描已启动（每 ${SCAN_INTERVAL_MS / 60000} 分钟一轮）`);
}

function stopScheduler() {
  if (!timer) return;
  clearInterval(timer);
  timer = null;
}

module.exports = {
  SCAN_INTERVAL_MS,
  LAST_REPORT_SQL,
  parseSqliteTime,
  extractLivenessThresholds,
  evaluateOne,
  runOnce,
  startScheduler,
  stopScheduler
};
