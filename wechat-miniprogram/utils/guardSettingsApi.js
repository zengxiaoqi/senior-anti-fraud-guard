// 子女端「守护设置」共用层：API 读写 + 取值范围 + 档位定义 + 入口页摘要。
//
// 与服务端 services/guardSettingsPolicy.js 的 GUARD_SETTING_BOUNDS / FAMILY_WRITABLE_SETTINGS
// 一一对应，与 Android 端 GuardSettingBounds / family/GuardRuleSubActivity 同一套口径。
// 改任何边界或档位值，四处（本文件 / services/guardSettingsPolicy.js /
// workers/src 同名策略 / Android GuardSettingBounds）要一起改。
//
// API 契约（双后端一致）：
//   GET  /api/auth/elder-settings/family/:elderId → { success, settings }
//   POST /api/auth/elder-settings/family/:elderId  body { settings: 部分字段 } → { success, settings: merged }
//   服务端是增量合并（Object.assign(存量, 增量)），所以每页只提交自己的字段是安全的；
//   清除家基准传 settings.homeCleared = true（服务端在 sanitize 之前从 settings 里取）。

const API_BASE = '/api/auth/elder-settings/family/';

// ──────────────────────────────────────────
//  取值范围（与服务端 GUARD_SETTING_BOUNDS 逐字对齐）
// ──────────────────────────────────────────

const BOUNDS = {
  callThresholdMinutes:   { min: 1, max: 240, def: 15 },
  paymentThreshold:       { min: 1, max: 1000000, def: 500 },
  recordingMaxSegments:   { min: 1, max: 6, def: 3 },
  recordingSegmentMinutes:{ min: 1, max: 10, def: 5 },
  homeAwayRadiusMeters:   { min: 100, max: 5000, def: 500 },
  stayMoveMeters:         { min: 20, max: 1000, def: 100 },
  homeStayMinutes:        { min: 5, max: 240, def: 40 },
  fallFreefallMinMs:      { min: 100, max: 400, def: 150 }
};

// ──────────────────────────────────────────
//  档位定义（与 Android 端各 RuleActivity 逐字对齐）
// ──────────────────────────────────────────

// 位置档位：离家半径 / 停留判定半径 / 停留时长
const LOCATION_PRESETS = [
  { label: '宽松', away: 2000, stayMove: 500, minutes: 60 },
  { label: '标准', away: 500,  stayMove: 100, minutes: 40 },
  { label: '严格', away: 200,  stayMove: 50,  minutes: 15 }
];

// 通话/支付档位：通话分钟 / 支付金额
const CALLPAY_PRESETS = [
  { label: '宽松', call: 30, pay: 2000 },
  { label: '标准', call: 15, pay: 500 },
  { label: '严格', call: 5,  pay: 100 }
];

// 跌倒检测三档：失重（自由落体）段最短时长
// 120≈7cm / 150≈11cm（默认，与老人端 FallDetectionPolicy 一致）/ 250≈30cm
const FALL_LEVELS = [
  { label: '灵敏', ms: 120, desc: '最容易检出摔倒（手机揣裤兜、滑兜式跌倒也接得住），代价是轻放手机更容易误报' },
  { label: '标准', ms: 150, desc: '推荐。手机从 11 厘米以上掉落才算摔，玩手机放下、随手轻放都不会触发' },
  { label: '保守', ms: 250, desc: '几乎零误报（30 厘米以上掉落才算摔），适合老人习惯把手机放手边/桌上的情况' }
];

const FALL_DEFAULT_MS = 150;

// ──────────────────────────────────────────
//  API
// ──────────────────────────────────────────

/** 读取当前老人的守护配置。elderId 无效时按"未绑定"回调 null */
function fetchSettings(elderId, onSuccess, onError) {
  const app = getApp();
  if (!elderId || elderId <= 0) { onSuccess(null); return; }
  app.apiRequest({
    url: `${app.globalData.serverHost}${API_BASE}${elderId}`,
    timeout: 10000,
    success: (res) => {
      if (res.data && res.data.success) onSuccess(res.data.settings || {});
      else onError((res.data && res.data.error) || `HTTP ${res.statusCode}`);
    },
    fail: () => onError('网络异常，请重试')
  });
}

/**
 * 增量保存。只提交传入的字段，服务端合并后回传全量 merged。
 * opts.homeCleared = true 时额外携带清除家基准指令（服务端删除 homeLat/homeLng）。
 */
function saveSettings(elderId, partial, opts, onSuccess, onError) {
  const o = opts || {};
  const app = getApp();
  if (!elderId || elderId <= 0) {
    onError('尚未绑定守护对象，改动无法保存');
    return;
  }
  const settings = Object.assign({}, partial || {});
  if (o.homeCleared) settings.homeCleared = true;
  app.apiRequest({
    url: `${app.globalData.serverHost}${API_BASE}${elderId}`,
    method: 'POST',
    data: { settings },
    timeout: 10000,
    success: (res) => {
      if (res.data && res.data.success) onSuccess(res.data.settings || {});
      else onError((res.data && res.data.error) || `HTTP ${res.statusCode}`);
    },
    fail: () => onError('网络异常，请重试')
  });
}

// ──────────────────────────────────────────
//  数值工具
// ──────────────────────────────────────────

/** 按服务端同款规则收敛；非法输入返回 null */
function clampInt(key, v) {
  const b = BOUNDS[key];
  if (!b) return null;
  const n = Math.round(Number(v));
  if (!isFinite(n)) return null;
  return Math.min(b.max, Math.max(b.min, n));
}

/** 支付金额：允许小数，范围 1~1000000 */
function clampMoney(v) {
  const n = Number(v);
  if (!isFinite(n)) return null;
  return Math.min(1000000, Math.max(1, n));
}

/**
 * "已按范围收敛：通话时长 300 → 240" 这类补充告知。
 * 静默 clamp 会让人以为设了 300、实际按 240 守护，然后以为系统有 bug。
 * pairs: [label, 原值, 收敛后值]，只列发生变化的项。
 */
function clampNote(pairs) {
  const changed = (pairs || []).filter((p) => p && p[1] !== p[2]);
  if (!changed.length) return '';
  return '已按范围收敛：' + changed.map((p) => `${p[0]} ${p[1]} → ${p[2]}`).join('、');
}

/** 服务端没配过的字段返回空串（让用户看得见"还没设过"），而不是拿默认值填表 */
function posStr(settings, key) {
  const n = Number((settings || {})[key]);
  return isFinite(n) && n > 0 ? String(n) : '';
}

/** 家基准是否已设置（0/0 表示未设置，与服务端 hasFamilyHome 同口径） */
function hasHomeBase(settings) {
  const lat = Number((settings || {}).homeLat);
  const lng = Number((settings || {}).homeLng);
  return isFinite(lat) && isFinite(lng) && lat !== 0 && lng !== 0;
}

/** 2000.0 显示成 "2000"——金额里出现小数点会让人怀疑填错了 */
function trimMoney(v) {
  const n = Number(v);
  if (!isFinite(n)) return '';
  return n === Math.round(n) ? String(Math.round(n)) : String(n);
}

// ──────────────────────────────────────────
//  入口页摘要（与 Android ElderGuardSettingsActivity.render 同口径）
// ──────────────────────────────────────────

function buildSummaries(s) {
  const unset = { summary: '未设置', warn: false };

  // 家基准：未设置是要紧事，用警示黄
  const home = hasHomeBase(s)
    ? { summary: `已设置：${Number(s.homeLat).toFixed(6)}, ${Number(s.homeLng).toFixed(6)}`, warn: false }
    : { summary: '⚠️ 未设置，离家判定可能失真', warn: true };

  const away = Number(s.homeAwayRadiusMeters) > 0 ? Number(s.homeAwayRadiusMeters) : 0;
  const stayMin = Number(s.homeStayMinutes) > 0 ? Number(s.homeStayMinutes) : 0;
  const location = away && stayMin
    ? { summary: `离家超过 ${away} 米 · 停留超过 ${stayMin} 分钟`, warn: false }
    : unset;

  const call = Number(s.callThresholdMinutes) > 0 ? Number(s.callThresholdMinutes) : 0;
  const pay = Number(s.paymentThreshold) > 0 ? Number(s.paymentThreshold) : 0;
  let callpay;
  if (call && pay) {
    callpay = { summary: `通话超过 ${call} 分钟 · 单笔超 ${trimMoney(pay)} 元`, warn: false };
  } else if (call) {
    callpay = { summary: `通话超过 ${call} 分钟 · 支付金额未设置`, warn: false };
  } else if (pay) {
    callpay = { summary: `通话时长未设置 · 单笔超 ${trimMoney(pay)} 元`, warn: false };
  } else {
    callpay = unset;
  }

  const seg = Number(s.recordingMaxSegments) > 0 ? Number(s.recordingMaxSegments) : 0;
  const per = Number(s.recordingSegmentMinutes) > 0 ? Number(s.recordingSegmentMinutes) : 0;
  const auto = s.recordingAutoUpload === undefined ? true : !!s.recordingAutoUpload;
  const recording = seg && per
    ? { summary: `${seg} 段 × ${per} 分钟 ≈ ${seg * per} 分钟 · ${auto ? '自动上传已开' : '仅存本地'}`, warn: false }
    : unset;

  const ms = Number(s.fallFreefallMinMs);
  let fall;
  if (!isFinite(ms) || ms < 100 || ms > 400) {
    fall = unset;
  } else if (ms === 120) {
    fall = { summary: '灵敏 · 手机揣裤兜滑落也能检出，轻放更易误报', warn: false };
  } else if (ms === 150) {
    fall = { summary: '标准（推荐）· 从 11 厘米以上掉落才算摔', warn: false };
  } else {
    fall = { summary: '保守 · 从 30 厘米以上掉落才算摔，几乎零误报', warn: false };
  }

  const adskip = s.adSkipEnabled
    ? { summary: '已开启 · 自动点「跳过」按钮（需老人手机授权无障碍）', warn: false }
    : { summary: '未开启', warn: false };

  return { home, location, callpay, recording, fall, adskip };
}

module.exports = {
  BOUNDS,
  LOCATION_PRESETS,
  CALLPAY_PRESETS,
  FALL_LEVELS,
  FALL_DEFAULT_MS,
  fetchSettings,
  saveSettings,
  clampInt,
  clampMoney,
  clampNote,
  posStr,
  hasHomeBase,
  trimMoney,
  buildSummaries
};
