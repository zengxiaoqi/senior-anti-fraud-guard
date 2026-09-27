const sqlite3 = require('sqlite3').verbose();
const path = require('path');

const dbPath = path.join(__dirname, '..', 'data.sqlite');

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
