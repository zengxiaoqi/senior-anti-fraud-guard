// 时间格式化工具
// SQLite 的 CURRENT_TIMESTAMP / DATETIME 默认存 UTC 时间，
// 老人端本机日志用的是本地时间，导致子女端看到的时间差 8 小时。
// 统一在服务端 API 出口转成北京时间 "YYYY-MM-DD HH:mm:ss"。

const TZ = 'Asia/Shanghai';

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

/** 对 db 查询结果行数组批量转换 created_at（返回新数组，不改原对象） */
function toBeijingRows(rows) {
  return (rows || []).map(r => ({ ...r, created_at: toBeijing(r.created_at) }));
}

module.exports = { toBeijing, toBeijingRows };
