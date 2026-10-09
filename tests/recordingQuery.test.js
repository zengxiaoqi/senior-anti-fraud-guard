const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  buildListWhere,
  normalizeListPaging,
  buildSessionQuery,
  buildDetailQuery,
  buildSessionCountQuery,
  buildRecordingCountQuery,
  buildFraudCountQuery,
  escapeLikePattern,
  SEARCHABLE,
  RANGE_DAYS,
  LEVEL_SQL,
  DEFAULT_LIMIT,
  MAX_LIMIT,
} = require('../services/recordingQuery');

const BASE = 'elder_id = ?';

test('range=7d 生成 7 天下界，30d 生成 30 天下界', () => {
  const now = Date.now();
  const w7 = buildListWhere({ q: '', range: '7d', level: 'all', evidence: false }, now);
  assert.match(w7.sql, /julianday\(recorded_at\) >= julianday\(\?\)/);
  assert.equal(w7.params.length, 1); // 只有下界；elderId 由调用方在前面拼
  const days7 = Math.round((now - new Date(w7.params[0]).getTime()) / 86400000);
  assert.equal(days7, 7);

  const w30 = buildListWhere({ q: '', range: '30d', level: 'all', evidence: false }, now);
  assert.equal(Math.round((now - new Date(w30.params[0]).getTime()) / 86400000), 30);
  assert.equal(RANGE_DAYS['7d'], 7);
  assert.equal(RANGE_DAYS['30d'], 30);
});

test('range 下界是 ISO 串，两种时间格式都能比（recorded_at 与 created_at 格式不同）', () => {
  const w = buildListWhere({ q: '', range: '7d', level: 'all', evidence: false }, Date.now());
  assert.match(w.params[0], /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
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
  assert.deepEqual(SEARCHABLE, ['transcript', 'place_name', 'reason', 'fraud_verdict']);
  for (const col of SEARCHABLE) {
    assert.ok(w.sql.includes(col), `缺少检索字段 ${col}`);
  }
  assert.match(w.sql, /LIKE \? ESCAPE '\\'/);
  assert.equal(w.params.length, 1);
});

test('q 中的 % 和 _ 被转义，不会变成通配符', () => {
  const w = buildListWhere({ q: '50%_off', range: 'all', level: 'all', evidence: false });
  assert.equal(w.params[0], '%50\\%\\_off%');
  assert.equal(w.params[0].indexOf('50%_off'), -1);
});

test('q 里的反斜杠也被转义，不会吃掉后面的转义符', () => {
  assert.equal(escapeLikePattern('a\\b%'), 'a\\\\b\\%');
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

test('多个筛选条件用 AND 连接，参数顺序与占位符顺序一致', () => {
  const w = buildListWhere({ q: 'x', range: '7d', level: 'fraud', evidence: true });
  assert.equal(w.sql.split(' AND ').length, 5); // elder_id + q + range + level + evidence
  // q 的占位符在 range 之前（拼接顺序即占位符顺序）
  assert.ok(w.sql.indexOf('transcript') < w.sql.indexOf('recorded_at'));
  assert.equal(w.params.length, 2); // q 的 like 值 + range 下界
  assert.equal(w.params[0], '%x%');
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

test('buildSessionQuery 按 session_id 聚合并切页', () => {
  const { sql, params } = buildSessionQuery(7, { sql: BASE, params: [] }, 3, 0);
  assert.match(sql, /GROUP BY session_id/);
  assert.match(sql, /ORDER BY last_id DESC/);
  assert.match(sql, /LIMIT \? OFFSET \?/);
  assert.deepEqual(params, [7, 3, 0]);
});

test('buildSessionQuery 把 WHERE 的参数排在 elderId 之后、分页之前', () => {
  const where = buildListWhere({ q: '退款', range: '7d', level: 'all', evidence: false }, Date.now());
  const { params } = buildSessionQuery(7, where, 3, 6);
  assert.equal(params[0], 7);
  assert.equal(params[1], '%退款%');
  assert.equal(params[params.length - 2], 3);
  assert.equal(params[params.length - 1], 6);
});

test('buildSessionQuery 统计字段齐全', () => {
  const { sql } = buildSessionQuery(7, { sql: BASE, params: [] }, 3, 0);
  for (const col of ['COUNT(*)', 'SUM(duration_ms)', 'MIN(recorded_at)']) {
    assert.ok(sql.includes(col), `缺少 ${col}`);
  }
  assert.match(sql, /MAX\(id\)\s+AS last_id/);
});

test('buildDetailQuery 用 IN 展开 session_id 列表', () => {
  const { sql, params } = buildDetailQuery(7, ['a', 'b', 'c']);
  assert.match(sql, /session_id IN \(\?,\?,\?\)/);
  assert.match(sql, /ORDER BY id DESC/);
  assert.deepEqual(params, [7, 'a', 'b', 'c']);
});

test('buildDetailQuery 的占位符个数与 session_id 数量一致', () => {
  const ids = ['x', 'y'];
  const { sql, params } = buildDetailQuery(7, ids);
  assert.equal(sql.split('?').length - 1, params.length);
  assert.equal(params.length, ids.length + 1);
});

test('buildDetailQuery 对 session_id 中的引号做转义（防注入）', () => {
  const { sql, params } = buildDetailQuery(7, ["a' OR 1=1 --"]);
  // session_id 是数据不是 SQL 结构，值一律走占位符；这里断言引号没进到 SQL 文本里
  assert.ok(!sql.includes("' OR 1=1"));
  assert.equal(params[1], "a' OR 1=1 --");
});

test('buildDetailQuery 空列表不生成 IN ()，而是返回空结果查询', () => {
  const { sql, params } = buildDetailQuery(7, []);
  assert.ok(!sql.includes('IN ()'));
  assert.match(sql, /WHERE 1 = 0/);
  assert.deepEqual(params, []);
});

test('三个统计查询都不带 LIMIT/OFFSET（翻页时标题数字不跳变）', () => {
  const where = buildListWhere({ q: '', range: '7d', level: 'all', evidence: false }, Date.now());
  for (const build of [buildSessionCountQuery, buildRecordingCountQuery, buildFraudCountQuery]) {
    const { sql, params } = build(7, where);
    assert.ok(!/LIMIT|OFFSET/.test(sql), `${build.name} 不该带分页`);
    assert.equal(params[0], 7);
  }
  assert.match(buildSessionCountQuery(7, where).sql, /COUNT\(DISTINCT session_id\)/);
  assert.match(buildFraudCountQuery(7, where).sql, /fraud_status = 'FRAUD'/);
});

test('统计查询的 WHERE 与列表查询共用同一份筛选条件', () => {
  const where = buildListWhere({ q: '退款', range: 'all', level: 'fraud', evidence: true });
  const { sql } = buildRecordingCountQuery(7, where);
  assert.ok(sql.includes(where.sql));
});
