# 子女端守护设置页 + R6 告警等级修正 · 设计

> 日期：2026-10-08 ｜ 关联路线图：Phase 1 的 1-9、1-10，风险登记册 R6
> 状态：设计已确认，待实现

---

## 1. 要解决的两件事

### 1.1 1-9「家基准」只有消费端，没有生产端

Phase 1 标记 1-9 已完成，但核对代码后发现：消费端齐全，生产端为零。

| 环节 | 状态 | 落点 |
|---|---|---|
| 云端存储与校验 | ✅ | `routes/auth.js:99-100` bounds、`:116-125` sanitize |
| 老人端消费 | ✅ | `LocationGuardService.kt:225-236` 云端优先、`:441-463` 首次设家告知 |
| 老人端本地缓存 | ✅ | `GuardConfig.kt:360-371` `homeLat`/`homeLng`/`hasFamilyHome` |
| **子女端写入 UI** | ❌ | 全工程无任何代码写 `homeLat`/`homeLng`，只能手调 `POST /api/elder-settings` |

后果有两处，都很糟：

1. `LocationGuardService.kt:441-463` 的一次性通知里写着「请在子女端设置中修正」，而那个页面不存在。老人（及帮老人装 App 的子女）按提示去找，找不到。
2. 「首次定位即家」的兜底路径长期是唯一可用路径 —— 老人在商场首次打开 App 就出门，之后所有「离家」判定全部失真且无从发现。

### 1.2 1-10 的「可配置」那一半从未落地

路线图 1-10 标 ✅ 的实际内容是「500/100/40 三个硬编码搬进 `GuardConfig` 并接上云端同步」。但 `activity_settings.xml` 的输入框只有四个：`et_call_threshold` / `et_payment_threshold` / `et_rec_segments` / `et_rec_segment_minutes`。

`homeAwayRadiusMeters` / `stayMoveMeters` / `homeStayMinutes` 在整个 Android UI 层零出现 —— `GuardConfig.kt:397-399` 的 `currentSettingsPayload()` 只是把这三个**代码默认值**（500/100/40）原样推到云端。

所以这三项至今**没有任何人能改**。这是个比 1-9 更隐蔽的问题：它看起来是"已接好同步只差 UI"，实际是"同步的是默认值"。

### 1.3 R6 的缓解措施未兑现，且现有文案是错的

风险登记册 R6 承诺「明确降级路径 + UI 标注未启用状态」。实际：

- UI 标注的字符串「通话中的支付行为联动将不可用」**全工程不存在** —— 它只出现在 `CallRiskWatcher.kt:41-42` 的注释里，该注释还声称设置页已标注。写注释时以为改了。
- `SettingsActivity.kt:221` 显示 `ℹ️ 未开启（不影响现有功能）`。
- `SettingsActivity.kt:257` 文案是「Phase 1 的「通话中打开支付 App」行为判定依赖它，该功能尚未上线」。

最后这句现在是**假信息**。功能已上线（`CallRiskWatcher`），未授权时 `sampleForegroundOnce()` 第一件事就 return（`:167-173`），`COERCION_RISK` 告警完全不会发生 —— 这是高危告警的静默失效，性质与「通知使用权缺失 → 支付监听不触发」同类，属 ⚠️ 降级。

### 1.4 决策：子女端成为规则唯一写入方

老人端保留设置页（健康自检面板 + 姓名/手机号），但**规则参数全部改为只读**。

理由：老人不会配置，让他们改这些参数只会产生误操作；而阈值是否合理，判断者是子女。同时单一写入方彻底消除「子女改了、老人端页面显示旧值、实际已被覆盖」这类矛盾。

---

## 2. 服务端设计

### 2.1 新增子女端写入路由

```js
// routes/auth.js
router.post('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => { ... });
router.get('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => { ... });
```

`elderId` **只从路径参数取，绝不读 `req.body.elderId`**。`requireBoundElder`（`services/tokenAuth.js:38-50`）已经校验「token 用户本人或与该 elderId 存在绑定关系」，所以路径参数是经过校验的；body 里的同名字段一律忽略。

路径参数是必需的，不是命名风格问题：`requireBoundElder:39` 从 `req.params.elderId ?? req.params.id ?? req.params.userId` 取值，取不到直接 400。所以不能设计成无路径参数的 `POST /elder-settings/family`。

现状是 `POST /elder-settings`（`:633`）零鉴权 —— 任何知道 `elderId` 都能改老人的守护规则。这属于路线图 G15（6 个写入接口无鉴权，Phase 4 待办）。新接口从第一天就带鉴权，不把第 7 个无鉴权写接口加进去。

读取端一并收口：新增 `/elder-settings/family/:elderId` 供子女端使用，原 `GET /elder-settings`（`:622`，老人端换机恢复用，免登录）保持不变。

### 2.2 字段白名单

子女端可写（对齐 `GUARD_SETTING_BOUNDS` 现有字段）：

| 字段 | bounds | 说明 |
|---|---|---|
| `callThresholdMinutes` | 1~240 | 通话预警时长（分钟） |
| `paymentThreshold` | 1~1000000 | 支付预警金额（元） |
| `recordingMaxSegments` | 1~6 | 录音段数 |
| `recordingSegmentMinutes` | 1~10 | 单段录音时长（分钟） |
| `recordingAutoUpload` | bool | 自动上传开关 |
| `homeLat` / `homeLng` | -90~90 / -180~180 | 家基准坐标 |
| `homeAwayRadiusMeters` | 100~5000 | 离家判定半径（米） |
| `stayMoveMeters` | 20~1000 | 停留判定半径（米） |
| `homeStayMinutes` | 5~240 | 陌生地点停留时长（分钟） |

显式排除两个，各有理由：

- **`trustedCallNumbersJson`** — 语义是"老人信任谁"（免打扰）。子女用自己的社交关系替老人做防诈白名单决策，而信任链正是诈骗话术主要利用的东西。子女端不提供这个口。
- **`highRiskPackages`** — 改它会直接改变老人端的告警面（把某 App 从高危表移除，对应类型的告警就静默消失）。这是运维级动作，误操作后果是漏报。后续需要远程规则管理时单独设计，不混在守护参数里。

实现为一个显式常量数组 `FAMILY_WRITABLE_SETTINGS`，不做黑名单 —— 新增字段时默认不可写，必须主动加进来。黑名单会在字段增多后意外放行。

### 2.3 清除家基准

现有 `sanitizeGuardSettings` 的 `homeLat`/`homeLng` 没有 `def`，而客户端 `hasFamilyHome`（`GuardConfig.kt:371`）把 `0/0` 当"未设置"。两条路径都不能表达"清除"。

新增请求字段 `homeCleared: true`。

`sanitizeGuardSettings`（`routes/auth.js:103-127`）只处理 `GUARD_SETTING_BOUNDS` 里的键，`homeCleared` 会被静默丢掉，所以必须在 sanitize **之前**从原始 body 读出来：

```js
const raw = req.body && req.body.settings;
const homeCleared = !!(raw && raw.homeCleared === true);
const clean = pickFamilyWritable(sanitizeGuardSettings(raw));

// 先应用再清除：清除必须最后生效，
// 否则同一请求里若既传了 homeCleared 又传了新坐标，行为会依赖 Object.assign 的键序
const merged = Object.assign({}, parseGuardSettings(elder.guard_settings) || {}, clean);
if (homeCleared) {
  delete merged.homeLat;
  delete merged.homeLng;
}
```

必须在 family 路由里显式 delete 已有的 home 键。靠"不下发就保留旧值"的默认合并行为，用户点了清除会什么都没发生，且界面上看不出异常。

客户端不并发发这两个（清除按钮与保存按钮互斥），但服务端仍按"清除优先"处理，避免将来任何一次误调用产生歧义。

服务端回传 merged 后，客户端用 merged 覆盖本机 —— 服务端永远是最终权威（沿用 `SettingsActivity.pushSettingsThenFinish:415` 的既有做法）。

### 2.4 WS 通知

`auth.js` 新增 `setHub({ notifyElderSettingsChanged })`，由 `server.js` 注入，复用已有的 `clients` Map（`server.js:46`）。与 `recordingsRoutes.setHub`（`server.js:81-84`）同一套路，路由不反向依赖 server。

```js
{ type: 'ELDER_SETTINGS_UPDATED', data: { settings: merged, changedBy: 'family' } }
```

**老人端离线时不缓存这条。** 与 `pendingInterrupts`（`server.js:50`）的取舍相反：指令有时效性，错过就该作废；配置没有时效性，缓存一份陈旧配置反而会在上线时覆盖掉更新的值。离线老人靠下次拉取兜底。

---

## 3. 子女端设计

### 3.1 页面

新建 `family/ElderGuardSettingsActivity.kt` + `res/layout/activity_elder_guard_settings.xml`。

入口：`DashboardFragment` 的「敏感地点围栏」卡片旁新增 `btn_guard_settings`。**不占底部菜单** —— `menu_family_bottom.xml` 已有 5 项（控制台/防诈告警/亲情轨迹/维权证据/防护指南），再加第 6 项会让老人端 App 的子女侧导航过载。

进入时 `GET /api/auth/elder-settings/family/{boundElderId}` 拉一次，填入各输入框。

### 3.2 布局

```
🏠 家基准位置
   当前基准：已设为 28.228000, 112.938000
   [🗺️ 地图选点]  [📍 用当前位置]
   [🗑️ 清除家基准]
   ⓘ 未设置时老人端以「首次定位到的位置」为准。建议尽早设置：
     老人在商场首次打开 App 就出门的话，之后的离家判定会全部失真。

⚙️ 位置守护阈值
   离家判定半径      [500 ] 米    100~5000
   停留判定半径      [100 ] 米    20~1000
   陌生地点停留时长  [40  ] 分钟   5~240

📞 通话与支付
   通话预警时长      [15  ] 分钟   1~240
   支付预警金额      [500 ] 元     1~1000000

🎙️ 录音存证
   自动上传          [开关]
   录音段数          [3   ] 段     1~6
   单段录音时长      [10  ] 分钟   1~10
   当前：约 30 分钟。弱网下单段越短越不容易上传失败。

[保存守护设置]
```

只显示坐标，不显示地名。子女端接 `regeo` 的地名链路需要一次服务端往返，收益不抵复杂度 —— 坐标足以让子女确认"是不是那个小区"。

### 3.3 范围校验

客户端 `coerceIn` 与服务端 `GUARD_SETTING_BOUNDS`（`routes/auth.js:90-101`）现在是两处独立手写的数字，会漂。

新增 `util/GuardSettingBounds.kt` 作为客户端唯一来源，所有 clamp 从这里读，注释指明必须与服务端保持一致。跨语言一致性无法用测试断言，改为：客户端 clamp 后提交，**保存成功后用服务端 merged 覆盖本机**（§2.3）。

超出范围不静默接受也不直接报错 —— 沿用 `SettingsActivity.kt:341-359` 的既有处理：明确告知会收敛到哪个值。静默 clamp 会让人以为设了 9 段、实际生效 6 段，然后以为系统有 bug。

### 3.4 保存失败的文案

与老人端 `pushSettingsThenFinish` 的处理**方向相反**：

| | 老人端 | 子女端 |
|---|---|---|
| 本机状态 | 已生效 | 未生效（子女端本机没有这套参数，事实源在云端） |
| 文案重点 | 「已生效但未同步云端，换手机可能恢复成旧设置」 | 「云端未修改，老人端仍在按旧参数守护」 |

子女端文案**不能说**"下次同步会覆盖回旧值" —— 云端本来就是事实源，保存失败时它从未变过，不存在覆盖。照搬老人端那句话会让人以为参数被谁改了。

子女端保存失败必须说清"这次改动没有生效"，不能沿用老人端那句"本机已按新设置运行"。

### 3.5 复用而非复制

- `MapPickerActivity` 直接用。它不接受 input extras，初值来自 `GET /api/events/location/{elderId}`（`:127`），返回 `RESULT_OK` + `extra_lat`/`extra_lng`（WGS-84）。照 `GeofenceManageActivity.kt:56-65` 的 `pickLauncher` 写法接。
- 「用当前位置」：把 `GeofenceManageActivity.kt:253-301` 的 `fillFromLastKnownOrListen` 抽成 `util/OneShotLocation.kt`，两处共用。复制一遍的后果是两边的兜底策略会各自漂移。

---

## 4. 配置下发设计

### 4.1 问题

子女端保存后老人端完全不知道。老人端拉云端配置只有两个时机：「重新登记」（`MainActivity.kt:947,1432,1519`）和「自己点保存设置」（`SettingsActivity.kt:415,466`）。都不发生时，子女改的参数永远不生效。

### 4.2 WS 推送

`GuardWebSocketManager` 新增 `ELDER_SETTINGS_UPDATED` 分支：

```
收到 → GuardConfig.applySettingsFromServer(data.settings)
     → 通知 LocationGuardService 重算家基准
```

### 4.3 必须一起修的缺陷：homeSet 一次性闩锁

`LocationGuardService.kt:225-234`：

```kotlin
if (!homeSet) {
    if (GuardConfig.hasFamilyHome) { homeLat = ...; homeLng = ... }
    else { homeLat = lat; homeLng = lng; notifyHomeBaseSet(lat, lng) }
    homeSet = true
    stayStartTime = System.currentTimeMillis()
}
```

`homeSet` 置 true 后永不回退。**云端家基准改了之后，这三个字段永远停在旧值，改设置完全无效且无任何报错。** 只做 WS 下发而不修这里，1-9 依然是坏的。

新增 `resetHomeBase()`：清 `homeSet` 与 `stayStartTime`，下次 `handleLocation` 重新取基准。

`stayStartTime` 必须一起清。家基准换了之后，旧的停留计时是按旧基准算出来的，不清会让老人在刚改完设置的一瞬间就撞上一条 `LOCATION_RISK`「在陌生地点停留超 40 分钟」—— 而他可能根本没出门。

清除家基准后也会走到回退分支（首次定位 + 一次性告知），这正是期望行为。

**服务未运行时收到 WS 指令**：此时 `LocationGuardService` 进程可能不存在，`resetHomeBase()` 无处可调。`GuardConfig` 是 SharedPreferences，`applySettingsFromServer` 已经把新值落盘，下次服务启动时 `homeSet` 天然是 false，直接取到新值。所以只有「服务正在运行」这一种情况需要主动重置 —— 按 `GuardServiceStarter.isRunning()` 判断，服务没跑就不用通知。

### 4.4 拉取兜底

- `LocationGuardService.onCreate` 拉一次（`:111` `fetchGeofences()` 旁边加）。
- 后续复用 `fetchGeofences` 已有的 30 分钟节流时钟（`:214`），不额外增加请求。

拉取路径也必须触发 `resetHomeBase()`，不能只有 WS 路径触发 —— 否则 WS 恰好断着的时候改了家基准，拉取虽然把 `GuardConfig.homeLat` 更新了，内存里的 `homeLat` 仍是旧值，行为与"没生效"完全一致且无任何日志。

判定「家基准是否变了」有现成依据：`GuardConfig.applySettingsFromServer` 返回被实际覆盖的字段名列表（`GuardConfig.kt:420`），其中包含 `"家的基准位置"`（`:462`）。以该返回值驱动重置，不自己比对坐标 —— 自己比对会漏掉"服务端值与本机相同但内存值不同"这类状态。

WS 断连、指令丢失、老人端长时间离线 —— 三种情况都由这条兜底覆盖。

### 4.5 顺手修的过期文案

| 位置 | 问题 |
|---|---|
| `CallRiskWatcher.kt:41-42` | 声称设置页已标注某个不存在的字符串 |
| `SettingsActivity.kt:215` | 「Phase 1 预留项」 |
| `SettingsActivity.kt:230` | 「Phase 1 的行为判定还没做」 |
| `SettingsActivity.kt:253-255` | 「该项将在 Phase 1 由通话状态监听替代」—— Phase 1 已完成 |
| `SettingsActivity.kt:257` | 「该功能尚未上线」—— 已上线 |

---

## 5. 老人端设置页改造

`activity_settings.xml` 的 4 个 `EditText`（`et_call_threshold` / `et_payment_threshold` / `et_rec_segments` / `et_rec_segment_minutes`）改为只读 `TextView`，照 `tv_family_name`（`:232`）的既有做法。标题改为「守护参数（由子女设置）」。

`SettingsActivity` 相应改动：

- `save()` 删掉 4 个阈值的解析、校验、`coerceIn` 与本地写入（`:322-366`）。
- `TextWatcher` 实时换算提示（`:69-94`）改为静态文本，`tv_rec_minutes_hint`（`:163`）仍显示合计时长，但措辞改为「当前生效」。
- 保存按钮只提交姓名/手机号。
- **`pushElderSettings` 调用点保留** —— 资料同步成功后仍上行一次本机规则。原因：老人端换手机时靠这条恢复规则；若彻底删除，Phase 0.5 之前建立的「规则跟着账号走」会在换机后断掉。

---

## 6. R6 修正

### 6.1 告警等级：ℹ️ → ⚠️

未授予 `PACKAGE_USAGE_STATS` 时 `COERCION_RISK` 完全不发生（`CallRiskWatcher.kt:167-173` 直接 return），性质是高危告警静默失效。

| 位置 | 现状 | 改为 |
|---|---|---|
| `SettingsActivity.kt:221` | `ℹ️ 未开启（不影响现有功能）` | `⚠️ 未开启（通话联动告警已停用）` / `✅ 已开启` |
| `SettingsActivity.kt:256-259` | `upcoming` 桶 | 移入 `degraded` 桶，文案：「未开启则通话中打开支付 App / 远程控制软件不会告警，通话时长预警与陌生号码判定仍有效」 |
| `SettingsActivity.kt:230` 注释 | 「Phase 1 的行为判定还没做」 | 改为说明为何是降级而非备用 |

### 6.2 首页横幅：不加

`MainActivity.renderGuardAlertBanner:441-442` 刻意排除了这一项。新逻辑下它确实属 `warnings`，但**不加**：横幅是老人每天看的屏，而该权限在 MIUI 上实测被静默拦截、只能 UI 手开（§3.5 路线图 3.5 节实测记录）。把它放进首页横幅会让一个多数人开不了的权限变成日常噪音，反而稀释真正致命的告警。

设置页自检是用户主动去查的地方，标 ⚠️ 足够。横幅保持现状，注释更新为说明这个取舍的理由。

### 6.3 不补厂商跳转 intent

R6 承诺的「`VendorPermissionHelper` 补 4 厂商使用情况访问 intent」不做。

`VendorPermissionHelper` 的铁律是「未真机验证的组件不猜」—— 路线图 3.5 节实测删掉了两个不存在的 MIUI 组件，理由是「猜错的代价是点了没反应，比不给入口更糟」。华为/OPPO/vivo 三家无实测数据。

按钮⑥ 保持 `SystemPermissionState.openUsageAccessSettings`（`:103`）三级兜底 + `manualSteps(USAGE_ACCESS)` 手工路径文案。R6 登记册该行改为「厂商跳转待真机验证后再补」。

---

## 7. 测试

沿用 Phase 0.5 建立的 `node --test`，零新增依赖。

### 7.1 Node 侧（`tests/`）

| 文件 | 覆盖 |
|---|---|
| `tests/guardSettings.test.js` | `FAMILY_WRITABLE_SETTINGS` 白名单：`trustedCallNumbersJson` / `highRiskPackages` 被丢弃；越界值被 clamp；非数字被丢；`homeCleared:true` 删除两个 home 键且优先于同请求内的坐标；body 里的 `elderId` 被忽略 |

`pickFamilyWritable` 与 `homeCleared` 的合并逻辑设计成可直接 require 的纯函数（参照 `services/riskAlertPolicy.js` / `services/locationSensitivity.js` 的既有形态），不为了测它而起一个 HTTP server。

集成层面另需人工核对（无法自动断言）：路由确实挂上了 `requireFamilyAuth` + `requireBoundElder`，且未绑定关系时返回 403。

### 7.2 JVM 侧（`android/app/src/test/`）

现有 `LocationHeartbeatPolicyTest` / `LocationPermissionPlanTest` 是纯函数测试的先例。本次新增逻辑全部按这个模式设计：

| 文件 | 覆盖 |
|---|---|
| `GuardSettingBoundsTest.kt` | 每个字段的 clamp 上下界；越界输入的收敛结果 |

`resetHomeBase()` 的重置语义属于状态机，但触发逻辑可抽成纯函数 `shouldResetHomeBase(wasSet, cloudHomeChanged)`，一并测掉。

### 7.3 变异验证

按 Phase 0.5 的做法注入变异确认测试非空转：

- 白名单改成黑名单（`highRiskPackages` 可写）→ 应捕获
- `homeCleared` 分支删掉 → 应捕获
- bounds 上下界写反 → 应捕获

### 7.4 无法自动验证的部分

WS 下发到老人端生效这条链路需要两个客户端在线，只能真机验证。列入 Phase 1 的真机验证清单（§9 遗留问题 L1 已记「待真机验证」）。

---

## 8. 不做的事

| 项 | 原因 |
|---|---|
| `trustedCallNumbersJson` 子女端可写 | §2.2 |
| `highRiskPackages` 子女端可写 | §2.2 |
| 首页横幅加入使用情况访问告警 | §6.2 |
| 厂商使用情况访问跳转 intent | §6.3 |
| 老人端首次设家的「二次确认」 | 路线图 1-9 原文要求。§4.3 的 `resetHomeBase()` 落地后，老人端已有清晰的一次性告知通知；真正的二次确认需要老人端有修改入口，而 §5 已把参数全部移交给子女端 |
| 小程序端守护设置 | 路线图 Phase 5 范围。本设计只做 Android 子女端 |

---

### 8.1 对路线图状态的影响

本次实现后需要回写路线图的三处：

| 路线图位置 | 现状 | 应改为 |
|---|---|---|
| §3 Phase 1 表格 1-10 | `✅`（含 1-10 说明「500/100/40 硬编码 → GuardConfig」） | 补记：UI 入口此前缺失，由本次补齐于子女端；老人端改只读 |
| §5 风险登记册 R6 | 缓解列写「UI 标注未启用状态 + VendorPermissionHelper 补 4 厂商」 | 前半已完成（§6.1）；后半改为「待真机验证后再补」（§6.3） |
| §9 执行状态 Phase 1 行 | 「1-1~1-8、1-10、1-11 全部落地」 | 补记 1-9 的生产端此前缺失 |

另外 §3.5 的实测记录里，`npm test` 计数写的是 112 例，实际当前为 143 例（新增 `timeFormat` / `locationSensitivity` / `riskAlertPolicy` 三个测试文件）。实现时一并更正。

---

## 9. 遗留与后续

| # | 项 | 说明 |
|---|---|---|
| L1 | WS 下发链路待真机验证 | §7.4 |
| L2 | 边界值两端一致性靠服务端权威兜底 | 跨语言无法用测试断言，见 §3.3 |
| L3 | 围栏 `dwell_minutes = 0` 默认值意味着不告警 | `GeofenceManageActivity` 未做「半径/停留成对」约束。属 1-8 的遗留项，与本次无关 |
| L4 | `1-5` 频次判定仅在 `stranger == true` 时运行 | 与本次无关，属 1-5 已知局限 |
