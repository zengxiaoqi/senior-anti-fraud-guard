# App 内自升级（一键升级）

> 适用版本：**v1.7.4（versionCode 16）起**。更早的版本里根本没有升级代码，
> 必须先手动装一次 v1.7.4，之后这台设备才能自升级（引导问题，无法绕过）。

## 为什么要做

本 App 不进应用商店，靠自建分发。以前"升级"要把 APK 发给对方再教他装一遍，
老人几乎做不到 —— 于是他们手机上的版本永远停在半年前，修好的守护能力在
最需要保护的人那里从来没生效过。

## 用户看到什么

1. **设置页底部**「版本与升级」卡片：显示当前版本号 + 「检查更新」按钮。
2. **老人端/子女端主页启动时**静默查一次：有新版本才弹窗，同一个版本**一天最多提示一次**；
   点了「稍后再说」当天不再打扰。手动点按钮不受节流限制（一定有反馈）。
3. 弹窗显示新版本号、体积、更新说明 → 点「立即升级」→ 进度对话框（百分比 + 已下载/总量）
   → 下载完自动拉起系统安装界面 → 用户点一次「安装」。

首次升级需要一次**系统授权**：Android 8.0+ 要求用户手动打开「允许来自此来源的应用」。
App 会讲清楚去哪里开、开完回来自动继续，不需要再点一次升级。

## 老设备第一次怎么装上（引导）

手机浏览器直接打开下面这条链接即可下载安装包，下载完点一下装上就行：

```
https://guard.chataifree.eu.org/api/app-update/download
```

服务端带 `Content-Type: application/vnd.android.package-archive` 与
`Content-Disposition: attachment; filename="AntiFraudGuard-v1.7.4-release.apk"`，
浏览器会正常存成 APK。装完这台设备就进入自升级通道了。

有 adb 时更快（保留数据、无需手动点）：

```bat
adb install -r dist\AntiFraudGuard-v1.7.4-release.apk
```

## 真机一键验证

```bat
node scripts\verify-app-update-device.js
```

自动完成：读设备上的 versionCode → 拉起老人端主页触发启动检查 → 读界面确认弹窗
→ 点「立即升级」→ 盯下载进度 → 确认系统安装器被拉起。装了 v1.7.4 且已是最新版时，
它会提示先发一个更高版本再测。

## 端到端链路

```
App  GET /api/app-update/latest?versionCode=14
       ↓ 比对 versionCode（绝不比 versionName 字符串）
Server 读发布清单 → { hasUpdate, latest:{ versionCode, versionName, sizeBytes, sha256, changelog, force, downloadUrl } }
       ↓
App  GET /api/app-update/download?versionCode=15   （OkHttp，边下边算 SHA-256）
       ↓ 下载到 filesDir/updates/，摘要不一致即删包重来
App  FileProvider(content://) → ACTION_VIEW → 系统安装器
```

## 服务端：事实源是 `dist/releases.json`

| 后端 | 清单位置 | APK 位置 |
|---|---|---|
| 本地 Express（热备） | `dist/releases.json`（直接读文件） | `dist/*.apk`（sendFile） |
| Cloudflare Workers（生产） | R2 `releases/latest.json`（KV 缓存 5 分钟） | R2 `releases/*.apk`（流式返回） |

两端接口契约完全一致，`tests/contract/app-update.contract.test.js` 对两个后端跑同一套用例。

**服务端自己不扫目录猜版本**：文件名里只有 versionName，而判断"要不要升"只能用
versionCode —— `1.10.0 < 1.9.0` 在字符串比较下是真的，按它判会永远不提示升级。

## 发布一个新版本（唯一正确顺序）

```bat
:: 1. 先 bump 版本号（versionCode 必须 +1，否则客户端不会认为有更新）
::    android\app\build.gradle → versionCode / versionName

:: 2. 打包（固定签名 release，保证覆盖安装不丢数据）
build-apk.bat

:: 3. 生成发布清单 + 传到 R2（生产）
node scripts/publish-release.js --note "这次改了什么" --upload
```

- `publish-release.js` 从 `build.gradle` 读版本号（人手写会错）、算 APK 的 SHA-256 与体积、
  写 `dist/releases.json`（history 保留最近 5 个版本，用于回滚）。
- **不加 `--upload` 只更新本地后端**，生产仍分发旧包。
- wrangler 的 `--remote` 是必须的：不加会写进本地 Miniflare 模拟实例，
  提示"Upload complete"但生产根本读不到。

### 强制升级（慎用）

```bat
node scripts/publish-release.js --min-code 15 --upload
```

低于该 versionCode 的客户端会被标记 `force=true`：弹窗不给「稍后再说」。
**只对"老版本会把数据搞坏"的情况使用** —— 老人不会点就等于彻底用不了 App。

## 回滚

1. 把上一个版本的记录改回 `latest`（`dist/releases.json` 的 `history` 里有），重新跑
   `node scripts/publish-release.js --apk dist/<旧包>.apk --upload`；
2. 或直接删掉 R2 的 `releases/latest.json` → 客户端查不到更新，停在现有版本。

## 验证记录

2026-10-09 在真机（Android 12，adb 无线调试）上跑通完整链路，设备日志为证：

```
v1.7.4(16) → 弹窗「发现新版本 v1.7.5（23.0 MB）」→ 点立即升级
→ 授权引导 → 系统设置页开启「允许来自此来源的应用」→ 返回自动续接下载
→ 进度 43% → I/AppUpdate: 安装包下载完成: AntiFraudGuard-1.7.5-18.apk（24126077 字节）
→ FileProvider 拉起 com.android.packageinstaller → 点「更新」
→ InstallSuccess，versionCode 变为 17
```

## 已知限制

- **下载速度**：23MB 走 Cloudflare 隧道实测 86~102KB/s，需要 4~5 分钟。
  已在进度对话框里写明"建议连接 WiFi"。要提速只能缩短单段体积或换国内 CDN。
- **国产 ROM**：小米/华为/OPPO 会在安装前额外弹一次风险确认，这是系统行为。
- **不能静默安装**：Play 商店之外的"完全无感升级"需要设备管理器/系统签名，
  本应用不做（也不该做）。

## 相关文件

| 文件 | 作用 |
|---|---|
| `android/.../util/AppUpdateManager.kt` | 查版本 / 下载 / SHA-256 校验 / 拉起安装 |
| `android/.../util/AppUpdateUi.kt` | 提示弹窗、进度、授权引导、设置页返回续接 |
| `android/.../config/GuardConfig.kt` | 提示节流（同一版本一天一次） |
| `routes/appUpdate.js` | 本地 Express 路由 |
| `workers/src/routes/appUpdate.ts` | Workers 路由（R2 + KV 缓存） |
| `scripts/publish-release.js` | 生成清单 + 上传 R2 |
| `tests/contract/app-update.contract.test.js` | 双后端契约测试（7 例） |
