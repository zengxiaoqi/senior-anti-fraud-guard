-- 0001 初始建表（自本地 database/db.js 翻译）
--
-- 与本地库的差异只有一处：本地库历经多次 ALTER TABLE 补列（wx_openid / password_hash /
-- mobile / guard_settings / dwell_minutes），D1 直接以最终形态建表，不搬迁历史演进包袱。
-- 语义必须与本地库保持一致，否则契约测试（tests/contract）会红。

-- 1. 用户与亲情绑定表
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  role TEXT NOT NULL CHECK(role IN ('elder', 'family')),
  name TEXT NOT NULL,
  phone TEXT UNIQUE NOT NULL,
  bind_code TEXT UNIQUE NOT NULL,
  bound_user_id INTEGER,
  wx_openid TEXT,
  password_hash TEXT,
  mobile TEXT,
  guard_settings TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 2. 登录态表（替代本地 tokenAuth.js 的内存 Map）
CREATE TABLE IF NOT EXISTS auth_tokens (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens(user_id);

-- 3. 风险行为感知事件表
CREATE TABLE IF NOT EXISTS risk_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  severity TEXT NOT NULL CHECK(severity IN ('LOW', 'MEDIUM', 'HIGH')),
  details TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

-- 4. 地理轨迹与敏感地点日志表
CREATE TABLE IF NOT EXISTS locations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  address TEXT NOT NULL,
  is_sensitive INTEGER DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

-- 5. 大额交易与扣款单据存证表
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  amount REAL NOT NULL,
  payee_name TEXT NOT NULL,
  payee_account TEXT,
  order_no TEXT UNIQUE,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

-- 6. 敏感地点围栏表
CREATE TABLE IF NOT EXISTS geofences (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  radius INTEGER NOT NULL DEFAULT 200,
  dwell_minutes INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

-- 7. 环境录音存证表
--    file_name 列在 Workers 版存 R2 的 object key（列名不动，客户端不感知存储差异）
CREATE TABLE IF NOT EXISTS recordings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  session_id TEXT NOT NULL,
  segment_index INTEGER NOT NULL DEFAULT 1,
  reason TEXT NOT NULL,
  place_name TEXT,
  file_name TEXT NOT NULL,
  original_name TEXT,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  size_bytes INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT,
  mime_type TEXT DEFAULT 'audio/mp4',
  recorded_at DATETIME,
  latitude REAL,
  longitude REAL,
  address TEXT,
  transcript TEXT,
  transcript_status TEXT DEFAULT 'PENDING' CHECK(transcript_status IN ('PENDING','DONE','FAILED','SKIPPED')),
  transcript_error TEXT,
  fraud_status TEXT DEFAULT 'PENDING' CHECK(fraud_status IN ('PENDING','ANALYZING','FRAUD','SUSPECT','SAFE','FAILED')),
  fraud_score REAL,
  fraud_verdict TEXT,
  fraud_labels TEXT,
  suspect_role TEXT,
  keep_as_evidence INTEGER NOT NULL DEFAULT 0,
  retention_until DATETIME,
  cleanup_reason TEXT,
  reviewed_by_family INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(elder_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_recordings_elder ON recordings(elder_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_recordings_session ON recordings(session_id, segment_index);
CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(elder_id, fraud_status);
-- cron 分析扫描按状态捞待处理记录
CREATE INDEX IF NOT EXISTS idx_recordings_pending ON recordings(transcript_status, id);
