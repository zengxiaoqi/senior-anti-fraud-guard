// 录音证据生命周期清理
//
// 需求是"只有存在诈骗的才保留作为证据"，但直接"AI 判为非诈骗就立刻删除"是危险的：
// 老人可能正处在被骗过程中，AI 一次误判就再也找不回原始录音。
// 因此这里的策略是：
//   FRAUD            → 永久保留（keep_as_evidence=1，无 retention_until）
//   SUSPECT / 转写失败 → 临时保留到 retention_until，期间子女端可人工复核推翻
//   SAFE             → 临时保留到 retention_until，到期才清理
//
// 清理动作本身是"删文件 + 删记录"，不留副本；每次清理都写日志便于追溯。
const db = require('../database/db');
const store = require('../services/recordingStore');

const DEFAULT_INTERVAL_MS = 30 * 60 * 1000; // 每 30 分钟扫一次
let timer = null;

/** 扫描一次到期记录并清理，返回清理条数 */
function sweepExpired(now = new Date()) {
  return new Promise((resolve) => {
    db.all(
      `SELECT id, elder_id, file_name, fraud_status, retention_until, cleanup_reason
       FROM recordings
       WHERE retention_until IS NOT NULL
         AND retention_until <= ?
         AND keep_as_evidence = 0`,
      [now.toISOString()],
      (err, rows) => {
        if (err) {
          console.error('扫描到期录音失败:', err.message);
          return resolve(0);
        }
        if (!rows || rows.length === 0) return resolve(0);

        let cleaned = 0;
        for (const r of rows) {
          // 再确认一次：人工复核过的一律不删，哪怕 retention_until 已过
          db.get('SELECT reviewed_by_family FROM recordings WHERE id = ?', [r.id], (e, cur) => {
            if (e || !cur) return;
            if (cur.reviewed_by_family) return;

            store.deleteStoredFile(r.file_name);
            db.run(
              'DELETE FROM recordings WHERE id = ? AND keep_as_evidence = 0 AND reviewed_by_family = 0',
              [r.id],
              (delErr) => {
                if (delErr) {
                  console.error(`清理录音 #${r.id} 失败:`, delErr.message);
                  return;
                }
                cleaned += 1;
                console.log(
                  `🗑️ 已清理录音 #${r.id}（老人 ${r.elder_id}，研判 ${r.fraud_status}，` +
                  `到期 ${r.retention_until}）${r.cleanup_reason ? `｜${r.cleanup_reason}` : ''}`
                );
              }
            );
          });
        }
        // 异步逐条处理，等一小会儿再汇总
        setTimeout(() => resolve(cleaned), 800);
      }
    );
  });
}

/** 启动定时清理（server.js 启动时调用） */
function startScheduler() {
  if (timer) return;
  const interval = parseInt(process.env.RECORDING_CLEANUP_INTERVAL_MS || String(DEFAULT_INTERVAL_MS), 10);
  timer = setInterval(() => { sweepExpired().catch(() => {}); }, interval);
  if (timer.unref) timer.unref();
  // 启动后先跑一次，接管历史遗留的到期数据
  setTimeout(() => { sweepExpired().catch(() => {}); }, 5000);
  console.log(`🧹 录音证据清理任务已启动（每 ${Math.round(interval / 60000)} 分钟扫描一次）`);
}

function stopScheduler() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

module.exports = { startScheduler, stopScheduler, sweepExpired };
