/**
 * 判活查询（getLastReportAt）读开销基准。
 *
 * 2026-10-11 生产把 D1 免费套餐 500 万行/天的额度打爆，根因就是这条查询：
 * 三张表 MAX(created_at)，而 risk_events / locations 当时**完全没有索引**，
 * 于是每轮扫描都是全表扫描。修复两件事：
 *   1. 补 (elder_id, created_at) 索引 + 判活专用部分索引（0003_query_indexes.sql）
 *   2. 每条分支写成"倒序取 1 条"，让 SQLite 在索引上倒着走一格就停，
 *      而不是把该老人的历史行全扫一遍再取 MAX
 * 本脚本用合成数据量这两个改动的差距，并校验新旧写法结果一致。
 *
 * 用法：node scripts/bench-liveness-query.js [每表行数]
 * 注：脚本结束会留下 bench-tmp.sqlite，用 rm 清掉即可。
 */
const sqlite3 = require('sqlite3');
const fs = require('fs');

const N = Number(process.argv[2] || 30000);
const ROUNDS = 96; // 一天 15 分钟一轮的扫描次数
const tmp = 'bench-tmp.sqlite';

// 事故当时的写法：无索引 + MAX(created_at)
const OLD = `SELECT MAX(ts) AS last_ts FROM (
     SELECT MAX(created_at) AS ts FROM risk_events
      WHERE elder_id = ?1 AND event_type NOT IN ('ELDER_SILENT','ELDER_OFFLINE','ELDER_RECONNECTED')
     UNION ALL SELECT MAX(created_at) FROM locations  WHERE elder_id = ?1
     UNION ALL SELECT MAX(created_at) FROM recordings WHERE elder_id = ?1)`;

// 修复后的写法：每条分支是独立标量子查询，ORDER BY ... LIMIT 1 只作用于本分支。
// （复合 SELECT 上的 ORDER BY/LIMIT 会作用到整个 UNION，必须各自包一层）
const NEW = `SELECT MAX(ts) AS last_ts FROM (
     SELECT (SELECT created_at FROM risk_events
              WHERE elder_id = ?1 AND event_type NOT IN ('ELDER_SILENT','ELDER_OFFLINE','ELDER_RECONNECTED')
              ORDER BY created_at DESC LIMIT 1) AS ts
     UNION ALL
     SELECT (SELECT created_at FROM locations WHERE elder_id = ?1 ORDER BY created_at DESC LIMIT 1)
     UNION ALL
     SELECT (SELECT created_at FROM recordings WHERE elder_id = ?1 ORDER BY created_at DESC LIMIT 1)
   )`;

if (fs.existsSync(tmp)) fs.unlinkSync(tmp);
const db = new sqlite3.Database(tmp);

function run(sql, args) {
  return new Promise((resolve, reject) =>
    db.all(sql, args || [], (err, rows) => (err ? reject(err) : resolve(rows)))
  );
}

function seed() {
  return new Promise((resolve, reject) => {
    db.serialize(() => {
      db.run('CREATE TABLE risk_events (id INTEGER PRIMARY KEY AUTOINCREMENT, elder_id INTEGER, event_type TEXT, severity TEXT, details TEXT, created_at TEXT)');
      db.run('CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, elder_id INTEGER, latitude REAL, longitude REAL, address TEXT, is_sensitive INTEGER, created_at TEXT)');
      db.run('CREATE TABLE recordings (id INTEGER PRIMARY KEY AUTOINCREMENT, elder_id INTEGER, created_at TEXT, file_name TEXT)');
      db.run('BEGIN');
      const ins = db.prepare('INSERT INTO risk_events (elder_id, event_type, created_at) VALUES (?,?,?)');
      const ins2 = db.prepare('INSERT INTO locations (elder_id, latitude, longitude, address, created_at) VALUES (?,?,?,?,?)');
      const ins3 = db.prepare('INSERT INTO recordings (elder_id, created_at) VALUES (?,?)');
      const base = Date.UTC(2026, 9, 1);
      for (let i = 0; i < N; i++) {
        const elder = (i % 3) + 10; // 3 位老人，12 是被测对象
        const ts = new Date(base + i * 60000).toISOString().replace('T', ' ').slice(0, 19);
        ins.run(elder, i % 50 === 0 ? 'ELDER_SILENT' : 'CALL_RISK', ts);
        ins2.run(elder, 30.5, 114.3, 'addr', ts);
        ins3.run(elder, ts);
      }
      db.run('COMMIT', (e) => (e ? reject(e) : resolve()));
    });
  });
}

function bench(sql) {
  return new Promise((resolve) => {
    let done = 0;
    const t0 = Date.now();
    for (let k = 0; k < ROUNDS; k++) {
      db.all(sql, [12], () => {
        if (++done === ROUNDS) resolve(Date.now() - t0);
      });
    }
  });
}

(async () => {
  await seed();

  // 阶段一：事故现场（无索引 + MAX）
  const oldMs = await bench(OLD);
  const oldVal = (await run(OLD, [12]))[0].last_ts;

  // 阶段二：修复后（索引 + 倒序取 1 条）
  await run('CREATE INDEX idx_risk_events_elder_time ON risk_events(elder_id, created_at)');
  await run("CREATE INDEX idx_risk_events_last_report ON risk_events(elder_id, created_at) WHERE event_type NOT IN ('ELDER_SILENT','ELDER_OFFLINE','ELDER_RECONNECTED')");
  await run('CREATE INDEX idx_locations_elder_time ON locations(elder_id, created_at)');
  await run('CREATE INDEX idx_recordings_elder_created ON recordings(elder_id, created_at)');
  const newMs = await bench(NEW);
  const newVal = (await run(NEW, [12]))[0].last_ts;

  console.log(`每表 ${N} 行 / ${Math.round(N / 3)} 行每人，模拟一天 ${ROUNDS} 轮扫描：`);
  console.log(`  修复前（无索引 + MAX）        ${oldMs} ms`);
  console.log(`  修复后（索引 + 倒序 LIMIT 1） ${newMs} ms`);
  console.log(`  结果一致性：${oldVal} vs ${newVal} -> ${oldVal === newVal ? '一致' : '不一致 <<< 有问题'}`);
  const plan = await run('EXPLAIN QUERY PLAN ' + NEW, [12]);
  console.log('  修复后执行计划：');
  plan.forEach((x) => console.log('   ', x.detail));
  db.close();
})();
