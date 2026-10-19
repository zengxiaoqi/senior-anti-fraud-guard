/**
 * 风险事件统一文案与格式化工具（小程序子女端）。
 *
 * 与 Android 端 util/EventLabels.kt 同源移植，规则必须保持一致：
 *  - eventName：事件类型 → 可读文案（含 emoji 前缀）
 *  - formatDetails：details 键值对 → 一行"人话"
 *    （拦坐标串/包名/超长值/内部诊断字段——这些对读者毫无意义）
 *  - shortLocalTime：时间戳 → "今天 12:59" / "昨天 18:44" / "10-08 12:59"
 *
 * 服务端下发两种时间格式（见 workers/src/lib/timeFormat.ts）：
 *  1) ISO UTC：      "2026-10-10T04:59:00.000Z"
 *  2) 北京时间裸串： "2026-10-10 12:59:00"
 * 两种都必须能解析（Android 端曾因只解析 ISO 格式把整页时间过滤丢空的教训）。
 */

function eventName(type) {
  const map = {
    'SOS': '🆘 一键紧急求助',
    'FALL_RISK': '🚑 检测到疑似跌倒',
    'ELDER_OFFLINE': '📴 老人已失联',
    'ELDER_SILENT': '🔕 长时间无上报',
    'ELDER_RECONNECTED': '✅ 已恢复上报',
    'GEOFENCE_RECORDING': '📍 进入敏感地点（自动录音）',
    'GEOFENCE_EXIT': '📍 离开敏感地点（录音停止）',
    'GEOFENCE_DWELL': '⏱ 敏感地点停留过久',
    'LOCATION_RISK': '📍 敏感地点停留',
    'LOCATION_UPDATE': '📍 位置更新',
    'PAYMENT_RISK': '💳 大额支付预警',
    'CALL_RISK': '📞 异常通话预警',
    'COERCION_RISK': '🚨 通话中被诱导操作',
    'CALL_STAT': '📞 通话记录',
    'REMOTE_INTERRUPT': '🚨 远程打断',
    'DEVICE_ONLINE': '📶 设备上线',
    'APP_CRASH': '🛠️ 老人端应用异常退出',
    'PING_TEST': '🔍 连接测试'
  };
  return map[type] || type;
}

function severityName(sev) {
  if (sev === 'HIGH') return '高危';
  if (sev === 'MEDIUM') return '中危';
  return '低危';
}

/** 内部诊断/重复/不可读字段：对读者没有价值，只在原始记录里保留 */
const DETAIL_SKIP = new Set([
  'location_name', 'event_desc', 'latitude', 'longitude', 'lat', 'lng',
  'trigger', 'device', 'impact_at', 'timestamp', 'from', 'window_hours',
  'raw_text', 'stack', 'file', 'geofence_id', 'dwell_minutes_threshold',
  // source 在扣款事件里是包名（已被包名规则拦掉），在崩溃事件里是 "CrashReporter"
  'source'
]);

function looksLikeCoord(s) {
  return /\d+\.\d+\s*[,，]\s*\d+\.\d+/.test(s);
}

/** 包名形态：com.eg.android.AlipayGphone。无空格且至少三段点分小写 */
function looksLikePackage(s) {
  return /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$/.test(s);
}

/** details 键 → 中文标签；空串 = 值本身自解释 */
function detailLabel(k) {
  const map = {
    'address': '位置',
    'amount': '金额',
    'payee_name': '收款方',
    'payee_account': '卡号',
    'order_no': '订单号',
    'peak_magnitude': '撞击峰值(m/s²)',
    'foreground_app': '前台应用',
    'call_number': '来电号码',
    'caller_number': '来电号码',
    'duration_so_far_min': '已通话',
    'duration_minutes': '已持续',
    'stay_minutes': '已持续',
    'threshold_minutes': '已持续',
    'duration_sec': '通话',
    'peak_risk': '峰值风险',
    'is_stranger': '陌生号码',
    'calls_in_2h': '两小时来电',
    'geofence_name': '敏感地点',
    'note': ''
  };
  return map[k] !== undefined ? map[k] : k;
}

/** 单个 details 键 → 人话短语；返回空串表示这条对读者没有意义，省略 */
function detailValue(k, v) {
  switch (k) {
    case 'elder_confirmed':
      return v === 'true' ? '老人已确认平安' : '老人未响应确认';
    case 'is_away_from_home':
      return v === 'true' ? '已离家外出' : '在家附近';
    // 布尔三态不能直接打印 true/false：老人读到的会是"陌生号码 true"
    case 'is_stranger':
      if (v === 'true') return '陌生号码';
      if (v === 'false') return '熟悉号码';
      return '号码来源不明';
    case 'coercion_reported':
      return v === 'true' ? '通话中疑似被诱导操作' : '';
    case 'amount':
      return '金额 ' + v + ' 元';
    case 'calls_in_2h':
      return '两小时内来电 ' + v + ' 次';
    case 'duration_sec':
      return '通话 ' + v + ' 秒';
    case 'geofence_name':
      return '敏感地点 ' + v;
    // 通话中切到远程控制/支付 App 是"被诱导操作"最硬的证据，必须说清楚
    case 'coercion_app':
      return '通话中打开了 ' + v;
    // 阈值不能也叫"已持续"：否则列表行会出现"已持续 25 分钟；已持续 20 分钟"
    case 'threshold_minutes':
      return '超过 ' + v + ' 分钟会提醒';
    case 'title':
    case 'message':
    case 'note':
      return v;
    default: {
      const label = detailLabel(k);
      if (label === k) return label + ' ' + v;
      if (k.endsWith('_min') || k.endsWith('_minutes')) return label + ' ' + v + ' 分钟';
      return label + ' ' + v;
    }
  }
}

/**
 * 事件详情 → 一行人话。
 * 服务端会对带坐标的告警异步补 location_name（真名）；优先展示它，
 * 坐标原始值/包名/超长值不进列表行（与 Android formatDetails 同规则）。
 */
function formatDetails(details) {
  if (!details) return '';
  if (typeof details === 'string') return details;

  const parts = [];
  const locName = String(details.location_name || '').trim();
  if (locName) parts.push('📍 ' + locName);

  const desc = String(details.event_desc || '').trim();
  if (desc) parts.push(desc);

  const keys = Object.keys(details);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    if (DETAIL_SKIP.has(k)) continue;
    const raw = details[k];
    if (raw === null || raw === undefined) continue;
    if (typeof raw === 'object') continue;
    const v = String(raw).trim();
    if (!v || v === 'null') continue;
    // 三类一律不进列表行：坐标串 / 包名 / 超长值（通知原文、崩溃堆栈）
    if (looksLikeCoord(v) || looksLikePackage(v) || v.length > 60) continue;
    const text = detailValue(k, v);
    if (text) parts.push(text);
  }
  return parts.join('；');
}

/** 时间解析：兼容 ISO UTC 与北京时间裸串，失败返回 null */
function parseTime(rawStr) {
  const raw = String(rawStr || '');
  if (!raw) return null;
  if (raw.indexOf('T') !== -1) {
    // ISO：无时区标记按 UTC 处理（铁律：Z 判断在截小数点之前）
    const normalized = (raw.endsWith('Z') || raw.indexOf('+') !== -1)
      ? raw
      : raw.split('.')[0] + 'Z';
    const d = new Date(normalized);
    return isNaN(d.getTime()) ? null : d;
  }
  // 空格分隔 = 子女端北京时间裸串，按东八区解析回正确的时间点
  const m = raw.substring(0, 19).match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
  if (!m) return null;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4] - 8, +m[5], +m[6]));
  return isNaN(d.getTime()) ? null : d;
}

function pad2(n) { return n < 10 ? '0' + n : '' + n; }

/** 本机时区的"今日零点"毫秒值（与 Android todayStartMillis 同规则）。
 *  不要用 now % 86400000 算：那是 UTC 日边界，东八区会整体偏 8 小时。 */
function todayStartMillis(now) {
  const d = now ? new Date(now) : new Date();
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六'];

/** 时间串 → 列表分组头：今天 / 昨天 / "10月6日 周一"（与 Android dayHeader 同规则） */
function dayHeader(raw) {
  const date = parseTime(raw);
  if (!date) return '';
  const dayDiff = Math.round((todayStartMillis() - todayStartMillis(date.getTime())) / 86400000);
  if (dayDiff === 0) return '今天';
  if (dayDiff === 1) return '昨天';
  return (date.getMonth() + 1) + '月' + date.getDate() + '日 周' + WEEKDAYS[date.getDay()];
}

/** 两事件是否同类同级（连续同类合并 ×N 的判据，与 Android sameKind 完全一致） */
function sameKind(a, b) {
  return !!a && !!b && a.event_type === b.event_type && a.severity === b.severity;
}

/** 时间字符串 → "今天 12:59" / "昨天 18:44" / "MM-dd HH:mm" / "yyyy-MM-dd" */
function shortLocalTime(iso) {
  const date = parseTime(iso);
  if (!date) return '';
  const hourStr = pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const dayDiff = Math.round((startOfDay(new Date()) - startOfDay(date)) / 86400000);
  if (dayDiff === 0) return '今天 ' + hourStr;
  if (dayDiff === 1) return '昨天 ' + hourStr;
  if (dayDiff >= 2 && dayDiff <= 30) {
    return pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) + ' ' + hourStr;
  }
  return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
}

module.exports = {
  eventName,
  severityName,
  formatDetails,
  shortLocalTime,
  parseTime,
  dayHeader,
  todayStartMillis,
  sameKind
};
