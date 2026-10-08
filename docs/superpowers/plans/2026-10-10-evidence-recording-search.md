# 维权证据页 · 录音检索化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 维权证据页默认只展示最近 3 次录音会话，其余通过搜索与筛选检索；结构化证据包上移到页面首位。

**Architecture:** 服务端 `GET /api/recordings/list/:elderId` 从「拉 200 条全量 + JS 分组」改为「SQL 按 session_id 聚合分页 + 按 session_id 取明细」，新增 `q`/`range`/`level`/`limit`/`offset` 参数。Android 端维护筛选状态，用 `reqSeq` 丢弃过期响应，「加载更多」把新页追加到现有列表。

**Tech Stack:** Node.js + Express + SQLite（CommonJS，`node --test`）；Android Kotlin + `LinearLayout`/`TextView`（手搓控件，不引 Chip/Spinner）。

**设计稿：** `docs/superpowers/specs/2026-10-10-evidence-recording-search-design.md`

---

## 现状事实（实现前必读）

- `/api/recordings/list` 的唯一调用方是 `EvidenceFragment.kt:275`，目前带 `?group=1`。改响应结构不会影响其它端。
- 小程序 `pages/evidence` 不调用该接口，它只渲染证据包 JSON。
- `routes/recordings.js` 现有 list 实现在 312~395 行区间，是一段「`db.all` 全量 → `toBeijingRows` → `map` → JS `groupBy`」的连续逻辑。
- `services/regeo.js` 已导出 `describePlace(lat, lng, db, elderId)`（返回 `{ name, source }`，`source` 取 `geo`/`anchor`/`hotspot`/`coord`）与 `looksLikeRealAddress(addr)`。`routes/recordings.js` 当前**没有**引用 regeo。
- `geofence.js` 的 `/list/:elderId` 里已有「坐标名 → `describePlace` 补全 → 仅 `source === 'geo'` 时写回库」的成熟做法，本计划照抄这个模式。
- `android/.../util/JsonUtils.kt` 导出 `JSONObject.optStringOrEmpty(name)`，这是项目里唯一正确的字符串读取方式（`optString` 遇 JSON null 会返回字符串 `"null"`）。
- Android 项目**没有** `familyDelete`，删除录音走的是 `POST /api/recordings/:id/review` + `keep=false`。
- `recordings.recorded_at` 是 UTC ISO 串（如 `2026-10-08T12:40:37.000Z`），而 SQLite 的 `CURRENT_TIMESTAMP` 存的是 `YYYY-MM-DD HH:mm:ss` UTC。**两种格式都存在于 `recordings` 表**：`recorded_at` 由客户端上传（ISO），`created_at` 由 DB 默认值生成（空格分隔）。按时间筛选必须用 `recorded_at`，且比较时要兼容两种格式 —— 见 Task 1 Step 3。
- 跑测试：`npm test`（当前 143 个用例全绿）。跑 Android 单测：`cd android; gradle test`。

---

## File Structure

| 文件 | 责任 |
| --- | --- |
| `services/recordingQuery.js`（新建） | 筛选 WHERE 片段与分页参数归一化的**纯函数**，不碰 DB，可单测 |
| `tests/recordingQuery.test.js`（新建） | 上述纯函数的单测 |
| `routes/recordings.js`（改） | 接入筛选与分页、统计独立 COUNT、`reasonLabel` 地名补全 |
| `android/.../res/layout/fragment_family_evidence.xml`（改） | 调换容器顺序，新增标题行/搜索框/筛选行/加载更多 |
| `android/.../family/EvidenceFragment.kt`（改） | 筛选状态、请求防抖与 `reqSeq`、分页追加、空态与错误态 |
| `android/.../family/RecordingPlayerCard.kt`（改） | 空 `transcript` 不渲染 |
| `docs/api-reference.md`（改） | 更新 `/list` 参数表与响应字段 |

把查询构造放在 `services/` 而非 `routes/` 内：`require('../routes/recordings')` 会连带 `database/db.js`，在测试进程里真的打开 `data.sqlite`。`tests/` 现有用例全是纯函数级、无 DB 依赖，这个特性必须保住。

---

## Task 1: 筛选与分页的查询构造（纯函数 + 单测）

**Files:**
- Create: `services/recordingQuery.js`
- Create: `tests/recordingQuery.test.js`

- [ ] **Step 1: 写失败的测试**

创建 `tests/recordingQuery.test.js`：

```js
const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  buildListWhere,
  normalizeListPaging,
  RANGE_DAYS,
  LEVEL_SQL,
  DEFAULT_LIMIT,
  MAX_LIMIT,
} = require('../services/recordingQuery');

const BASE = 'elder_id = ?';

test('range=7d 生成 7 天下界，30d 生成 30 天下界', () => {
  const now = Date.now();
  const w7 = buildListWhere({ q: '', range: '7d', level: 'all', evidence: false }, now);
  assert.match(w7.sql, /recorded_at >= \?/);
  assert.equal(w7.params.length, 2);            // elderId + 下界
  const days7 = Math.round((now - w7.params[1]) / 86400000);
  assert.equal(days7, 7);

  const w30 = buildListWhere({ q: '', range: '30d', level: 'all', evidence: false }, now);
  assert.equal(Math.round((now - w30.params[1]) / 86400000), 30);
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
  assert.equal(w.params.length, 1);
});

test('q 中的 % 和 _ 被转义，不会变成通配符', () => {
  const w = buildListWhere({ q: '50%_off', range: 'all', level: 'all', evidence: false });
  assert.equal(w.params[0], '%50\\%\\_off%');
  assert.ok(!/params.*=.*50%_off/.test(w.params[0]));
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

test('buildSessionQuery 统计字段齐全', () => {
  const { sql } = buildSessionQuery(7, { sql: BASE, params: [] }, 3, 0);
  for (const col of ['COUNT(*)', 'SUM(duration_ms)', 'MIN(recorded_at)', 'MAX(id) AS last_id']) {
    assert.ok(sql.includes(col), `缺少 ${col}`);
  }
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test "tests/recordingQuery.test.js"`
Expected: FAIL，报 `Cannot find module '../services/recordingQuery'`

- [ ] **Step 3: 实现 recordingQuery.js**

创建 `services/recordingQuery.js`：

```js
// 录音列表的筛选与分页：纯查询构造，不碰 DB。
//
// 为什么单独抽一层：routes/recordings.js 一 require 就会连带 database/db.js，
// 在测试进程里真的打开 data.sqlite。tests/ 现有 143 个用例全是纯函数级、
// 无 DB 依赖，这里必须维持同样的性质 —— 否则跑一次测试就污染生产库。
//
// 为什么分页必须在 SQL 里做：老实现是「LIMIT 200 拉全量 → JS 里 groupBy」，
// JS 分组发生在拿到全部行之后，切页仍然要全量拉回，流量白费。隧道实测吞吐
// 86~102KB/s，这个项目对流量敏感。

/** 时间范围 → 天数。未列出的值一律按 all 处理。 */
const RANGE_DAYS = { '7d': 7, '30d': 30 };

/**
 * 风险等级 → SQL 片段。
 *
 * 判定口径必须在服务端定死，客户端不得各自解释。
 * PENDING / ANALYZING（研判在途）刻意不进任何一档：单列一个「待研判」
 * 对子女没有决策价值，只会让人以为那些录音有问题。
 */
const LEVEL_SQL = {
  fraud: "fraud_status = 'FRAUD'",
  suspect: "fraud_status IN ('SUSPECT','FAILED')",
  safe: "fraud_status = 'SAFE'",
  untranscribed: "(transcript_status IN ('SKIPPED','FAILED') OR transcript IS NULL)",
};

/** 会被全文检索的文本字段：转写内容、录音地点、触发来源、AI 结论。 */
const SEARCHABLE = ['transcript', 'place_name', 'reason', 'fraud_verdict'];

const DEFAULT_LIMIT = 3;
const MAX_LIMIT = 20;
const BASE_WHERE = 'elder_id = ?';

/**
 * 把用户输入里的 LIKE 通配符转义。
 *
 * 不转义的话，子女搜一个「50%」就变成匹配全部 —— 这不是小问题：
 * 检索结果突然从 3 条变成几百条，他会以为系统出故障。
 * 必须配合 SQL 里的 ESCAPE '\\' 一起用。
 */
function escapeLikePattern(raw) {
  return String(raw).replace(/[\\%_]/g, (ch) => '\\' + ch);
}

/**
 * 构造 WHERE 片段。
 *
 * @param {{q?:string, range?:string, level?:string, evidence?:boolean}} filters
 * @param {number} [now] 注入当前时间，便于测试断言
 * @returns {{sql:string, params:Array}} sql 为完整 WHERE 子句（不含 WHERE 关键字）
 */
function buildListWhere(filters, now) {
  const f = filters || {};
  const clauses = [BASE_WHERE];
  const params = [];

  const q = String(f.q == null ? '' : f.q).trim();
  if (q) {
    const like = '%' + escapeLikePattern(q) + '%';
    clauses.push('(' + SEARCHABLE.map((c) => `${c} LIKE ? ESCAPE '\\'`).join(' OR ') + ')');
    // 一个 like 值要重复 SEARCHABLE.length 次，顺序与占位符一致
    for (let i = 0; i < SEARCHABLE.length; i++) params.push(like);
  }

  const days = RANGE_DAYS[String(f.range || '')];
  if (days) {
    // recorded_at 是 UTC ISO 串（客户端上传），与 DB 生成的 created_at 空格格式不同。
    // 这里传 ISO 串让 SQLite 直接做字符串比较可行，但为了不依赖格式假设，
    // 统一用 julianday() 归一到数值再比 —— 两种格式它都能吃。
    const at = typeof now === 'number' ? now : Date.now();
    clauses.push(`recorded_at IS NOT NULL AND julianday(recorded_at) >= julianday(?)`);
    params.push(new Date(at - days * 86400000).toISOString());
  }

  const levelSql = LEVEL_SQL[String(f.level || '')];
  if (levelSql) clauses.push(levelSql);

  if (f.evidence) clauses.push('keep_as_evidence = 1');

  return { sql: clauses.join(' AND '), params };
}

/**
 * 分页参数归一化。非法值一律回落到默认，不抛错 ——
 * 子女端把参数传错时的正确表现是「看到默认列表」，不是白屏。
 */
function normalizeListPaging(query) {
  const q = query || {};
  let limit = parseInt(q.limit, 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = DEFAULT_LIMIT;
  if (limit > MAX_LIMIT) limit = MAX_LIMIT;

  let offset = parseInt(q.offset, 10);
  if (!Number.isFinite(offset) || offset < 0) offset = 0;

  return { limit, offset };
}

/** 第一步：按 session_id 聚合并切页，拿到这一页的会话。 */
function buildSessionQuery(elderId, where, limit, offset) {
  return {
    sql:
      `SELECT session_id,
              COUNT(*)         AS segment_count,
              SUM(duration_ms) AS total_duration_ms,
              MIN(recorded_at) AS started_at,
              MAX(id)          AS last_id
       FROM recordings
       WHERE ${where.sql}
       GROUP BY session_id
       ORDER BY last_id DESC
       LIMIT ? OFFSET ?`,
    params: [elderId, ...where.params, limit, offset],
  };
}

/**
 * 第二步：用第一页拿到的 session_id 取明细。
 *
 * 占位符个数必须与 params 长度严格一致，否则 sqlite3 会静默绑错值 ——
 * 这是本项目「静默失效」类 bug 的典型来源。
 */
function buildDetailQuery(elderId, sessionIds) {
  const holes = sessionIds.map(() => '?').join(',');
  return {
    sql:
      `SELECT * FROM recordings
       WHERE elder_id = ? AND session_id IN (${holes})
       ORDER BY id DESC`,
    params: [elderId, ...sessionIds],
  };
}

/** 会话总数（筛选后，不受 limit/offset 影响）。 */
function buildSessionCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(DISTINCT session_id) AS n FROM recordings WHERE ${where.sql}`,
    params: [elderId, ...where.params],
  };
}

/** 录音总数（筛选后，不受 limit/offset 影响）。 */
function buildRecordingCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql}`,
    params: [elderId, ...where.params],
  };
}

/** 检出诈骗的录音数（筛选后，不受 limit/offset 影响）。 */
function buildFraudCountQuery(elderId, where) {
  return {
    sql: `SELECT COUNT(*) AS n FROM recordings WHERE ${where.sql} AND fraud_status = 'FRAUD'`,
    params: [elderId, ...where.params],
  };
}

module.exports = {
  buildListWhere,
  normalizeListPaging,
  buildSessionQuery,
  buildDetailQuery,
  buildSessionCountQuery,
  buildRecordingCountQuery,
  buildFraudCountQuery,
  escapeLikePattern,
  RANGE_DAYS,
  LEVEL_SQL,
  DEFAULT_LIMIT,
  MAX_LIMIT,
};
```

- [ ] **Step 4: 运行测试确认通过**

Run: `node --test "tests/recordingQuery.test.js"`
Expected: PASS，`# pass 21`（或更多）

- [ ] **Step 5: 跑全量测试确认没打破既有行为**

Run: `npm test`
Expected: `# pass 164`，`# fail 0`（原 143 + 新增用例）

- [ ] **Step 6: 提交**

```bash
git add services/recordingQuery.js tests/recordingQuery.test.js
git commit -m "feat: 录音列表的筛选与分页查询构造"
```

---

## Task 2: 服务端接入筛选与分页

**Files:**
- Modify: `routes/recordings.js:1-30`（顶部 import）
- Modify: `routes/recordings.js:306-395`（`GET /list/:elderId` 整个实现）

- [ ] **Step 1: 加 import**

在 `routes/recordings.js` 顶部 require 区（现有 `const store = require('../services/recordingStore');` 那一段）追加：

```js
const {
  buildListWhere, normalizeListPaging,
  buildSessionQuery, buildDetailQuery,
  buildSessionCountQuery, buildRecordingCountQuery, buildFraudCountQuery,
} = require('../services/recordingQuery');
```

- [ ] **Step 2: 替换 list 实现**

把 `router.get('/list/:elderId', ...)` 到其闭合 `});` 的整段替换为：

```js
router.get('/list/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);
  const sessionGroup = req.query.group === '1';

  const where = buildListWhere({
    q: req.query.q,
    range: req.query.range,
    level: req.query.level,
    evidence: req.query.evidence === '1',
  });
  const { limit, offset } = normalizeListPaging(req.query);

  // 第一步：按会话切页。分页必须在 SQL 里做 —— 老实现拉 200 条全量再 JS 分组，
  // 切页时流量白费（隧道实测 86~102KB/s）。
  const sessionQuery = buildSessionQuery(elderId, where, limit, offset);

  db.all(sessionQuery.sql, sessionQuery.params, (sessErr, sessionRows) => {
    if (sessErr) return res.status(500).json({ success: false, error: sessErr.message });

    const sessionIds = (sessionRows || []).map((r) => r.session_id);

    const respond = (rows, stats) => {
      const formatted = toBeijingRows(rows || []).map((r) => {
        const { token, expiresAt } = sign(r.id, req.authUserId);
        return {
          id: r.id,
          sessionId: r.session_id,
          segmentIndex: r.segment_index,
          reason: r.reason,
          reasonLabel: r.reason === 'SOS' ? '一键紧急求助' : `进入敏感地点「${r.place_name || '未知'}」`,
          placeName: r.place_name,
          fileName: r.file_name,
          durationMs: r.duration_ms,
          sizeBytes: r.size_bytes,
          sha256: r.sha256,
          recordedAt: r.recorded_at,
          address: r.address,
          latitude: r.latitude,
          longitude: r.longitude,
          transcript: r.transcript,
          transcriptStatus: r.transcript_status,
          transcriptError: r.transcript_error,
          fraudStatus: r.fraud_status,
          fraudScore: r.fraud_score,
          fraudVerdict: r.fraud_verdict,
          fraudLabels: safeParseLabels(r.fraud_labels),
          suspectRole: r.suspect_role,
          keepAsEvidence: !!r.keep_as_evidence,
          retentionUntil: r.retention_until,
          cleanupReason: r.cleanup_reason,
          reviewedByFamily: !!r.reviewed_by_family,
          // 播放器直接用这个 URL 发 GET，不带自定义头，所以必须带签名
          streamUrl: `/api/recordings/stream/${r.id}?token=${encodeURIComponent(token)}`,
          streamTokenExpiresAt: expiresAt,
        };
      });

      if (!sessionGroup) {
        return res.json({
          success: true,
          data: { recordings: formatted, total: stats.totalRecordings, ...stats },
        });
      }

      // 按会话聚合。一次连续录音的多段归到一起，子女端看得更清楚。
      // 分组顺序沿用第一步的会话顺序（已按 MAX(id) DESC 排好），不能靠 Map 插入序碰运气。
      const byId = new Map(sessionRows.map((r) => [r.session_id, r]));
      const sessions = [];
      for (const r of formatted) {
        let g = sessions.find((s) => s.sessionId === r.sessionId);
        if (!g) {
          const meta = byId.get(r.sessionId) || {};
          g = {
            sessionId: r.sessionId,
            reason: r.reason,
            reasonLabel: r.reasonLabel,
            placeName: r.placeName,
            startedAt: meta.started_at || r.recordedAt,
            segmentCount: 0,
            totalDurationMs: 0,
            isFraud: false,
            isSuspect: false,
            hasTranscript: false,
            recordings: [],
          };
          sessions.push(g);
        }
        g.segmentCount += 1;
        g.totalDurationMs += r.durationMs || 0;
        g.recordings.push(r);
        if (r.fraudStatus === 'FRAUD') g.isFraud = true;
        if (r.fraudStatus === 'SUSPECT' || r.fraudStatus === 'FAILED') g.isSuspect = true;
        if (r.transcript) g.hasTranscript = true;
      }

      res.json({
        success: true,
        data: {
          sessions,
          ...stats,
          hasMore: offset + sessionIds.length < (stats.totalSessions || 0),
          appliedFilters: {
            q: String(req.query.q || '').trim(),
            range: RANGE_DAYS[req.query.range] ? req.query.range : 'all',
            level: LEVEL_SQL[req.query.level] ? req.query.level : 'all',
            evidence: req.query.evidence === '1',
          },
        },
      });
    };

    // 统计必须独立 COUNT 且不带 LIMIT：否则翻到第二页时标题上的
    // 「共 17 段，其中 2 段诈骗」会随翻页跳变。
    const statsQuery = {
      sessions: buildSessionCountQuery(elderId, where),
      recordings: buildRecordingCountQuery(elderId, where),
      fraud: buildFraudCountQuery(elderId, where),
    };
    db.get(statsQuery.sessions.sql, statsQuery.sessions.params, (e1, s1) => {
      db.get(statsQuery.recordings.sql, statsQuery.recordings.params, (e2, s2) => {
        db.get(statsQuery.fraud.sql, statsQuery.fraud.params, (e3, s3) => {
          const stats = {
            totalSessions: (s1 && s1.n) || 0,
            totalRecordings: (s2 && s2.n) || 0,
            fraudCount: (s3 && s3.n) || 0,
          };
          if (e1 || e2 || e3) {
            return res.status(500).json({ success: false, error: (e1 || e2 || e3).message });
          }

          if (sessionIds.length === 0) return respond([], stats);

          const detail = buildDetailQuery(elderId, sessionIds);
          db.all(detail.sql, detail.params, (dErr, rows) => {
            if (dErr) return res.status(500).json({ success: false, error: dErr.message });
            respond(rows, stats);
          });
        });
      });
    });
  });
});
```

- [ ] **Step 3: 补 import 里用到的两个常量**

Step 2 的响应体引用了 `RANGE_DAYS` 与 `LEVEL_SQL`，把它们加进 Step 1 的 import：

```js
const {
  buildListWhere, normalizeListPaging,
  buildSessionQuery, buildDetailQuery,
  buildSessionCountQuery, buildRecordingCountQuery, buildFraudCountQuery,
  RANGE_DAYS, LEVEL_SQL,
} = require('../services/recordingQuery');
```

- [ ] **Step 4: 确认后端可达**

**不要盲目再起一个 server。** 计划任务 `AntiFraudGuardBackend` 常驻占用 3000 端口（见 MEMORY.md），重复启动会 `EADDRINUSE`。先探活：

```powershell
curl "http://localhost:3000/api/health"
```

Expected: `{"success":true,"message":"ok","time":...}`

若确实没在跑，用演示库起一个（不要用 `data.sqlite`，那是真实数据）：

```powershell
$env:DB_PATH = "data.demo.sqlite"; node server.js
```

若 `data.demo.sqlite` 不存在，先 `npm run seed`。

- [ ] **Step 5: 写一个临时脚本验证 SQL 层**

`/list` 需要 `X-Auth-Token`，临时脚本里造 token 很别扭。改为直接验证查询构造在真实 SQLite 上能跑通（这才是本 Task 容易出错的地方 —— 参数数量不匹配会静默绑错值）。

创建 `scripts/tmp-verify-list.js`，跑完即删：

```js
// 临时验证脚本，不入库。直接对真实 SQLite 执行构造出的 SQL，
// 目的是确认参数占位符数量与顺序正确（错了 sqlite3 会静默绑错值）。
process.env.DB_PATH = require('path').join(__dirname, '..', 'data.demo.sqlite');
const db = require('../database/db');
const q = require('../services/recordingQuery');

const CASES = [
  'group=1',
  'group=1&range=7d',
  'group=1&level=fraud',
  'group=1&level=suspect',
  'group=1&level=safe',
  'group=1&level=untranscribed',
  'group=1&q=退款',
  'group=1&evidence=1',
  'group=1&limit=1&offset=0',
  'group=1&limit=1&offset=1',
  'group=1&limit=999&offset=-3',
];

function run(sql, params) {
  return new Promise((resolve) => {
    db.all(sql, params, (err, rows) => resolve(err ? `ERR ${err.message}` : rows));
  });
}

db.get("SELECT id FROM users WHERE role = 'elder' LIMIT 1", [], async (e, row) => {
  if (e || !row) {
    console.error('演示库里没有老人账号，先跑 npm run seed');
    process.exit(1);
  }
  const elderId = row.id;
  let failures = 0;

  for (const qs of CASES) {
    const p = new URLSearchParams(qs);
    const where = q.buildListWhere({
      q: p.get('q'), range: p.get('range'),
      level: p.get('level'), evidence: p.get('evidence') === '1',
    });
    const { limit, offset } = q.normalizeListPaging(p);

    const sq = q.buildSessionQuery(elderId, where, limit, offset);
    const sessions = await run(sq.sql, sq.params);
    if (typeof sessions === 'string') { console.log(`FAIL ${qs}: ${sessions}`); failures++; continue; }

    const line = [`${qs} -> ${sessions.length} 会话 (limit=${limit} offset=${offset})`];

    if (sessions.length > 0) {
      const ids = sessions.map((r) => r.session_id);
      const dq = q.buildDetailQuery(elderId, ids);
      // 占位符个数必须等于 params 长度，否则 sqlite3 会静默绑错值
      const holes = (dq.sql.match(/\?/g) || []).length;
      if (holes !== dq.params.length) {
        line.push(`FAIL 占位符 ${holes} != 参数 ${dq.params.length}`);
        failures++;
      }
      const detail = await run(dq.sql, dq.params);
      if (typeof detail === 'string') { line.push(`FAIL ${detail}`); failures++; }
      else line.push(`${detail.length} 条明细`);
    }

    const cnt = q.buildRecordingCountQuery(elderId, where);
    const c = await run(cnt.sql, cnt.params);
    if (typeof c === 'string') { line.push(`FAIL count ${c}`); failures++; }
    else line.push(`筛选后共 ${c[0].n} 条`);

    console.log(line.join('  |  '));
  }

  console.log(failures === 0 ? '\n全部通过' : `\n${failures} 项失败`);
  process.exit(failures === 0 ? 0 : 1);
});
```

Run: `node scripts/tmp-verify-list.js`
Expected: 每行输出会话数与明细数，最后打印 `全部通过`。

重点确认三行：
- `group=1&limit=999&offset=-3` → `limit=20 offset=0`（被夹到上限、负数归零）
- `group=1&limit=1&offset=1` → 正常返回，不报错
- 任一带 `level` 或 `q` 的行 → 不出现 `FAIL`

- [ ] **Step 6: 删掉临时脚本**

```bash
Remove-Item scripts/tmp-verify-list.js
```

- [ ] **Step 7: 跑测试**

Run: `npm test`
Expected: `# fail 0`

- [ ] **Step 8: 提交**

```bash
git add routes/recordings.js
git commit -m "feat: 录音列表支持按时间/等级/关键词筛选与会话分页"
```

---

## Task 3: reasonLabel 地名补全

**Files:**
- Modify: `routes/recordings.js`（顶部 require 区 + list 的 `respond()` 内）

**背景（为什么这是真问题）:** 截图里 `reasonLabel` 显示成「进入敏感地点『曾爷爷常去地点(28.273, 113.062)』」。`recordings.place_name` 是客户端上传时带上来的，未经服务端地名推断，坐标串直接进了标题。子女看到一串数字认不出自己配的是哪个地点，会以为配置丢了 —— 这是「静默的可理解性故障」，比报错更难排查。

- [ ] **Step 1: 引入 regeo**

在 Task 2 Step 1 的 require 区追加：

```js
const geo = require('../services/regeo');
```

- [ ] **Step 2: 替换 respond 函数**

Task 2 引入的 `respond` 函数（从 `const respond = (rows, stats) => {` 到它闭合的 `};`）整段替换为下面这版。改动要点：把响应体收敛到 `sendResponse()` 闭包，坐标串名称的异步补全调度插在它前面。

```js
    const respond = (rows, stats) => {
      const formatted = toBeijingRows(rows || []).map((r) => {
        const { token, expiresAt } = sign(r.id, req.authUserId);
        return {
          id: r.id,
          sessionId: r.session_id,
          segmentIndex: r.segment_index,
          reason: r.reason,
          reasonLabel: r.reason === 'SOS' ? '一键紧急求助' : `进入敏感地点「${r.place_name || '未知'}」`,
          placeName: r.place_name,
          fileName: r.file_name,
          durationMs: r.duration_ms,
          sizeBytes: r.size_bytes,
          sha256: r.sha256,
          recordedAt: r.recorded_at,
          address: r.address,
          latitude: r.latitude,
          longitude: r.longitude,
          transcript: r.transcript,
          transcriptStatus: r.transcript_status,
          transcriptError: r.transcript_error,
          fraudStatus: r.fraud_status,
          fraudScore: r.fraud_score,
          fraudVerdict: r.fraud_verdict,
          fraudLabels: safeParseLabels(r.fraud_labels),
          suspectRole: r.suspect_role,
          keepAsEvidence: !!r.keep_as_evidence,
          retentionUntil: r.retention_until,
          cleanupReason: r.cleanup_reason,
          reviewedByFamily: !!r.reviewed_by_family,
          // 播放器直接用这个 URL 发 GET，不带自定义头，所以必须带签名
          streamUrl: `/api/recordings/stream/${r.id}?token=${encodeURIComponent(token)}`,
          streamTokenExpiresAt: expiresAt,
        };
      });

      // 真正的响应体在这里发一次。抽成闭包是为了让「先用原值响应、
      // 补全完地名再响应」两条路径复用同一段渲染逻辑。
      const sendResponse = () => {
        if (!sessionGroup) {
          return res.json({
            success: true,
            data: { recordings: formatted, total: stats.totalRecordings, ...stats },
          });
        }

        // 按会话聚合。一次连续录音的多段归到一起，子女端看得更清楚。
        // 顺序沿用第一步查出的会话顺序（已按 MAX(id) DESC 排好），
        // 不靠 Map 的插入顺序碰运气。
        const metaBySession = new Map(sessionRows.map((r) => [r.session_id, r]));
        const sessions = [];
        for (const r of formatted) {
          let g = sessions.find((s) => s.sessionId === r.sessionId);
          if (!g) {
            const meta = metaBySession.get(r.sessionId) || {};
            g = {
              sessionId: r.sessionId,
              reason: r.reason,
              reasonLabel: r.reasonLabel,
              placeName: r.placeName,
              startedAt: meta.started_at || r.recordedAt,
              segmentCount: 0,
              totalDurationMs: 0,
              isFraud: false,
              isSuspect: false,
              hasTranscript: false,
              recordings: [],
            };
            sessions.push(g);
          }
          g.segmentCount += 1;
          g.totalDurationMs += r.durationMs || 0;
          g.recordings.push(r);
          if (r.fraudStatus === 'FRAUD') g.isFraud = true;
          if (r.fraudStatus === 'SUSPECT' || r.fraudStatus === 'FAILED') g.isSuspect = true;
          if (r.transcript) g.hasTranscript = true;
        }

        res.json({
          success: true,
          data: {
            sessions,
            ...stats,
            hasMore: offset + sessionIds.length < (stats.totalSessions || 0),
            appliedFilters: {
              q: String(req.query.q || '').trim(),
              range: RANGE_DAYS[req.query.range] ? req.query.range : 'all',
              level: LEVEL_SQL[req.query.level] ? req.query.level : 'all',
              evidence: req.query.evidence === '1',
            },
          },
        });
      };

      // 坐标串名称补全。
      //
      // 为什么要有：recordings.place_name 是老人端上传时带上来、未经服务端地名推断的，
      // 坐标串直接进标题就成了「进入敏感地点『曾爷爷常去地点(28.273, 113.062)』」。
      // 子女看到一串数字认不出自己配的是哪个地点，会以为配置丢了 ——
      // 这是「静默的可理解性故障」，比报错更难排查。
      //
      // 做法对齐 geofence.js 的 /list/:elderId：先用原值响应（describePlace 要查
      // 地图 key，阻塞列表不可接受），Promise.all 补全后再发一次响应。
      const needGuess = formatted.filter(
        (r) => r.reason !== 'SOS'
          && !geo.looksLikeRealAddress(r.placeName || '')
          && Number.isFinite(r.latitude) && r.latitude !== 0
          && Number.isFinite(r.longitude) && r.longitude !== 0
      );

      if (needGuess.length === 0) return sendResponse();

      Promise.all(needGuess.map((r) =>
        geo.describePlace(r.latitude, r.longitude, db, elderId)
          .then(({ name, source }) => ({ rec: r, name, source }))
          .catch(() => null)
      )).then((hits) => {
        for (const hit of (hits || []).filter(Boolean)) {
          if (!hit.name) continue;
          hit.rec.placeName = hit.name;
          hit.rec.reasonLabel = `进入敏感地点「${hit.name}」`;
          // 只把真实地图地名写回库。「常去地点①」这类推断描述覆盖原值会丢信息，
          // 老人下次再录时仍要重新推断，而原值可能本来就是有用的地点名。
          if (hit.source === 'geo') {
            db.run('UPDATE recordings SET place_name = ? WHERE id = ?', [hit.name, hit.rec.id]);
          }
        }
        sendResponse();
      });
    };
```

**关键点：`sendResponse()` 有且只有两条调用路径** —— `needGuess.length === 0` 时直接调，非空时在 `Promise.all().then()` 里调。函数末尾不要再无条件补一次 `sendResponse()`，那样会重复响应、Express 抛 `ERR_HTTP_HEADERS_SENT`。

- [ ] **Step 3: 验证不影响正常名称**

Run: `npm test`
Expected: `# fail 0`

再跑 Task 2 Step 5 的临时脚本（重新创建、跑完删除），确认 list 的 SQL 层仍正常。

- [ ] **Step 4: 提交**

```bash
git add routes/recordings.js
git commit -m "fix: 录音列表的地点名兜底推断，去掉标题里的坐标串"
```

---

## Task 4: 空 transcript 不渲染

**Files:**
- Modify: `android/.../family/RecordingPlayerCard.kt:136-137`

**背景:** 截图里每张卡片显示「转写内容：`null`」。原因是 `rec.optString("transcript", "")` 在服务端返回 JSON `null` 时取到的是字符串 `"null"`（org.json 的行为），于是 `transcript.isNotEmpty()` 为真，走进了「有转写」分支。这正是 AGENT.MD 第 7 条铁律记的坑。

- [ ] **Step 1: 改用 optStringOrEmpty**

第 12 行 import 区加：

```kotlin
import com.antifraud.guard.util.optStringOrEmpty
```

第 136-137 行改为：

```kotlin
        val transcript = rec.optStringOrEmpty("transcript")
        val status = rec.optStringOrEmpty("transcriptStatus").ifEmpty { "PENDING" }
```

第 163 行同理（`transcriptError` 也可能是 JSON null）：

```kotlin
                val err = rec.optStringOrEmpty("transcriptError").ifEmpty { "未配置语音转写服务" }
```

- [ ] **Step 2: 把该文件里其它 optString 一并换掉**

同一文件还有几处会踩同样的坑，逐个替换为 `optStringOrEmpty`：

| 行 | 原文 | 替换为 |
| --- | --- | --- |
| 55 | `rec.optString("reasonLabel", "录音")` | `rec.optStringOrEmpty("reasonLabel").ifEmpty { "录音" }` |
| 81 | `rec.optString("recordedAt", "")` | `rec.optStringOrEmpty("recordedAt")` |
| 84 | `rec.optString("address", "")` | `rec.optStringOrEmpty("address")` |
| 97 | `rec.optString("fraudVerdict", "")` | `rec.optStringOrEmpty("fraudVerdict")` |
| 295 | `rec.optString("retentionUntil", "")` | `rec.optStringOrEmpty("retentionUntil")` |
| 347-348 | `rec.optString("streamUrl", "")` | `rec.optStringOrEmpty("streamUrl")` |
| 448 | `rec.optString("fraudStatus", "PENDING")` | `rec.optStringOrEmpty("fraudStatus").ifEmpty { "PENDING" }` |
| 458 | `rec.optString("fraudStatus", "PENDING")` | 同上 |

- [ ] **Step 3: 编译验证**

Run: `cd android; gradle compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

**注意（项目已知坑）:** `compileDebugKotlin` 偶发一次性假失败（报源码里确实存在的类 `Unresolved`）。遇到时**重跑一次**再判断，不要急着改代码。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/antifraud/guard/family/RecordingPlayerCard.kt
git commit -m "fix: 录音卡片改用 optStringOrEmpty，避免把 JSON null 显示成 null 字符串"
```

---

## Task 5: 布局调整 —— 证据包上移 + 新增筛选控件

**Files:**
- Modify: `android/.../res/layout/fragment_family_evidence.xml`

- [ ] **Step 1: 调整容器顺序**

把 `ll_evidence_container` 移到 `ll_recording_container` **之前**。原文件 115~160 行的 `ScrollView` 内部改成：

```xml
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <TextView
                android:id="@+id/tv_evidence_empty"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="40dp"
                android:gravity="center"
                android:text="暂无证据材料\n绑定老人并产生风险记录后自动生成"
                android:textColor="#94A3B8"
                android:textSize="14sp"
                android:visibility="gone" />

            <!-- 结构化证据包：出事时报给警方的那份，是本页主体，排最前 -->
            <LinearLayout
                android:id="@+id/ll_evidence_container"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingHorizontal="16dp" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginHorizontal="16dp"
                android:layout_marginVertical="12dp"
                android:background="#E2E8F0" />

            <!-- 录音存证区（独立容器，单独刷新），原始素材，需要主动翻 -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingHorizontal="16dp">

                <!-- 标题行：打包下载按钮跟着录音区走，和它作用的对象同处一个区块，
                     否则子女会以为打包的是上面那堆流水 -->
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:paddingTop="4dp"
                    android:paddingBottom="8dp">

                    <TextView
                        android:id="@+id/tv_recording_header"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="🎙 环境录音存证"
                        android:textColor="#1E293B"
                        android:textSize="15sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/btn_pack_recordings"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:background="@drawable/bg_btn_primary"
                        android:paddingHorizontal="12dp"
                        android:paddingVertical="6dp"
                        android:text="打包下载"
                        android:textColor="#FFFFFF"
                        android:textSize="13sp" />
                </LinearLayout>

                <EditText
                    android:id="@+id/et_recording_search"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:background="@drawable/bg_input"
                    android:hint="🔍 搜索转写内容或地点"
                    android:imeOptions="actionSearch"
                    android:inputType="text"
                    android:maxLines="1"
                    android:paddingHorizontal="12dp"
                    android:paddingVertical="10dp"
                    android:textColor="#1E293B"
                    android:textColorHint="#94A3B8"
                    android:textSize="13sp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:paddingTop="8dp"
                    android:paddingBottom="4dp">

                    <TextView
                        android:id="@+id/btn_filter_range"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:background="@drawable/bg_input"
                        android:paddingHorizontal="12dp"
                        android:paddingVertical="6dp"
                        android:text="近7天"
                        android:textColor="#475569"
                        android:textSize="13sp" />

                    <TextView
                        android:id="@+id/btn_filter_level"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="8dp"
                        android:background="@drawable/bg_input"
                        android:paddingHorizontal="12dp"
                        android:paddingVertical="6dp"
                        android:text="全部等级"
                        android:textColor="#475569"
                        android:textSize="13sp" />

                    <TextView
                        android:id="@+id/btn_filter_evidence"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="8dp"
                        android:background="@drawable/bg_input"
                        android:paddingHorizontal="12dp"
                        android:paddingVertical="6dp"
                        android:text="只看证据"
                        android:textColor="#475569"
                        android:textSize="13sp" />
                </LinearLayout>

                <LinearLayout
                    android:id="@+id/ll_recording_container"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical" />

                <TextView
                    android:id="@+id/tv_recording_empty"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="24dp"
                    android:gravity="center"
                    android:text="暂无录音存证\n老人按下紧急求助或进入你登记的敏感地点时，会自动录音并上传到这里"
                    android:textColor="#94A3B8"
                    android:textSize="13sp"
                    android:visibility="gone" />

                <!-- 筛选后无结果：文案必须写明是筛选导致，否则子女以为录音被系统删了 -->
                <LinearLayout
                    android:id="@+id/ll_recording_no_match"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="24dp"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:visibility="gone">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:gravity="center"
                        android:text="没有符合当前筛选条件的录音"
                        android:textColor="#94A3B8"
                        android:textSize="13sp" />

                    <TextView
                        android:id="@+id/btn_clear_filters"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_btn_primary"
                        android:paddingHorizontal="16dp"
                        android:paddingVertical="8dp"
                        android:text="清除筛选条件"
                        android:textColor="#FFFFFF"
                        android:textSize="13sp" />
                </LinearLayout>

                <TextView
                    android:id="@+id/btn_load_more"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_horizontal"
                    android:layout_marginTop="8dp"
                    android:layout_marginBottom="24dp"
                    android:background="@drawable/bg_input"
                    android:paddingHorizontal="20dp"
                    android:paddingVertical="10dp"
                    android:text="加载更多"
                    android:textColor="#475569"
                    android:textSize="13sp"
                    android:visibility="gone" />
            </LinearLayout>
        </LinearLayout>
    </ScrollView>
```

- [ ] **Step 2: 顶部标题栏去掉「打包下载」**

原文件 8~47 行的标题栏里删掉 `btn_pack_recordings` 那个 `TextView`，只留 `btn_copy_evidence`。改成：

```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:padding="16dp"
        android:paddingBottom="8dp">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="📋 维权证据"
            android:textColor="#1E293B"
            android:textSize="20sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/btn_copy_evidence"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_btn_primary"
            android:paddingHorizontal="14dp"
            android:paddingVertical="6dp"
            android:text="一键复制"
            android:textColor="#FFFFFF"
            android:textSize="13sp" />
    </LinearLayout>
```

- [ ] **Step 3: 把 tv_evidence_empty 从 ScrollView 外移进来**

原文件 104~113 行的 `tv_evidence_empty` 整块删除（Step 1 已经把它放进 `ScrollView` 内、证据包容器之前）。

- [ ] **Step 4: 确认没有重复 id**

Run: `Select-String -Path android/app/src/main/res/layout/fragment_family_evidence.xml -Pattern 'android:id="@+id/(\w+)"' -AllMatches | ForEach-Object { $_.Matches.Groups[1].Value } | Group-Object | Where-Object Count -gt 1`

Expected: 无输出（有输出说明 id 重复，`aapt` 会报错）

- [ ] **Step 5: 编译验证**

Run: `cd android; gradle compileDebugKotlin` → 期望 `BUILD SUCCESSFUL`（布局错误在这一步暴露）

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/res/layout/fragment_family_evidence.xml
git commit -m "feat: 维权证据页布局调整——证据包上移，录音区加搜索与筛选控件"
```

---

## Task 6: EvidenceFragment 筛选状态与分页

**Files:**
- Modify: `android/.../family/EvidenceFragment.kt`

这是本计划最大的一块。`EvidenceFragment.kt` 当前 509 行，将新增约 200 行。

- [ ] **Step 1: 加字段与常量**

在类顶部（第 27 行 `private var lastFetch = 0L` 附近）插入：

```kotlin
    // ── 录音列表的筛选与分页状态 ──
    // 这些状态必须活在 Fragment 上而不是每次请求重建，否则子女切去地图看一眼
    // 再回来，筛选条件和已翻到的页数就被冲掉了。
    private var filterQuery = ""
    private var filterRange = "all"
    private var filterLevel = "all"
    private var filterEvidence = false

    /** 已加载的会话数（offset 的依据） */
    private var loadedSessionCount = 0
    /** 服务端报告的筛选后会话总数 */
    private var totalSessionCount = 0
    private var moreAvailable = false

    /**
     * 请求序号：只有最新一次请求的响应才允许渲染。
     *
     * 没有它会出这个 bug：子女快速连点筛选按钮，慢的那次旧请求后到，
     * 把新筛选条件的结果覆盖掉 —— 界面上显示的是「近7天」但内容是「近30天」。
     */
    private var reqSeq = 0

    /** 搜索框防抖：避免每敲一个字打一次接口 */
    private val searchDebounce = Runnable { fetchRecordings(resetPaging = true) }

    private var loadMoreBtn: TextView? = null
    private var searchInput: EditText? = null
    private var filterRangeBtn: TextView? = null
    private var filterLevelBtn: TextView? = null
    private var filterEvidenceBtn: TextView? = null
    private var noMatchBox: android.view.View? = null
    private var recordingHeader: TextView? = null
```

import 区加：

```kotlin
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
```

并在 companion 或类内加常量：

```kotlin
    companion object {
        /** 与服务端 services/recordingQuery.js 的 DEFAULT_LIMIT 保持一致 */
        private const val PAGE_SIZE = 3
        private val RANGE_CYCLE = listOf("7d", "30d", "all")
        private val LEVEL_CYCLE = listOf("fraud", "suspect", "safe", "untranscribed", "all")
        private val RANGE_LABEL = mapOf("7d" to "近7天", "30d" to "近30天", "all" to "全部时间")
        private val LEVEL_LABEL = mapOf(
            "fraud" to "检出诈骗",
            "suspect" to "疑似风险",
            "safe" to "未发现诈骗",
            "untranscribed" to "未转写",
            "all" to "全部等级"
        )
    }
```

- [ ] **Step 2: onViewCreated 里绑定新控件**

现有 `onViewCreated`（第 54~93 行）中，`packBtn = view.findViewById(R.id.btn_pack_recordings)` 之后插入：

```kotlin
        searchInput = view.findViewById(R.id.et_recording_search)
        filterRangeBtn = view.findViewById(R.id.btn_filter_range)
        filterLevelBtn = view.findViewById(R.id.btn_filter_level)
        filterEvidenceBtn = view.findViewById(R.id.btn_filter_evidence)
        noMatchBox = view.findViewById(R.id.ll_recording_no_match)
        loadMoreBtn = view.findViewById(R.id.btn_load_more)
        recordingHeader = view.findViewById(R.id.tv_recording_header)

        // 搜索：防抖 500ms。子女打字不快，但也不该每敲一个字打一次接口。
        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchInput?.removeCallbacks(searchDebounce)
                searchInput?.postDelayed(searchDebounce, 500)
            }
        })

        filterRangeBtn?.setOnClickListener { cycleFilter(RANGE_CYCLE, filterRange) { filterRange = it; refreshFilterButtons() } }
        filterLevelBtn?.setOnClickListener { cycleFilter(LEVEL_CYCLE, filterLevel) { filterLevel = it; refreshFilterButtons() } }
        filterEvidenceBtn?.setOnClickListener {
            filterEvidence = !filterEvidence
            refreshFilterButtons()
            fetchRecordings(resetPaging = true)
        }
        view.findViewById<TextView>(R.id.btn_clear_filters).setOnClickListener { clearFilters() }
        loadMoreBtn?.setOnClickListener { fetchRecordings(resetPaging = false) }

        refreshFilterButtons()
```

- [ ] **Step 3: 加筛选辅助方法**

```kotlin
    /** 循环切换筛选值：点一下前进一档，到末尾回到开头 */
    private fun cycleFilter(cycle: List<String>, current: String, apply: (String) -> Unit) {
        val idx = cycle.indexOf(current)
        apply(cycle[(idx + 1) % cycle.size])
        fetchRecordings(resetPaging = true)
    }

    /** 刷新三个筛选按钮的文案与选中态 */
    private fun refreshFilterButtons() {
        filterRangeBtn?.apply {
            text = RANGE_LABEL[filterRange] ?: "全部时间"
            applyPillSelected(RANGE_LABEL[filterRange] != "全部时间")
        }
        filterLevelBtn?.apply {
            text = LEVEL_LABEL[filterLevel] ?: "全部等级"
            applyPillSelected(filterLevel != "all")
        }
        filterEvidenceBtn?.apply {
            text = if (filterEvidence) "✓ 只看证据" else "只看证据"
            applyPillSelected(filterEvidence)
        }
    }

    /** 选中态：主色底白字；未选中：bg_input 深灰字 */
    private fun android.widget.TextView.applyPillSelected(selected: Boolean) {
        if (selected) {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF2563EB.toInt())
            }
            setTextColor(0xFFFFFFFF.toInt())
        } else {
            setBackgroundResource(com.antifraud.guard.R.drawable.bg_input)
            setTextColor(0xFF475569.toInt())
        }
    }

    /** 清除全部筛选：搜索框也要清，否则「清了筛选但搜索词还在」会让人以为没生效 */
    private fun clearFilters() {
        searchInput?.removeCallbacks(searchDebounce)
        searchInput?.setText("")
        filterQuery = ""
        filterRange = "all"
        filterLevel = "all"
        filterEvidence = false
        refreshFilterButtons()
        fetchRecordings(resetPaging = true)
    }
```

- [ ] **Step 4: 替换 fetchRecordings**

把现有的 `fetchRecordings(forceFetch: Boolean = false)`（第 267~286 行）整段替换为：

```kotlin
    /**
     * 拉录音列表。
     *
     * @param resetPaging true = 回到第一页（筛选变化时）；false = 追加下一页
     */
    private fun fetchRecordings(resetPaging: Boolean = true, forceFetch: Boolean = false) {
        if (!GuardConfig.isFamilyBound) return
        val now = System.currentTimeMillis()
        // 首屏与刚被 WS 事件唤醒时不节流，保证子女第一时间看到新录音；
        // 常规 onResume 才做 10s 节流，避免反复切页打接口
        if (!forceFetch && !resetPaging && now - lastRecordingFetch < 10_000) return
        lastRecordingFetch = now

        val seq = ++reqSeq
        val offset = if (resetPaging) 0 else loadedSessionCount

        loadMoreBtn?.apply {
            isEnabled = false
            text = "加载中…"
        }

        ApiClient.familyGet(buildListUrl(offset),
            onSuccess = { res ->
                // 过期响应直接丢弃：快切筛选时慢的旧请求后到会覆盖新结果
                if (seq != reqSeq) return@familyGet
                loadMoreBtn?.isEnabled = true

                val data = res.optJSONObject("data") ?: JSONObject()
                totalSessionCount = data.optInt("totalSessions", 0)
                moreAvailable = data.optBoolean("hasMore", false)

                if (resetPaging) {
                    loadedSessionCount = 0
                    sessions = ArrayList()
                }
                sessions.addAll(parseSessions(data))
                loadedSessionCount = sessions.size

                renderRecordingList()
            },
            onError = { err ->
                if (seq != reqSeq) return@familyGet
                loadMoreBtn?.isEnabled = true
                loadMoreBtn?.text = "加载更多"
                // 保留已有列表不清空：清空会让子女以为录音没了
                val e = view ?: return@familyGet
                if (!e.isShown) return@familyGet
                Toast.makeText(context, "录音列表获取失败：$err", Toast.LENGTH_SHORT).show()
            })
    }

    /** 拼接带筛选与分页的列表 URL。参数顺序固定，方便排查问题。 */
    private fun buildListUrl(offset: Int): String {
        val sb = StringBuilder("/api/recordings/list/${GuardConfig.boundElderId}")
        sb.append("?group=1")
        sb.append("&limit=").append(PAGE_SIZE)
        sb.append("&offset=").append(offset)
        sb.append("&range=").append(filterRange)
        sb.append("&level=").append(filterLevel)
        if (filterEvidence) sb.append("&evidence=1")
        if (filterQuery.isNotEmpty()) {
            sb.append("&q=").append(java.net.URLEncoder.encode(filterQuery, "UTF-8"))
        }
        return sb.toString()
    }

    /** 解析响应里的 sessions 数组 */
    private fun parseSessions(data: JSONObject): List<JSONObject> {
        val arr = data.optJSONArray("sessions") ?: return emptyList()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }
```

- [ ] **Step 5: 加 sessions 字段并重写渲染**

加字段（Step 1 附近）：

```kotlin
    /** 已加载的会话，「加载更多」是往这里追加而不是重新请求整页 */
    private var sessions = ArrayList<JSONObject>()
```

把现有的 `renderRecordings(data)`（第 288~321 行）替换为：

```kotlin
    /**
     * 渲染录音区。
     *
     * 与证据包分开刷新：两者刷新时机不同（录音有 WS 实时事件，证据包只有 30s 节流），
     * 合成一个方法会导致新录音到达时把证据包也重拉一遍。
     */
    private fun renderRecordingList() {
        val root = recordingContainer ?: return
        val empty = recordingEmptyView
        val noMatch = noMatchBox

        // 先释放上一轮的播放器，避免切换数据时还在播旧的
        playerCards.forEach { it.onDetachFromWindow() }
        playerCards.clear()
        root.removeAllViews()

        val hasAnyRecord = totalSessionCount > 0
        val isFiltered = filterQuery.isNotEmpty() || filterRange != "all" ||
                         filterLevel != "all" || filterEvidence

        // 三种空态必须区分清楚：本来就没录音 / 筛选后没结果 / 加载中
        when {
            sessions.isEmpty() && isFiltered -> {
                noMatch?.visibility = View.VISIBLE
                empty?.visibility = View.GONE
            }
            sessions.isEmpty() && !hasAnyRecord && !isFiltered -> {
                empty?.visibility = View.VISIBLE
                empty?.text = "暂无录音存证\n\n老人按下紧急求助，或进入你登记的敏感地点时，\n会自动录音并上传到这里。\n\n若老人端提示「录音待发送」，说明还在路上，稍等片刻或下拉刷新。"
                noMatch?.visibility = View.GONE
            }
            else -> {
                empty?.visibility = View.GONE
                noMatch?.visibility = View.GONE
            }
        }

        val fraudCount = totalFraudCount
        recordingHeader?.text = "🎙 环境录音存证（共 $totalRecordingCount 段" +
            (if (fraudCount > 0) "，其中 $fraudCount 段判定为诈骗）" else "）") +
            (if (isFiltered) "（已筛选，匹配 ${totalSessionCount} 次录音）" else "")

        for (s in sessions) {
            root.addView(buildSessionCard(s))
        }

        loadMoreBtn?.visibility = if (moreAvailable) View.VISIBLE else View.GONE
        loadMoreBtn?.text = "加载更多"
        loadMoreBtn?.isEnabled = true
    }
```

同时加两个统计字段（Step 1 附近），并在 Step 4 的 `onSuccess` 里赋值：

```kotlin
    /** 服务端返回的筛选后录音总数与诈骗数，标题要用 */
    private var totalRecordingCount = 0
    private var totalFraudCount = 0
```

在 `onSuccess` 的 `totalSessionCount = ...` 之后插入：

```kotlin
                totalRecordingCount = data.optInt("totalRecordings", 0)
                totalFraudCount = data.optInt("fraudCount", 0)
```

- [ ] **Step 6: buildSessionCard 保持不变**

现有 `buildSessionCard(session)`（第 324~385 行）不需要改 —— 它接收单个会话对象，与分页逻辑正交。

- [ ] **Step 7: WS 事件改为重置到第一页**

现有 WS listener 里（第 84~90 行）：

```kotlin
                "RECORDING_UPLOADED", "RECORDING_ANALYZED",
                "RECORDING_REVIEWED", "RECORDING_DELETED" -> {
                    // 清掉节流立刻重拉，让子女第一时间看到新证据
                    lastFetch = 0L
                    activity?.runOnUiThread { fetchRecordings(forceFetch = true) }
                }
```

改为：

```kotlin
                "RECORDING_UPLOADED", "RECORDING_ANALYZED",
                "RECORDING_REVIEWED", "RECORDING_DELETED" -> {
                    lastFetch = 0L
                    activity?.runOnUiThread {
                        // 新录音插到列表最前面。子女若已翻到第 3 页，不归零就永远
                        // 看不到它 —— 宁可打断他当前的位置，也不能让他错过新证据。
                        // 用 toast 说明是页面主动跳回，免得他以为界面自己乱了。
                        val wasPaging = loadedSessionCount > PAGE_SIZE
                        fetchRecordings(resetPaging = true, forceFetch = true)
                        if (wasPaging) {
                            Toast.makeText(context, "录音有更新，已回到最新列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
```

- [ ] **Step 8: onResume 不重置筛选**

现有 `onResume`（第 227~231 行）：

```kotlin
    override fun onResume() {
        super.onResume()
        fetchEvidence()
        fetchRecordings()
    }
```

改为：

```kotlin
    override fun onResume() {
        super.onResume()
        fetchEvidence()
        // 不重置筛选条件与已翻到的页数：子女翻历史时切去地图看一眼再回来，
        // 位置被冲掉很烦。首屏（sessions 为空）才拉第一页。
        if (sessions.isEmpty()) fetchRecordings(resetPaging = true)
    }
```

- [ ] **Step 9: onDestroyView 取消防抖回调**

现有 `onDestroyView`（第 95~102 行）中，在 `super.onDestroyView()` 之后插入：

```kotlin
        // 防抖回调留着会在 Fragment 销毁后再触发一次请求，泄漏 Activity 引用
        searchInput?.removeCallbacks(searchDebounce)
```

- [ ] **Step 10: 编译验证**

Run: `cd android; gradle compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: 提交**

```bash
git add android/app/src/main/java/com/antifraud/guard/family/EvidenceFragment.kt
git commit -m "feat: 录音列表默认只显示 3 次会话，支持搜索/时间/等级/证据筛选与加载更多"
```

---

## Task 7: 更新文档

**Files:**
- Modify: `docs/api-reference.md`

AGENT.MD 第 7 条铁律：改了代码要同一次提交改对应文档。

- [ ] **Step 1: 更新 `/list` 参数表**

`docs/api-reference.md` 里 `/api/recordings` 表格的 `GET /list/:elderId` 一行替换为：

```md
| GET | `/list/:elderId` | 录音列表（按会话分页）；`?group=1` 按会话聚合，`?limit`=会话数（默认 3，上限 20），`?offset`、`?q`=关键词、`?range`=7d/30d/all、`?level`=fraud/suspect/safe/untranscribed/all、`?evidence=1` 只看证据 | Token + 绑定校验 |
```

并在其后补一小节：

```md
### 录音列表的筛选与分页

分页单位是**会话**（一次连续录音），不是录音条数。`limit` 默认 3、`offset` 默认 0、`limit` 上限 20。

`level` 判定口径（服务端定死）：

| level | 条件 |
| --- | --- |
| `fraud` | `fraud_status = 'FRAUD'` |
| `suspect` | `fraud_status IN ('SUSPECT','FAILED')` |
| `safe` | `fraud_status = 'SAFE'` |
| `untranscribed` | `transcript_status IN ('SKIPPED','FAILED')` 或 `transcript IS NULL` |

`PENDING` / `ANALYZING`（研判在途）不进任何一档，只在 `all` 中可见。未识别的 `level` / `range` 静默回落到 `all`，非法 `limit` / `offset` 回落默认值，不报错。

`group=1` 时响应新增：`totalSessions`（筛选后总会话数，用于判断有无下一页）、`hasMore`、`appliedFilters`。`totalRecordings` 与 `fraudCount` 是筛选后的独立统计，不随翻页变化。
```

- [ ] **Step 2: 校对文档里已过时的描述**

同一文件里 `/api/recordings` 表格前的旧描述提到「≤200 条」，改为「默认 3 次会话（可筛选检索）」。

- [ ] **Step 3: 提交**

```bash
git add docs/api-reference.md
git commit -m "docs: 补录音列表的筛选与分页参数说明"
```

---

## Task 8: 端到端验收

- [ ] **Step 1: 跑全量测试**

Run: `npm test`
Expected: `# fail 0`，用例数 ≥ 164

- [ ] **Step 2: 打包 Release APK**

```powershell
.\build-apk.bat --nopause
```

Expected: `BUILD SUCCESS`，产物在 `dist\AntiFraudGuard-v1.7.2-release.apk`

**注意:** 必须带 `--nopause`，否则脚本末尾的 `pause` 会卡死会话。默认 Release（固定签名可覆盖升级保数据）。

- [ ] **Step 3: 验证新代码真进了包**

Gradle 的 UP-TO-DATE 不可信，必须读 APK 内 dex 搜本次新增的字符串常量：

```powershell
python -c "import zipfile;d=zipfile.ZipFile(r'dist\AntiFraudGuard-v1.7.2-release.apk');n=[x for x in d.namelist() if x.endswith('.dex')];b=b''.join(d.read(x) for x in n);print([s for s in ['搜索转写内容或地点','没有符合当前筛选条件的录音','清除筛选条件','加载更多'] if s.encode('utf-8') in b])"
```

Expected: 打印出全部 4 个字符串。若某个缺失，说明该改动没进包（插值文案编译后不留字面量，所以这 4 个都是必然保留的常量，可作标记物）。

- [ ] **Step 4: 真机验收清单**

装包后逐项确认：

| 检查项 | 期望 |
| --- | --- |
| 首屏顺序 | 守护对象信息 → 扣款流水 → 可疑通话 → 位置轨迹 → 分隔线 → 录音区 |
| 顶部按钮 | 只有「一键复制」，没有「打包下载」 |
| 录音区标题行 | 「🎙 环境录音存证（共 N 段…）」右侧有「打包下载」 |
| 首屏录音卡片 | 只显示 3 个会话 |
| 加载更多 | 点一次多 3 个会话，追加在下方，序号连续 |
| 搜索框 | 输入关键词，500ms 后列表刷新为匹配结果 |
| 时间筛选 | 点「近7天」→「近30天」→「全部时间」循环 |
| 等级筛选 | 点「全部等级」循环到「检出诈骗」→ 只剩诈骗录音 |
| 只看证据 | 开关生效，选中态变蓝底白字 |
| 筛选无结果 | 显示「没有符合当前筛选条件的录音」+「清除筛选条件」 |
| 清除筛选 | 搜索框清空 + 三个筛选回到默认 + 列表恢复 |
| 地点名 | 卡片标题不再出现坐标串 |
| 空转写 | 未转写的录音不显示「转写内容：null」 |
| 加载更多防重 | 连点按钮不会重复请求，按钮变灰 |
| 服务端 500 | 已有列表保留，toast 报错 |

- [ ] **Step 5: 提交文档验收记录**

```bash
git add -A
git commit -m "chore: 录音检索化功能验收通过"
```

若第 4 步发现需要修的问题，改完单独提交，不与本步混在一起。

---

## 已知风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| `recorded_at` 格式不一致 | 客户端上传的是 ISO（`2026-10-08T12:40:37.000Z`），DB 默认值是 `2026-10-08 12:40:37` | `buildListWhere` 用 `julianday()` 归一后再比，两种格式都能吃 |
| 筛选后 `totalSessions` 与 `totalRecordings` 不成比例 | 按地点检索可能命中多个会话的零散分段 | 两者各自独立 `COUNT`，不用一个推另一个 |
| `describePlace` 阻塞列表响应 | 要查地图 key，实测可到数秒 | 先用原值响应，`Promise.all` 补全后再响应；仅 `source === 'geo'` 写回库 |
| WS 打断子女翻页 | 新录音到达会归零到第一页 | 归零 + toast 说明；取舍是「保证新证据可见」优先 |
| `sessions.find()` 是 O(n²) | 会话数一页最多 20，实际无压力 | 若日后放宽上限，改为先建 Map |
| 分页 SQL 改写引入的参数错位 | sqlite3 参数数量不匹配会静默绑错值 | `buildDetailQuery` 的测试断言了 `?` 个数与 `params.length` 一致 |