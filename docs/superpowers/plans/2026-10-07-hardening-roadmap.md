# 长者防诈守护 · 加固与能力扩展路线图

> 状态：待执行 | 基线 commit：`6b9eba0` | 日期：2026-10-07
> 范围：5 条竞品调研建议 + 4 项范围外并入项，共 8 个 Phase

## 0. 背景与决策记录

### 0.1 竞品调研结论（摘要）

| 类别 | 代表 | 结论 |
|---|---|---|
| 官方免费 | 国家反诈中心、Family Link、Find My、腾讯鹅家守护 | 只覆盖**号码级/知识级**防护，无亲属联动、无线下场景、无事后举证 |
| 国际开源 | silverguard、lilyServes、Kavach、Saaya、Panopticon | 多数为竞赛 Demo 或境外云依赖；**无一做中国语境落地、无一做证据链** |
| 可复用零件 | SmsForwarder、Traccar、Pixel Telo、长辈明白卡 | 保活工程、端侧拦截、政策型设计值得抄 |
| 本项目护城河 | 中文语境落地、无感感知、告知式存证、四链证据包、三端打通 | 需固化为可验证的工程承诺 |

### 0.2 已决策事项（不可回退）

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| D1 | 隐私分级形态 | **单 APK + 运行时开关**（三档 `privacyMode`） | 维护简单；代价是不能宣称 Manifest 级不可联网，需靠机制自证（见 Phase 2） |
| D2 | 前台应用判定权限 | **`PACKAGE_USAGE_STATS`** | 保持零无障碍依赖，守住 README 合规叙事 |
| D3 | 官方数据融合深度 | **仅 L0 + L1** | 黑灰产号码库/工商比对需政府或运营商合作，MVP 不可得 |
| D4 | 范围外 4 项 | **全部并入** | 小程序接录音链路 / 安全加固 / 补测试 / 正式域名 |

### 0.3 明确不做（Out of Scope）

| 项 | 原因 |
|---|---|
| 黑灰产号码库、工商信息比对 | 需外部合作，数据不可得 |
| 端侧 LLM 实时通话分析（Kavach/Panopticon 路线） | 低端机 8B 推理占 8.8GB 内存；老人设备普遍 4GB |
| 通话音频流接入 | Android 禁止第三方接入 Telecom 音频流 |
| 双 APK（evidence/guard flavor） | D1 决策排除 |
| 无障碍服务 | 商店审核风险 + 与合规叙事冲突 |
| PDF 证据报告 | 依赖未引入；ZIP + 中文 txt 已满足立案提交 |
| 第三方时间戳（TSA） | 需付费采购，列入 Phase 7 后的独立评估 |

---

## 1. 现状基线（代码事实）

> 本节是所有 Phase 的依据。数字来源为全仓库 grep + 逐文件阅读。

### 1.1 已具备（可复用资产）

| 能力 | 实现位置 |
|---|---|
| 三前台服务常驻（location×2 / microphone×1） | `ForegroundGuardService` / `LocationGuardService` / `RecordingGuardService` |
| 录音可靠上传：SQLite 持久队列 + SHA-256 + 指数退避(10s→15min) + 网络恢复唤醒 + 冷启动续传 | `UploadQueue.kt` + `UploadQueueDbHelper.kt` + `ApiClient.uploadRecording` |
| 分段录音 10min/段 × 最多 6 段 + 停止时当前段不丢 | `RecordingGuardService.kt:188-248` |
| 录音分层保留 + 人工复核窗口 + 到期清理 | `services/recordingCleanup.js` + `recordings.retention_until` |
| 两级诈骗研判：26 条强正则 + 8 条弱信号 + LLM 兜底降级 | `services/fraudDetector.js` + `services/llmClient.js` |
| "分析失败 ≠ 安全"的工程纪律（ASR 失败一律 SUSPECT + 14 天保留） | `routes/recordings.js:223-256` |
| 地图/地点四层降级链（高德/腾讯 → 300m 锚点继承 → 120m 热点聚类 → 坐标串） | `services/regeo.js` + `services/placeGuess.js` |
| 证据包：ZIP（中文 txt 清单 + 真 SHA-256）+ JSON 摘要 + URL 签名播放鉴权 | `routes/recordings.js:475-579` / `routes/evidence.js` / `services/signToken.js` |
| WS 实时通道 + 老人离线时打断指令缓存补发 | `server.js:22` / `server.js:131-145` |
| 高德地图双模式（原生 SDK / WebView 瓦片回退）+ GCJ↔WGS 精确反纠偏 | `GeoConverter.kt` / `MapPickerActivity.kt` / `build.gradle:85` |
| 围栏 CRUD + 地图选点 + "用当前位置"一键填 | `GeofenceManageActivity.kt` / `GeofenceDialogHelper.kt` |
| 防护规则本机 + 云端双向同步 | `GuardConfig.kt:235-275` |
| WS 远程强打断 → 全屏意图通知（Android 10+ 合规通道） | `GuardWebSocketManager.kt:256-304` |
| 厂商（MIUI/EMUI/ColorOS/OriginOS）权限引导跳转 | `VendorPermissionHelper.kt` |

### 1.2 P0 级结构性缺口

| # | 缺口 | 证据 |
|---|---|---|
| G1 | **无开机自启**：Manifest 内无任何 `<receiver>`，无 `BOOT_COMPLETED` | `AndroidManifest.xml` 全文；`ForegroundGuardService.kt:28-31` 注释声称有，实则链路不存在 |
| G2 | **后台定位拿不到**：`ACCESS_BACKGROUND_LOCATION` 已声明，运行时从未申请"始终允许" | `AndroidManifest.xml:28` vs `MainActivity.kt:1116-1126` |
| G3 | **无第二重保活**：无 `requestRebind` / `AlarmManager` / `JobScheduler` / `WorkManager`；`WAKE_LOCK` 权限声明但 0 使用 | grep 0 命中 |
| G4 | **电池优化白名单**：无 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，无引导 | `VendorPermissionHelper.kt:55-67` 只覆盖自启动/后台弹出/权限管理 |
| G5 | **呼叫筛选服务大概率不被调用**：无 `ACTION_CHANGE_SCREENING_COMPOSITION` 引导 | grep 0 命中 |
| G6 | **通话监测三缺口**：无时长统计落库、无陌生号码判定（无 `READ_CONTACTS`）、无频次判定 | `CallScreeningGuardService.kt` 全文 72 行 |
| G7 | **`GuardConfig.callThresholdMinutes` 是死配置** | `CallScreeningGuardService.kt:51` 硬编码 `15`，从不读配置 |
| G8 | **支付告警后台被丢弃**：`NotificationListenerService` 走 `startActivity`，Android 10+ 静默丢弃，无全屏意图降级 | `NotificationPayListenerService.kt:69-76` |
| G9 | **无预录音打断**：README:93 宣称的"子女预录语音"0% 实现；`MediaPlayer` 仅存在于子女端播放器，`TextToSpeech` 全工程 0 命中，`assets/` 无音频 | `EmergencyAlertActivity.kt:141-163` 仅闹铃音播一次 + 无限震动 |
| G10 | **非真全屏**：主题是 `NoActionBar`，无 `FLAG_FULLSCREEN`/沉浸式，状态栏仍显示；无 `SYSTEM_ALERT_WINDOW` | `AndroidManifest.xml:144-152` |
| G11 | **`FLAG_KEEP_SCREEN_ON` 仅在 API<27 生效** | `EmergencyAlertActivity.kt:124-139` |
| G12 | **围栏静默开录**：`LocationGuardService.onEnterGeofence()` 直接启动录音，零告知 | `LocationGuardService.kt:238-244` |
| G13 | **围栏只有进出判定，无停留阈值**：`dwellMinutes` 概念 0 命中；"停留"判定只存在于"离家停留"且 500m/100m/40min 全硬编码 | `LocationGuardService.kt:200-220` / `:111-128` |
| G14 | **服务端零风控**：所有阈值在 Android 端；老人换机/被绕过则云端失明 | `server.js` 全文 |
| G15 | **6 个写入接口无鉴权** | `events.js:15` / `geofence.js:23` / `recordings.js:71` / `auth.js:441,549,601,612` |
| G16 | **WS 完全无鉴权**，直信客户端 `userId` | `server.js:123-126` |
| G17 | **绑定码无防爆破**：6 位纯数字、无次数限制、无有效期 | `auth.js:141-143` |
| G18 | **token 在进程内存 Map**，服务重启全员登出 | `tokenAuth.js:7` |
| G19 | **小程序零录音能力**：0 处 `/api/recordings`、0 处 `downloadFile`、0 处 `openInnerAudioContext`；"一键复制报案材料文本"实为复制 JSON | `wechat-miniprogram/pages/evidence/` |
| G20 | **微信订阅消息从未申请**：0 处 `requestSubscribeMessage`，HIGH 级推送链路不通 | grep 0 命中 |
| G21 | **硬编码 cpolar 临时域名** | `wechat-miniprogram/config.js:12` |
| G22 | **零自动化测试**：无 `npm test`、无测试框架；`fraudDetector`/`asr`/`regeo`/`zipWriter`/`signToken` 全无测试 | `package.json:7-12` |
| G23 | **队列升级丢数据**：`onUpgrade` 直接 `DROP TABLE` | `UploadQueueDbHelper.kt:80-84`；`RiskEventDbHelper.kt:37-40` 同 |
| G24 | **事件类型无 CHECK 约束**，裸字符串散落 6 处；README 声称的 `EVENT_SUSPICIOUS_CALL` 等 3 个名称在代码中从不存在 | `db.js:68-78` vs `README.md:64,70,78` |
| G25 | **ZIP 全内存打包**：逐个 `readFileSync` + `Buffer.concat`，超出几百 MB 即 OOM（文档却称支持 GB 级） | `zipWriter.js:127` + `recordings.js:502` |
| G26 | **轨迹只有 10 个点**：位置接口硬编码 `LIMIT 10` | `events.js:112` |
| G27 | **`is_sensitive` 字段从未被置 1** | `db.js:89` |
| G28 | **"AI 拍照识诈"无拍照能力**：0 处 `Camera`/`ImageCapture`/`ACTION_IMAGE_CAPTURE`，只有文本输入框 | `AiScanActivity.kt` + `activity_ai_scan.xml` |
| G29 | **`/api/ai/scan` 是假 AI**：7 个 `includes` 硬编码关键词，与 `fraudDetector` 26 条完全脱节 | `routes/ai.js:10-19` |

---

## 2. 目标架构

```
老人端 Android App
├── 可靠性层   BootReceiver · AlarmManager/JobScheduler 双保活 · 电池优化白名单
├── 感知层     通话状态(Phase1) · 前台高危App(Phase1) · 通知栏支付 · 位置/围栏/停留
├── 合规层     ConsentGate 统一告知门控 · privacyMode 三档 · 录音分级
├── 存证层     分段录音 · SHA-256 · 本地队列 · 告知式上传
└── 干预层     全屏覆屏 · 子女预录语音 · 一键拨号 · 全屏意图通知(后台兜底)

云端风险引擎 (新增 services/riskEngine.js)
├── 入参：客户端事件 + 客户端定级 + 客户端规则版本
├── 复核：影子模式记录 → 观察 2 周 → 切换覆盖
├── 官方源 L0：96110 来电 / 12381 短信话术 → FRAUD_PATTERNS
├── 地理源 L1：高德 POI 类目映射 → locations.is_sensitive
└── 输出：CLIENT / SERVER / CONFLICT 三源标记 + acked_at 处置闭环

子女端
├── Android family App：录音播放 / 围栏远程确认 / 语音录制
└── 微信小程序：录音链路 + 证据包下载 + 订阅消息 + 处置闭环
```

---

## 3. Phase 任务卡

### Phase 0 · 守护可靠性基线 ｜ 3 天 ｜ ✅ 编译通过（2026-10-07）

> **前置 Phase**：无 ｜ **消除缺口**：G1~G5, G7, G8, G12(部分), G23

**构建环境**（此前误判为"不存在"，实际在 `D:\Android`，PATH 里没有而已）：

| 项 | 路径 |
|---|---|
| Gradle | `D:\Android\gradle\gradle-8.7\bin\gradle.bat` |
| Android SDK | `D:\Android`（platforms: android-33；build-tools: 30.0.3 / 33.0.2；platform-tools 含 adb） |
| JDK | `D:\Program Files\Java\jdk-21.0.10`（`C:\Program Files\Java\latest\jdk-21` 是指向它的符号链接） |
| 复现命令 | `JAVA_HOME=... ANDROID_HOME=D:\Android D:\Android\gradle\gradle-8.7\bin\gradle.bat --project-dir android assembleDebug --console=plain` |

**验证结果**：`compileDebugKotlin` ✅ → `assembleDebug` ✅ → `app-debug.apk`（24.8 MB）→ `lintDebug` 仅剩 4 个**既有** Error。

#### 交付物

| ID | 任务 | 状态 | 产出 |
|---|---|---|---|
| 0-1 | 开机自启 | ✅ | 新建 `service/BootReceiver.kt`；Manifest 新增 `RECEIVE_BOOT_COMPLETED` + receiver，监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `QUICKBOOT_POWERON`(含 HTC)。同步重建保活调度（升级会清掉已注册闹钟） |
| 0-2 | 双保活兜底 | ✅ | 新建 `service/GuardKeepAliveScheduler.kt`：`setAndAllowWhileIdle` 10min + `JobScheduler`(15min, `setPersisted`) 双通道；`KeepAliveReceiver` / `KeepAliveJob` 落地点；**心跳留痕**（`recordHeartbeat` / `heartbeatGapMinutes`）用于真机读数 |
| 0-3 | 后台定位授权 | ✅ | `GuardServiceStarter.hasBackgroundLocation()` + 设置页跳转（跳应用详情页→权限→位置信息→始终允许） |
| 0-4 | 电池优化白名单 | ✅ | Manifest 加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；`VendorPermissionHelper.batteryOptimizationIntent()` 按品牌分流（MIUI/HarmonyOS/ColorOS/OriginOS），打不开时退系统页 |
| 0-5 | 呼叫筛选授权 | ✅ | 新建 `util/SystemPermissionState.kt`：`isCallScreeningRole()` + `requestCallScreeningRole()`，ROM 未实现 role 时退默认应用设置页 |
| 0-6 | 死权限 + type 修正 | ✅ | 删 `READ_CALL_LOG`（0 使用）；同时删掉 `MainActivity.checkAndRequestPermissions()` 里对它的申请（否则 checkSelfPermission 恒 DENIED 形成静默空循环）；`ForegroundGuardService` 移除与实际用途不符的 `foregroundServiceType="location"` |
| 0-7 | 队列迁移 | ✅ | 新建 `db/SqliteMigrations.kt`（幂等 `addColumnIfAbsent` / `createIndexIfAbsent` / `hasColumn`）；两个 DbHelper 的 `onUpgrade` 从 `DROP TABLE` 改为增量迁移，并新增 `onDowngrade` 不破坏数据 |
| 0-8 | Manifest `<queries>` | ✅ 已补（改判） | 初版判断"本阶段不需要"是**错的**：lint `QueryPermissionsNeeded` 指向 `VendorPermissionHelper`/`SettingsActivity` 里的 `resolveActivity`。已补 `<queries>`，显式声明 6 个系统设置 action + 9 个厂商包。**Phase 1 判断银行 App 是否安装时同样依赖它** |
| 0-4b | 撤掉 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ✅（计划外） | lint 报 `BatteryLife`：该权限在 Play 仅允许闹钟/日历/设备管理/企业 MDM 等场景，老人防诈类应用**很难过审**，一旦被拒影响整个上架。而实际行为并不需要它 —— 只把"电池优化列表"跳转成本降到零即可。已移除权限声明与 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 回退分支 |
| 0-9 | 修死配置 | ✅ | `CallScreeningGuardService` 改读 `GuardConfig.callThresholdMinutes`（原先硬编码 15，设置页改了完全无效），并上报 `threshold_minutes` 便于核对 |
| 0-10 | 修后台告警丢弃 | ✅ | 新建 `util/EmergencyAlertLauncher.kt`：三级降级链（前台 `startActivity` → 全屏意图通知 → 高优先级通知+警报音+震动）。`NotificationPayListenerService` 与 `GuardWebSocketManager` **统一收敛**到这一处 |
| 0-11 | 清理死代码 | ✅ | 删除 `GuardWebSocketManager.showFullScreenAlertNotification()`（已下沉到 EmergencyAlertLauncher）及失效常量/import |

#### 附带完成（超出原任务，但属于同一失效链路）

| 项 | 内容 |
|---|---|
| 服务拉起收敛 | 新建 `service/GuardServiceStarter.kt`：开机/闹钟/Job/界面四条路径共用同一套判断，避免"界面显示守护中、实际服务没跑"的状态分裂；`startForegroundSafe` 统一吞掉 `ForegroundServiceStartNotAllowedException` |
| **守护健康自检面板** | 设置页新增「守护健康自检」：13 项状态实时展示 + 6 个一键修复按钮 + `onResume` 自动回检。**只做跳转引导而不回检 = 等于没做**（用户跳出去随便点两下就回来，App 却以为引导完成） |
| 前后台状态同步 | `MainActivity.onResume/onPause` 主动同步 `EmergencyAlertLauncher.isAppInForeground`。原先只有 `GuardWebSocketManager` 维护 Activity 计数，而支付告警在守护服务没跑时也会弹，那个计数器根本没初始化 |

#### 编译期验证（`compileDebugKotlin` / `assembleDebug`）

编译器抓到的 5 个静态检查抓不到的真错误：

| # | 错误 | 原因 |
|---|---|---|
| C1 | `CallScreeningGuardService.kt:63 Unresolved reference: Log` | 加日志忘了 `import android.util.Log` |
| C2 | `EmergencyAlertLauncher.kt Unresolved reference: enableSound` | `NotificationChannel` 没有 `enableSound`（那是旧 `Notification.Builder` 的 API）。已改为无条件 `setSound(uri, AudioAttributes)` |
| C3 | `SystemPermissionState.kt Unresolved reference: ROLE_CALLER_ID` | 该常量并不存在；系统没有单独的"来电显示"角色，统一用 `ROLE_CALL_SCREENING` |
| C4 | `SystemPermissionState.kt Unresolved reference: hasNextEvents` | 实际签名是 `hasNextEvent()`（单数）。用 `javap` 查 `android.jar` 确认，同时确认 `UsageEvents` **未实现 `AutoCloseable`** —— 之前用 `.use{}` 是错的 |
| C5 | 3 处 `ObsoleteSdkInt` | minSdk 26 下 `SDK_INT >= O` 恒真，多余判断 |

#### Lint 结果

| 项 | 结果 |
|---|---|
| `lintDebug` Error | **4 个，全部是既有问题**（`family/GeofenceManageActivity.kt:245,258` MissingPermission ×2、`RecordingGuardService.kt:143` NewApi、`activity_emergency_alert.xml:15` UseAppTint）。这 4 个文件本次**未改动**，Phase 0 **未引入任何新 Error** |
| 本次改动文件的 Error | 0 |
| 本次改动文件的关键 Warning | 已全部消除：`QueryPermissionsNeeded`（补 `<queries>`）、`BatteryLife`（撤受限权限）、`ObsoleteSdkInt` ×3 |
| 剩余 Warning | 均为无害/既有：`PrivateApi`（`SystemProperties` 反射，刻意为之）、`Autofill`、`Overdraw`、`SetTextI18n` 等 |

> ⚠️ `lintDebug` 任务因这 4 个既有 Error 仍会整体失败。若要让 CI 能跑，需要先修它们或建 lint baseline —— 建议归入 Phase 7。

#### APK 产物核验

| 检查 | 结果 |
|---|---|
| `aapt2 dump permissions` | `READ_CALL_LOG` 已移除 ✅；`RECEIVE_BOOT_COMPLETED` 已生效 ✅；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 已撤除 ✅ |
| `aapt2 dump xmltree` | `BootReceiver` / `KeepAliveReceiver` / `KeepAliveJob` 三个新组件均已打进包 ✅ |
| JS 回归测试 | `npm test` 112 例全绿 ✅（本次未触碰 JS 逻辑） |

#### 静态交叉校验（在能编译之前做的）

| 检查 | 结果 |
|---|---|
| `R.id.*` 引用 vs `activity_settings.xml` 定义 | 17 ↔ 17，双向零差异 |
| Manifest 组件 vs Kotlin 类文件 | 全部命中（`KeepAliveJob`/`KeepAliveReceiver` 与 Scheduler 同文件，合法） |
| `HealthReport` 字段 vs 设置页使用 | 13/13 全部被消费，无死字段 |
| 花括号/圆括号平衡 | 全部平衡（`SettingsActivity` 的 `(`/`)` 差 1 是注释里的 `1)`，HEAD 版本即存在，非本次引入） |
| UTF-8 完整性 | 扫描全部 `.kt`/`.xml`，无 mojibake / 无 U+FFFD |

#### 静态校验中抓到的自身缺陷（已修）

| # | 问题 | 后果 |
|---|---|---|
| S1 | `healthReport` 里 `alarmPending.let{...}.let{ pending \|\| it }` 的 `it` 是 `Unit` | 类型错误，编译不过 |
| S2 | `UsageEvents` 用 `.use{}` 扩展 | `UsageEvents` 的 `AutoCloseable` 是 API 29+，minSdk 26 上有歧义/风险；改为 AppOps 权威判定 + 探测兜底 |
| S3 | `heartbeatCount` 日志重复 `+1` | `apply()` 已同步更新内存值，日志数字偏大 1 |
| S4 | `AppOpsManager.unsafeCheckOpNoThrow` | 公开 API 稳定性存疑，换 `checkOpNoThrow` |
| S5 | `requestCallScreeningRole` 里重复构造 Intent | 逻辑冗余 |
| S6 | **PowerShell 5.1 的 `Get-Content -Raw` + `WriteAllText` 把中文注释写坏** | 整个 `GuardKeepAliveScheduler.kt` 的 UTF-8 被破坏。已用 `write` 工具整体重写。**后续禁止用 PowerShell 处理中文源码** |

#### 真机验证清单（R1 无法靠代码验证，这是替代方案）

原计划是「Day-1 四真机 spike」。**本机当前无设备连接，且模拟器不可用**（CPU 虚拟化固件未开、无 SLAT、未安装 system-images —— 这台机器是嵌套 VM）。

因此改为两条路：**一条命令的自动化脚本** + **手工确认 App 内面板**。

##### 自动化脚本（首选）

`scripts/verify-keepalive.ps1` —— 已写好并通过语法/编码校验：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1 -WaitMinutes 60 -Serial <设备序列号>
powershell -ExecutionPolicy Bypass -File scripts\verify-keepalive.ps1 -SkipReboot   # 不想重启时
```

它自动完成：定位 adb → 校验设备 → 卸载旧包（清理心跳基线）→ 装 APK → 尽力 grant 运行时权限 → 清空 logcat → **重启设备** → 每分钟采样一次心跳并打印 → 采集闹钟/Job/服务/电池白名单/通知使用权/呼叫筛选角色 → 给出 `PASS / WARN / FAIL` 判定。

**判定阈值**（直接对应 R1）：

| 心跳间隔 | 判定 | 含义 |
|---|---|---|
| ≤ 25 分钟 | **PASS** | 落在预期 10~25 分钟窗口内，国产 ROM 没能拦住我们 |
| 25~40 分钟 | **WARN** | 系统在节流但没杀，功能仍可用 |
| > 40 分钟 或无心跳 | **FAIL** | 被系统/厂商杀后台。此时自启动 + 电池白名单 + 厂商后台开关必须全开 |

无设备时脚本以 `exit 2` 退出并打印可操作的排查提示（已实测）。

##### 三个开关必须手工开（adb 授权不了）

`NotificationListener` 通知使用权与 `ROLE_CALL_SCREENING` 呼叫筛选角色**无法用 adb 授予**，必须手机上点。这恰好也验证引导链路是否有效：

| 开关 | 位置 | 不开的后果 |
|---|---|---|
| 电池优化豁免 | 设置 → 应用 → 电池 → 不受限制 | 后台随时被杀 |
| 通知使用权 | 设置 → 通知 → 设备与应用通知 → 本应用 | 大额支付监听完全不触发 |
| 来电显示/骚扰拦截角色 | 设置 → 应用 → 默认应用 → 来电显示 | `onScreenCall` 永不被调用，通话时长监测整条线是死的 |

##### 手工读数（不走脚本时）

1. 装好 → 重启手机 → **不要打开 App** → 等 30 分钟
2. 打开「设置 → 守护健康自检」，看：`保活心跳间隔`（正常 10~20 分钟）、`保活调度`（应「已挂载」）、`前台守护服务`（应「运行中」）
3. 逐个点 6 个修复按钮，**返回后确认状态变绿** —— 回检失效就说明跳转没生效
4. `adb shell dumpsys jobscheduler | findstr com.antifraud.guard`
5. `adb logcat -s GuardKeepAlive GuardKeepAliveRx GuardStarter GuardBootReceiver`

##### 待回填

| 品牌 | ROM | 心跳间隔 | 判定 | 备注 |
|---|---|---|---|---|
| 小米 12S (`diting`/`22081212C`) | Android 15 / MIUI V816 | **15.3 分钟内自主唤醒 3 次** | **PASS** | 见下方实测明细 |
| 华为 / 荣耀 | EMUI / HarmonyOS ? | *待测* | | |
| OPPO / 一加 | ColorOS ? | *待测* | | |
| vivo / iQOO | OriginOS ? | *待测* | | |
| （原厂兜底）Android 13 | AOSP | *待测* | | 基线对照 |

**R1 在 MIUI 上已用数据关闭。** 双通道（AlarmManager 10min + JobScheduler 15min）都能在无用户干预下把服务拉起并留痕。

---

## 3.5 线上事故与修复：位置上报在退到后台后永久停止（2026-10-08）

### 现象

子女端告警列表里只剩「设备上线」，位置更新停在 `2026-10-08 00:27`，此后 12.5 小时零位置事件；而 13:01 手动点「保存/测试后端连接」产生的 PING 却正常。

### 排查过程中的两个误判（记录下来避免重犯）

| 误判 | 真相 |
|---|---|
| 「PING 还在发 → 前台服务和 WS 都活着，只是位置服务死了」 | **`DEVICE_ONLINE` 不是心跳**，它由 `MainActivity.kt` 的「保存/测试后端连接」按钮点击触发（`btnSaveUrl` 的 onClick）。13:01 那条是用户手动点的，**完全不能证明服务此前存活** |
| 「同一老人 55 分钟内从长沙跳到广州，1000 公里」是上报逻辑错乱 | 那是两个不同 provider（GPS/NETWORK）各自上报的**静态陈旧定位**；且 `elder 12` 与 `elder 14` 两个账号的数据混在同一列表里造成了误读。修复后坐标已稳定 |

### 根因

`ACCESS_BACKGROUND_LOCATION` 在整个 Android 工程里只出现在 2 处：Manifest 声明 + 健康检查读取。**运行时从未申请过** —— `MainActivity.requestLocationPermission()` 只申请了 FINE + COARSE。

Android 规定 `LocationManager.requestLocationUpdates()` 的回调**只在 App 前台投递**，除非持有后台定位。因此：注册瞬间回调 2 次 → 用户退出 App → 系统停投递 → 永久静默。而前台服务、通知栏、界面「守护正常」全部照常，子女端也以为老人一直没动。

实测确认：小米 12S 上 `ACCESS_BACKGROUND_LOCATION: granted=false`，位置权限正是「仅在使用中允许」。

### 修复

| 层 | 内容 |
|---|---|
| 权限申请 | 新增 `LocationPermissionPlan`（纯函数 + 10 个单元测试），按 Android 版本分流：8/9 前台即后台；10 可打包请求；**11+ 系统禁止打包，必须跳设置页** |
| 申请流程接入 | `MainActivity.requestLocationPermission()` 改为读该策略；`onResume` 增加回检，发现「仅在使用中允许」就弹窗说明后果（每天最多一次） |
| **独立心跳兜底** | 新增 `LocationHeartbeatPolicy`（纯函数 + 13 个单元测试）+ `LocationGuardService` 心跳线程：**不依赖系统投递回调**，按间隔补报最后已知位置，并周期性重注册订阅（厂商 ROM 会偷偷清掉 `requestLocationUpdates`） |
| 告警形态 | 从一次性弹窗改为**首页常驻红色横幅**（关不掉、每次回前台重算、点一下跳设置），理由：一次性弹窗被「知道了」关掉后无任何提示，而故障本身完全静默 |
| 严重度分级 | 健康面板从两档改三档：🚨失效 / ⚠️降级 / ℹ️暂不影响。呼叫筛选角色与使用情况访问属于「Phase 1 才需要」，混进红色会稀释真告警 |

### 修复效果（实测）

```
15:43  2 条  ← 注册后首次突发（GPS+NETWORK）
15:53  1 条  ← 心跳兜底
16:03  1 条  ← 心跳兜底
16:13  2 条
16:18  1 条
16:28  1 条  ← 稳定 10 分钟一次
```

后台定位已授权，心跳累计 18 次。**没有心跳兜底的话，此时仍然是零上报** —— 因为该机室内 `satellites=0`（无 GPS 星）、network 定位也已陈旧 13 小时，系统回调本身就没在触发。

### 顺带修复：3 项权限在国产 ROM 上开不了

| 权限 | 原因 | 处理 |
|---|---|---|
| 呼叫筛选角色 | 可开 | 实测 `adb shell cmd role add-role-holder android.app.role.CALL_SCREENING com.antifraud.guard` **成功**（UI 路径需自行操作） |
| 通知使用权 | **跳 AOSP 的 `ACTION_NOTIFICATION_LISTENER_SETTINGS` 会被 MIUI 拦下直接拒绝**；且该 action 在 Android 15 上 `query-activities` 返回 *No activities found*（AOSP 页面未导出） | 改跳 MIUI 自己的 `com.miui.permcenter.permissions.PermissionsEditorActivity`（实测存在且可启动，`mFocusedApp` 已验证）。无法用 adb 授予（需 `WRITE_SECURE_SETTINGS`），只能 UI |
| 使用情况访问 | MIUI **静默拦截** appops：`cmd appops set ... allow` 执行后仍回 `default` | 只能 UI；因属「Phase 1 才需要」，已降级为 ℹ️ 不再计入告警 |

同时删除了两个**实测不存在**的 MIUI 组件（`AppDetailsDefaultAppActivity`、`HiddenAppsConfigActivity`）—— 猜错的代价是「点了没反应」，比不给入口更糟。其余品牌的厂商组件一律返回空列表走已验证的系统页，并提供手工步骤文案兜底。

### 遗留问题（本轮发现，未修）

| # | 问题 | 说明 |
|---|---|---|
| L1 | **围栏触发录音仍是静默的** | 日志实测：`进入敏感地点围栏「曾爷爷常去地点」，自动开启录音存证`。用户无任何告知，与 README「不使用隐蔽偷录」的承诺冲突。属 Phase 2 的 2-2（`ConsentGate`） |
| L2 | GPS 室内无星时坐标长期陈旧 | 当前靠 network provider + 心跳维持。若长时间无新定位，应在子女端显式标注「坐标可能已过期 N 小时」而不是照常显示 |
| L3 | 保活双通道在同一秒内重复触发 | 日志可见 `距上次 0 分钟` 连着两次。无害但日志噪声，且 `heartbeat_count` 会高估保活次数 |

---

### Phase 0.5 · 测试基线 ｜ 1.5 天 ｜ ✅ 已完成（2026-10-07）

> **前置**：无（应先于所有改动规则的 Phase）｜ **消除缺口**：G22

| ID | 任务 | 目标文件 | 状态 |
|---|---|---|---|
| 0.5-1 | 测试框架 | `package.json` 加 `test` / `test:watch` | ✅ |
| 0.5-2 | 规则引擎测试 | `tests/fraudDetector.test.js`（41 例） | ✅ |
| 0.5-3 | 地理降级链测试 | `tests/regeo.test.js`（31 例，含 placeGuess） | ✅ |
| 0.5-4 | 签名鉴权测试 | `tests/signToken.test.js`（14 例，4 类攻击 + 8 类畸形输入） | ✅ |
| 0.5-5 | ZIP 写入器测试 | `tests/zipWriter.test.js`（16 例，自带最小 ZIP 读取器反解产物） | ✅ |
| 0.5-6 | ASR 降级测试 | `tests/asr.test.js`（10 例） | ✅ |

**成果**：`npm test` → **112 例全绿**（`node --test`，零新增依赖）。测试全部针对真实模块、无 mock 生产代码。

**变异测试验证（证明测试非空转）**：逐个注入 12 处变异，确认被捕获：

| 注入的缺陷 | 结果 |
|---|---|
| `signToken` 去掉 rid 归属校验（可串用他人录音） | ✅ 捕获 |
| `signToken` 去掉过期校验 | ✅ 捕获 |
| `zipWriter` 本地头 CRC 写错 | ✅ 捕获（首轮**存活**，补断言后捕获） |
| `zipWriter` 外部属性整数溢出（`zipWriter.js:78-81` 历史崩溃） | ✅ 捕获 |
| `asr` 把 SKIPPED 谎报成 DONE（会导致证据被误删） | ✅ 捕获 |
| `asr` 跳过未配置短路 | ✅ 捕获 |
| `regeo` 兜底返回空串 | ✅ 捕获 |
| `placeGuess` 去掉 300m 锚点阈值 | ✅ 捕获 |
| `fraudDetector` FRAUD 阈值 55→95（首轮**存活**，补用例后捕获） | ✅ 捕获 |
| `fraudDetector` `score>=28` → `score>=900` | ⚠️ **等价变异体**：两分支均赋 `SUSPECT` 且无其他副作用，状态断言无法区分（不是测试缺陷） |
| `regeo` 去掉 24h 缓存过期 | ⚠️ **未覆盖**：需时间注入才能测，暂记为已知盲区 |
| 无操作（noop） | ⚠️ 存活属预期 |

**首轮存活暴露的测试缺口（已补）**：
1. FRAUD 阈值 `score >= 55` 分支原本没有任何用例能独立触发（所有命中强信号的文本也顺带越过了 55 分）。已补 `医疗/保健品诱导(40) + 低价旅游诱导(35)` 累积用例，并断言"用例前提是无任何 weight≥50 规则命中"。
2. ZIP 本地头与中央目录的 CRC/长度/文件名一致性原本不校验。已补 `readEntryData` 三项交叉比对。

---

### Phase 1 · 通话行为判定 ｜ 4.5 天

> **前置**：0.5 ｜ **消除缺口**：G6, G7, G13（部分）｜ **对应建议**：Saaya 模式

**判定逻辑**：陌生来电进入摘机状态（`OFFHOOK`）期间，前台应用命中高危表 → 立即触发。

- 支付类：`com.tencent.mm`、`com.eg.android.AlipayGphone` + 主要银行包名
- **远程控制类（最高危，冒充公检法"屏幕共享"场景）**：TeamViewer / AnyDesk / 向日葵 / ToDesk / RustDesk
- 白名单机制：子女/老人可将号码加入本地信任列表，信任后跳过监测

| ID | 任务 | 改动文件 |
|---|---|---|
| 1-1 | 高危 App 注册表 | 新建 `util/HighRiskAppRegistry.kt`（分类 + 包名 + 危险等级 + 可远程更新） |
| 1-2 | 通话状态监听 | 新建 `service/CallRiskWatcherService.kt`：`PhoneStateListener` + 仅在 `OFFHOOK` 期间以 2s 采样 `UsageStatsManager.queryEvents()` |
| 1-3 | 通话记录落库 | 新建 `db/CallRecordDbHelper.kt`：`call_records` 表（number / is_stranger / start / end / duration_sec / hangup_by / peak_risk） |
| 1-4 | 陌生号码判定 | 比对 `ContactsContract`；`AndroidManifest.xml` 加 `READ_CONTACTS` |
| 1-5 | 频次判定 | 2h 内同号 ≥3 次 → `MEDIUM`（可后置，见 §6） |
| 1-6 | 权限与降级 | 加 `PACKAGE_USAGE_STATS`；`VendorPermissionHelper` 补 4 厂商"使用情况访问"intent；未授权时降级为"通知支付监听 + 通话时长"并在 UI 标注「行为联动保护未启用」 |
| 1-7 | 事件上报 | 新增事件类型 `COERCION_RISK`（HIGH）+ `CALL_STAT`；`routes/events.js` 识别 |
| 1-8 | 围栏停留阈值 | `geofences` 表加 `dwell_minutes`；`LocationGuardService.kt:200-220` 补围栏内停留计时；`GeofenceManageActivity` 加阈值配置（半径/停留时间成对） |
| 1-9 | "家"基准修正 | `LocationGuardService.kt:101-106` 家 = 首次定位坐标且永不更新 → 改由子女端显式设置 + 首次使用二次确认 |
| 1-10 | 阈值可配置 | `LocationGuardService.kt:112,115,120` 的 500m/100m/40min 硬编码 → `GuardConfig`（走 `GuardConfig.kt:235-275` 云端同步） |
| 1-11 | 子女端展示 | `AlertsFragment.kt:157` / `DashboardFragment.kt:397` 加中文标签与详情渲染 |

**风险**：`PACKAGE_USAGE_STATS` 在国产 ROM 权限路径不统一（复用 1-6）；`queryEvents` 高频调用耗电（限 `OFFHOOK` 期间 + 2s 采样）。

#### Phase 1 完成记录（2026-10-08）

> 代码完成 + `assembleDebug` 通过 + `npm test` 112 例全绿。真机验证（通话判定/频次实测）待回填。
> 更正：此记录写的 112 例是 Phase 0.5 时的计数，Phase 1 收尾时实际为 143 例（新增 `timeFormat` / `locationSensitivity` / `riskAlertPolicy` 三个测试文件）；2026-10-09 守护设置落地后非契约单测达 162 例。

| ID | 结果 | 落点 |
|---|---|---|
| 1-1 | ✅ | `util/HighRiskAppRegistry.kt`：远程控制类 CRITICAL + 支付类 HIGH 静态表；远程更新走 `guard_settings.highRiskPackages`（服务端字符串透传 + 客户端逐条丢弃脏数据兜底） |
| 1-2 | ✅ | `service/CallRiskWatcher.kt`：PhoneStateListener + OFFHOOK 期间 2s 采样 UsageEvents（`MOVE_TO_FOREGROUND` 取最新前台包名）；命中即报 COERCION_RISK（一通最多一次） |
| 1-3 | ✅ | `db/CallRecordDbHelper.kt`：call_records 表（number / is_stranger 三态 / start / end / duration_sec / peak_risk；hangup_by 置空——本版本无可靠挂断方来源） |
| 1-4 | ✅ | READ_CONTACTS（Manifest + 运行时申请）+ PhoneLookup 比对；权限被拒 → 三态 null，绝不把"查不了"当"陌生"上报 |
| 1-5 | ✅ | 2h 内同号 ≥3 次 → CALL_RISK MEDIUM（含本次计数；信任号码跳过） |
| 1-6 | ✅* | PACKAGE_USAGE_STATS 声明（`tools:ignore`）；降级链：未授权 usage access → 仅通话时长监测（自检面板 Phase 0 已标注后果）。*四厂商"使用情况访问"专属跳转组件**刻意未加**——遵循 VendorPermissionHelper"未真机验证的组件不猜"铁律，走 `SystemPermissionState.openUsageAccessSettings` 三级兜底 |
| 1-7 | ✅ | COERCION_RISK（恒 HIGH）/ CALL_STAT（恒 LOW）/ GEOFENCE_DWELL（恒 MEDIUM）；`routes/events.js` 服务端按类型归一化 severity（不盲信客户端自报，防漏报）；子女端三处标签渲染 |
| 1-8 | ✅ | `geofences.dwell_minutes`（建表列 + ALTER 迁移）；LocationGuardService 停留计时（一进一出最多一报）；子女端围栏表单新增停留阈值输入（0~720 分钟） |
| 1-9 | ✅* | 家基准优先云端 `guard_settings.homeLat/homeLng`（服务端 bounds 含坐标校验）；回退首次定位时发一次性通知告知基准值（不再静默自作主张）。*子女端"远程设家"UI 后置（数据通道已通）→ **2026-10-09 补齐**：生产端缺失（全工程无任何代码写 homeLat/homeLng），已由子女端 `ElderGuardSettingsActivity` 补上（地图选点/当前位置/清除，走带鉴权的 `/elder-settings/family/:elderId`）；`homeSet` 一次性闩锁同日修复（`resetHomeBase()` + WS 推送 + 30 分钟拉取兜底，见 `HomeBasePolicy`） |
| 1-10 | ✅ | 500/100/40 硬编码 → `GuardConfig.homeAwayRadiusMeters / stayMoveMeters / homeStayMinutes`，走既有本机+云端双向同步 → **2026-10-09 补记**：此前三项在 Android UI 层零出现（同步的是代码默认值，实际没人能改）；UI 入口已补齐于子女端守护设置页，老人端设置页改只读（单一写入方） |
| 1-11 | ✅ | AlertsFragment / DashboardFragment / RiskLogActivity 新增三类事件中文标签 |

**对路线图的三处刻意偏离**：

1. **CallRiskWatcher 不是独立 Service**：独立前台服务 = 老人端第 4 条常驻通知（诱导用户一键全关）；普通 Service 后台启动受限（Android 8+）。挂在 ForegroundGuardService 生命周期里，开机/闹钟/Job/界面四条保活路径全部继承。
2. **时长预警去重**：呼叫筛选角色在位时，呼入通话的时长预警仍由 CallScreeningGuardService 负责，Watcher 以 `CallScreeningGuardService.lastDurationReportAt`（@Volatile 共享）去重；呼出通话 Watcher 是唯一监测者。
3. **信任号码 UI 后置**：数据层 + 云端同步（`trustedCallNumbersJson`，走 StringSet 规避的 JSON 字符串方案）已就绪，设置页入口放下一个小版本。

**已知局限**：呼出号码拿不到（READ_CALL_LOG 刻意不申请）→ 陌生/频次判定只覆盖呼入；围栏停留计时精度受位置回调间隔（约 10 分钟）限制，阈值粒度为分钟级。

---

### Phase 1.5 · 打断闭环补全 ｜ 4.5 天

> **前置**：1 ｜ **消除缺口**：G8, G9, G10, G11

| ID | 任务 | 改动文件 |
|---|---|---|
| 1.5-1 | 真全屏覆屏 | 新建 `Theme.AlertFullScreen`（沉浸式 + `layoutInDisplayCutoutMode=shortEdges`）；`EmergencyAlertActivity` 应用之 |
| 1.5-2 | 常亮修复 | `EmergencyAlertActivity.kt:124-139` 把 `FLAG_KEEP_SCREEN_ON` 移出 legacy 分支，API 27+ 生效 |
| 1.5-3 | 子女预录语音 | 链路：子女端录制 → `POST /api/recordings/voice` → 老人端 `service/VoiceMessageStore.kt` 下载缓存 `filesDir/voice/` → 打断时 `MediaPlayer` 循环播 + `AudioFocus(AUDIOFOCUS_ALARM)` |
| 1.5-4 | 兜底喊话 | `assets/` 预置通用"防诈喊话"音频（子女未录音时不得无声）；可参考 Kavach 的三通道（色/声/振）设计 |
| 1.5-5 | 后端 voice 接口 | `routes/recordings.js` 加 voice 上传/拉取；`server.js` `INTERRUPT_CMD` 带 `voiceUrl`；`signToken.js` 签名复用 |
| 1.5-6 | 子女端录音入口 | Android family + 小程序各加录制入口 |
| 1.5-7 | 打断降级链 | 前台 → `startActivity`；后台 → 全屏意图通知；再失败 → 通知 + 震动 + 闹铃（已有 `GuardWebSocketManager.kt:244-253` 逻辑，扩展为三级） |

---

### Phase 2 · 隐私分级与合规告知 ｜ 3 天

> **前置**：1.5 ｜ **消除缺口**：G12 ｜ **对应建议**：Kavach 隐私架构（单 APK 形态）

| ID | 任务 | 说明 |
|---|---|---|
| 2-1 | `ConsentGate` 统一门控 | 新建 `service/ConsentGate.kt`：所有敏感动作（录音 / 后台定位 / 语音打断 / 远程覆屏）必须过门控，触发前有**老人端可见横幅 + 子女端可见"已告知"标记** |
| 2-2 | 修围栏静默开录 | `LocationGuardService.kt:238-244` 改为过 `ConsentGate`；触发时先发通知，**子女端远程确认后才录** |
| 2-3 | `privacyMode` 三档 | `GuardConfig` 新增：`FULL`（全功能）/ `EVIDENCE_ONLY`（**主动断 WS + 停 UploadQueue + 停所有网络请求**，子女端顶部常驻横幅「老人端离线存证模式，数据未上传」）/ `PAUSED`（全停仅本地 SOS）。走 `GuardConfig.kt:235-275` 现成同步通道 |
| 2-4 | 录音分级 | `recordingMode`：`OFF` / `SUSPECT_ONLY`（仅 SOS + 子女远程触发）/ `ASSISTED`（围栏需远程确认，超时 5min 降级为不录）。先做 `OFF` + `SUSPECT_ONLY`，`ASSISTED` 后置（§6） |
| 2-5 | 原始件只读副本 | `recordingStore.js` 增加：录音落盘后保留只读副本，不覆盖不转码（`docs:214` 自认缺口） |
| 2-6 | 设置页透明度 | 明示三档各自关闭哪些能力（透明而非恐吓） |
| 2-7 | README 话术重写 | `README.md:123-129` 删除无法验证的承诺（"不使用隐藏图标"等），改为四条**可验证**承诺：① 录音只在 3 类触发点 ② 每条录音有 SHA-256 + 完整触发链 ③ 子女可随时远程停止录音 ④ `EVIDENCE_ONLY` 模式下当场断网可验证零请求 |

---

### Phase 3 · 云端风控 + 官方数据融合 ｜ 4.5 天

> **前置**：0.5 ｜ **消除缺口**：G14, G27, G29 ｜ **对应建议**：官方数据源 L0+L1

| ID | 任务 | 改动文件 |
|---|---|---|
| 3-1 | `services/riskEngine.js` | 服务端二次定级；入参含客户端定级 + 规则版本；输出 CLIENT / SERVER / CONFLICT 三源 |
| 3-2 | **影子模式优先** | 首期服务端判定**只记录不下发**，子女端不展示；观察 2 周比对误报率后再切覆盖。UI 切换为覆盖时须显示"设备判定 / 云端判定"双标签，否则子女遇矛盾会失去信任 |
| 3-3 | 事件表扩展 | `db.js` `risk_events` 加 `source` / `server_severity` / `acked_at` / `rule_version`；`event_type` 加 CHECK 约束（G24） |
| 3-4 | **L0-1** | `fraudDetector.js` `FRAUD_PATTERNS` 加 `96110`（公安部预警专线，来电本身即高危）/ `12381`（涉诈短信端口）两条强信号 |
| 3-5 | **L0-2** | Android `CallRiskWatcherService` 判来电 ∈ {96110, 110, 12381} → 直接 `severity=HIGH`，`details.evidence` 写明依据 |
| 3-6 | **L1** | 高德 POI 类目映射表（`regeo.js:108-135` 已取 POI 名，只差映射）：`养生馆/理疗/保健品/会销/茶楼/拍卖/黄金回收 → SENSITIVE`；`银行/ATM/网点 → SENSITIVE_HIGH`。命中即置 `locations.is_sensitive=1`（G27） |
| 3-7 | 处置闭环 | 子女端可标记已处理；`acked_at` 回写 |
| 3-8 | AI 扫描脱节修复 | `routes/ai.js:10-19` 复用 `fraudDetector.analyzeByRules`（G29）；`AiScanActivity` 改走 `ApiClient`（当前手搓 `HttpURLConnection` 绕过统一超时/地址校验） |

---

### Phase 4 · 安全加固 ｜ 3 天

> **前置**：3（必须在 Phase 5 前：Phase 5 会把录音签名 URL 经 `downloadFile` 暴露到公网）｜ **消除缺口**：G15~G18

| ID | 任务 | 改动文件 |
|---|---|---|
| 4-1 | 老人端写入鉴权 | `events.js:15` / `geofence.js:23` / `recordings.js:71` / `auth.js:441,549,601,612` 六处加设备级凭证（`elderId + 设备密钥`，首次激活时签发并落 `users.guard_settings`）。**不能直接用 family token**：老人端本就没有账号密码 |
| 4-2 | WS 握手鉴权 | `server.js:123-126` 直信客户端 `userId` → 改为 `upgrade` 阶段校验 token，未通过即断开 |
| 4-3 | 绑定码防爆破 | `auth.js:141-143` 加：5 次失败锁定 10 分钟 + 绑定码有效期（建议 30 天）+ 首次绑定需短信验证（可选） |
| 4-4 | token 持久化 | `tokenAuth.js:7` 内存 `Map` → SQLite 表；顺手解决服务重启全员登出（G18） |
| 4-5 | 依赖审计 | `npm audit` + 复核 `multer` 上传限制（`recordings.js:61` 单段 60MB 上限是否有总请求体限制） |

---

### Phase 5 · 小程序接录音链路 ｜ 5 天

> **前置**：4 ｜ **消除缺口**：G19, G20, G26

| ID | 任务 | 说明 |
|---|---|---|
| 5-0 | **spike 先行** | 先验证小程序侧 `downloadFile` 大文件 + `openInnerAudioContext` 时长限制 + ZIP 下载可行性。若不可行，退化为"分段下载 + 后端预生成清单"或引导走公众号 H5。**此 spike 结果决定本 Phase 方案，必须 Day-1 做** |
| 5-1 | 录音列表 | `pages/evidence/` 接 `/api/recordings/list/:elderId?group=1&evidence=1`；替代当前"复制 JSON 文本" |
| 5-2 | 录音播放 | `wx.downloadFile` + `wx.openInnerAudioContext`；URL 签名复用 `signToken.js`（30 分钟 TTL，注意缓存） |
| 5-3 | 转写与研判展示 | 展示 `transcript` / `fraud_status` / `fraud_score` / `fraud_labels` / `suspect_role` |
| 5-4 | 人工复核 | 接 `POST /api/recordings/:id/review`（子女可推翻 AI 判定 → `reviewed_by_family=1`，`recordingCleanup.js:36-39` 已保证此类不删） |
| 5-5 | 远程停止录音 | 接 `RECORDING_STOP_CMD`（`server.js:237` 已实现）+ `/api/recordings/status/:elderId` 录音中指示灯（后端已就绪，前端零对接） |
| 5-6 | 证据包下载 | 替掉"一键复制报案材料文本"；ZIP 结构与 `recordings.js:498-502` 一致 |
| 5-7 | **订阅消息** | `requestSubscribeMessage`（全工程 0 命中）打通 `routes/events.js:72-88` HIGH 级推送；`services/wechat.js:56-61` 未配置时只打日志"模拟推送"，需真实配置 |
| 5-8 | 轨迹页修复 | `map.js` 去硬编码北京初始坐标（`:8-9`）；`events.js:112` `LIMIT 10` 改分页/时间范围查询 |
| 5-9 | 围栏可视化 | 补齐小程序侧围栏图层（Android App 已有 `TrackFragment.fetchFences`，小程序缺） |
| 5-10 | 概览页 | `dashboard` 补真实风险指数（当前只有两条计数）；`lastFetch` 30s 节流导致回前台不刷新（`:25`）→ 改 `onShow` 触发 |

---

### Phase 6 · 教育模块复用 ｜ 1.5 天

> **前置**：5 ｜ **对应建议**：复用 MIT 项目

引入 `Long-MJ/silver-hair-guardian`（MIT，6 类真实骗局场景：冒充公检法 / 养老投资 / 保健品 / 冒充亲友 / 中奖退税 / 黄昏恋），接入 `pages/guide/guide`。**二级收益**：游戏过程中的话术可作为补充语料回流 `fraudDetector.js`。

---

### Phase 7 · 正式域名与发布 ｜ 2 天

> **前置**：5, 6 ｜ **消除缺口**：G21

- `wechat-miniprogram/config.js:12` cpolar 临时域名 → 正式域名
- 走 `scripts/mp-publish/` 工具链同步服务器域名（`README.md:159-161`）
- Android 签名 / CI（沿用 `6b9eba0` 的 debug 签名约定，生产需换正式 keystore）
- 演示环境（`npm run start:demo`，绑定码 `246810`/`135791`）全链路回归

---

## 4. 依赖图与排期

```
Phase 0 ──┬────────────────────────────► Phase 1 ──► Phase 1.5 ──► Phase 2
          │                                  ▲
          ▼                                  │
       Phase 0.5 ──┬──► Phase 3 ──► Phase 4 ─┴──► Phase 5 ──► Phase 6 ──► Phase 7
```

| Phase | 内容 | 天数 | 累计 |
|---|---|---|---|
| 0 | 守护可靠性基线 | 3 | 3 |
| 0.5 | 测试基线 | 1.5 | 4.5 |
| 1 | 通话行为判定 | 4.5 | 9 |
| 1.5 | 打断闭环 | 4.5 | 13.5 |
| 2 | 隐私分级与告知 | 3 | 16.5 |
| 3 | 云端风控 + L0/L1 | 4.5 | 21 |
| 4 | 安全加固 | 3 | 24 |
| 5 | 小程序录音链路 | 5 | 29 |
| 6 | 教育模块 | 1.5 | 30.5 |
| 7 | 域名与发布 | 2 | **32.5** |

---

## 5. 风险登记册

| ID | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | **国产 ROM 保活失败** | ~~高~~ → **已在 MIUI 实测关闭** | 小米 12S / Android 15 / MIUI V816 实测：15.3 分钟内 App 自主唤醒 3 次（AlarmManager + JobScheduler 双通道均生效）。双通道方案本身可行 |
| R1b | 华为 / OPPO / vivo 三家保活表现未知 | 中 | 三家各补一台即可关闭。MIUI 已 PASS 说明方案有效，风险降级为「覆盖面」而非「可行性」 |
| R1c | **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 属 Play 受限权限** | 已消除 | 已撤除权限声明与代申请分支，只保留跳转系统列表（用户自行划掉）。实测小米上电池优化确为「已豁免」 |
| R8 | **围栏触发录音仍无任何告知**（L1） | **高（合规）** | 真机日志实测已发生：`进入敏感地点围栏…自动开启录音存证`。与 README「不使用隐蔽偷录」承诺直接冲突。**Phase 2 的 ConsentGate 不能再往后推** |
| R9 | **3 项权限在国产 ROM 上开不了** | 高 | 呼叫筛选角色实测可用 adb 开通；通知使用权被 MIUI 拦下并直接拒绝（且 AOSP 页面在 Android 15 未导出）；使用情况访问被 appops 静默拦截。已改品牌感知路由 + 手工步骤兜底，但通知使用权若长期开不了，需换实现方案 |
| R2 | **小程序录音下载不可行**（`downloadFile` 大文件 / `openInnerAudioContext` 时长 / ZIP 下载限制） | 中 | Phase 5 方案需重构 | 5-0 spike Day-1；备选：分段下载 + 后端预生成清单，或引导走公众号 H5 |
| R3 | **服务端二次定级引入新误报** | 中 | 子女看到"设备/云端"矛盾而失去信任 | 3-2 先跑 2 周影子模式；UI 必须双标签；CONFLICT 标记为需人工确认而非直接采信云端 |
| R4 | **安全加固晚于功能开发** | — | 攻击面持续扩大 | Phase 4 硬性前置于 Phase 5 |
| R5 | **Phase 0.5 被跳过**（工期紧时最先被砍） | 中 | Phase 3 改规则无回归保护，误报率不可知 | 规则引擎改动集中在 Phase 3，Phase 0.5 仅 1.5 天，不可省 |
| R6 | **`PACKAGE_USAGE_STATS` 授权率低**（国产 ROM 路径不统一、需手动开） | 中 | 行为联动保护实际启用率低 | UI 标注已完成（2026-10-09：使用情况访问缺失由 ℹ️ 备用改判 ⚠️ 降级——未授权时 COERCION_RISK 高危告警完全不会发生）；厂商专属跳转 intent **待真机验证后再补**（遵循 VendorPermissionHelper「未真机验证的组件不猜」铁律），走 `SystemPermissionState.openUsageAccessSettings` 三级兜底 + 手工路径文案 |
| R7 | **后置清单被忘记**（§6） | 中 | 需求缺口 | 单独成节，每 Phase 收尾时复核 |

---

## 6. 后置清单（工期紧张时可砍）

| 项 | 所属 Phase | 理由 |
|---|---|---|
| Phase 7 正式域名 | 7 | 唯一可完全并行（不依赖代码），可交他人或单独一周；也可先只换域名不做发布流程 |
| Phase 6 教育模块 | 6 | 纯体验增益，防护闭环已由 1~5 覆盖 |
| `recordingMode` 的 `ASSISTED` 档 | 2-4 | 三档只做 `OFF` + `SUSPECT_ONLY` 即覆盖合规诉求（默认不自动录 + 可一键全开）；围栏远程确认交互较复杂 |
| 通话频次判定（2h 内同号 ≥3 次） | 1-5 | 陌生号码判定 + 时长统计是刚需；频次价值中等 |

**砍掉以上四项 → ~25 天。**

---

## 7. 附录 · 既有 Bug 清单（Phase 0 内修）

| # | Bug | 位置 |
|---|---|---|
| B1 | `GuardConfig.callThresholdMinutes` 死配置（硬编码 15） | `CallScreeningGuardService.kt:51` |
| B2 | 支付告警后台 `startActivity` 被系统丢弃，无全屏意图降级 | `NotificationPayListenerService.kt:69-76` |
| B3 | 升级 `DROP TABLE` 丢失待传录音与本地风险事件 | `UploadQueueDbHelper.kt:80-84`、`RiskEventDbHelper.kt:37-40` |
| B4 | 未声明 `<queries>`，厂商设置页跳转全落兜底 | `AndroidManifest.xml` |
| B5 | `ForegroundGuardService` 声明 `foregroundServiceType="location"` 但不定位（Android 14+ 审查风险） | `ForegroundGuardService.kt` |
| B6 | `FLAG_KEEP_SCREEN_ON` 仅 API<27 生效 | `EmergencyAlertActivity.kt:124-139` |
| B7 | "家"基准 = 首次定位坐标且永不更新 | `LocationGuardService.kt:101-106` |
| B8 | 500m/100m/40min 全硬编码，不可配置 | `LocationGuardService.kt:112,115,120` |
| B9 | `reportNormalLocation` 的 `severity` 三元两边都是 `"LOW"`（复制粘贴残留） | `LocationGuardService.kt:146` |
| B10 | 金额正则不支持千分位（`¥1,234.00` → 匹配到 `1`）、不支持"万"后缀、只取首个匹配 | `NotificationPayListenerService.kt:36` |
| B11 | 通知包名匹配用 `"mm"` 子串，会误命中任意含 mm 的包名 | `NotificationPayListenerService.kt:17-32` |
| B12 | `order_no` 伪造为 `ORD_ANDROID_${timestamp}`，无真实交易凭证字段 | `NotificationPayListenerService.kt:51` |
| B13 | 死代码 `fallbackFenceName` 全仓库无调用 | `routes/geofence.js:124-126` |
| B14 | 前向引用（`:144` 用到 `:150` 才声明的 `const`），当前能跑但脆弱 | `fraudDetector.js:144` / `:150` |
| B15 | `AiScanActivity` 绕过 `ApiClient` 手搓 `HttpURLConnection`，不受统一超时/地址校验约束 | `AiScanActivity.kt:40-95` |
| B16 | `getRunningServices` 已废弃 | `MainActivity.kt:1167-1170` |
| B17 | `zipWriter.js` 全内存 `Buffer.concat`，声称支持 GB 级实际会 OOM | `zipWriter.js:127` + `recordings.js:502` |
| B18 | `timeFormat.js` 只转 `created_at`，`recorded_at`/`retention_until` 未统一处理 | `services/timeFormat.js:33-35` |

### 7.1 测试期新发现（2026-10-07，Phase 0.5 产出）

写测试时暴露出的既有问题。**均已用断言固化当前行为**，因此"修掉"会让测试失败——这是故意的，改行为时必须同步改测试并更新本表。

| # | 发现 | 证据 | 建议 |
|---|---|---|---|
| T1 | 弱信号「索要通话验证码」(30) 是**死规则**：`/(我这边马上\|稍等\|挂断\|你听一下).{0,10}(验证码\|密码)/` 被强信号「索要验证码/密码」(60) 完全覆盖，永不可能独立命中 | `fraudDetector.js:57` vs `:25` | 删掉可简化规则表；若将来弱化强信号，此条断言会先失败提醒 |
| T2 | `stripCoordTail` 剥坐标尾巴的分支对锚点继承路径**不可达**：`inheritFromAnchor` 先用 `looksLikeRealAddress` 过滤锚点，而后者拒绝一切以坐标对结尾的字符串 | `placeGuess.js:60-64` vs `:41-44` | 二选一：放宽 `looksLikeRealAddress` 对锚点的判定，或删掉 `stripCoordTail` 的坐标分支 |
| T3 | `looksLikeRealAddress` 要求 `length > 1`，导致单字地名（"家""店"）不能作为锚点 | `placeGuess.js:36` / `regeo.js:71` | 若产品上会填单字店名，需放宽到 `length >= 1` |
| T4 | `guessPlace` 按 elderId 缓存 5 分钟，**子女端刚登记的围栏最多 5 分钟后才对老人位置生效** | `regeo.js:268` `HOTSPOT_TTL_MS` | 前端提示"围栏生效可能有 5 分钟延迟"，或在 `/api/geofence/add` 后清缓存 |
| T5 | `cacheKey` 用 `toFixed(4)` 是四舍五入而非截断，坐标恰好落在 `x.xxxx5` 边界时会跳到相邻网格 | `regeo.js:31` | 属于可接受行为；若要求网格稳定，改用 `Math.trunc` |
| T6 | `asr.js` 是**半静态读取**：`PROVIDER` 加载时读一次，`isConfigured()` 里的密钥却实时读 `process.env`。运行期改 env 会造成状态不一致，也极难测试 | `asr.js:24` vs `:28-37` | 统一改为加载时捕获为模块常量 |
| T7 | 拼错 `ASR_PROVIDER` 时错误文案报"缺 API 密钥"，不提示具体 provider 名，排查会被误导 | `asr.js:56-59` | 文案里回显实际 provider 名 |
| T8 | `fraudDetector` 的 `score >= 28` 分支与 `else` 分支**行为完全等价**（均赋 `SUSPECT`），属死代码 | `fraudDetector.js:115-119` | 删掉 `else` 合并，或给两分支不同的 `retentionDays` 使其有意义 |
| T9 | 24h 地理缓存过期逻辑（`regeo.js:38`）**无测试覆盖** | — | 需引入可注入时钟才能测；已知盲区 |

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-07 | 初版创建。基于竞品调研（5 条建议）与全仓库代码审查（29 项结构性缺口 G1~G29、18 个既有 Bug B1~B18），结合 4 项已决策事项形成 8 个 Phase 的执行路线图。 |
| 2026-10-07 | **Phase 0.5 完成**。新增 `npm test`（`node --test`，零新增依赖），建立 112 例回归测试覆盖 `fraudDetector` / `regeo`+`placeGuess` / `signToken` / `zipWriter` / `asr`。经 12 处变异注入验证测试有效性（10 捕获、1 等价变异体、1 已知盲区）。测试期新发现 9 项既有问题，记入 §7.1（T1~T9）。 |
| 2026-10-07 | **Phase 0 完成**（编译通过，待真机验证）。新增开机自启 `BootReceiver`、双通道保活 `GuardKeepAliveScheduler`、统一服务拉起 `GuardServiceStarter`、紧急警报三级降级 `EmergencyAlertLauncher`、系统权限状态 `SystemPermissionState`、幂等迁移原语 `SqliteMigrations`、设置页「守护健康自检」面板。修复：支付告警后台被静默丢弃、通话阈值死配置、`DROP TABLE` 销毁待传录音、死权限、错误 FGS type。**纠正三处此前的误判**：① 构建环境一直存在于 `D:\Android`（gradle-8.7 + android-33 + adb）与 `D:\Program Files\Java\jdk-21.0.10`，此前因只查 PATH 与默认位置而误报"无 SDK"；② `<queries>` 确实需要（lint `QueryPermissionsNeeded` 指向 `resolveActivity`）；③ R1 无法靠代码验证，改为"保活可观测 + 心跳读数"。计划外撤掉 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 以规避 Play 政策风险。 |
| 2026-10-08 | **Phase 1 完成**（代码 + 编译 + 112 例回归全绿，待真机验证）。通话行为判定全链路：`HighRiskAppRegistry`（可远程更新）+ `CallRiskWatcher`（OFFHOOK 2s 采样 → COERCION_RISK）+ `CallRecordDbHelper`（通话落库）+ 陌生判定（三态）+ 频次判定 + 时长预警去重；`geofences.dwell_minutes` 停留告警；500/100/40 阈值入 GuardConfig 云端同步；家基准支持云端显式设置 + 首次设家告知；events.js 服务端 severity 归一化。偏离与局限见 §3 Phase 1 完成记录。 |

---

## 9. 执行状态

| Phase | 状态 | 说明 |
|---|---|---|
| Phase 0 · 守护可靠性基线 | ✅ 编译通过，**待真机验证** | `assembleDebug` 产出 app-debug.apk；APK 权限/组件已用 `aapt2` 核验；lint 无新增 Error。R1 需装到 MIUI/HarmonyOS/ColorOS/OriginOS 各一台读心跳间隔后回填 |
| Phase 0.5 · 测试基线 | ✅ 已完成 | 112 例；变异验证通过 |
| Phase 1 · 通话行为判定 | ✅ 代码完成（2026-10-08，待真机验证） | 1-1~1-8、1-10、1-11 全部落地；1-9 完成数据链路+首次设家告知（子女端远程设家 UI 后置）。npm test 112 全绿 + assembleDebug 通过。三处刻意偏离与已知局限见 §3 Phase 1 完成记录。**2026-10-09 补记**：1-9 生产端（子女端写入 UI）与 1-10 的 UI 入口此前缺失，已由子女端 `ElderGuardSettingsActivity` 补齐（含 `/elder-settings/family/:elderId` 带鉴权路由、字段白名单、清除家基准、WS 推送 + 30 分钟拉取兜底、`homeSet` 闩锁修复）；老人端守护参数改只读；R6 使用情况访问改判 ⚠️ 降级 |
| Phase 1.5 · 打断闭环 | ⬜ 未开始 | 依赖 1 |
| Phase 2 · 隐私分级与告知 | ⬜ 未开始 | 依赖 1.5 |
| Phase 3 · 云端风控 + L0/L1 | ⬜ 未开始 | 依赖 0.5（已就绪）；规则改动已有回归保护 |
| Phase 4 · 安全加固 | ⬜ 未开始 | 硬性前置于 5 |
| Phase 5 · 小程序录音链路 | ⬜ 未开始 | 依赖 4；Day-1 需先做 R2 spike |
| Phase 6 · 教育模块 | ⬜ 未开始 | 依赖 5；可后置 |
| Phase 7 · 域名与发布 | ⬜ 未开始 | 可并行 |