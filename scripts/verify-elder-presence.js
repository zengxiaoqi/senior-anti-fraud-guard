/**
 * 失联扫描的端到端验证（手工脚本，非自动化测试）。
 * 用真实的临时库跑完整状态机：失联 → 节流 → 恢复。
 */
const path = require('path');
const tmpDb = process.env.TMP_DB;

const db = require(path.join(__dirname, '..', 'database', 'db'));
const scanner = require(path.join(__dirname, '..', 'services', 'elderPresenceScanner'));

const MIN = 60 * 1000;
const alerts = [];

function ins(eld, type, createdAt) {
  return new Promise((res) => db.run(
    'INSERT INTO risk_events (elder_id,event_type,severity,details,created_at) VALUES (?,?,?,?,?)',
    [eld, type, 'LOW', '{}', createdAt], () => res()
  ));
}
function clear(eld) {
  return new Promise((res) => {
    db.run('DELETE FROM risk_events WHERE elder_id = ?', [eld], () =>
      db.run('DELETE FROM locations WHERE elder_id = ?', [eld], () => res()));
  });
}
function scan() {
  return scanner.runOnce({ onAlert: (id, t, s, d) => alerts.push(`${t}/${s}@${d.minutes_since_report}m`) });
}
const sqlNowMinus = (min) => new Date(Date.now() - min * MIN).toISOString().replace('T', ' ').slice(0, 19);
const assert = (cond, msg) => { if (!cond) { console.log('❌ ' + msg); process.exitCode = 1; } else console.log('✅ ' + msg); };

(async () => {
  await new Promise(r => setTimeout(r, 1200));
  const E = 1;

  // 场景：5 小时无任何老人端上报
  await clear(E);
  await ins(E, 'LOCATION_UPDATE', sqlNowMinus(300));

  alerts.length = 0;
  let r = await scan();
  assert(alerts.some(a => a.startsWith('ELDER_OFFLINE/HIGH')), `第1轮判失联并告警 HIGH [${alerts.join(', ')}]`);

  alerts.length = 0;
  r = await scan();
  assert(alerts.length === 0, `第2轮被节流，不重复告警 [${alerts.join(', ') || '无'}]`);

  alerts.length = 0;
  await scan();
  assert(alerts.length === 0, '第3轮仍被节流（老告警不得把自己当成"活着的证据"）');

  // 场景：老人真的重新上报了
  alerts.length = 0;
  await ins(E, 'LOCATION_UPDATE', sqlNowMinus(1));
  await scan();
  assert(alerts.some(a => a.startsWith('ELDER_RECONNECTED/LOW')), `恢复后发一条 RECONNECTED [${alerts.join(', ')}]`);

  alerts.length = 0;
  await scan();
  assert(alerts.length === 0, '恢复后再次节流，不重复发恢复事件');

  // 场景：恢复后又失联，应能重新告警（节流状态已被清空）
  await clear(E);
  await ins(E, 'LOCATION_UPDATE', sqlNowMinus(300));
  alerts.length = 0;
  await scan();
  assert(alerts.some(a => a.startsWith('ELDER_OFFLINE/HIGH')), '恢复后再次失联能重新告警（闩锁已清）');

  // 场景：只落在 warn 与 offline 之间的 SILENT
  await clear(E);
  await ins(E, 'LOCATION_UPDATE', sqlNowMinus(120));
  alerts.length = 0;
  await scan();
  assert(alerts.some(a => a.startsWith('ELDER_SILENT/MEDIUM')), `中间档判 SILENT/MEDIUM [${alerts.join(', ')}]`);

  // 从 SILENT 升级到 OFFLINE 必须再报一次 HIGH
  // 注意先清库：last_report_at 取的是 MAX(created_at)，
  // 若保留 2 小时前那条更新的行，补一条 5 小时前的行不会改变判定结果。
  await clear(E);
  await ins(E, 'LOCATION_UPDATE', sqlNowMinus(300));
  alerts.length = 0;
  await scan();
  assert(alerts.some(a => a.startsWith('ELDER_OFFLINE/HIGH')), 'SILENT→OFFLINE 升级需再报一次 HIGH');

  console.log('\n完成。');
})().catch(e => { console.error(e); process.exitCode = 1; });
