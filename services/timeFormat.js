// 时间格式化工具
// SQLite 的 CURRENT_TIMESTAMP / DATETIME 默认存 UTC 时间，
// 老人端本机日志用的是本地时间，导致子女端看到的时间差 8 小时。
// 统一在服务端 API 出口转成北京时间 "YYYY-MM-DD HH:mm:ss"。

const TZ = 'Asia/Shanghai';

/**
 * 会在 API 出口泄漏给客户端的时间字段（camelCase 与 snake_case 两种命名都覆盖）。
 *
 * ## 为什么要有这个清单
 * 2026-10-08 出过一次真实事故：子女端「维权证据」页同一张卡片上，
 * 分组头显示 `08:28`（UTC 裸串）、条目显示 `16:28`（北京时间）。
 *
 * 根因不是某一行写错，而是**根本没有"哪些字段该被转换"的契约**：
 * `toBeijingRows` 只转 `created_at`，`recorded_at` / `retention_until` 原样输出，
 * 于是每个客户端都得自己判断要不要补时区 —— 谁忘了补就出错，而且不会有任何报错。
 *
 * 有了这份清单，"转哪些"由服务端一处决定，客户端一律原样显示，
 * 也就不会出现"两个页面同一个字段显示不同时间"这种问题。
 *
 * 客户端**不得**再对这些字段做二次时区转换（否则会多加 8 小时）。
 */
const TIME_FIELDS = Object.freeze([
  'created_at', 'createdAt',
  'recorded_at', 'recordedAt', 'startedAt',
  'retention_until', 'retentionUntil',
  'reviewed_at', 'reviewedAt',
  'uploaded_at', 'uploadedAt',
]);

/** UTC 时间字符串/Date -> 北京时间 "YYYY-MM-DD HH:mm:ss"（无法解析时原样返回） */
function toBeijing(input) {
  if (!input) return input;
  let d;
  if (input instanceof Date) {
    d = input;
  } else {
    const s = String(input);
    const hasTz = /Z$|[+-]\d{2}:?\d{2}$/.test(s);
    // SQLite "YYYY-MM-DD HH:mm:ss" 不带时区，按 UTC 解析
    d = new Date(hasTz ? s : s.replace(' ', 'T') + 'Z');
  }
  if (isNaN(d.getTime())) return input;

  const p = new Intl.DateTimeFormat('en-GB', {
    timeZone: TZ,
    hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).formatToParts(d).reduce((acc, x) => (acc[x.type] = x.value, acc), {});

  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/**
 * 批量转换一行里的所有时间字段（返回新对象，不改原对象）。
 *
 * 两个约束：
 *  1. 不就地修改入参 —— 调用方经常复用同一行做别的事，就地改会拿到脏数据。
 *  2. 缺失的字段**不补** undefined 键 —— 否则 `JSON.stringify` 会把它变成 null，
 *     客户端 `optString()` 会读到字符串 "undefined"。
 */
function toBeijingRows(rows) {
  return (rows || []).map((r) => {
    if (!r || typeof r !== 'object') return r;
    const out = { ...r };
    for (const f of TIME_FIELDS) {
      if (f in out) out[f] = toBeijing(out[f]);
    }
    return out;
  });
}

module.exports = { toBeijing, toBeijingRows, TIME_FIELDS, TZ };
