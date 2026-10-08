# 子女端守护设置页 + R6 告警等级修正 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让子女端成为守护规则的唯一写入方（补齐路线图 1-9 家基准的生产端、1-10 缺失的 UI 入口），并把「使用情况访问」缺失的告警等级从 ℹ️ 备用改为 ⚠️ 降级（R6）。

**Architecture:** 服务端新增一对带鉴权的子女端路由 `/api/auth/elder-settings/family/:elderId`，字段白名单限制子女可写的范围；Android 子女端新增 `ElderGuardSettingsActivity`；老人端设置页的规则输入框改为只读；配置下发走 WS 推送 + 30 分钟拉取兜底，两条路径都必须触发 `LocationGuardService.resetHomeBase()`。

**Tech Stack:** Node.js + Express + `node:test`（零新增依赖）；Android Kotlin + `org.json` + JUnit4 JVM 测试（`android/app/src/test/`）。

**设计文档:** `docs/superpowers/specs/2026-10-08-guardian-settings-and-r6-design.md`

---

## 背景：三个被掩盖的问题

动手前必须理解这三件事，否则会写出"看起来对但没生效"的代码。

### 一、1-9 的家基准只有消费端

`LocationGuardService.kt:225-236` 读 `GuardConfig.hasFamilyHome`，`GuardConfig.kt:360-371` 缓存坐标，服务端 `routes/auth.js:99-100` 有 bounds 校验 —— 但**全工程没有任何代码写 `homeLat`/`homeLng`**。子女端只能手调 `POST /api/elder-settings`。

`LocationGuardService.kt:452` 的一次性通知写着「如有偏差，请在子女端设置中修正」，而那个页面不存在。

### 二、1-10 的「可配置」从未落地

`activity_settings.xml` 的输入框只有 `et_call_threshold` / `et_payment_threshold` / `et_rec_segments` / `et_rec_segment_minutes`。

`homeAwayRadiusMeters` / `stayMoveMeters` / `homeStayMinutes` 在 Android UI 层零出现。`GuardConfig.kt:397-399` 只是把这三个**代码默认值**推到云端。这三项至今没人能改。

### 三、`homeSet` 是一次性闩锁

```kotlin
// LocationGuardService.kt:225-236
if (!homeSet) {
    if (GuardConfig.hasFamilyHome) { homeLat = ...; homeLng = ... }
    else { homeLat = lat; homeLng = lng; notifyHomeBaseSet(lat, lng) }
    homeSet = true
    stayStartTime = System.currentTimeMillis()
}
```

`homeSet` 置 true 后永不回退。云端家基准改了之后，内存里的坐标永远停在旧值，**改设置完全无效且无任何报错**。只做下发而不修这里，1-9 依然是坏的。

---

## 文件结构

### 新建

| 文件 | 职责 |
|---|---|
| `services/guardSettingsPolicy.js` | 纯函数：`FAMILY_WRITABLE_SETTINGS` 白名单、`pickFamilyWritable`、`mergeFamilySettings`。不依赖 express，可被测试直接 require |
| `tests/guardSettings.test.js` | 上述纯函数的单测 |
| `android/.../util/GuardSettingBounds.kt` | 客户端 bounds 唯一来源，所有 clamp 从这里读（纯逻辑，JVM 可测） |
| `android/.../util/OneShotLocation.kt` | 一次性定位：从 `GeofenceManageActivity` 抽出，两处共用 |
| `android/.../family/ElderGuardSettingsActivity.kt` | 子女端守护设置页 |
| `android/.../res/layout/activity_elder_guard_settings.xml` | 该页布局 |
| `android/.../location/HomeBasePolicy.kt` | 纯函数：判定是否需要重置家基准 |
| `android/app/src/test/.../util/GuardSettingBoundsTest.kt` | bounds 单测 |
| `android/app/src/test/.../location/HomeBasePolicyTest.kt` | 重置判定单测 |

### 修改

| 文件 | 改动 |
|---|---|
| `routes/auth.js` | 新增 family 路由 + `setHub` + `homeCleared` 处理 |
| `server.js` | 注入 auth hub，推 `ELDER_SETTINGS_UPDATED` |
| `android/.../api/ApiClient.kt` | 新增 `fetchFamilyElderSettings` / `pushFamilyElderSettings` |
| `android/.../family/DashboardFragment.kt` + `fragment_family_dashboard.xml` | 新增「守护设置」入口 |
| `android/.../family/GeofenceManageActivity.kt` | 改用 `OneShotLocation` |
| `android/.../AndroidManifest.xml` | 注册 `ElderGuardSettingsActivity` |
| `android/.../service/GuardWebSocketManager.kt` | 处理 `ELDER_SETTINGS_UPDATED` |
| `android/.../service/LocationGuardService.kt` | `resetHomeBase()` + 拉取兜底 |
| `android/.../SettingsActivity.kt` + `activity_settings.xml` | 规则改只读、R6 告警等级、过期文案 |
| `android/.../service/CallRiskWatcher.kt` | 过期注释 |
| `docs/superpowers/plans/2026-10-07-hardening-roadmap.md` | 回写状态 |

---

## 构建与测试命令

**Node 测试**（工作目录为仓库根）:
```
npm test
```
期望：`# pass` 大于等于现有 143，且 `# fail 0`。

**Android 编译**（PowerShell，工作目录为仓库根）:
```
$env:JAVA_HOME="D:\Program Files\Java\jdk-21.0.10"
$env:ANDROID_HOME="D:\Android"
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
期望末行 `BUILD SUCCESSFUL`。

**Android JVM 测试**:
```
$env:JAVA_HOME="D:\Program Files\Java\jdk-21.0.10"
$env:ANDROID_HOME="D:\Android"
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain
```

> ⚠️ **禁止用 PowerShell 的 `Get-Content` / `Set-Content` 处理中文源码。** 路线图 S6 记录了这个坑：PowerShell 5.1 的 `Get-Content -Raw` + `WriteAllText` 把 `GuardKeepAliveScheduler.kt` 的 UTF-8 写坏了。编辑文件一律用编辑工具。

---

## Task 1: 服务端策略模块（bounds + sanitize + 子女白名单 + 合并）

**Files:**
- Create: `services/guardSettingsPolicy.js`
- Test: `tests/guardSettings.test.js`

> **为什么把 bounds/sanitize 从 `routes/auth.js` 搬出来**
> `sanitizeGuardSettings` 与 `GUARD_SETTING_BOUNDS` 是纯函数，但当前住在 `routes/auth.js` 里。
> 测试要 `require('../routes/auth')` 就得连 `database/db`、真的打开 SQLite 文件 ——
> 测试进程里凭空产生一个 db 句柄，还可能在开发机已有 db 上加锁。
> 按 `services/riskAlertPolicy.js` / `services/locationSensitivity.js` 的既有形态，
> 策略逻辑归 `services/`，路由只做 HTTP 与鉴权。
>
> 安全性核查：`sanitizeGuardSettings` 的唯一调用点是 `routes/auth.js:639`，
> `GUARD_SETTING_BOUNDS` 只被 `sanitizeGuardSettings` 使用，搬走无其他影响。

- [ ] **Step 1: 写失败测试**

创建 `tests/guardSettings.test.js`：

```javascript
const test = require('node:test');
const assert = require('node:assert');
const {
  GUARD_SETTING_BOUNDS,
  sanitizeGuardSettings,
  extractHomeCleared,
  FAMILY_WRITABLE_SETTINGS,
  pickFamilyWritable,
  mergeFamilySettings
} = require('../services/guardSettingsPolicy');

test('白名单放行全部守护规则字段', () => {
  const expected = [
    'callThresholdMinutes', 'paymentThreshold',
    'recordingMaxSegments', 'recordingSegmentMinutes', 'recordingAutoUpload',
    'homeLat', 'homeLng',
    'homeAwayRadiusMeters', 'stayMoveMeters', 'homeStayMinutes'
  ];
  assert.deepStrictEqual(FAMILY_WRITABLE_SETTINGS.slice().sort(), expected.slice().sort());
});

test('信任号码白名单对子女端关闭（信任链是诈骗话术利用的东西）', () => {
  const out = pickFamilyWritable({ trustedCallNumbersJson: '["13800138000"]' });
  assert.strictEqual(out.trustedCallNumbersJson, undefined);
});

test('高危应用规则白名单对子女端关闭（改了会静默缩小告警面）', () => {
  const out = pickFamilyWritable({ highRiskPackages: '[{"pkg":"com.a","level":"HIGH"}]' });
  assert.strictEqual(out.highRiskPackages, undefined);
});

test('白名单外的未知字段一律丢弃', () => {
  const out = pickFamilyWritable({ callThresholdMinutes: 20, evilKey: 'rm -rf /' });
  assert.strictEqual(out.callThresholdMinutes, 20);
  assert.strictEqual(out.evilKey, undefined);
});

test('白名单外的字段不会因为值合法而被放行', () => {
  // 反向断言：确保实现是白名单而非黑名单
  const out = pickFamilyWritable({ guard_settings: '{"x":1}', role: 'elder' });
  assert.deepStrictEqual(Object.keys(out), []);
});

test('pickFamilyWritable 不修改入参', () => {
  const input = { callThresholdMinutes: 20 };
  pickFamilyWritable(input);
  assert.deepStrictEqual(input, { callThresholdMinutes: 20 });
});

test('合并是增量的：未下发的字段保留旧值', () => {
  const merged = mergeFamilySettings(
    { callThresholdMinutes: 15, homeLat: 28.2, homeLng: 112.9 },
    { callThresholdMinutes: 25 },
    false
  );
  assert.strictEqual(merged.callThresholdMinutes, 25);
  assert.strictEqual(merged.homeLat, 28.2, '未下发的家基准必须保留，不能被整体覆盖冲掉');
});

test('homeCleared 删除两个家坐标键', () => {
  const merged = mergeFamilySettings(
    { callThresholdMinutes: 15, homeLat: 28.2, homeLng: 112.9 },
    {},
    true
  );
  assert.strictEqual('homeLat' in merged, false);
  assert.strictEqual('homeLng' in merged, false);
  assert.strictEqual(merged.callThresholdMinutes, 15, '清除家基准不应牵连其他字段');
});

test('homeCleared 优先于同请求内的坐标（清除必须最后生效）', () => {
  // 客户端不并发发这两个，但服务端行为不应依赖 Object.assign 的键序
  const merged = mergeFamilySettings(
    { homeLat: 1, homeLng: 2 },
    { homeLat: 28.2, homeLng: 112.9 },
    true
  );
  assert.strictEqual('homeLat' in merged, false);
  assert.strictEqual('homeLng' in merged, false);
});

test('合并不修改两个入参对象', () => {
  const stored = { callThresholdMinutes: 15 };
  const incoming = { callThresholdMinutes: 25 };
  mergeFamilySettings(stored, incoming, false);
  assert.strictEqual(stored.callThresholdMinutes, 15);
  assert.strictEqual(incoming.callThresholdMinutes, 25);
});

test('merged 返回新对象，不与 stored 共用引用', () => {
  const stored = { callThresholdMinutes: 15 };
  const merged = mergeFamilySettings(stored, {}, false);
  merged.callThresholdMinutes = 99;
  assert.strictEqual(stored.callThresholdMinutes, 15, '改 merged 不能污染库里已存的配置');
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test tests/guardSettings.test.js`
Expected: FAIL，错误信息含 `Cannot find module '../services/guardSettingsPolicy'`

- [ ] **Step 3: 写实现**

创建 `services/guardSettingsPolicy.js`：

```javascript
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
```

- [ ] **Step 4: 从 routes/auth.js 删除已搬走的定义**

修改 `routes/auth.js`：删除 `:90-127` 的 `GUARD_SETTING_BOUNDS` 与 `sanitizeGuardSettings` 整段定义。

把 `:6` 的 import 改为：

```javascript
const { issueToken, requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const {
  sanitizeGuardSettings, extractHomeCleared,
  pickFamilyWritable, mergeFamilySettings
} = require('../services/guardSettingsPolicy');
```

- [ ] **Step 5: 运行测试确认通过**

Run: `node --test tests/guardSettings.test.js`
Expected: `# fail 0`，11 个测试全过

- [ ] **Step 6: 运行全量 Node 测试确认无回归**

Run: `npm test`
Expected: `# fail 0`，`# pass` ≥ 154

- [ ] **Step 7: 提交**

```
git add services/guardSettingsPolicy.js routes/auth.js tests/guardSettings.test.js
git commit -m "feat: 守护规则策略抽为服务模块，子女端字段白名单与增量合并"
```

---

## Task 2: 服务端 family 路由

**Files:**
- Modify: `routes/auth.js`（`:622-661` 之后新增路由）、文件末尾（`setHub`）

- [ ] **Step 1: 写失败测试**

在 `tests/guardSettings.test.js` 末尾追加（这些函数在 Task 1 已实现，本步验证搬移后行为未变）：

```javascript
test('越界数值被收敛到边界内', () => {
  const clean = sanitizeGuardSettings({ homeStayMinutes: 99999, homeAwayRadiusMeters: 1 });
  assert.strictEqual(clean.homeStayMinutes, 240);
  assert.strictEqual(clean.homeAwayRadiusMeters, 100);
});

test('布尔型字段只接受布尔值', () => {
  assert.strictEqual(sanitizeGuardSettings({ recordingAutoUpload: true }).recordingAutoUpload, true);
  assert.strictEqual(sanitizeGuardSettings({ recordingAutoUpload: 'yes' }).recordingAutoUpload, undefined);
});

test('NaN 输入不写进配置（Math.min 与字符串比较会静默产出 NaN）', () => {
  const clean = sanitizeGuardSettings({ homeStayMinutes: 'abc', paymentThreshold: null });
  assert.strictEqual(clean.homeStayMinutes, undefined, '非数字字符串必须被丢弃而不是变成 NaN');
  assert.strictEqual(clean.paymentThreshold, undefined);
});

test('homeLat/homeLng 没有默认值，不会被塞进 0', () => {
  const clean = sanitizeGuardSettings({ homeLat: 28.2, homeLng: 112.9 });
  assert.strictEqual(clean.homeLat, 28.2);
  assert.strictEqual(clean.homeLng, 112.9);
  // 缺一个就不写另一个的默认值，避免半对坐标被当成有效家基准
  const half = sanitizeGuardSettings({ homeLat: 28.2 });
  assert.strictEqual(half.homeLat, 28.2);
  assert.strictEqual(half.homeLng, undefined);
});

test('trust 与 highRisk 字符串字段原样透传给策略层（白名单在下一道关卡过滤）', () => {
  const clean = sanitizeGuardSettings({ trustedCallNumbersJson: '["13800138000"]' });
  assert.strictEqual(clean.trustedCallNumbersJson, '["13800138000"]');
  assert.strictEqual(pickFamilyWritable(clean).trustedCallNumbersJson, undefined,
    '透传归 sanitize，拦截归白名单 —— 两道关卡职责不同');
});

test('字符串字段长度超限或格式不对时丢弃', () => {
  assert.strictEqual(
    sanitizeGuardSettings({ trustedCallNumbersJson: 'x'.repeat(20001) }).trustedCallNumbersJson,
    undefined
  );
  assert.strictEqual(
    sanitizeGuardSettings({ highRiskPackages: 'not-json' }).highRiskPackages,
    undefined
  );
});
```

- [ ] **Step 2: 运行测试确认通过**

Run: `node --test tests/guardSettings.test.js`
Expected: `# fail 0`。全部 17 个测试通过 —— 这些断言在 Task 1 的实现下就应成立，本步的目的是**在动路由之前把搬移后的行为钉死**，避免"改路由时顺手改坏 sanitize 却没人发现"。

若 FAIL，说明 Task 1 的实现有偏差，先修 Task 1。

- [ ] **Step 3: 加 hub 容器**

在 `routes/auth.js` 的 import 之后加入：

```javascript
// 由 server.js 注入的回调集合（setHub）
let hubRef = {};
```

- [ ] **Step 4: 新增 family 路由**

在 `routes/auth.js` 的 `POST /elder-settings` 之后追加：

```javascript
// ──────────────────────────────────────────
//  子女端守护设置（带鉴权）
//
//  与上面 POST /elder-settings 的区别：
//    上面那条零鉴权，任何知道 elderId 的人都能改老人的守护规则
//    （路线图 G15，Phase 4 待办）。它是老人端自己用的通道，
//    因为老人端本就没有账号密码。
//    这条给子女端用，elderId 只从路径参数取，且必须通过绑定关系校验。
//
//  为什么"篡改老人的家基准"必须拦住：改家基准会直接让「离家 / 停留」
// 告警失效 —— 而这正是子女发现老人被骗的第一道信号。
// 属于可被恶意利用的静默降级，不能留成无鉴权接口。
//
//  路径参数是必需的而非命名风格问题：requireBoundElder
//  （services/tokenAuth.js:39）从 req.params 取值，取不到直接 400。
// ──────────────────────────────────────────

router.get('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);

  db.get("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });
    res.json({ success: true, settings: parseGuardSettings(elder.guard_settings) });
  });
});

router.post('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);

  const raw = req.body && req.body.settings;
  // 顺序不可调换：homeCleared 必须在 sanitize 之前取出，
  // sanitize 之后它已被丢弃（见 extractHomeCleared 的注释）
  const homeCleared = extractHomeCleared(raw);
  const clean = pickFamilyWritable(sanitizeGuardSettings(raw));

  if (!Object.keys(clean).length && !homeCleared) {
    return res.status(400).json({ error: '没有可保存的配置项' });
  }

  db.get("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });

    const merged = mergeFamilySettings(
      parseGuardSettings(elder.guard_settings), clean, homeCleared);

    db.run('UPDATE users SET guard_settings = ? WHERE id = ?',
      [JSON.stringify(merged), elderId], (err) => {
        if (err) return res.status(500).json({ error: err.message });
        console.log(`👪 子女端 [ID: ${elderId}] 守护规则已更新: ${JSON.stringify(merged)}`);
        // 通知老人端立刻重算。没通知也没关系：老人端有 30 分钟拉取兜底，
        // 且 GuardConfig 是 SharedPreferences，服务下次启动自然读到新值。
        if (typeof hubRef.notifyElderSettingsChanged === 'function') {
          hubRef.notifyElderSettingsChanged(elderId, merged);
        }
        res.json({ success: true, settings: merged });
      });
  });
});
```

- [ ] **Step 5: 加 setHub 导出**

把 `routes/auth.js` 末尾的 `module.exports = router;` 改为：

```javascript
module.exports = router;
// setHub 同 recordings.js：让路由能回调推送，但不反向依赖 server。
// 老人端离线时不缓存这条指令 —— 配置没有时效性，
// 缓存一份陈旧配置会在上线时覆盖掉更新的值。
module.exports.setHub = (hub) => { hubRef = hub || {}; };
```

- [ ] **Step 6: 运行测试确认通过**

Run: `npm test`
Expected: `# fail 0`，`# pass` ≥ 160

- [ ] **Step 7: 提交**

```
git add routes/auth.js tests/guardSettings.test.js
git commit -m "feat: 子女端守护设置读写接口（带鉴权 + 字段白名单 + 清除家基准）"
```

---

## Task 3: 服务端 WS 推送

**Files:**
- Modify: `server.js:76-84`（注入 auth hub）

- [ ] **Step 1: 新增 notifyElderSettingsChanged**

在 `server.js` 的 `notifyBoundFamily` 函数之后（`:72` 之后）加入：

```javascript
/**
 * 推送守护规则变更给老人端。
 *
 * 老人端离线时**不缓存** —— 与 pendingInterrupts 的取舍相反：
 * 指令（远程打断、停止录音）有时效性，错过就该作废；
 * 配置没有时效性，缓存一份陈旧配置会在上线时覆盖掉更新的值。
 * 离线老人靠 30 分钟拉取兜底即可。
 */
function notifyElderSettingsChanged(elderId, settings) {
  const ws = clients.get(Number(elderId));
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    console.log(`⚙️ 老人端 [ID: ${elderId}] 不在线，配置变更等下次拉取兜底`);
    return;
  }
  try {
    ws.send(JSON.stringify({
      type: 'ELDER_SETTINGS_UPDATED',
      data: { settings, changedBy: 'family' }
    }));
    console.log(`⚙️ 守护规则变更已推送到老人端 [ID: ${elderId}]`);
  } catch (e) {
    console.error('推送守护规则给老人端失败:', e.message);
  }
}
```

- [ ] **Step 2: 注入到 auth 路由**

在 `server.js` 的 `recordingsRoutes.setHub({...})`（`:81-84`）之后加入：

```javascript
authRoutes.setHub({ notifyElderSettingsChanged });
```

- [ ] **Step 3: 手工核对（无法自动断言）**

启动服务并确认没有语法错误：

Run: `node -e "require('./server.js')"`
Expected: 不抛异常，输出 `🚀 服务已启动` 之类字样后需手动 Ctrl+C 中断。

若输出含 `SyntaxError` 或 `authRoutes.setHub is not a function`，说明 Task 2 的 exports 块没改对。

- [ ] **Step 4: 提交**

```
git add server.js
git commit -m "feat: 守护规则变更经 WS 推送给老人端"
```

---

## Task 4: 客户端 bounds 单一来源

**Files:**
- Create: `android/app/src/main/java/com/antifraud/guard/util/GuardSettingBounds.kt`
- Create: `android/app/src/test/java/com/antifraud/guard/util/GuardSettingBoundsTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `android/app/src/test/java/com/antifraud/guard/util/GuardSettingBoundsTest.kt`：

```kotlin
package com.antifraud.guard.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护规则取值范围的单一权威。
 *
 * ## 为什么要单独一个文件
 * 服务端 `routes/auth.js` 的 `GUARD_SETTING_BOUNDS` 与客户端的 clamp
 * 原先是两处独立手写的数字。两处都会改，而没有任何东西会告诉开发者
 * "你改的这一处和那边不一致" —— 于是用户设了 600 米，服务端静默收敛成
 * 500，界面上显示 600，实际按 500 守护，且不报任何错。
 *
 * 跨语言一致性无法用测试断言，所以定一条规则：
 * **客户端 clamp 只为改善体验，服务端返回的 merged 永远是最终权威。**
 * 所有客户端侧写入都必须用服务端回传的 merged 覆盖本机。
 */
class GuardSettingBoundsTest {

    // ── 每个字段的上下界都必须真的生效 ──────────────────────────────────

    @Test
    fun `离家半径超出范围时收敛到边界`() {
        assertEquals(100, GuardSettingBounds.awayRadius(1))
        assertEquals(500, GuardSettingBounds.awayRadius(500))
        assertEquals(5000, GuardSettingBounds.awayRadius(99999))
    }

    @Test
    fun `停留判定半径超出范围时收敛到边界`() {
        assertEquals(20, GuardSettingBounds.stayMoveMeters(0))
        assertEquals(100, GuardSettingBounds.stayMoveMeters(100))
        assertEquals(1000, GuardSettingBounds.stayMoveMeters(5000))
    }

    @Test
    fun `停留时长超出范围时收敛到边界`() {
        assertEquals(5, GuardSettingBounds.stayMinutes(1))
        assertEquals(40, GuardSettingBounds.stayMinutes(40))
        assertEquals(240, GuardSettingBounds.stayMinutes(9999))
    }

    @Test
    fun `通话预警时长超出范围时收敛到边界`() {
        assertEquals(1, GuardSettingBounds.callMinutes(0))
        assertEquals(15, GuardSettingBounds.callMinutes(15))
        assertEquals(240, GuardSettingBounds.callMinutes(100000))
    }

    @Test
    fun `录音段数与单段时长超出范围时收敛到边界`() {
        assertEquals(1, GuardSettingBounds.recordingSegments(0))
        assertEquals(6, GuardSettingBounds.recordingSegments(99))
        assertEquals(1, GuardSettingBounds.recordingSegmentMinutes(0))
        assertEquals(10, GuardSettingBounds.recordingSegmentMinutes(99))
    }

    // ── 负数与非法输入：不能崩，也不能变成"无限制" ──────────────────────

    @Test
    fun `负数不会被当成有效值透传`() {
        // 负半径会让"距离 > 负数"恒真，等于永远判定为离家。
        // 任何一条守护规则被写成负数都必须收敛到下界，而不是照用。
        assertEquals(100, GuardSettingBounds.awayRadius(-1))
        assertEquals(20, GuardSettingBounds.stayMoveMeters(-999))
        assertEquals(5, GuardSettingBounds.stayMinutes(-40))
        assertEquals(1, GuardSettingBounds.callMinutes(-5))
        assertEquals(1, GuardSettingBounds.recordingSegments(-3))
    }

    // ── 坐标：这是唯一不能 clamp 的字段 ─────────────────────────────────
    //
    // 坐标落在合法范围内就原样通过。超范围要返回 null（"无效"）而不是
    // 收敛到边界 —— 把纬度 999 收敛成 90 会静默把家基准设到北极。

    @Test
    fun `合法坐标原样通过`() {
        assertEquals(28.2281, GuardSettingBounds.validLatitude(28.2281)!!, 1e-9)
        assertEquals(-33.8688, GuardSettingBounds.validLatitude(-33.8688)!!, 1e-9)
        assertEquals(112.9381, GuardSettingBounds.validLongitude(112.9381)!!, 1e-9)
        assertEquals(-70.6693, GuardSettingBounds.validLongitude(-70.6693)!!, 1e-9)
    }

    @Test
    fun `超范围坐标返回 null 而不是收敛到边界`() {
        assertEquals(null, GuardSettingBounds.validLatitude(91.0))
        assertEquals(null, GuardSettingBounds.validLatitude(-91.0))
        assertEquals(null, GuardSettingBounds.validLongitude(181.0))
        assertEquals(null, GuardSettingBounds.validLongitude(-181.0))
    }

    @Test
    fun `零坐标对家基准而言等于未设置`() {
        // 0,0 是几内亚湾外海的一个合法坐标点，但对"家"没有意义，
        // 且 GuardConfig.hasFamilyHome 也把它当未设置。保持一致。
        assertEquals(null, GuardSettingBounds.validLatitude(0.0))
        assertEquals(null, GuardSettingBounds.validLongitude(0.0))
    }

    @Test
    fun `NaN 与无穷大坐标一律拒绝`() {
        // 客户端 toDoubleOrNull() 拿不到 NaN，但云端脏数据可能带来
        assertEquals(null, GuardSettingBounds.validLatitude(Double.NaN))
        assertEquals(null, GuardSettingBounds.validLongitude(Double.POSITIVE_INFINITY))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```
$env:JAVA_HOME="D:\Program Files\Java\jdk-21.0.10"
$env:ANDROID_HOME="D:\Android"
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain --tests "*GuardSettingBoundsTest*"
```
Expected: 编译失败，`Unresolved reference: GuardSettingBounds`

- [ ] **Step 3: 写实现**

创建 `android/app/src/main/java/com/antifraud/guard/util/GuardSettingBounds.kt`：

```kotlin
package com.antifraud.guard.util

/**
 * 守护规则取值范围的单一权威（Android 侧）。
 *
 * ## 跨语言一致性问题
 * 同样的上下界在两处手写：
 *   - 服务端 `routes/auth.js` 的 `GUARD_SETTING_BOUNDS`
 *   - 这里
 *
 * 两处都会改，而没有任何机制会提示"你改的这处和那边不一致"。
 * 后果是用户设了 600 米、服务端静默收敛成 500，界面上显示 600、
 * 实际按 500 守护，且不报任何错 —— 用户和子女都无从察觉。
 *
 * 因为跨语言无法用测试断言一致性，所以定一条硬规则：
 * **服务端返回的 merged 永远是最终权威**，客户端 clamp 只为改善体验。
 * 任何客户端侧写入成功后，都必须用服务端回传的 merged 覆盖本机
 * （照 `SettingsActivity.pushSettingsThenFinish` 的既有做法）。
 *
 * 修改本文件时必须同步 `routes/auth.js`。
 */
object GuardSettingBounds {

    // ── 位置守护阈值 ────────────────────────────────────────────────────
    const val AWAY_RADIUS_MIN = 100
    const val AWAY_RADIUS_MAX = 5000
    const val AWAY_RADIUS_DEFAULT = 500

    const val STAY_MOVE_MIN = 20
    const val STAY_MOVE_MAX = 1000
    const val STAY_MOVE_DEFAULT = 100

    const val STAY_MINUTES_MIN = 5
    const val STAY_MINUTES_MAX = 240
    const val STAY_MINUTES_DEFAULT = 40

    // ── 通话与支付 ──────────────────────────────────────────────────────
    const val CALL_MINUTES_MIN = 1
    const val CALL_MINUTES_MAX = 240
    const val CALL_MINUTES_DEFAULT = 15

    // ── 录音存证 ────────────────────────────────────────────────────────
    const val REC_SEGMENTS_MIN = 1
    const val REC_SEGMENTS_MAX = 6
    const val REC_SEGMENTS_DEFAULT = 3

    const val REC_SEGMENT_MINUTES_MIN = 1
    const val REC_SEGMENT_MINUTES_MAX = 10
    const val REC_SEGMENT_MINUTES_DEFAULT = 10

    /** 离家判定半径（米）。负数也会被抬到下界 —— 负半径会让「距离 > 负数」恒真。 */
    fun awayRadius(v: Int): Int = v.coerceIn(AWAY_RADIUS_MIN, AWAY_RADIUS_MAX)

    /** 停留判定半径（米） */
    fun stayMoveMeters(v: Int): Int = v.coerceIn(STAY_MOVE_MIN, STAY_MOVE_MAX)

    /** 陌生地点停留时长（分钟） */
    fun stayMinutes(v: Int): Int = v.coerceIn(STAY_MINUTES_MIN, STAY_MINUTES_MAX)

    /** 通话预警时长（分钟） */
    fun callMinutes(v: Int): Int = v.coerceIn(CALL_MINUTES_MIN, CALL_MINUTES_MAX)

    /** 单次录音段数 */
    fun recordingSegments(v: Int): Int = v.coerceIn(REC_SEGMENTS_MIN, REC_SEGMENTS_MAX)

    /** 单段录音时长（分钟） */
    fun recordingSegmentMinutes(v: Int): Int =
        v.coerceIn(REC_SEGMENT_MINUTES_MIN, REC_SEGMENT_MINUTES_MAX)

    /**
     * 校验纬度，非法时返回 null。
     *
     * 坐标是唯一**不能 clamp** 的字段：把纬度 999 收敛成 90
     * 会静默把家基准设到北极，界面看不出任何异常。
     * 0 被一并拒绝，因为 GuardConfig.hasFamilyHome 也把 0/0 当未设置，
     * 两处口径必须一致，否则会出现"服务端认为已设、客户端认为未设"。
     */
    fun validLatitude(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null
        else if (v == 0.0) null
        else if (v < -90.0 || v > 90.0) null
        else v

    /** 校验经度，非法时返回 null。理由同 [validLatitude]。 */
    fun validLongitude(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null
        else if (v == 0.0) null
        else if (v < -180.0 || v > 180.0) null
        else v
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain --tests "*GuardSettingBoundsTest*"
```
Expected: `BUILD SUCCESSFUL`，10 个测试全过

- [ ] **Step 5: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/util/GuardSettingBounds.kt android/app/src/test/java/com/antifraud/guard/util/GuardSettingBoundsTest.kt
git commit -m "feat: 守护规则取值范围收敛为客户端单一来源"
```

---

## Task 5: 家基准重置判定纯函数

**Files:**
- Create: `android/app/src/main/java/com/antifraud/guard/location/HomeBasePolicy.kt`
- Create: `android/app/src/test/java/com/antifraud/guard/location/HomeBasePolicyTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `android/app/src/test/java/com/antifraud/guard/location/HomeBasePolicyTest.kt`：

```kotlin
package com.antifraud.guard.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「云端家基准变了，要不要重置内存里的基准」判定。
 *
 * ## 背景：一次性闩锁
 * `LocationGuardService.homeSet` 置 true 后永不回退，
 * 于是云端家基准改了之后，内存里的 `homeLat/homeLng` 永远停在旧值。
 * 子女端改了设置、界面上显示新坐标、实际仍按旧坐标判定 ——
 * 而且没有任何报错。这是本项目反复出现的同一类病：
 * "看起来生效了，其实没有"（通话阈值死配置、录音模式被清空是同族）。
 *
 * ## 判定为什么不自己比对坐标
 * `GuardConfig.applySettingsFromServer` 已经返回了被实际覆盖的字段名，
 * 其中含「家的基准位置」。用那个返回值驱动重置，比自己比对坐标可靠：
 * 自己比对会漏掉"服务端值与本机相同、但内存里是更早的值"这种状态。
 */
class HomeBasePolicyTest {

    @Test
    fun `云端家基准变更且已设过基准时必须重置`() {
        assertTrue(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `云端家基准没变时不重置`() {
        // 每次心跳/拉取都会走到这个判定，无脑重置会让 stayStartTime 被不断清零，
        // 「停留超 40 分钟」的告警永远不触发。
        assertFalse(
            "云端没变就重置会清掉停留计时，导致停留告警永不触发",
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = false,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `还没设过基准时不需要重置`() {
        // homeSet 本来就是 false，下次 handleLocation 自然会取云端值
        assertFalse(
            HomeBasePolicy.shouldReset(
                wasHomeSet = false,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `服务未运行时不重置（GuardConfig 已落盘，下次启动自然生效）`() {
        // 没有服务进程，resetHomeBase() 无处可调。
        // 而 GuardConfig 是 SharedPreferences，服务下次启动时 homeSet 天然为 false。
        assertFalse(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = false
            )
        )
    }

    @Test
    fun `清除家基准也算变更，需要重置回退路径`() {
        // 清除后 hasFamilyHome 变 false，重置才能走到"首次定位 + 一次性告知"分支
        assertTrue(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain --tests "*HomeBasePolicyTest*"
```
Expected: 编译失败，`Unresolved reference: HomeBasePolicy`

- [ ] **Step 3: 写实现**

创建 `android/app/src/main/java/com/antifraud/guard/location/HomeBasePolicy.kt`：

```kotlin
package com.antifraud.guard.location

/**
 * 「云端家基准变了，要不要重置内存里的基准」判定（纯逻辑，可在 JVM 上直接测）。
 *
 * ## 要解决的缺陷
 * `LocationGuardService` 里：
 *
 * ```kotlin
 * if (!homeSet) {
 *     if (GuardConfig.hasFamilyHome) { homeLat = ...; homeLng = ... }
 *     else { homeLat = lat; homeLng = lng; notifyHomeBaseSet(lat, lng) }
 *     homeSet = true
 *     stayStartTime = System.currentTimeMillis()
 * }
 * ```
 *
 * `homeSet` 是一次性闩锁，置 true 后永不回退。于是子女端改了家基准，
 * `GuardConfig.homeLat` 被更新了，内存里的 `homeLat` 却还是旧值 ——
 * 界面上显示新坐标，实际按旧坐标判定，没有任何报错。
 *
 * 只修"下发"不修"重置"，等于什么都没修。
 *
 * ## 为什么三个条件都要
 * - `wasHomeSet`：没设过时 `homeSet` 本来就是 false，下次定位自然取云端值
 * - `cloudHomeChanged`：无条件重置会不断清零 `stayStartTime`，
 *   「在陌生地点停留超 40 分钟」的告警就永远不触发了
 * - `serviceRunning`：没有服务进程时无处可调；且 `GuardConfig` 是
 *   SharedPreferences 已落盘，服务下次启动自然读到新值
 */
object HomeBasePolicy {

    @JvmStatic
    fun shouldReset(wasHomeSet: Boolean, cloudHomeChanged: Boolean, serviceRunning: Boolean): Boolean =
        wasHomeSet && cloudHomeChanged && serviceRunning
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain --tests "*HomeBasePolicyTest*"
```
Expected: `BUILD SUCCESSFUL`，5 个测试全过

- [ ] **Step 5: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/location/HomeBasePolicy.kt android/app/src/test/java/com/antifraud/guard/location/HomeBasePolicyTest.kt
git commit -m "feat: 家基准重置判定抽为纯函数"
```

---

## Task 6: LocationGuardService 重置 + 拉取兜底

**Files:**
- Modify: `android/app/src/main/java/com/antifraud/guard/service/LocationGuardService.kt:44-48`、`:111`、`:214-216`、`:225-236`、`:441-463`

- [ ] **Step 1: 加 resetHomeBase 方法**

在 `LocationGuardService.kt` 的 `notifyHomeBaseSet`（`:441`）之前插入：

```kotlin
    /**
     * 重置家基准与停留计时，让下次 handleLocation 重新取云端值。
     *
     * ## 为什么必须有这个方法
     * `homeSet` 是一次性闩锁（见 handleLocation 里的 `if (!homeSet)`），
     * 置 true 后永不回退。云端家基准改了之后，内存里的 homeLat/homeLng
     * 永远停在旧值 —— 子女端改了设置、界面上显示新坐标、实际按旧坐标
     * 判定，而且没有任何报错。
     *
     * ## 为什么 stayStartTime 必须一起清
     * 家基准换了之后，旧的停留计时是按旧基准算出来的。
     * 不清会让老人在刚改完设置的一瞬间就撞上一条
     * LOCATION_RISK「在陌生地点停留超 40 分钟」—— 而他可能根本没出门。
     *
     * 调用前请先过 [HomeBasePolicy.shouldReset]。
     */
    fun resetHomeBase() {
        if (!homeSet) return          // 本来就没设过，下次定位自然取新值
        homeSet = false
        stayStartTime = 0L
        Log.i(TAG, "家基准已重置，下次位置更新将重新取云端配置")
    }
```

- [ ] **Step 2: onCreate 加一次配置拉取**

把 `LocationGuardService.kt:103-112` 的 `onCreate` 改为：

```kotlin
    override fun onCreate() {
        super.onCreate()
        GuardConfig.init(this)
        ApiClient.init(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startLocationUpdates()
        scheduleHeartbeat()
        fetchGeofences()
        // 拉一次云端守护规则：子女端可能在远端改过家基准与阈值
        fetchGuardSettings()
    }
```

- [ ] **Step 3: 加 fetchGuardSettings**

在 `fetchGeofences()`（`:306-335`）之后插入：

```kotlin
    /**
     * 拉取云端守护规则并落地到本机。
     *
     * ## 为什么需要这条兜底
     * 子女端改设置走 WS 推送，但 WS 会断、指令会丢、老人端会长时间离线。
     * 这条路径保证最长一个节流周期内（30 分钟）一定收敛到最新配置。
     *
     * ## 复用围栏的节流时钟
     * 与 fetchGeofences 共用 30 分钟窗口（见 handleLocation 里的判断），
     * 不额外增加请求 —— 老人端在后台，额外请求是要算进耗电预算的。
     */
    private fun fetchGuardSettings() {
        ApiClient.fetchElderSettings(
            elderId = GuardConfig.elderId,
            onSuccess = { settings ->
                if (settings == null) {
                    Log.i(TAG, "云端未配置守护规则，保持本机当前值")
                    return@fetchElderSettings
                }
                val applied = GuardConfig.applySettingsFromServer(settings)
                if (applied.isEmpty()) return@fetchElderSettings

                val homeChanged = applied.contains("家的基准位置")
                Log.i(TAG, "已从云端恢复守护规则：${applied.joinToString("、")}")

                // 关键：只更新 GuardConfig 不够，内存里的 homeLat/homeLng
                // 还停在旧值（homeSet 闩锁），必须显式重置才真正生效。
                if (HomeBasePolicy.shouldReset(
                        wasHomeSet = homeSet,
                        cloudHomeChanged = homeChanged,
                        serviceRunning = true)) {
                    resetHomeBase()
                }
            },
            onError = { err -> Log.w(TAG, "守护规则拉取失败: $err") }
        )
    }
```

- [ ] **Step 4: 节流判断里加上配置拉取**

把 `LocationGuardService.kt:213-216` 改为：

```kotlin
        // 围栏列表与守护规则共用一个 30 分钟窗口：后台多一次请求是要算进
        // 耗电预算的，而这两类数据的新鲜度要求相同（子女端改了都要等一会儿）。
        if (System.currentTimeMillis() - fencesFetchedAt > 30 * 60 * 1000L) {
            fetchGeofences()
            fetchGuardSettings()
        }
```

- [ ] **Step 5: import HomeBasePolicy**

在 `LocationGuardService.kt` 的 import 区（`:21` `import com.antifraud.guard.location.LocationHeartbeatPolicy` 之后）加入：

```kotlin
import com.antifraud.guard.location.HomeBasePolicy
```

- [ ] **Step 6: 修 notifyHomeBaseSet 文案**

把 `LocationGuardService.kt:450-453` 的 `.setContentText(...)` 改为：

```kotlin
                .setContentText(
                    "已将当前位置设为家的基准（${String.format("%.4f", lat)}, " +
                        "${String.format("%.4f", lng)}）。" +
                        "这是临时兜底：老人出门在外时首次打开 App 会被误当作家。" +
                        "可在子女端「守护设置」里改为真实住址。"
                )
```

原文案写的是「如有偏差，请在子女端设置中修正」，而那个页面当时并不存在。

- [ ] **Step 7: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/service/LocationGuardService.kt
git commit -m "fix: 云端家基准变更不再被一次性闩锁吞掉，并加 30 分钟拉取兜底"
```

---

## Task 7: 老人端 WS 处理配置变更

**Files:**
- Modify: `android/app/src/main/java/com/antifraud/guard/service/GuardWebSocketManager.kt:165-173`

- [ ] **Step 1: 新增指令分支**

在 `GuardWebSocketManager.kt` 的 `if (type == "EMERGENCY_INTERRUPT") {`（`:165`）之前插入：

```kotlin
                        if (type == "ELDER_SETTINGS_UPDATED") {
                            // 子女端改了守护规则（家基准 / 各项阈值）
                            val settings = json.optJSONObject("data")?.optJSONObject("settings")
                            if (settings == null) {
                                Log.w(TAG, "收到守护规则变更但载荷为空，忽略")
                            } else {
                                val applied = GuardConfig.applySettingsFromServer(settings)
                                Log.i(TAG, "收到子女端守护规则变更：${applied.joinToString("、")}")

                                // 只更新 GuardConfig 不够：LocationGuardService 内存里的
                                // homeLat/homeLng 被 homeSet 闩锁锁住，必须显式通知重置。
                                // 服务没在跑时不需要通知 —— GuardConfig 已落盘，
                                // 服务下次启动时 homeSet 天然为 false，直接读到新值。
                                if (applied.contains("家的基准位置") &&
                                    GuardServiceStarter.isRunning(
                                        context, LocationGuardService::class.java)) {
                                    context.startService(
                                        Intent(context, LocationGuardService::class.java)
                                            .setAction(LocationGuardService.ACTION_HOME_BASE_CHANGED)
                                    )
                                }
                            }
                        }
```

- [ ] **Step 2: 加 action 常量与 onStartCommand 处理**

在 `LocationGuardService.kt` 的 companion object 里（`:35-37`）加入：

```kotlin
        /** 子女端改了家基准，请求重置内存中的基准（GuardWebSocketManager 下发） */
        const val ACTION_HOME_BASE_CHANGED = "com.antifraud.guard.action.HOME_BASE_CHANGED"
```

在 `LocationGuardService.kt` 中新增 `onStartCommand` 覆写（放在 `onCreate` 之后）：

```kotlin
    /**
     * 响应"家基准已变更"的启动指令。
     *
     * 注意 startService 而不是 startForegroundService：这个服务本身已经是
     * 前台服务（onCreate 里 startForeground 过），Android 8+ 用
     * startForegroundService 反而要求在 5 秒内再调一次 startForeground，
     * 白白引入一个后台启动限制的失败点。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HOME_BASE_CHANGED) {
            if (HomeBasePolicy.shouldReset(
                    wasHomeSet = homeSet,
                    cloudHomeChanged = true,
                    serviceRunning = true)) {
                resetHomeBase()
            }
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }
```

- [ ] **Step 3: 修 CallRiskWatcher 过期注释**

把 `CallRiskWatcher.kt:41-42` 改为：

```kotlin
 *    未授权时自动降级为仅通话时长监测 —— 设置页「守护健康自检」第 ⑥ 项
 *    会把它标成 ⚠️ 降级，并说明具体停用了什么。
```

原注释声称设置页已标注某个实际不存在的字符串。

- [ ] **Step 4: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

若报 `Unresolved reference: GuardServiceStarter`，说明该文件未 import。它与 `LocationGuardService` 同在 `com.antifraud.guard.service` 包下，无需 import。

- [ ] **Step 5: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/service/GuardWebSocketManager.kt android/app/src/main/java/com/antifraud/guard/service/LocationGuardService.kt android/app/src/main/java/com/antifraud/guard/service/CallRiskWatcher.kt
git commit -m "feat: 老人端经 WS 接收守护规则变更并重置家基准"
```

---

## Task 8: R6 告警等级与文案

**Files:**
- Modify: `android/app/src/main/java/com/antifraud/guard/SettingsActivity.kt:215-230`、`:252-259`
- Modify: `android/app/src/main/java/com/antifraud/guard/MainActivity.kt:441-442`

- [ ] **Step 1: 改状态行**

把 `SettingsActivity.kt:221` 改为：

```kotlin
            appendLine("使用情况访问：${if (h.usageAccessGranted) "✅ 已开启" else "⚠️ 未开启（通话联动告警已停用）"}")
```

- [ ] **Step 2: 改建议桶归属**

把 `SettingsActivity.kt:256-259` 的 `upcoming +=` 改为 `degraded +=`，并移出 `upcoming` 段：

```kotlin
        if (!h.usageAccessGranted) {
            // ⚠️ 降级而非 ℹ️ 备用：CallRiskWatcher.sampleForegroundOnce()
            // 第一件事就是检查这个权限，没有它就直接 return，
            // 于是「通话中打开支付 App / 远程控制软件」的 COERCION_RISK
            // 高危告警完全不会发生。性质与「通知使用权缺失 → 支付监听不触发」
            // 相同，属高危告警静默失效。
            degraded += "未开启「使用情况访问」：通话中打开支付 App / 远程控制软件" +
                    "（屏幕共享类诈骗）将不会告警。通话时长预警与陌生号码判定仍有效。" +
                    "该项在国产 ROM 上较难开启，需手动设置"
        }
```

并把 `:252-255` 的 `upcoming +=`（呼叫筛选角色）改为：

```kotlin
        if (!h.callScreeningEnabled) {
            upcoming += "来电显示/呼叫筛选角色未授予：现有通话时长监测不会被系统调用。" +
                    "该项目前已由 CallRiskWatcher 的通话状态监听覆盖，不影响告警"
        }
```

- [ ] **Step 3: 改注释**

把 `SettingsActivity.kt:215-218` 的注释改为：

```kotlin
            // 「来电显示角色」确实还没有已实现功能依赖它 —— Phase 1 的
            // CallRiskWatcher 用 PhoneStateListener 独立完成了通话监测，不经过系统角色。
            // 但「使用情况访问」不同：没有它 COERCION_RISK 完全不发生（见下方 degraded 桶）。
            // 两项口径不同，所以一个标 ℹ️ 一个标 ⚠️。
            // 口径必须与首页横幅一致（见 MainActivity.renderGuardAlertBanner），
            // 否则用户数出 3 个问题、横幅却只有 1 项，会以为有一处统计错了。
```

把 `SettingsActivity.kt:226-234` 的注释块改为：

```kotlin
        // 分三档是刻意的，因为这三类问题的**性质**完全不同，混在一起会误导用户：
        //
        //   1. 失效  = 有功能已经不能用了，必须马上修
        //   2. 降级  = 功能还能用但会漏（通知使用权缺失时扣款监听不触发；
        //              使用情况访问缺失时通话中的支付/远程控制联动不告警）
        //   3. 备用  = 当前没有任何已实现功能依赖它
        //
        // 把「使用情况访问」标成备用是错的：Phase 1 的行为判定已上线，
        // 未授权时 COERCION_RISK 根本不会触发，是漏报方向的告警失效。
        //
        // 告警一旦不准，真正的告警就会被当成噪音，这正是安全类 UI 最常见的失效方式。
```

- [ ] **Step 4: 首页横幅注释更新（不加该项）**

把 `MainActivity.kt:441-442` 改为：

```kotlin
            // 「使用情况访问」刻意不列：它确实属 warnings（未开启时通话中的
            // 支付/远程控制联动不告警），但该权限在 MIUI 上实测被静默拦截、
            // 只能 UI 手开，多数用户都开不了。放进首页横幅等于让一个
            // 大多数人开不了的权限变成日常噪音，反而稀释真正致命的告警。
            // 设置页「守护健康自检」是用户主动去查的地方，标 ⚠️ 足够。
```

- [ ] **Step 5: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/SettingsActivity.kt android/app/src/main/java/com/antifraud/guard/MainActivity.kt
git commit -m "fix: 使用情况访问缺失是告警降级而非备用（R6）"
```

---

## Task 9: 抽出 OneShotLocation

**Files:**
- Create: `android/app/src/main/java/com/antifraud/guard/util/OneShotLocation.kt`
- Modify: `android/app/src/main/java/com/antifraud/guard/family/GeofenceManageActivity.kt:67-69`、`:240-301`

- [ ] **Step 1: 写工具类**

创建 `android/app/src/main/java/com/antifraud/guard/util/OneShotLocation.kt`：
package com.antifraud.guard.util

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/**
 * 一次性定位：拿一次坐标就取消订阅。
 *
 * ## 为什么要抽出来
 * 「📍 使用当前位置」这个按钮在子女端有两处（围栏管理、守护设置），
 * 逻辑相同且都不简单：先取缓存，超过 2 分钟就监听一次实时定位，
 * 15 秒还拿不到就回退缓存，再失败才报错。
 *
 * 复制一遍的后果是两份兜底策略各自漂移 —— 典型表现是
 * 围栏页 15 秒超时、守护设置页 30 秒超时，用户完全无法理解这个差异。
 *
 * ## 为什么不用 FusedLocationProviderClient
 * 它需要 Google Play Services，在国产 ROM 上常年不可用，
 * 而本项目的目标设备就是国产 ROM。`LocationManager` 是唯一可靠的选择。
 */
class OneShotLocation(
    private val context: Context,
    /** 拿到坐标时回调。已是 WGS-84，无需转换。 */
    private val onLocated: (Location) -> Unit,
    /** 三级兜底全部失败时回调（errMsg 面向用户，可直接展示） */
    private val onFailed: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null

    /** 执行定位。调用方需自行确认已持有 ACCESS_FINE_LOCATION。 */
    fun start() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = PROVIDERS
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }

            // 2 分钟内的缓存直接用，不去等实时定位（老人常在室内，GPS 无星）
            if (last != null && System.currentTimeMillis() - last.time < CACHE_TTL_MS) {
                onLocated(last)
                return
            }

            val l = LocationListener { loc ->
                cancel()
                onLocated(loc)
            }
            listener = l
            for (provider in LIVE_PROVIDERS) {
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, l)
                } catch (_: Exception) { }
            }

            handler.postDelayed({
                if (listener === l) {
                    cancel()
                    if (last != null) onLocated(last)
                    else onFailed("定位失败，请到空旷处重试或手动输入坐标")
                }
            }, LISTEN_TIMEOUT_MS)
        } catch (e: Exception) {
            onFailed("定位异常：${e.message}")
        }
    }

    /** 取消订阅。Activity 销毁时必须调用，否则定位回调会打到已销毁的界面。 */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { l ->
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(l)
            } catch (_: Exception) { }
        }
        listener = null
    }

    private companion object {
        /** 缓存位置在此时长内视为可用，不去等实时定位 */
        const val CACHE_TTL_MS = 2 * 60 * 1000L
        /** 等实时定位的最长时间 */
        const val LISTEN_TIMEOUT_MS = 15_000L

        /** 取缓存时遍历的 provider（含被动，被动能拿到别的 App 刚定位的结果） */
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        /** 主动监听只用前两个；PASSIVE 不可主动注册 */
        val LIVE_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }
}
```

- [ ] **Step 2: 编译验证工具类**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: GeofenceManageActivity 改用工具类**

把 `GeofenceManageActivity.kt:67-69` 改为：

```kotlin
    /** 单次定位（"使用当前位置"） */
    private var oneShot: OneShotLocation? = null
```

把 `:240-301` 的 `onUseLocationTap` / `fillFromLastKnownOrListen` / `fillCoordinates` / `cancelSingleShot` 四个方法整体替换为：

```kotlin
    private fun onUseLocationTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
            return
        }
        fetchCurrentLocation()
    }

    /** 一次性定位：逻辑与超时策略集中在 [OneShotLocation]，与守护设置页共用 */
    private fun fetchCurrentLocation() {
        oneShot?.cancel()
        Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show()
        oneShot = OneShotLocation(
            context = this,
            onLocated = { loc ->
                etLat.setText(String.format("%.6f", loc.latitude))
                etLng.setText(String.format("%.6f", loc.longitude))
                Toast.makeText(this, "已填入当前位置坐标", Toast.LENGTH_SHORT).show()
            },
            onFailed = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        ).also { it.start() }
    }
```

把 `:100-103` 的 `onDestroy` 改为：

```kotlin
    override fun onDestroy() {
        oneShot?.cancel()
        oneShot = null
        super.onDestroy()
    }
```

把 `:305-311` 的权限回调改为：

```kotlin
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                Toast.makeText(this, "位置权限被拒绝，请手动输入坐标", Toast.LENGTH_SHORT).show()
            }
        }
```

删除不再使用的 import（`android.location.LocationListener`、`android.location.LocationManager`、`android.os.Handler`、`android.os.Looper`），加入 `com.antifraud.guard.util.OneShotLocation`。若 `singleShotListener` 字段在替换后仍被引用，删除该字段。

- [ ] **Step 4: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`。若报 `Unresolved reference: singleShotListener`，说明旧字段未删干净。

- [ ] **Step 5: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/util/OneShotLocation.kt android/app/src/main/java/com/antifraud/guard/family/GeofenceManageActivity.kt
git commit -m "refactor: 一次性定位抽为共用工具，消除两份兜底策略"
```

---

## Task 10: ApiClient 子女端守护设置接口

**Files:**
- Modify: `android/app/src/main/java/com/antifraud/guard/api/ApiClient.kt:680`（pushElderSettings 之后）

- [ ] **Step 1: 新增两个函数**

在 `ApiClient.kt` 的 `pushElderSettings`（`:663-680`）之后、`}` 之前加入：

```kotlin
    /**
     * 子女端拉取老人的守护规则（带登录态）。
     *
     * 走的是 /family/ 路径而不是老人端那条免登录通道 —— 读取本身不敏感，
     * 但既然写侧已经收口到带鉴权，读侧一并收口，避免留下一个
     * "谁都能读别人老人守护配置"的口子。
     */
    fun fetchFamilyElderSettings(
        elderId: Int = GuardConfig.boundElderId,
        onSuccess: (JSONObject?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (elderId <= 0) {
            onSuccess(null)
            return
        }
        familyGet("/api/auth/elder-settings/family/$elderId",
            onSuccess = { res -> onSuccess(res.optJSONObject("settings")) },
            onError = onError)
    }

    /**
     * 子女端保存守护规则。
     *
     * elderId 只放在路径参数里，不放进 body —— 服务端只认路径参数，
     * body 里的同名字段会被忽略。这样即使将来有别处误传，也不会
     * 出现"改了 A 老人的配置却显示成功"的情况。
     *
     * @param clearHome true 表示清除家基准（服务端会删掉 homeLat/homeLng 两个键）
     */
    fun pushFamilyElderSettings(
        settings: JSONObject,
        clearHome: Boolean = false,
        elderId: Int = GuardConfig.boundElderId,
        onSuccess: (JSONObject?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (elderId <= 0) {
            onError("尚未绑定守护对象，守护参数无法保存")
            return
        }
        val body = JSONObject().apply {
            put("settings", settings)
            if (clearHome) put("homeCleared", true)
        }
        familyPost("/api/auth/elder-settings/family/$elderId", body,
            onSuccess = { res -> onSuccess(res.optJSONObject("settings")) },
            onError = onError)
    }
```

- [ ] **Step 2: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/api/ApiClient.kt
git commit -m "feat: ApiClient 子女端守护设置读写接口"
```

---

## Task 11: 子女端守护设置页布局

**Files:**
- Create: `android/app/src/main/res/layout/activity_elder_guard_settings.xml`

- [ ] **Step 1: 写布局**

创建 `android/app/src/main/res/layout/activity_elder_guard_settings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F1F5F9">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- 家基准位置 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_card"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🏠 家基准位置"
                android:textColor="#1E293B"
                android:textSize="16sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tv_home_base"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:background="@drawable/bg_geofence_summary"
                android:padding="12dp"
                android:text="正在读取…"
                android:textColor="#1E293B"
                android:textSize="13sp" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:lineSpacingExtra="3dp"
                android:text="ⓘ 未设置时，老人端会以「首次定位到的位置」为准。若老人是在外面时首次打开 App 就算成了家，之后的离家判定会全部失真，建议尽早设置。"
                android:textColor="#64748B"
                android:textSize="12sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btn_pick_home_map"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_btn_primary"
                    android:text="🗺️ 地图选点"
                    android:textColor="#FFFFFF"
                    android:textSize="14sp" />

                <Button
                    android:id="@+id/btn_pick_home_location"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_marginStart="10dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_btn_primary"
                    android:text="📍 用当前位置"
                    android:textColor="#FFFFFF"
                    android:textSize="14sp" />
            </LinearLayout>

            <Button
                android:id="@+id/btn_clear_home"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="10dp"
                android:text="🗑️ 清除家基准"
                android:textSize="14sp" />
        </LinearLayout>

        <!-- 位置守护阈值 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/bg_card"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="⚙️ 位置守护阈值"
                android:textColor="#1E293B"
                android:textSize="16sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="决定什么时候算「离家」、什么时候算「在陌生地点停留过久」"
                android:textColor="#94A3B8"
                android:textSize="12sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:text="离家判定半径（米）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_away_radius"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="100~5000"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="停留判定半径（米）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_stay_move"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="20~1000"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="陌生地点停留时长（分钟）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_stay_minutes"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="5~240"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />
        </LinearLayout>

        <!-- 通话与支付 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/bg_card"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="📞 通话与支付"
                android:textColor="#1E293B"
                android:textSize="16sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="通话预警时长（分钟）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_call_threshold"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="1~240"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="支付预警金额（元）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_payment_threshold"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="1~1000000"
                android:inputType="numberDecimal"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />
        </LinearLayout>

        <!-- 录音存证 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/bg_card"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🎙️ 录音存证"
                android:textColor="#1E293B"
                android:textSize="16sp"
                android:textStyle="bold" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="录满后自动上传到服务器"
                    android:textColor="#475569"
                    android:textSize="13sp" />

                <Switch
                    android:id="@+id/sw_auto_upload"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />
            </LinearLayout>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="录音段数"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_rec_segments"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="1~6"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="单段录音时长（分钟）"
                android:textColor="#475569"
                android:textSize="13sp" />

            <EditText
                android:id="@+id/et_rec_segment_minutes"
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_input"
                android:hint="1~10"
                android:inputType="number"
                android:paddingHorizontal="12dp"
                android:textColor="#1E293B"
                android:textColorHint="#94A3B8"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/tv_rec_total_hint"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text=""
                android:textColor="#10B981"
                android:textSize="12sp" />
        </LinearLayout>

        <Button
            android:id="@+id/btn_save_guard_settings"
            android:layout_width="match_parent"
            android:layout_height="50dp"
            android:layout_marginTop="16dp"
            android:background="@drawable/bg_btn_primary"
            android:text="保存守护设置"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:layout_marginBottom="8dp"
            android:lineSpacingExtra="3dp"
            android:text="保存后会立即同步到老人手机。若同步失败，老人端仍按旧参数守护 —— 本页会明确告知失败原因，不会显示成已保存。"
            android:textColor="#64748B"
            android:textSize="12sp" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 2: 核对 id 清单**

确认布局里的 12 个 id 与下一步 Activity 用到的一致：

```
tv_home_base
btn_pick_home_map
btn_pick_home_location
btn_clear_home
et_away_radius
et_stay_move
et_stay_minutes
et_call_threshold
et_payment_threshold
sw_auto_upload
et_rec_segments
et_rec_segment_minutes
tv_rec_total_hint
btn_save_guard_settings
```

（14 个，含 `tv_rec_total_hint` 与 `btn_save_guard_settings`。）

- [ ] **Step 3: 提交**

```
git add android/app/src/main/res/layout/activity_elder_guard_settings.xml
git commit -m "feat: 子女端守护设置页布局"
```

---

## Task 12: 子女端守护设置页 Activity

**Files:**
- Create: `android/app/src/main/java/com/antifraud/guard/family/ElderGuardSettingsActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`（注册 activity）

- [ ] **Step 1: 写 Activity**

创建 `android/app/src/main/java/com/antifraud/guard/family/ElderGuardSettingsActivity.kt`：

```kotlin
package com.antifraud.guard.family

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.OneShotLocation
import org.json.JSONObject

/**
 * 子女端守护设置：家基准 + 全部守护规则阈值的唯一入口。
 *
 * ## 为什么是唯一入口
 * 老人端设置页原先有 4 个阈值输入框，但**没有任何一处能设置家基准与
 * 位置阈值** —— `homeAwayRadiusMeters` / `stayMoveMeters` / `homeStayMinutes`
 * 在整个 Android UI 层零出现，只是被 `currentSettingsPayload()` 把代码默认值
 * 推到了云端。看着像"已接好同步只差 UI"，实际是"没人能改"。
 *
 * 现在规则全部收到这里，老人端改只读。这样：
 *   - 单一写入方，不会出现「子女改了、老人端页面显示旧值、实际已被覆盖」
 *   - 老人不用理解这些参数（他们本来也不会配置）
 *   - 子女才是真正知道"通话超过几分钟该提醒"的人
 *
 * ## 保存失败文案的方向
 * 与老人端 `pushSettingsThenFinish` 相反：老人端失败是"本机已生效但没同步"，
 * 这里是"云端没改成，老人端仍在按旧参数守护"。
 * 云端本来就是事实源，保存失败时它从未变过，不存在"会被覆盖回去"。
 */
class ElderGuardSettingsActivity : AppCompatActivity() {

    private companion object {
        const val REQ_LOCATION = 300
    }

    private lateinit var tvHomeBase: TextView
    private lateinit var etAwayRadius: EditText
    private lateinit var etStayMove: EditText
    private lateinit var etStayMinutes: EditText
    private lateinit var etCallThreshold: EditText
    private lateinit var etPaymentThreshold: EditText
    private lateinit var swAutoUpload: Switch
    private lateinit var etRecSegments: EditText
    private lateinit var etRecSegmentMinutes: EditText
    private lateinit var tvRecTotalHint: TextView
    private lateinit var btnSave: Button

    private var oneShot: OneShotLocation? = null
    private var saving = false

    /** 待写入云端的家基准；null 表示"未设置"，保存时下发 homeCleared */
    private var homeLat: Double? = null
    private var homeLng: Double? = null
    /** 用户点过「清除家基准」但还没保存 */
    private var homeClearedPending = false

    private val pickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        setHome(
            data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
            data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0),
            "已选点为家基准，保存后生效"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_guard_settings)
        title = "守护设置"

        tvHomeBase            = findViewById(R.id.tv_home_base)
        etAwayRadius          = findViewById(R.id.et_away_radius)
        etStayMove            = findViewById(R.id.et_stay_move)
        etStayMinutes         = findViewById(R.id.et_stay_minutes)
        etCallThreshold       = findViewById(R.id.et_call_threshold)
        etPaymentThreshold    = findViewById(R.id.et_payment_threshold)
        swAutoUpload          = findViewById(R.id.sw_auto_upload)
        etRecSegments         = findViewById(R.id.et_rec_segments)
        etRecSegmentMinutes   = findViewById(R.id.et_rec_segment_minutes)
        tvRecTotalHint        = findViewById(R.id.tv_rec_total_hint)
        btnSave               = findViewById(R.id.btn_save_guard_settings)

        findViewById<Button>(R.id.btn_pick_home_map).setOnClickListener {
            pickLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_pick_home_location).setOnClickListener { onUseLocationTap() }
        findViewById<Button>(R.id.btn_clear_home).setOnClickListener { onClearHomeTap() }
        btnSave.setOnClickListener { onSaveTap() }

        val recWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { renderRecHint() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etRecSegments.addTextChangedListener(recWatcher)
        etRecSegmentMinutes.addTextChangedListener(recWatcher)

        loadSettings()
    }

    override fun onDestroy() {
        oneShot?.cancel()
        oneShot = null
        super.onDestroy()
    }

    // ──────────────────────────────────────────
    //  读取
    // ──────────────────────────────────────────

    private fun loadSettings() {
        if (!GuardConfig.isFamilyBound) {
            tvHomeBase.text = "尚未绑定守护对象，绑定后即可设置守护参数"
            btnSave.isEnabled = false
            return
        }
        tvHomeBase.text = "正在读取…"
        ApiClient.fetchFamilyElderSettings(
            onSuccess = { s -> renderSettings(s) },
            onError = { err ->
                tvHomeBase.text = "读取失败：$err"
                Toast.makeText(this, "读取守护参数失败：$err", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 用服务端返回的原始配置填表。
     *
     * 刻意不用默认值填表：服务端没配过的字段显示空，用户看得见
     * "这项还没设过"，而不是看到一个看起来很正常的 500 然后以为那就是实际值。
     * 保存时所有空值按 [GuardSettingBounds] 的默认值补齐。
     */
    private fun renderSettings(s: JSONObject?) {
        if (s == null) {
            tvHomeBase.text = "尚未设置家基准"
            fillDefaults()
            return
        }

        val hasLat = s.has("homeLat") && !s.isNull("homeLat")
        val hasLng = s.has("homeLng") && !s.isNull("homeLng")
        if (hasLat && hasLng) {
            homeLat = s.optDouble("homeLat")
            homeLng = s.optDouble("homeLng")
            homeClearedPending = false
            renderHomeBase()
        } else {
            homeLat = null
            homeLng = null
            tvHomeBase.text = "尚未设置家基准"
        }

        etAwayRadius.setText(s.optInt("homeAwayRadiusMeters", -1).takeIf { it > 0 }?.toString() ?: "")
        etStayMove.setText(s.optInt("stayMoveMeters", -1).takeIf { it > 0 }?.toString() ?: "")
        etStayMinutes.setText(s.optInt("homeStayMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        etCallThreshold.setText(s.optInt("callThresholdMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        etPaymentThreshold.setText(s.optDouble("paymentThreshold", -1.0).takeIf { it > 0 }?.toString() ?: "")
        etRecSegments.setText(s.optInt("recordingMaxSegments", -1).takeIf { it > 0 }?.toString() ?: "")
        etRecSegmentMinutes.setText(s.optInt("recordingSegmentMinutes", -1).takeIf { it > 0 }?.toString() ?: "")
        swAutoUpload.isChecked = s.optBoolean("recordingAutoUpload", true)

        renderRecHint()
        btnSave.isEnabled = true
    }

    private fun fillDefaults() {
        etAwayRadius.setText(GuardSettingBounds.AWAY_RADIUS_DEFAULT.toString())
        etStayMove.setText(GuardSettingBounds.STAY_MOVE_DEFAULT.toString())
        etStayMinutes.setText(GuardSettingBounds.STAY_MINUTES_DEFAULT.toString())
        etCallThreshold.setText(GuardSettingBounds.CALL_MINUTES_DEFAULT.toString())
        etPaymentThreshold.setText("500")
        etRecSegments.setText(GuardSettingBounds.REC_SEGMENTS_DEFAULT.toString())
        etRecSegmentMinutes.setText(GuardSettingBounds.REC_SEGMENT_MINUTES_DEFAULT.toString())
        swAutoUpload.isChecked = true
        renderRecHint()
        btnSave.isEnabled = true
    }

    private fun renderHomeBase() {
        tvHomeBase.text = "当前基准：${String.format("%.6f", homeLat)}, ${String.format("%.6f", homeLng)}" +
                (if (homeClearedPending) "\n⚠️ 已标记清除，点下方「保存守护设置」后才生效" else "")
    }

    /**
     * 实时换算录音总时长。
     * 照搬老人端设置页的既有做法（`SettingsActivity.updateRecHint`）：
     * 填错当场就能看出，比保存后被静默 clamp 再让用户困惑要好。
     */
    private fun renderRecHint() {
        val seg = etRecSegments.text.toString().trim().toIntOrNull() ?: 0
        val per = etRecSegmentMinutes.text.toString().trim().toIntOrNull() ?: 0
        if (seg == 0 || per == 0) {
            tvRecTotalHint.text = "请填写：段数 1~6，每段 1~10 分钟"
            tvRecTotalHint.setTextColor(0xFF94A3B8.toInt())
            return
        }
        val cs = GuardSettingBounds.recordingSegments(seg)
        val cp = GuardSettingBounds.recordingSegmentMinutes(per)
        val clamped = cs != seg || cp != per
        tvRecTotalHint.text = if (clamped) {
            "超出范围：将按 $cs 段 × $cp 分钟 = ${cs * cp} 分钟保存"
        } else {
            "当前：$seg 段 × $per 分钟，约 ${seg * per} 分钟。弱网下单段越短越不容易上传失败。"
        }
        tvRecTotalHint.setTextColor(
            if (!clamped && per < 8) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
        )
    }

    // ──────────────────────────────────────────
    //  家基准操作
    // ──────────────────────────────────────────

    private fun setHome(lat: Double, lng: Double, msg: String) {
        val vLat = GuardSettingBounds.validLatitude(lat)
        val vLng = GuardSettingBounds.validLongitude(lng)
        if (vLat == null || vLng == null) {
            // 不能像其他数值那样 clamp 到边界：把纬度 999 收敛成 90
            // 会静默把家基准设到北极，界面上看不出任何异常
            Toast.makeText(this, "坐标无效（纬度须 -90~90 且不为 0，经度须 -180~180 且不为 0）",
                Toast.LENGTH_LONG).show()
            return
        }
        homeLat = vLat
        homeLng = vLng
        homeClearedPending = false
        renderHomeBase()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun onUseLocationTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
            return
        }
        fetchCurrentLocation()
    }

    private fun fetchCurrentLocation() {
        oneShot?.cancel()
        Toast.makeText(this, "正在获取当前位置...", Toast.LENGTH_SHORT).show()
        oneShot = OneShotLocation(
            context = this,
            onLocated = { loc -> setHome(loc.latitude, loc.longitude, "已用当前位置，保存后生效") },
            onFailed = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        ).also { it.start() }
    }

    /**
     * 清除家基准。
     *
     * 刻意做成"标记待清除"而不是立即提交：用户在输入框里可能还有没保存的
     * 改动，立即提交会连带把那些一起提交掉，而界面上完全看不出发生了什么。
     */
    private fun onClearHomeTap() {
        if (homeLat == null && !homeClearedPending) {
            Toast.makeText(this, "当前本就未设置家基准", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除家基准")
            .setMessage("清除后老人端会退回「首次定位到的位置」作为家。若老人是在外面时首次打开 App，离家判定会失真。\n\n点「保存守护设置」后生效。")
            .setPositiveButton("标记清除") { _, _ ->
                homeLat = null
                homeLng = null
                homeClearedPending = true
                renderHomeBase()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    private fun onSaveTap() {
        if (saving) {
            Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        val away = etAwayRadius.text.toString().trim().toIntOrNull()
        val stayMove = etStayMove.text.toString().trim().toIntOrNull()
        val stayMin = etStayMinutes.text.toString().trim().toIntOrNull()
        val callMin = etCallThreshold.text.toString().trim().toIntOrNull()
        val payAmt = etPaymentThreshold.text.toString().trim().toDoubleOrNull()
        val seg = etRecSegments.text.toString().trim().toIntOrNull()
        val perMin = etRecSegmentMinutes.text.toString().trim().toIntOrNull()

        if (away == null || stayMove == null || stayMin == null || callMin == null ||
            payAmt == null || seg == null || perMin == null) {
            Toast.makeText(this, "有输入框为空或格式不正确，请检查", Toast.LENGTH_SHORT).show()
            return
        }
        if (payAmt < 1) {
            Toast.makeText(this, "支付预警金额须大于 0", Toast.LENGTH_SHORT).show()
            return
        }

        // 收敛提示：静默 clamp 会让人以为设了 9 段、实际生效 6 段，
        // 然后以为系统有 bug。明确告知会收敛到哪个值。
        val clamped = listOf(
            "离家半径" to (away to GuardSettingBounds.awayRadius(away)),
            "停留判定半径" to (stayMove to GuardSettingBounds.stayMoveMeters(stayMove)),
            "停留时长" to (stayMin to GuardSettingBounds.stayMinutes(stayMin)),
            "通话时长" to (callMin to GuardSettingBounds.callMinutes(callMin)),
            "录音段数" to (seg to GuardSettingBounds.recordingSegments(seg)),
            "单段时长" to (perMin to GuardSettingBounds.recordingSegmentMinutes(perMin))
        ).filter { (r) -> r.second.first != r.second.second }

        val body = JSONObject().apply {
            put("homeAwayRadiusMeters", GuardSettingBounds.awayRadius(away))
            put("stayMoveMeters", GuardSettingBounds.stayMoveMeters(stayMove))
            put("homeStayMinutes", GuardSettingBounds.stayMinutes(stayMin))
            put("callThresholdMinutes", GuardSettingBounds.callMinutes(callMin))
            put("paymentThreshold", payAmt)
            put("recordingAutoUpload", swAutoUpload.isChecked)
            put("recordingMaxSegments", GuardSettingBounds.recordingSegments(seg))
            put("recordingSegmentMinutes", GuardSettingBounds.recordingSegmentMinutes(perMin))
        }

        val clearHome = homeClearedPending
        if (!clearHome && homeLat != null && homeLng != null) {
            body.put("homeLat", homeLat)
            body.put("homeLng", homeLng)
        }

        saving = true
        btnSave.isEnabled = false

        ApiClient.pushFamilyElderSettings(
            settings = body,
            clearHome = clearHome,
            onSuccess = { merged ->
                saving = false
                btnSave.isEnabled = true
                homeClearedPending = false
                // 服务端 merged 是最终权威：用它回填，而不是用本地 clamp 后的值。
                // 这样客户端与服务端 bounds 万一漂移，界面显示的仍是真实生效值。
                renderSettings(merged)
                val suffix = if (clamped.isEmpty()) "" else {
                    "\n已按范围收敛：" + clamped.joinToString("、") {
                        "${it.first} ${it.second.first} → ${it.second.second}"
                    }
                }
                Toast.makeText(this, "✅ 守护设置已保存并同步到老人手机$suffix",
                    Toast.LENGTH_LONG).show()
            },
            onError = { err ->
                saving = false
                btnSave.isEnabled = true
                // 方向与老人端相反：这里失败 = 云端没改成，老人端仍在按旧参数守护。
                // 不能沿用老人端那句"本机已按新设置运行"。
                AlertDialog.Builder(this)
                    .setTitle("保存失败")
                    .setMessage("原因：$err\n\n这次改动没有保存，老人手机仍在按原来的参数守护。\n\n请检查网络后重试。")
                    .setPositiveButton("重试") { _, _ -> onSaveTap() }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                Toast.makeText(this, "位置权限被拒绝，可用「地图选点」代替", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

- [ ] **Step 2: 注册到 Manifest**

在 `AndroidManifest.xml` 里找到 `.family.GeofenceManageActivity` 那一行，在其后加入：

```xml
        <activity
            android:name=".family.ElderGuardSettingsActivity"
            android:exported="false"
            android:label="守护设置" />
```

- [ ] **Step 3: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

常见报错与处置：
- `Unresolved reference: activity_elder_guard_settings` → Task 11 布局没建或文件名拼错
- `Unresolved reference: bg_geofence_summary` → 该 drawable 只在围栏布局里用过，确认它存在于 `res/drawable/`

- [ ] **Step 4: 提交**

```
git add android/app/src/main/java/com/antifraud/guard/family/ElderGuardSettingsActivity.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat: 子女端守护设置页"
```

---

## Task 13: 控制台入口

**Files:**
- Modify: `android/app/src/main/res/layout/fragment_family_dashboard.xml:275-283`
- Modify: `android/app/src/main/java/com/antifraud/guard/family/DashboardFragment.kt:68-74`

- [ ] **Step 1: 布局加按钮**

在 `fragment_family_dashboard.xml` 的 `btn_geofence`（`:275-283`）之后、`</LinearLayout>` 之前加入：

```xml
            <Button
                android:id="@+id/btn_guard_settings"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="10dp"
                android:background="@drawable/bg_btn_primary"
                android:text="⚙️ 守护设置（家基准 · 阈值）"
                android:textColor="#FFFFFF"
                android:textSize="14sp" />
```

- [ ] **Step 2: 接线**

在 `DashboardFragment.kt` 的 `btn_geofence` 点击监听（`:68-74`）之后加入：

```kotlin
        // 守护设置（家基准 + 阈值）：子女端是这些参数的唯一入口
        view.findViewById<Button>(R.id.btn_guard_settings).setOnClickListener {
            if (!GuardConfig.isFamilyBound) {
                Toast.makeText(context, "请先绑定老人", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), ElderGuardSettingsActivity::class.java))
        }
```

若报 `Unresolved reference: Intent`，在文件顶部 import 区加入 `android.content.Intent`。

- [ ] **Step 3: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```
git add android/app/src/main/res/layout/fragment_family_dashboard.xml android/app/src/main/java/com/antifraud/guard/family/DashboardFragment.kt
git commit -m "feat: 控制台新增守护设置入口"
```

---

## Task 14: 老人端设置页规则改只读

**Files:**
- Modify: `android/app/src/main/res/layout/activity_settings.xml:47`、`:84`、`:127`、`:140`、`:163`
- Modify: `android/app/src/main/java/com/antifraud/guard/SettingsActivity.kt:44-105`、`:313-366`

- [ ] **Step 1: 布局：4 个 EditText 换只读 TextView**

把 `activity_settings.xml:41-70` 的通话预警时长区块（`et_call_threshold` 那个 `EditText`）替换为：

```xml
                <TextView
                    android:id="@+id/tv_call_threshold"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:background="@drawable/bg_input"
                    android:padding="12dp"
                    android:textColor="#1E293B"
                    android:textSize="14sp" />
```

对 `et_payment_threshold`、`:127` 的 `et_rec_segments`、`:140` 的 `et_rec_segment_minutes` 做同样处理，id 依次改为：

```
tv_call_threshold
tv_payment_threshold
tv_rec_segments
tv_rec_segment_minutes
```

每处 `EditText` 的 `android:hint`（如 `100~5000`、`1~6`）改为 `android:text`，让范围提示仍然可见。

`tv_rec_minutes_hint`（`:163`）的默认文案从「当前：…」改为 `android:text=""`，内容改由代码填充。

- [ ] **Step 2: 加只读区标题**

在这 4 个字段之前插入一个说明 TextView（放在原第一个输入框的位置）：

```xml
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@drawable/bg_card"
                android:lineSpacingExtra="4dp"
                android:padding="14dp"
                android:text="守护参数由子女在子女端 App 的「守护设置」里配置。这里只展示当前生效值，如需调整请让子女协助 —— 老人端保持只有一个写入方，避免两边改出矛盾。"
                android:textColor="#475569"
                android:textSize="13sp" />
```

- [ ] **Step 3: 删掉 TextWatcher，改静态渲染**

把 `SettingsActivity.kt:44-105` 里与 4 个 EditText 相关的一段替换为：

```kotlin
        val tvFamilyName     = findViewById<TextView>(R.id.tv_family_name)
        val etElderName      = findViewById<EditText>(R.id.et_elder_name)
        val etElderPhone     = findViewById<EditText>(R.id.et_elder_phone)
        val btnSave          = findViewById<Button>(R.id.btn_save_settings)
        tvHealthReport       = findViewById(R.id.tv_health_report)
        tvHealthAdvice       = findViewById(R.id.tv_health_advice)

        setupHealthPanel()

        renderGuardParamsReadOnly()

        etElderName.setText(if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName)
        etElderPhone.setText(GuardConfig.elderPhone)
        renderFamilyName(tvFamilyName)

        btnSave.setOnClickListener { view ->
            if (saving) {
                Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(etElderName, etElderPhone, btnSave)
        }
```

同时删除 `:69-94` 的 `updateRecHint` 局部 lambda 与两个 `addTextChangedListener` 调用、`:35-36` 之外的 `etCallThreshold` / `etPaymentThreshold` / `etRecSegments` / `etRecSegmentMinutes` / `tvRecMinutesHint` 局部变量声明（它们已改成只读 TextView，不再需要 EditText 引用）。

**只读 TextView 不要存成成员字段** —— `renderGuardParamsReadOnly()` 内部直接 `findViewById` 取，避免"字段没初始化就调方法"的崩溃（`lateinit` 在 `onCreate` 之前访问会抛 `UninitializedPropertyAccessException`）。

- [ ] **Step 4: 加只读渲染方法**

在 `renderFamilyName`（`:301`）之前插入：

```kotlin
    /**
     * 只读展示守护参数。
     *
     * 这些值的事实源在云端 guard_settings，写入方是子女端「守护设置」页。
     * 只读展示不是敷衍：老人被子女问起时能直接念出数字，
     * 这在纠纷场景里是唯一有价值的证据。
     */
    private fun renderGuardParamsReadOnly() {
        val seg = GuardConfig.recordingMaxSegments
        val per = GuardConfig.recordingSegmentMinutes
        findViewById<TextView>(R.id.tv_call_threshold).text =
            "通话预警时长：${GuardConfig.callThresholdMinutes} 分钟"
        findViewById<TextView>(R.id.tv_payment_threshold).text =
            "大额支付预警：¥${GuardConfig.paymentThreshold.toInt()}"
        findViewById<TextView>(R.id.tv_rec_segments).text = "单次录音：最多 $seg 段"
        findViewById<TextView>(R.id.tv_rec_segment_minutes).text = "每段时长：$per 分钟"
        findViewById<TextView>(R.id.tv_rec_minutes_hint).apply {
            text = "当前合计约 ${seg * per} 分钟"
            setTextColor(if (per >= 8) 0xFFFBBF24.toInt() else 0xFF10B981.toInt())
        }
    }
```

- [ ] **Step 5: save() 删掉阈值处理**

把 `SettingsActivity.kt:313-366` 中从 `val callMin = ...` 到 `GuardConfig.recordingSegmentMinutes = perSegment` 的整段删掉，方法签名与开头改为：

```kotlin
    private fun save(
        etElderName: EditText,
        etElderPhone: EditText,
        btnSave: Button
    ) {
        val newName = etElderName.text.toString().trim()
```

保留其后 `:368-382` 的姓名/手机号处理（`normalizePhoneInput`、格式校验、`finalPhone`、`profileChanged`）。删完后 `btnSave.isEnabled` 的重置逻辑若随阈值代码一起被删，需补回 `onError` 分支里的 `btnSave.isEnabled = true`。

- [ ] **Step 6: onResume 同步刷新只读值**

把 `SettingsActivity.kt:114-117` 的 `onResume` 改为：

```kotlin
    override fun onResume() {
        super.onResume()
        // 云端配置可能在子女端被改过，进页面时刷新只读展示，
        // 否则老人看到的是上次进页面时的旧值
        ApiClient.fetchElderSettings(
            onSuccess = { s ->
                s?.let { GuardConfig.applySettingsFromServer(it) }
                renderGuardParamsReadOnly()
            },
            onError = { /* 拉取失败时保留本机值，不打扰用户 */ }
        )
        if (::tvHealthReport.isInitialized) refreshHealth()
    }
```

- [ ] **Step 7: 确认 pushElderSettings 调用点仍在**

`SettingsActivity.kt` 的 `pushSettingsThenFinish`（`:411-433`）与 `submitElderProfile`（`:446-477`）里的 `ApiClient.pushElderSettings` 调用**保持不变**。

理由：老人端换手机时靠这条恢复规则。若彻底删除，Phase 0.5 建立的「规则跟着账号走」会在换机后断掉 —— 老人端仍持有本机规则值（`GuardConfig` 的 SharedPreferences），上行一次只是把它同步回云端，不会覆盖子女端的改动（服务端是增量合并，且子女端有优先的显式设置流程）。

- [ ] **Step 8: 编译验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android compileDebugKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`。若报 `Unresolved reference: et_call_threshold`，说明 Step 1 的 id 改名有遗漏。

- [ ] **Step 9: 提交**

```
git add android/app/src/main/res/layout/activity_settings.xml android/app/src/main/java/com/antifraud/guard/SettingsActivity.kt
git commit -m "refactor: 老人端守护参数改只读，写入方收敛到子女端"
```

---

## Task 15: 回写路线图

**Files:**
- Modify: `docs/superpowers/plans/2026-10-07-hardening-roadmap.md`

- [ ] **Step 1: 更正测试计数**

全文搜索 `112 例`，把 Phase 0 与 Phase 1 完成记录中的计数更正为 143（并注明后续新增的测试文件）。

- [ ] **Step 2: 补记 1-9 / 1-10 的真实状态**

在 Phase 1 表格的 1-9、1-10 两行后追加说明：此前 1-9 的生产端（子女端写入 UI）与 1-10 的 UI 入口均缺失，本次已补齐于子女端 `ElderGuardSettingsActivity`，老人端改只读。

- [ ] **Step 3: 更新 R6 行**

把风险登记册 R6 的缓解列改为：UI 标注已完成（使用情况访问标为 ⚠️ 降级）；厂商专属跳转 intent 待真机验证后再补，遵循 VendorPermissionHelper「未真机验证的组件不猜」铁律。

- [ ] **Step 4: 更新执行状态表**

把 §9 的 Phase 1 行补记 1-9 生产端补齐的时间点与新增页面名。

- [ ] **Step 5: 提交**

```
git add docs/superpowers/plans/2026-10-07-hardening-roadmap.md
git commit -m "docs: 回写路线图 1-9/1-10/R6 真实状态"
```

---

## Task 16: 变异验证与全量回归

- [ ] **Step 1: 注入变异 1 —— 白名单改成黑名单**

把 `services/guardSettingsPolicy.js` 的 `pickFamilyWritable` 改为：

```javascript
function pickFamilyWritable(settings) {
  return Object.assign({}, settings);
}
```

Run: `node --test tests/guardSettings.test.js`
Expected: FAIL，`信任号码白名单对子女端关闭` 等用例失败

Run 完整命令确认失败原因符合预期，然后恢复。

- [ ] **Step 2: 注入变异 2 —— 删掉 homeCleared 分支**

把 `mergeFamilySettings` 改为：

```javascript
function mergeFamilySettings(stored, incoming, homeCleared) {
  return Object.assign({}, stored || {}, incoming || {});
}
```

Run: `node --test tests/guardSettings.test.js`
Expected: FAIL，`homeCleared 删除两个家坐标键` 失败

恢复。

- [ ] **Step 3: 注入变异 3 —— bounds 上下界写反**

把 `GuardSettingBounds.kt` 的 `awayRadius` 改为：

```kotlin
    fun awayRadius(v: Int): Int = v.coerceIn(AWAY_RADIUS_MAX, AWAY_RADIUS_MIN)
```

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain --tests "*GuardSettingBoundsTest*"
```
Expected: FAIL（`coerceIn` 参数反了会抛 `IllegalArgumentException`，测试红）

恢复。

- [ ] **Step 4: 注入变异 4 —— resetHomeBase 少清一个字段**

把 `resetHomeBase` 改为：

```kotlin
    fun resetHomeBase() {
        if (!homeSet) return
        homeSet = false
    }
```

（删掉 `stayStartTime = 0L`）

Run: `compileDebugKotlin`
Expected: 仍 `BUILD SUCCESSFUL` —— 这行**没有测试覆盖**，如实记录为已知盲区。不要为了让它变红而临时加断言然后删掉。

- [ ] **Step 5: 全量回归**

Run: `npm test`
Expected: `# fail 0`，`# pass` ≥ 159

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`，全部 JVM 测试通过

- [ ] **Step 6: 打包验证**

Run:
```
& "D:\Android\gradle\gradle-8.7\bin\gradle.bat" --project-dir android assembleDebug --console=plain
```
Expected: `BUILD SUCCESSFUL`，产出 `android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 7: 核验 APK 组件**

Run:
```
& "D:\Android\build-tools\33.0.2\aapt2.exe" dump xmltree --file AndroidManifest.xml android/app/build/outputs/apk/debug/app-debug.apk | Select-String "ElderGuardSettingsActivity"
```
Expected: 能命中该组件名

- [ ] **Step 8: 提交（若有残留改动）**

```
git status --short
```
若工作区干净则跳过本步。

---

## 手工验证清单（无法自动化）

按顺序执行，每步都应有可观察的结果。

| # | 步骤 | 期望结果 |
|---|---|---|
| 1 | 子女端进「控制台 → 守护设置」 | 页面打开，7 个阈值输入框有值，家基准区显示「尚未设置」或坐标 |
| 2 | 点「🗺️ 地图选点」，选一个点确认 | `tv_home_base` 变成「当前基准：xx.xxxxxx, yy.yyyyyy」 |
| 3 | 不点保存直接返回，再次进入 | 坐标回到云端值 —— 未保存的改动不应持久化 |
| 4 | 点「🗑️ 清除家基准」→ 「标记清除」 | `tv_home_base` 追加「已标记清除，点下方保存后才生效」 |
| 5 | 填 `999999` 到离家半径，点保存 | Toast 提示「已按范围收敛：离家半径 999999 → 5000」，且 `et_away_radius` 回填 5000 |
| 6 | 断网，点保存 | 弹「保存失败 / 这次改动没有保存，老人手机仍在按原来的参数守护」—— **不是**「本机已生效」 |
| 7 | 恢复网络，保存成功，老人端在线 | `adb logcat -s LocationGuard` 出现「家基准已重置，下次位置更新将重新取云端配置」 |
| 8 | 老人端设好家基准后再断网保存 | 30 分钟内 `adb logcat -s LocationGuard` 应出现「已从云端恢复守护规则：家的基准位置…」 |
| 9 | 老人端设置页 | 4 个参数显示为只读文本，标题为「守护参数由子女在子女端 App 的「守护设置」里配置」 |
| 10 | 老人端健康自检，未授予使用情况访问 | 第 ⑥ 项显示「⚠️ 未开启（通话联动告警已停用）」，建议区在「⚠️ N 项功能降级」桶里 |
| 11 | 首页（`MainActivity`） | 横幅里**不出现**使用情况访问这一项 |

---

## 收尾

- [ ] 更新路线图 §9 执行状态表
- [ ] 把「WS 下发链路待真机验证」记入路线图 §3.5 的遗留问题（现 L1 之后新增一条）
- [ ] 告知用户：变异 4 暴露的 `stayStartTime` 未覆盖是已知盲区，需在后续补充

---

## 附：与设计文档的对应关系

| 设计文档章节 | 对应任务 |
|---|---|
| §2.1 family 路由 | Task 2 |
| §2.2 字段白名单 | Task 1 |
| §2.3 清除家基准 | Task 1 Step 8-9、Task 2 Step 5 |
| §2.4 WS 通知 | Task 3 |
| §3.1 页面 | Task 11、Task 12 |
| §3.2 布局 | Task 11 |
| §3.3 范围校验 | Task 4 |
| §3.4 保存失败文案 | Task 12 `onSaveTap` 的 `onError` |
| §3.5 复用而非复制 | Task 9 |
| §4.2 WS 推送 | Task 7 |
| §4.3 homeSet 闩锁 | Task 5、Task 6 |
| §4.4 拉取兜底 | Task 6 |
| §4.5 过期文案 | Task 6 Step 6、Task 7 Step 3、Task 8 |
| §5 老人端只读 | Task 14 |
| §6.1 告警等级 | Task 8 |
| §6.2 首页不加 | Task 8 Step 4 |
| §6.3 不补厂商 intent | 无需改动（仅路线图回写，见 Task 15） |
| §7 测试 | Task 1、2、4、5、16 |
| §8.1 路线图回写 | Task 15 |
