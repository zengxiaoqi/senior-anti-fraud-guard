// 时间字段时区转换的回归测试
//
// ## 事故背景（2026-10-08）
// 子女端「维权证据」页同一张卡片上出现两个不一致的时间：
//   分组头：2026-10-08 08:28   ← 错（UTC 裸串）
//   条  目：10-08 16:28         ← 对（客户端自己转成了北京时间）
//
// 根因不是某一处写错，而是**同一个数据走了两条渲染路径，只有一条做了时区转换**：
//   - `RecordingPlayerCard.formatTime()` 客户端自己用 TimeZone 转 ✅
//   - `EvidenceFragment` 的分组头直接对字符串 `.replace("T"," ").take(16)` ❌
//
// 而服务端的 `toBeijingRows()` 只转 `created_at` 一个字段，
// `recorded_at` / `retention_until` / `startedAt` 全部原样输出 —— 于是每个客户端都得自己补，
// 谁忘了补就出错，且没有任何测试会发现。
//
// 这组测试的作用：把"哪些字段必须转"钉死成契约。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { toBeijing, toBeijingRows, TIME_FIELDS } = require('../services/timeFormat');

// 固定基准：2026-10-08T08:28:20Z === 北京时间 2026-10-08 16:28:20
const UTC_ISO = '2026-10-08T08:28:20.000Z';
const UTC_SQL = '2026-10-08 08:28:20';
const BEIJING = '2026-10-08 16:28:20';

test('toBeijing 把 UTC ISO 转成北京时间', () => {
  assert.equal(toBeijing(UTC_ISO), BEIJING);
});

test('toBeijing 把 SQLite 无时区的 UTC 串按 UTC 解析后再转', () => {
  // 这是最危险的一种输入：没有 Z、没有偏移，猜错就是差 8 小时
  assert.equal(toBeijing(UTC_SQL), BEIJING);
});

test('toBeijing 已在北京时间的值不会被二次加 8 小时', () => {
  const alreadyBeijing = '2026-10-08 16:28:20';
  // 输入本身是 UTC 语义则必然 +8；这里断言的是"它确实按 UTC 语义处理"，
  // 避免以后有人误以为它能自动识别已转换过的值（不能，故客户端不得重复转换）
  assert.equal(toBeijing(alreadyBeijing), '2026-10-09 00:28:20');
});

test('toBeijing 支持 Date 对象', () => {
  assert.equal(toBeijing(new Date(UTC_ISO)), BEIJING);
});

test('toBeijing 对空值/无法解析的值原样返回，不抛错', () => {
  for (const bad of [null, undefined, '', '不是时间', 0]) {
    assert.doesNotThrow(() => toBeijing(bad));
  }
  assert.equal(toBeijing(null), null);
  assert.equal(toBeijing(undefined), undefined);
  assert.equal(toBeijing('不是时间'), '不是时间');
});

// ──────────────────────────────────────────────
//  契约：哪些字段必须被转换
// ──────────────────────────────────────────────

test('TIME_FIELDS 覆盖了所有会泄漏给客户端的时间字段', () => {
  // 这就是事故的根因：契约不存在，于是每个调用方各自决定要不要转
  for (const f of ['created_at', 'recorded_at', 'retention_until']) {
    assert.ok(TIME_FIELDS.includes(f), `TIME_FIELDS 必须包含 ${f}`);
  }
});

test('toBeijingRows 会把所有 TIME_FIELDS 都转成北京时间', () => {
  const rows = [{
    id: 1,
    created_at: UTC_SQL,
    recorded_at: UTC_ISO,
    retention_until: '2026-10-22T08:39:53.388Z',
    session_id: 'abc123'
  }];
  const [out] = toBeijingRows(rows);

  assert.equal(out.created_at, BEIJING);
  assert.equal(out.recorded_at, BEIJING);
  assert.equal(out.retention_until, '2026-10-22 16:39:53');
  assert.equal(out.session_id, 'abc123', '非时间字段不应被改动');
});

test('toBeijingRows 不修改入参（原数组原对象保持不变）', () => {
  const row = { created_at: UTC_SQL, recorded_at: UTC_ISO };
  toBeijingRows([row]);
  assert.equal(row.created_at, UTC_SQL, '入参被就地修改会导致调用方拿到脏数据');
  assert.equal(row.recorded_at, UTC_ISO);
});

test('toBeijingRows 对空数组与 null 安全', () => {
  assert.deepEqual(toBeijingRows([]), []);
  assert.deepEqual(toBeijingRows(null), []);
});

test('toBeijingRows 对缺字段的行不会补出 undefined 键', () => {
  const [out] = toBeijingRows([{ id: 9 }]);
  assert.equal(out.id, 9);
  assert.ok(!('created_at' in out), '缺失字段不该被写成 undefined 字符串');
});

// ──────────────────────────────────────────────
//  回归钉子：事故里那个具体字段
// ──────────────────────────────────────────────

test('回归 事故场景 recordedAt 必须已被转换（否则子女端分组头会显示 UTC）', () => {
  const rows = toBeijingRows([{ sessionId: 's1', recordedAt: UTC_ISO }]);
  assert.equal(rows[0].recordedAt, BEIJING);
});

test('回归 事故场景 客户端不能重复转换（服务端已转，客户端应原样显示）', () => {
  // 客户端 EvidenceFragment 目前做的是 .replace("T"," ").take(16)，
  // 对已转换的 "YYYY-MM-DD HH:mm:ss" 正好无损。对未转换的 ISO 就会少 8 小时。
  const served = toBeijing(UTC_ISO);                 // 服务端输出
  const displayed = served.replace('T', ' ').slice(0, 16); // 客户端当前逻辑
  assert.equal(displayed, '2026-10-08 16:28');
});