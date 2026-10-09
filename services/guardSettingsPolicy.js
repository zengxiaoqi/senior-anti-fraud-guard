/**
 * 守护规则的服务端策略：取值范围校验 + 子女端可写范围 + 增量合并。
 *
 * ## 为什么从 routes/auth.js 搬出来
 * 这些都是纯函数，但住在路由文件里意味着测试必须 `require('../routes/auth')`，
 * 而那会连带打开 database/db 的 SQLite 句柄 —— 测试进程凭空持有一个 db 文件，
 * 还可能在开发机已有的库上加锁。
 * 按 services/riskAlertPolicy.js / services/locationSensitivity.js 的既有形态，
 * 策略归 services/，路由只管 HTTP 与鉴权。
 */

// ──────────────────────────────────────────
//  取值范围（唯一权威）
// ──────────────────────────────────────────

/**
 * 每个规则字段的合法区间与默认值。
 *
 * 非法值一律丢弃用默认值，而不是静默收敛到边界 ——
 * 对规则参数来说，"用户填错了所以没生效"必须说得出口，
 * 而"我把它从 99999 悄悄改成 240，你以为设的是 99999"是最坏的一种。
 */
const GUARD_SETTING_BOUNDS = {
  callThresholdMinutes: { min: 1, max: 240, def: 15 },
  paymentThreshold:     { min: 1, max: 1000000, def: 500 },
  recordingMaxSegments: { min: 1, max: 6, def: 3 },
  recordingAutoUpload:  { bool: true, def: true },
  // Phase 1：位置阈值（1-10）与家基准（1-9，子女端显式设置后随配置通道下发）
  homeAwayRadiusMeters: { min: 100, max: 5000, def: 500 },
  stayMoveMeters:       { min: 20, max: 1000, def: 100 },
  homeStayMinutes:      { min: 5, max: 240, def: 40 },
  homeLat:              { min: -90, max: 90 },
  homeLng:              { min: -180, max: 180 }
};

/**
 * 逐项做类型与范围校验，非法值一律丢弃。
 *
 * 注意 homeLat/homeLng 没有 `def`：0/0 表示"未设置"（GuardConfig.hasFamilyHome
 * 也是这个口径），不能塞一个默认值进去 —— 那会把家基准设到几内亚湾。
 *
 * 归一化防护：老人端/子女端某版本可能送来 `key: "abc"`，
 * `Math.min/max` 与字符串比较会得出 NaN 而静默写进库。
 */
function sanitizeGuardSettings(input) {
  const out = {};
  if (!input || typeof input !== 'object') return out;

  // 字符串型配置（1-5 信任列表 / 1-1 高危 App 远程规则）：只做类型与长度校验，
  // 内容原样透传 —— 客户端解析失败有自己的兜底，不在传输层做深度解析卡死
  for (const key of ['trustedCallNumbersJson', 'highRiskPackages']) {
    const v = input[key];
    if (typeof v === 'string' && v.length <= 20000 && v.trimStart().startsWith('[')) {
      out[key] = v;
    }
  }

  for (const [key, bound] of Object.entries(GUARD_SETTING_BOUNDS)) {
    if (input[key] === undefined || input[key] === null) continue;
    if (bound.bool) {
      if (typeof input[key] === 'boolean') out[key] = input[key];
      continue;
    }
    const num = Number(input[key]);
    if (!Number.isFinite(num)) continue;
    out[key] = Math.min(bound.max, Math.max(bound.min, num));
  }
  return out;
}

/**
 * 读取"清除家基准"指令。
 *
 * ## 必须在 sanitizeGuardSettings 之前调用
 * sanitize 只处理 GUARD_SETTING_BOUNDS 里列出的键，homeCleared 不在其中，
 * 会被静默丢弃。而"不下发 homeLat 就保留旧值"正是增量合并的默认行为 ——
 * 也就是说用户点了清除，服务端会当作什么都没发生，且界面上看不出异常。
 *
 * 严格用 `=== true`：清除是不可撤销动作，宁可不清除也不能被脏数据误触发。
 */
function extractHomeCleared(rawSettings) {
  return !!rawSettings && rawSettings.homeCleared === true;
}

// ──────────────────────────────────────────
//  子女端可写范围
// ──────────────────────────────────────────

/**
 * 子女端可写字段。顺序按页面分区排列，便于核对是否遗漏。
 *
 * ## 为什么是白名单而不是黑名单
 * 黑名单在字段变多之后必然出现"新加的字段忘了加进黑名单 → 意外放行"。
 * 白名单的失败方向是反的：新字段默认不可写，要开放必须显式改这里。
 *
 * ## 两个刻意排除的字段
 *
 * `trustedCallNumbersJson` —— 语义是"老人信任谁"（免打扰白名单）。
 * 让子女用自己的社交关系替老人做防诈白名单决策是错的：
 * 信任链恰恰是诈骗话术主要利用的东西（"我是你儿子"），
 * 子女通讯录里的人不等于老人在生活里信任的人。
 *
 * `highRiskPackages` —— 改它会直接改变老人端的告警面。
 * 把某个包从高危表移除，对应类型的告警就此静默消失，
 * 而界面上没有任何东西提示这件事发生过。这是运维级动作，
 * 误操作后果是漏报，不适合和"调一下离家半径"放在同一个页面上。
 */
const FAMILY_WRITABLE_SETTINGS = [
  // 位置守护阈值（1-10）
  'homeAwayRadiusMeters',
  'stayMoveMeters',
  'homeStayMinutes',
  // 家基准（1-9）
  'homeLat',
  'homeLng',
  // 通话与支付
  'callThresholdMinutes',
  'paymentThreshold',
  // 录音存证
  'recordingAutoUpload',
  'recordingMaxSegments',
  'recordingSegmentMinutes'
];

/**
 * 保留白名单内的键，其余全部丢弃。
 *
 * 入参应当已经过 [sanitizeGuardSettings]（类型与范围校验）。
 * 这里只做"哪些键允许存在"的判定，不重复校验范围 ——
 * 两处各写一套 clamp 规则必然会漂。
 *
 * @param {object} settings 已 sanitize 的配置
 * @returns {object} 新对象，只含白名单内的键
 */
function pickFamilyWritable(settings) {
  const out = {};
  if (!settings || typeof settings !== 'object') return out;
  for (const key of FAMILY_WRITABLE_SETTINGS) {
    if (Object.prototype.hasOwnProperty.call(settings, key)) {
      out[key] = settings[key];
    }
  }
  return out;
}

// ──────────────────────────────────────────
//  合并
// ──────────────────────────────────────────

/**
 * 把子女端提交的字段增量合并到已存的配置上。
 *
 * 增量而非整体覆盖：老人端也会写同一份配置（换机后恢复规则、
 * 改资料后顺带上行一次），整体覆盖会把对方刚改的项无声冲掉。
 *
 * `homeCleared` 放在合并**之后**处理，保证"清除"永远赢：
 * 否则同一请求里若既传了 homeCleared 又传了新坐标，
 * 行为会依赖 Object.assign 的键序，成为一类无法复现的偶发 bug。
 *
 * @param {object} stored  服务端已存的 guard_settings
 * @param {object} incoming 已 sanitize 且已过白名单的提交内容
 * @param {boolean} homeCleared 是否请求清除家基准
 * @returns {object} 新对象，可安全写库
 */
function mergeFamilySettings(stored, incoming, homeCleared) {
  const merged = Object.assign({}, stored || {}, incoming || {});
  if (homeCleared) {
    delete merged.homeLat;
    delete merged.homeLng;
  }
  return merged;
}

module.exports = {
  GUARD_SETTING_BOUNDS,
  sanitizeGuardSettings,
  extractHomeCleared,
  FAMILY_WRITABLE_SETTINGS,
  pickFamilyWritable,
  mergeFamilySettings
};
