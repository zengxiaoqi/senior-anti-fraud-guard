-- 0003：为高频查询补索引，压低 D1 行读取量
--
-- 背景：2026-10-11 生产库打爆 D1 免费套餐「每日行读取」配额（code 7500），
-- 所有读库接口一律 500，老人端"保存资料"同步失败。
-- 直接原因：risk_events / locations 两张表**完全没有索引**，
-- 而失联扫描每 5 分钟跑一轮 getLastReportAt（三表 MAX(created_at)，
-- 见 src/db/dao/events.ts:115），288 轮/天全部走全表扫描。
--
-- 索引按两类查询形状各建一个：
--   (elder_id, created_at) → MAX(created_at) 变成一次索引端点查找
--   (elder_id, id DESC)    → ORDER BY id DESC LIMIT N 免排序、只扫 N 行
-- 两者不能合并：SQLite 不会用 created_at 上的顺序去满足 ORDER BY id DESC。
CREATE INDEX IF NOT EXISTS idx_risk_events_elder_time ON risk_events(elder_id, created_at);
CREATE INDEX IF NOT EXISTS idx_risk_events_elder_id ON risk_events(elder_id, id DESC);

-- 判活专用部分索引：排除服务端自造的失联告警行后倒序取 1 条，
-- 索引里就只剩"真上报"，倒着走第一格必然命中，连过滤都不用试错。
CREATE INDEX IF NOT EXISTS idx_risk_events_last_report ON risk_events(elder_id, created_at)
  WHERE event_type NOT IN ('ELDER_SILENT', 'ELDER_OFFLINE', 'ELDER_RECONNECTED');

CREATE INDEX IF NOT EXISTS idx_locations_elder_time ON locations(elder_id, created_at);
CREATE INDEX IF NOT EXISTS idx_locations_elder_id ON locations(elder_id, id DESC);

-- 录音表已有 (elder_id, id DESC)，补一条时间轴让判活的 MAX(created_at) 走索引
CREATE INDEX IF NOT EXISTS idx_recordings_elder_created ON recordings(elder_id, created_at);

-- 支付/围栏：证据包与围栏匹配都按 elder_id 过滤，表虽小但避免累积全表扫描
CREATE INDEX IF NOT EXISTS idx_payments_elder_id ON payments(elder_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_geofences_elder ON geofences(elder_id);
