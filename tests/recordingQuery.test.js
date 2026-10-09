const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  buildListWhere,
  normalizeListPaging,
  buildSessionQuery,
  buildSessionDetailQuery,
  buildSessionCountQuery,
  buildRecordingCountQuery,
  buildFraudCountQuery,
  escapeLikePattern,
  resolveRange,
  resolveLevel,
  withElder,
  RANGE_DAYS,
  LEVEL_SQL,
  SEARCHABLE,
  DEFAULT_LIMIT,
  MAX_LIMIT,
} = require('../services/recordingQuery');

const BASE = 'elder_id = ?';

test('range=7d 生成 7 天下界，30d 生成 30 天下界', () => {
  const now = Date.now();
  const w7 = buildListWhere({ q: '', range: '7d', level: 'all', evidence: false }, now);
  // buildListWhere 只产出「筛选条件」的参数，elder_id 的值由调用方前置
  assert.equal(w7.params.length, 1);
  assert.equal(w7.params[0], new Date(now - 7 * 86400000).toISOString());
  assert.match(w7.sql, /COALESCE\(julianday\(recorded_at\), 0\) >= julianday\(\?\)/);

  const w30 = buildListWhere({ q: '', range: '30d', level: 'all', evidence: false }, now);
  assert.equal(w30.params[0], new Date(now - 30 * 86400000).toISOString());
  assert.equal(RANGE_DAYS['7d'], 7);
  assert.equal(RANGE_DAYS['30d'], 30);
});

test('range=all 不产生时间条件', () => {
  const w = buildListWhere({ q: '', range: 'all', level: 'all', evidence: false });
  assert.equal(w.sql, BASE);
  assert.deepEqual(w.params, []);
});

test('未识别的 range 回落到 all，不报错也不返回空', () => {
  const w = buildListWhere({ q: '', range: '昨天', level: 'all', evidence: false });
  assert.equal(w.sql, BASE);
});

test('level 四档各自生成正确的 SQL 片段', () => {
  const q = (level) => buildListWhere({ q: '', range: 'all', level, evidence: false }).sql;
  assert.match(q('fraud'), /fraud_status = 'FRAUD'/);
  assert.match(q('suspect'), /fraud_status IN \('SUSPECT','FAILED'\)/);
  assert.match(q('safe'), /fraud_status = 'SAFE'/);
  assert.match(q('untranscribed'), /transcript_status IN \('SKIPPED','FAILED'\)/);
  assert.match(q('untranscribed'), /transcript IS NULL/);
  assert.equal(LEVEL_SQL.fraud, "fraud_status = 'FRAUD'");
});

test('level=all 不产生等级条件', () => {
  assert.equal(buildListWhere({ q: '', range: 'all', level: 'all', evidence: false }).sql, BASE);
});

test('未识别的 level 回落到 all', () => {
  assert.equal(buildListWhere({ q: '', range: 'all', level: '???', evidence: false }).sql, BASE);
});

test('PENDING / ANALYZING 不落入任何具体档（研判在途只在 all 里可见）', () => {
  const levels = ['fraud', 'suspect', 'safe', 'untranscribed'];
  for (const lv of levels) {
    const w = buildListWhere({ q: '', range: 'all', level: lv, evidence: false });
    assert.ok(
      !/fraud_status\s*(=|IN)\s*\('?(PENDING|ANALYZING)/.test(w.sql),
      `${lv} 不该包含 PENDING/ANALYZING`
    );
  }
});

test('q 检索 transcript / place_name / reason / fraud_verdict 四个字段', () => {
  const w = buildListWhere({ q: '退款', range: 'all', level: 'all', evidence: false });
  for (const col of ['transcript', 'place_name', 'reason', 'fraud_verdict']) {
    assert.match(w.sql, new RegExp(col));
  }
  assert.match(w.sql, /LIKE \? ESCAPE '\\'/);
  // 字段名用字面量而非 SEARCHABLE：这条断言的作用就是钉死「检索哪四列」，
  // 换成 SEARCHABLE 会变成拿实现自己的常量验实现自己，永远为真。
  assert.deepEqual(SEARCHABLE, ['transcript', 'place_name', 'reason', 'fraud_verdict']);
  // 四个字段各一个 LIKE 占位符，值必须重复 4 次 —— 少一个 SQLite 会绑错位
  assert.equal(w.params.length, SEARCHABLE.length);
  // 必须是同一个值重复 4 次，不能是 4 个不同的值 —— 换实现时这条能拦住
  assert.deepEqual(w.params, Array(SEARCHABLE.length).fill('%退款%'));
});

test('q 中的 % 和 _ 被转义，不会变成通配符', () => {
  const w = buildListWhere({ q: '50%_off', range: 'all', level: 'all', evidence: false });
  assert.equal(w.params[0], '%50\\%\\_off%');
});

test('q 为空白字符串时不产生 LIKE 条件', () => {
  for (const blank of ['', '   ', undefined, null]) {
    const w = buildListWhere({ q: blank, range: 'all', level: 'all', evidence: false });
    assert.equal(w.sql, BASE);
  }
});

test('evidence=1 只看有效证据', () => {
  const w = buildListWhere({ q: '', range: 'all', level: 'all', evidence: true });
  assert.match(w.sql, /keep_as_evidence = 1/);
});

test('多个筛选条件同时生效，占位符与参数严格对齐', () => {
  const w = buildListWhere({ q: 'x', range: '7d', level: 'fraud', evidence: true });

  // 逐条判存在，不按 ' AND ' 切分计段数 —— 切分计数把 SQL 的文本形状
  // 变成了事实约束，将来任何一个条件改写成含内部 AND 的子句就会假红，
  // 而语义其实没变。这里要守的是「条件都在」和「占位符与参数对齐」。
  for (const frag of [
    'elder_id = ?',
    'transcript LIKE ?',
    'COALESCE(julianday(recorded_at), 0)',
    "fraud_status = 'FRAUD'",
    'keep_as_evidence = 1',
  ]) {
    assert.ok(w.sql.includes(frag), `缺少条件片段: ${frag}`);
  }

  // elder_id 由调用方前置，所以占位符比 params 多一个
  assert.equal(w.sql.split('?').length - 1, w.params.length + 1);
  assert.equal(w.params.length, SEARCHABLE.length + 1); // q 的 LIKE + range 下界
  // q 的占位符在 range 之前（拼接顺序即占位符顺序）
  assert.ok(w.sql.indexOf('transcript') < w.sql.indexOf('julianday'));
});

test('normalizeListPaging 缺省为 limit=3 offset=0', () => {
  assert.deepEqual(normalizeListPaging({}), { limit: DEFAULT_LIMIT, offset: 0 });
  assert.equal(DEFAULT_LIMIT, 3);
});

test('limit 超过 20 被夹到上限', () => {
  assert.equal(normalizeListPaging({ limit: '999' }).limit, MAX_LIMIT);
  assert.equal(MAX_LIMIT, 20);
});

test('limit 为负数 / 非数字 / 0 落到默认值', () => {
  for (const bad of ['-5', 'abc', '0', '', null, undefined]) {
    assert.equal(normalizeListPaging({ limit: bad }).limit, DEFAULT_LIMIT, `limit=${bad}`);
  }
});

test('offset 为负归零，合法值原样返回', () => {
  assert.equal(normalizeListPaging({ offset: '-1' }).offset, 0);
  assert.equal(normalizeListPaging({ offset: 'abc' }).offset, 0);
  assert.equal(normalizeListPaging({ offset: '12' }).offset, 12);
});

test('normalizeListPaging 只在「传了但非法」时告警，首屏默认路径保持安静', () => {
  // 首屏不传分页参数是正常路径（默认 3 条）。若每次请求都刷 warn，
  // 真正的「参数名写错了」会被日常噪音淹没，日志就白加了。
  const seen = [];
  const real = console.warn;
  console.warn = (...a) => seen.push(a.join(' '));
  try {
    assert.deepEqual(normalizeListPaging({}), { limit: DEFAULT_LIMIT, offset: 0 });
    assert.deepEqual(normalizeListPaging({ limit: null, offset: null }), { limit: DEFAULT_LIMIT, offset: 0 });
    assert.deepEqual(normalizeListPaging({ limit: '', offset: '' }), { limit: DEFAULT_LIMIT, offset: 0 });
    assert.equal(seen.length, 0, `未传参数不该告警，实际 ${seen.length} 行: ${seen.join(' | ')}`);

    // 传了但非法 / 超上限 —— 这两类必须出声
    normalizeListPaging({ limit: 'abc' });
    normalizeListPaging({ limit: '-5' });
    normalizeListPaging({ offset: 'abc' });
    normalizeListPaging({ limit: '999' });
    assert.equal(seen.length, 4, `该告警的必须告警，实际 ${seen.length} 行: ${seen.join(' | ')}`);
    assert.ok(seen.every((l) => l.includes('[recordings]')), '日志应带 [recordings] 模块前缀');
  } finally {
    console.warn = real;
  }
});

test('buildSessionQuery 按 session_id 聚合并切页', () => {
  const { sql, params } = buildSessionQuery(7, { sql: BASE, params: [] }, 3, 0);
  assert.match(sql, /GROUP BY session_id/);
  assert.match(sql, /ORDER BY last_id DESC/);
  assert.match(sql, /LIMIT \? OFFSET \?/);
  assert.deepEqual(params, [7, 3, 0]);
});

test('buildSessionQuery 统计字段齐全', () => {
  const { sql } = buildSessionQuery(7, { sql: BASE, params: [] }, 3, 0);
  // SELECT 列表为了对齐做了空格填充，所以一律用 \s+ 而不是单个空格
  for (const col of [
    /COUNT\(\*\)\s+AS segment_count/,
    /SUM\(duration_ms\)\s+AS total_duration_ms/,
    /MIN\(recorded_at\)\s+AS started_at/,
    /MAX\(id\)\s+AS last_id/,
  ]) {
    assert.match(sql, col);
  }
});

test('buildSessionQuery 在 where.params 非空时占位符与参数严格对齐', () => {
  // 这是最该验的组合：elder_id 之后还有 q 的每个检索字段一个 LIKE + range 下界，
  // 再加 LIMIT/OFFSET。数量或顺序错位，sqlite3 会静默绑错值而不报错。
  const where = buildListWhere({ q: '退款', range: '7d', level: 'fraud', evidence: true });
  const { sql, params } = buildSessionQuery(9, where, 3, 6);
  assert.equal(sql.split('?').length - 1, params.length);
  assert.deepEqual(params, [
    9,
    ...Array(SEARCHABLE.length).fill('%退款%'),
    where.params[where.params.length - 1],
    3,
    6,
  ]);
  assert.equal(params[0], 9); // elder_id 必须在首位
  assert.equal(params[params.length - 2], 3); // limit
  assert.equal(params[params.length - 1], 6); // offset
});

test('三个 COUNT 统计查询都不带 LIMIT/OFFSET（统计不受分页影响）', () => {
  // 统计必须独立于分页：否则翻到第二页时标题上的「共 17 段，其中 2 段诈骗」
  // 会随翻页跳变。
  // 用 where.params 非空的场景跑，才能同时验到占位符与参数的对应关系。
  const where = buildListWhere({ q: 'x', range: '7d', level: 'fraud', evidence: true });
  for (const fn of [buildSessionCountQuery, buildRecordingCountQuery, buildFraudCountQuery]) {
    const { sql, params } = fn(7, where);
    assert.ok(!/\bLIMIT\b/i.test(sql), `${fn.name} 不该带 LIMIT`);
    assert.ok(!/\bOFFSET\b/i.test(sql), `${fn.name} 不该带 OFFSET`);
    // 统计查询的 sql 已内嵌 where.sql（含 elder_id = ?），且 params 首位就是
    // elderId，所以占位符与参数严格 1:1 —— 这里不像 buildListWhere 那样差 1
    assert.equal(sql.split('?').length - 1, params.length);
  }
});

test('会话统计用 COUNT(DISTINCT session_id)，不能是普通 COUNT', () => {
  const where = buildListWhere({ q: '', range: 'all', level: 'all', evidence: false });
  assert.match(buildSessionCountQuery(7, where).sql, /COUNT\(DISTINCT session_id\)/);
  assert.match(buildRecordingCountQuery(7, where).sql, /COUNT\(\*\)/);
  assert.match(buildFraudCountQuery(7, where).sql, /fraud_status = 'FRAUD'/);
});

test('escapeLikePattern 转义反斜杠/百分号/下划线，且被导出', () => {
  // 不转义的话，子女搜「50%」会变成匹配全部 —— 检索结果突然从 3 条变成几百条，
  // 他会以为系统出故障。
  assert.equal(escapeLikePattern('a\\b%c_d'), 'a\\\\b\\%c\\_d');
  assert.equal(escapeLikePattern('100%'), '100\\%');
  assert.equal(escapeLikePattern('a_b'), 'a\\_b');
  assert.equal(escapeLikePattern('普通文本'), '普通文本');
});

test('buildSessionDetailQuery 用 IN 展开 session_id 列表', () => {
  const { sql, params } = buildSessionDetailQuery(7, ['a', 'b', 'c']);
  assert.match(sql, /session_id IN \(\?,\?,\?\)/);
  assert.match(sql, /ORDER BY id DESC/);
  assert.deepEqual(params, [7, 'a', 'b', 'c']);
});

test('buildSessionDetailQuery 的占位符个数与 session_id 数量一致', () => {
  const ids = ['x', 'y'];
  const { sql, params } = buildSessionDetailQuery(7, ids);
  assert.equal(sql.split('?').length - 1, params.length);
  assert.equal(params.length, ids.length + 1);
});

test('buildSessionDetailQuery 的 session_id 值一律走占位符，不拼进 SQL 文本', () => {
  const { sql, params } = buildSessionDetailQuery(7, ["a' OR 1=1 --"]);
  assert.ok(!sql.includes("' OR 1=1"));
  assert.equal(params[1], "a' OR 1=1 --");
});

test('buildSessionDetailQuery 空列表不拼出 IN ()（筛得太狠时是正常路径，不该 500）', () => {
  // SQLite 不接受 `IN ()`，拼出来是语法错误；而「这一页没有会话」是完全正常的
  // 结果（比如子女把筛选条件叠到没有交集），必须返回空结果集而不是让路由打 500。
  const { sql, params } = buildSessionDetailQuery(7, []);
  assert.ok(!sql.includes('IN ()'));
  assert.match(sql, /WHERE 1 = 0/);
  assert.deepEqual(params, []);
});

test('withElder 把 elderId 前置到 where.params 首位', () => {
  // where.params 刻意不含 elderId（buildListWhere 的 elder_id = ? 留给调用方填）。
  // 顺序写错时 sqlite3 类型宽松不报错，查询静默返回 0 行，子女端只看到空列表。
  // 这个函数就是为了让调用方没有机会把顺序写反。
  const where = buildListWhere({ q: '退款', range: '7d', level: 'fraud', evidence: true });
  const out = withElder(where, 9);
  assert.equal(out[0], 9);
  assert.equal(out.length, where.params.length + 1);
  assert.deepEqual(out.slice(1), where.params);

  // 顺序敏感性：反着拼必须与正着拼不同，否则这函数没起到收口作用
  const reversed = [...where.params, 9];
  assert.notDeepEqual(out, reversed);

  // 无筛选条件时也要产出 elderId 一项
  assert.deepEqual(withElder({ sql: BASE, params: [] }, 7), [7]);
});

test('resolveRange / resolveLevel 把继承键归一成 all，不泄漏原型链上的值', () => {
  // 这两个是路由回显 appliedFilters 时要用的入口。裸查表的写法
  // （RANGE_DAYS[key] ? key : 'all'）会被继承来的 Object 构造函数骗过 ——
  // 返回 'constructor'，于是服务端没施加筛选却说「已筛选」，
  // 客户端渲染出一个没生效的筛选 chip。
  assert.equal(resolveRange('7d'), '7d');
  assert.equal(resolveRange('30d'), '30d');
  assert.equal(resolveRange('all'), 'all');
  assert.equal(resolveLevel('fraud'), 'fraud');
  assert.equal(resolveLevel('untranscribed'), 'untranscribed');

  const inherited = ['constructor', 'toString', 'valueOf', 'hasOwnProperty', '__proto__'];
  for (const bad of inherited) {
    assert.equal(resolveRange(bad), 'all', `resolveRange(${bad})`);
    assert.equal(resolveLevel(bad), 'all', `resolveLevel(${bad})`);
  }
  assert.equal(resolveRange(undefined), 'all');
  assert.equal(resolveLevel(null), 'all');
  assert.equal(resolveRange(''), 'all');
});

test('原型链上的继承键不被当成合法筛选值（防 ?range=constructor 打 500）', () => {
  // RANGE_DAYS / LEVEL_SQL 是普通对象字面量，裸查表会命中 Object.prototype
  // 的继承键。实测过：裸查表时 range=constructor 会让 new Date(NaN) 抛
  // RangeError，level=constructor 会把函数源码拼进 SQL。query 串直通进来，
  // 不收口就是可被外部触发的 500。
  const inherited = ['constructor', 'toString', 'valueOf', 'hasOwnProperty', '__proto__'];
  for (const bad of inherited) {
    const w = buildListWhere({ q: '', range: bad, level: 'all', evidence: false });
    assert.equal(w.sql, BASE, `range=${bad} 应回落到无时间条件`);

    const w2 = buildListWhere({ q: '', range: 'all', level: bad, evidence: false });
    assert.equal(w2.sql, BASE, `level=${bad} 应回落到无等级条件`);
  }
});

test('julianday 归一：ISO-Z 与空格分隔两种 recorded_at 格式都能被正确解析', async () => {
  // 防回归：客户端上传走 ApiClient 的 utcFormatter（...T...Z，无毫秒），
  // 路由兜底走 toISOString()（带毫秒），两种格式都真实存在于 recordings 表。
  // 一旦把 julianday() 简化成裸字符串比较，混合格式下会静默漏掉一部分录音。
  //
  // 必须 await：sqlite3 回调是异步的，若把断言直接写在 db.all 回调里，
  // 实测子测试会先判为 ok，断言失败只冒泡成文件级错误 —— 测试名显示 ok 但断言
  // 其实挂了，正是本项目最忌讳的「静默失效」。
  const sqlite3 = require('sqlite3');
  assert.ok(sqlite3, 'sqlite3 依赖缺失');

  const iso = '2026-10-08T12:40:37Z';
  const isoMs = '2026-10-08T12:40:37.000Z';
  const space = '2026-10-08 12:40:37';
  // 注入固定 now，让 7 天下界恒为 2026-10-01T12:40:37.000Z，
  // 否则用例会在 7 天后随真实时间推移自己失效。
  const q = buildListWhere(
    { q: '', range: '7d', level: 'all', evidence: false },
    Date.parse('2026-10-08T12:40:37Z')
  );
  assert.equal(q.params[0], '2026-10-01T12:40:37.000Z');
  // 去掉 elder_id 占位符（内存表没有 elder_id 列）
  const sql = q.sql.replace('elder_id = ? AND ', '');

  const rows = await new Promise((resolve, reject) => {
    const db = new sqlite3.Database(':memory:');
    db.serialize(() => {
      db.run('CREATE TABLE recordings (recorded_at TEXT)');
      const ins = db.prepare('INSERT INTO recordings VALUES (?)');
      ins.run([iso]);
      ins.run([isoMs]);
      ins.run([space]);
      ins.run([null]); // COALESCE 兜住 NULL，等价于原来的 IS NOT NULL，应被排除
      ins.finalize();
      db.all(`SELECT recorded_at FROM recordings WHERE ${sql}`, q.params, (err, r) => {
        db.close();
        if (err) reject(err);
        else resolve(r);
      });
    });
  });

  // 三种格式都应命中 7 天下界，NULL 行不命中
  assert.equal(rows.length, 3, `应命中 3 条，实际 ${rows.length}`);
  assert.deepEqual(rows.map((r) => r.recorded_at).sort(), [iso, isoMs, space].sort());
});
