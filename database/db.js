const sqlite3 = require('sqlite3').verbose();
const path = require('path');

// 数据库路径可用 DB_PATH 环境变量覆盖：
//   生产/本地运行        -> data.sqlite（不进版本库）
//   演示/评审环境        -> data.demo.sqlite（由 scripts/seed.js 生成，纯假数据）
//   重置为全新生产库      -> 删除 data.sqlite 后启动，会自动建表+预置最小数据
const dbPath = process.env.DB_PATH || path.join(__dirname, '..', 'data.sqlite');

const db = new sqlite3.Database(dbPath, (err) => {
  if (err) {
    console.error('❌ SQLite 数据库连接失败:', err.message);
  } else {
    console.log('✅ SQLite 数据库成功连接：', dbPath);
  }
});

// 初始化数据库表结构
db.serialize(() => {
  // 1. 用户与亲情绑定表
  db.run(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      role TEXT NOT NULL CHECK(role IN ('elder', 'family')),
      name TEXT NOT NULL,
      phone TEXT UNIQUE NOT NULL,
      bind_code TEXT UNIQUE NOT NULL,
      bound_user_id INTEGER,
      wx_openid TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  // 为已有数据库添加 wx_openid 字段（如果不存在）
  db.run(`ALTER TABLE users ADD COLUMN wx_openid TEXT`, (err) => {
    if (err && !err.message.includes('duplicate column')) {
      console.error('添加 wx_openid 字段失败:', err.message);
    }
  });

  // 为已有数据库添加 password_hash 字段（App 子女端账号密码登录，如果不存在）
  db.run(`ALTER TABLE users ADD COLUMN password_hash TEXT`, (err) => {
    if (err && !err.message.includes('duplicate column')) {
      console.error('添加 password_hash 字段失败:', err.message);
    }
  });

  // 为已有数据库添加 mobile 字段（子女端真实手机号；phone 字段被用户名占用，见 /register）
  db.run(`ALTER TABLE users ADD COLUMN mobile TEXT`, (err) => {
    if (err && !err.message.includes('duplicate column')) {
      console.error('添加 mobile 字段失败:', err.message);
    }
  });

  // 为已有数据库添加 guard_settings 字段（老人端防护规则的云端副本，JSON 字符串）
  //
  // 为什么必须落库：阈值类配置（通话预警时长、支付预警金额、录音段数）以前只存本机
  // SharedPreferences，老人换个手机 / 重装 App 就全部回到默认值 —— 用户在老手机上
  // 精心调好的"通话超 10 分钟就告警"，新手机上悄悄失效，而且没有任何提示。
  // 与 elderId 一样按"每个老人一份"存，换机后凭手机号找回账号即可自动恢复。
  db.run(`ALTER TABLE users ADD COLUMN guard_settings TEXT`, (err) => {
    if (err && !err.message.includes('duplicate column')) {
      console.error('添加 guard_settings 字段失败:', err.message);
    }
  });

  // 2. 风险行为感知事件表
  db.run(`
    CREATE TABLE IF NOT EXISTS risk_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      elder_id INTEGER NOT NULL,
      event_type TEXT NOT NULL,
      severity TEXT NOT NULL CHECK(severity IN ('LOW', 'MEDIUM', 'HIGH')),
      details TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(elder_id) REFERENCES users(id)
    )
  `);

  // 3. 地理轨迹与敏感地点日志表
  db.run(`
    CREATE TABLE IF NOT EXISTS locations (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      elder_id INTEGER NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      address TEXT NOT NULL,
      is_sensitive INTEGER DEFAULT 0,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(elder_id) REFERENCES users(id)
    )
  `);

  // 4. 大额交易与扣款单据存证表
  db.run(`
    CREATE TABLE IF NOT EXISTS payments (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      elder_id INTEGER NOT NULL,
      amount REAL NOT NULL,
      payee_name TEXT NOT NULL,
      payee_account TEXT,
      order_no TEXT UNIQUE,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(elder_id) REFERENCES users(id)
    )
  `);

  // 5. 敏感地点围栏表（子女端配置可疑地点，老人端进入围栏自动录音上报）
  db.run(`
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
    )
  `);

  // 为已有数据库添加 dwell_minutes 字段（1-8 围栏内停留告警阈值，0=不告警）
  db.run(`ALTER TABLE geofences ADD COLUMN dwell_minutes INTEGER NOT NULL DEFAULT 0`, (err) => {
    if (err && !err.message.includes('duplicate column')) {
      console.error('添加 dwell_minutes 字段失败:', err.message);
    }
  });

  // 6. 环境录音存证表（老人端分段录音自动上传，转写 + AI 研判后决定是否作为证据保留）
  db.run(`
    CREATE TABLE IF NOT EXISTS recordings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      elder_id INTEGER NOT NULL,
      session_id TEXT NOT NULL,                -- 一次连续录音（可能含多段）的会话标识
      segment_index INTEGER NOT NULL DEFAULT 1,-- 第几段
      reason TEXT NOT NULL,                    -- SOS / GEOFENCE，录音触发来源
      place_name TEXT,                         -- 围栏名称（GEOFENCE 时有值）
      file_name TEXT NOT NULL,                 -- 服务器落盘文件名（uploads/recordings 下）
      original_name TEXT,                      -- 老人端原始文件名
      duration_ms INTEGER NOT NULL DEFAULT 0,
      size_bytes INTEGER NOT NULL DEFAULT 0,
      sha256 TEXT,                             -- 文件内容摘要，报警取证的完整性校验
      mime_type TEXT DEFAULT 'audio/mp4',
      recorded_at DATETIME,                    -- 录音开始时间（UTC）
      latitude REAL,                           -- 录音时位置，便于"录音-位置-风险事件"三者对齐
      longitude REAL,
      address TEXT,
      transcript TEXT,                         -- 录音转写文本
      transcript_status TEXT DEFAULT 'PENDING' CHECK(transcript_status IN ('PENDING','DONE','FAILED','SKIPPED')),
      transcript_error TEXT,
      -- AI 诈骗研判结果
      fraud_status TEXT DEFAULT 'PENDING' CHECK(fraud_status IN ('PENDING','ANALYZING','FRAUD','SUSPECT','SAFE','FAILED')),
      fraud_score REAL,
      fraud_verdict TEXT,                      -- 研判结论文本
      fraud_labels TEXT,                       -- JSON 数组：命中的诈骗话术标签
      suspect_role TEXT,                       -- 疑似诈骗方角色：caller / callee / both
      -- 证据保留策略：仅 FRAUD 长期保留；SUSPECT 临时保留；SAFE 只留 N 天自动清理
      keep_as_evidence INTEGER NOT NULL DEFAULT 0,
      retention_until DATETIME,                -- 到期自动清理时间（SAFE 记录用）
      cleanup_reason TEXT,                     -- AI 判定为非诈骗时记录为何被清理
      reviewed_by_family INTEGER NOT NULL DEFAULT 0,  -- 子女端人工复核过（推翻 AI 判定）
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(elder_id) REFERENCES users(id)
    )
  `);

  // 8. 老人在线/失联状态表（子女端「老人失联告警」的唯一状态源）
  //
  // 为什么必须单独一张表，而不能靠 "SELECT MAX(created_at) FROM risk_events" 现算：
  //   - 失联判定需要区分「已经告警过 SILENT」和「已经告警过 OFFLINE」，
  //     这是跨扫描周期的状态，现算只能算出"当前几级"、算不出"之前报没报过"，
  //     结果就是每 5 分钟重复推一条同样的失联告警；
  //   - 恢复（RECONNECTED）也依赖这个状态：老人重新上报后要发一条"已恢复"，
  //     判据是"本轮之前处于告警态"。
  // 表很小（每个老人一行），但它是整条失联链路的唯一真相来源。
  db.run(`
    CREATE TABLE IF NOT EXISTS elder_liveness (
      elder_id INTEGER PRIMARY KEY,
      state TEXT NOT NULL DEFAULT 'ONLINE',        -- ONLINE / SILENT / OFFLINE
      alerted_state TEXT,                           -- 已经发过告警的级别（节流依据）
      last_report_at DATETIME,                      -- 最后一次任意上报（事件/轨迹/录音）
      last_state_change_at DATETIME,
      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(elder_id) REFERENCES users(id)
    )
  `);

  // 按状态捞待扫描老人：每轮扫描只处理非 ONLINE 的，省掉全表比对
  db.run(`CREATE INDEX IF NOT EXISTS idx_liveness_state ON elder_liveness(state)`);

  // 常用查询索引：按老人 + 时间倒序拉录音列表，按会话聚合分片
  db.run(`CREATE INDEX IF NOT EXISTS idx_recordings_elder ON recordings(elder_id, id DESC)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_recordings_session ON recordings(session_id, segment_index)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(elder_id, fraud_status)`);

  // 高频查询索引（对齐 workers/migrations/0003_query_indexes.sql）：
  // 判活扫描每轮都要 MAX(created_at)，列表接口都是 ORDER BY id DESC LIMIT N。
  // 两类查询形状各建一条索引——SQLite 不会用 created_at 的顺序去满足 ORDER BY id DESC。
  db.run(`CREATE INDEX IF NOT EXISTS idx_risk_events_elder_time ON risk_events(elder_id, created_at)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_risk_events_elder_id ON risk_events(elder_id, id DESC)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_locations_elder_time ON locations(elder_id, created_at)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_locations_elder_id ON locations(elder_id, id DESC)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_recordings_elder_created ON recordings(elder_id, created_at)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_payments_elder_id ON payments(elder_id, id DESC)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_geofences_elder ON geofences(elder_id)`);

  // 预置演示数据（如果用户表为空）
  db.get("SELECT COUNT(*) AS count FROM users", (err, row) => {
    if (row && row.count === 0) {
      console.log('🌱 正在为 SQLite 初始化预置测试账号...');
      
      // 插入老人账号 (id: 1, bind_code: 888888)
      db.run(`INSERT INTO users (id, role, name, phone, bind_code, bound_user_id) 
              VALUES (1, 'elder', '张爷爷', '13900001111', '888888', 2)`);

      // 插入子女账号 (id: 2, bound_user_id: 1)
      db.run(`INSERT INTO users (id, role, name, phone, bind_code, bound_user_id) 
              VALUES (2, 'family', '张强(儿子)', '13888889999', '999999', 1)`);

      // 插入初始位置轨迹
      db.run(`INSERT INTO locations (elder_id, latitude, longitude, address, is_sensitive) 
              VALUES (1, 39.9042, 116.4074, '北京市朝阳区阳光花园12号楼 (家)', 0)`);
    }
  });
});

module.exports = db;
