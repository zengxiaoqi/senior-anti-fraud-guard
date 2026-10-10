/**
 * 时间格式化（对齐本地 services/timeFormat.js）。
 *
 * SQLite 的 CURRENT_TIMESTAMP 存 UTC 裸串；API 出口统一转北京时间
 * "YYYY-MM-DD HH:mm:ss"。客户端对时间字段一律原样显示、不做二次转换。
 *
 * Workers 注意：Intl.DateTimeFormat 带 timeZone 在 workerd 可用；
 * 输出用 en-GB 格式部件拼装，与本地实现逐字一致。
 */

export const TZ = 'Asia/Shanghai';

export const TIME_FIELDS = Object.freeze([
  'created_at', 'createdAt',
  'recorded_at', 'recordedAt', 'startedAt',
  'retention_until', 'retentionUntil',
  'reviewed_at', 'reviewedAt',
  'uploaded_at', 'uploadedAt'
]);

/** UTC 时间字符串/Date -> 北京时间 "YYYY-MM-DD HH:mm:ss"（无法解析时原样返回） */
export function toBeijing(input: unknown): unknown {
  if (!input) return input;
  let d: Date;
  if (input instanceof Date) {
    d = input;
  } else {
    const s = String(input);
    const hasTz = /Z$|[+-]\d{2}:?\d{2}$/.test(s);
    // SQLite "YYYY-MM-DD HH:mm:ss" 不带时区，按 UTC 解析
    d = new Date(hasTz ? s : s.replace(' ', 'T') + 'Z');
  }
  if (isNaN(d.getTime())) return input;

  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: TZ,
    hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).formatToParts(d);
  const p: Record<string, string> = {};
  for (const x of parts) p[x.type] = x.value;

  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/**
 * 批量转换一行里的所有时间字段（返回新对象）。
 * 缺失的字段不补 undefined 键（JSON.stringify 会把 undefined 变 null，客户端
 * optString 会读到字符串 "undefined"）。
 */
export function toBeijingRows<T extends Record<string, unknown>>(rows: T[] | null | undefined): T[] {
  return (rows ?? []).map((r) => {
    if (!r || typeof r !== 'object') return r;
    const out = { ...r };
    for (const f of TIME_FIELDS) {
      if (f in out) (out as Record<string, unknown>)[f] = toBeijing(out[f]);
    }
    return out;
  });
}
