-- 老人在线/失联状态表（子女端「老人失联告警」的唯一状态源）
--
-- 对齐本地 database/db.js 的同名表。
-- 必须单独一张表而不是现算 MAX(created_at)：
--   - 失联告警要节流（同一级别只报一次），需要"上一轮已经报过什么"这个跨周期状态；
--   - 恢复事件（ELDER_RECONNECTED）同样依赖"本轮之前处于告警态"。
CREATE TABLE IF NOT EXISTS elder_liveness (
  elder_id INTEGER PRIMARY KEY,
  state TEXT NOT NULL DEFAULT 'ONLINE',        -- ONLINE / SILENT / OFFLINE
  alerted_state TEXT,                           -- 已经发过告警的级别（节流依据）
  last_report_at TEXT,                          -- 最后一次任意上报（事件/轨迹/录音）
  last_state_change_at TEXT,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_liveness_state ON elder_liveness(state);
