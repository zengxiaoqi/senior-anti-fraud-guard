/**
 * 生成演示数据库（纯假数据，永不包含真实用户信息）
 *
 * 用法：
 *   npm run seed                     # 生成 data.demo.sqlite
 *   node scripts/seed.js 自定义.sqlite
 *
 * 配套启动：npm run start:demo（自动建库并以 DB_PATH 指向演示库启动服务）
 *
 * 注意：建表 SQL 与 database/db.js 保持同步；改表结构时两边都要改。
 */
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const sqlite3 = require('sqlite3');

const DEFAULT_OUT = path.join(__dirname, '..', 'data.demo.sqlite');

/** 与 routes/auth.js 保持一致的密码哈希格式：salt:hash（scrypt, 64 字节 hex） */
function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role TEXT NOT NULL CHECK(role IN ('elder', 'family')),
    name TEXT NOT NULL,
    phone TEXT UNIQUE NOT NULL,
    bind_code TEXT UNIQUE NOT NULL,
    bound_user_id INTEGER,
    wx_openid TEXT,
    password_hash TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS risk_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    elder_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    severity TEXT NOT NULL CHECK(severity IN ('LOW', 'MEDIUM', 'HIGH')),
    details TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(elder_id) REFERENCES users(id)
  )`,
  `CREATE TABLE IF NOT EXISTS locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    elder_id INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    address TEXT NOT NULL,
    is_sensitive INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(elder_id) REFERENCES users(id)
  )`,
  `CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    elder_id INTEGER NOT NULL,
    amount REAL NOT NULL,
    payee_name TEXT NOT NULL,
    payee_account TEXT,
    order_no TEXT UNIQUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(elder_id) REFERENCES users(id)
  )`,
];

function run(db, sql, params = []) {
  return new Promise((resolve, reject) =>
    db.run(sql, params, (err) => (err ? reject(err) : resolve())));
}

async function main(outPath = DEFAULT_OUT) {
  if (fs.existsSync(outPath)) {
    fs.rmSync(outPath);
    console.log('已删除旧演示库:', outPath);
  }

  const db = new sqlite3.Database(outPath);
  try {
    for (const sql of SCHEMA) await run(db, sql);

    const elderCode = '246810';           // 演示绑定码，固定方便评审
    const familyCode = '135791';

    // 两个演示账号，默认已互相绑定
    await run(db, `INSERT INTO users (id, role, name, phone, bind_code, bound_user_id, password_hash)
                   VALUES (1, 'elder', '张爷爷(演示)', '13900001111', ?, 2, NULL)`, [elderCode]);
    await run(db, `INSERT INTO users (id, role, name, phone, bind_code, bound_user_id, password_hash)
                   VALUES (2, 'family', '张强(演示儿子)', '13888889999', ?, 1, ?)`,
      [familyCode, hashPassword('demo123456')]);

    // 风险事件样例（覆盖三种级别）
    const events = [
      ['陌生来电要求转账', 'HIGH', '老人接到自称"社保局"来电，要求向安全账户转账 5 万元，已触发强提醒'],
      ['AI 合成语音冒充家人', 'HIGH', '来电语音与儿子声纹高度相似，但索要验证码，判定为语音克隆诈骗'],
      ['屏幕共享诈骗', 'MEDIUM', '老人被诱导开启屏幕共享，检测到银行 App 在前台运行'],
      ['伪基站短信', 'MEDIUM', '收到积分兑换短信，链接域名注册不足 7 天'],
      ['推销保健品电话', 'LOW', '常规推销电话，已自动标记'],
    ];
    for (const [t, s, d] of events) {
      await run(db, `INSERT INTO risk_events (elder_id, event_type, severity, details) VALUES (1, ?, ?, ?)`, [t, s, d]);
    }

    // 位置轨迹样例（北京公开地标，假行程）
    const locs = [
      [39.9042, 116.4074, '北京市朝阳区阳光花园12号楼 (家)', 0],
      [39.9087, 116.3975, '北京市东城区社区卫生服务站', 1],
      [39.9135, 116.4040, '北京市东城区某菜市场', 0],
      [39.9087, 116.3750, '北京市西城区某公园', 0],
    ];
    for (const [la, lo, ad, s] of locs) {
      await run(db, `INSERT INTO locations (elder_id, latitude, longitude, address, is_sensitive) VALUES (1, ?, ?, ?, ?)`, [la, lo, ad, s]);
    }

    // 支付存证样例（假收款方）
    await run(db, `INSERT INTO payments (elder_id, amount, payee_name, payee_account, order_no)
                   VALUES (1, 19800.00, '某养生科技有限公司(演示)', '6222****1234', 'DEMO20261006001')`);
    await run(db, `INSERT INTO payments (elder_id, amount, payee_name, payee_account, order_no)
                   VALUES (1, 699.00, '某保健品商行(演示)', '6222****5678', 'DEMO20261006002')`);

    console.log('\n✅ 演示库已生成:', outPath);
    console.log('----------------------------------------');
    console.log('  老人端 bind_code :', elderCode);
    console.log('  子女端 bind_code :', familyCode);
    console.log('  风险事件 / 位置 / 支付 : 5 / 4 / 2 条');
    console.log('----------------------------------------');
    console.log('启动演示环境: npm run start:demo');
    console.log('启动生产环境: npm start  (继续用本地 data.sqlite, 不进版本库)');
    return outPath;
  } finally {
    db.close();
  }
}

if (require.main === module) {
  main(process.argv[2]).catch((e) => { console.error('生成失败:', e.message); process.exit(1); });
}

module.exports = { main, DEFAULT_OUT };
