# 开发与运维指南

## 环境

| 组件 | 版本 / 路径 |
| --- | --- |
| Node.js | CommonJS（`package.json` `type: commonjs`），零构建步骤 |
| 依赖 | express / ws / sqlite3 / multer / cors / axios |
| Android | Gradle `D:\Android\gradle\gradle-8.7\bin\gradle.bat`，SDK `D:\Android`，JDK `D:\Program Files\Java\jdk-21.0.10` |
| Android 配置 | `applicationId com.antifraud.guard`，minSdk 26 / targetSdk 33，`abiFilters 'arm64-v8a'` |
| 隧道 | cloudflared `D:\cloudflared\cloudflared.exe`，域名 `guard.chataifree.eu.org` |

## 常用命令

```bash
npm start            # 生产/本地服务（DB_PATH=data.sqlite）
npm run dev          # 同上
npm run start:demo   # 演示环境（data.demo.sqlite，不存在自动生成）
npm run seed         # 只重建演示库
npm test             # node --test tests/**/*.test.js
npm run test:watch
node scripts/reset-family-password.js <用户名> <新密码>
```

Windows 后台常驻用 `start-server-bg.bat`（幂等、不 pause），**不要**用 `start-server.bat`（含 `pause` 会卡死自动化会话）。看门狗 `scripts/watchdog-server.ps1`。

## 环境变量

写在项目根 `.env`（`scripts/load-env.js` 加载，已存在的真实环境变量优先，不被覆盖），模板见 `.env.example`。

| 分组 | 变量 |
| --- | --- |
| 服务 | `PORT`(3000)、`DB_PATH` |
| 微信 | `WECHAT_APPID`、`WECHAT_APPSECRET`、`WX_APP_ID`、`WX_APP_SECRET`、`WX_TEMPLATE_ID` |
| 逆地理编码 | `GEO_PROVIDER`(amap/tencent/NONE)、`AMAP_WEB_KEY`、`TENCENT_MAP_KEY`、`GEO_TIMEOUT_MS` |
| ASR 转写 | `ASR_PROVIDER`(whisper/tencent)、`ASR_BASE_URL`、`ASR_API_KEY`、`ASR_MODEL` / `TENCENT_APP_ID`、`TENCENT_SECRET_ID`、`TENCENT_SECRET_KEY`、`ASR_TIMEOUT_MS` |
| LLM 复核 | `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`LLM_TIMEOUT_MS` |
| 录音生命周期 | `RECORDING_SAFE_RETENTION_DAYS`(14)、`RECORDING_CLEANUP_INTERVAL_MS`、`PLAY_TOKEN_TTL_MS`、`EVIDENCE_TOKEN_SECRET` |

全部不配置也能跑：地址退化为坐标串，转写状态记 `SKIPPED`，录音照常上传存证。设计取向是"缺配置不崩、不假装成功"。

## 代码规范

- CommonJS：路由进 `routes/`，服务层进 `services/`，DB 只经 `database/db.js`。
- SQL 全参数化查询，不拼字符串。
- 日志 `console.log` + emoji 前缀标级别（`✅` 成功 / `⚠️` 告警 / `❌` 失败 / `⚡` 实时推送 / `📍` 地理 / `🎙️` 录音）。
- 时间统一在 API 出口转北京时间（`services/timeFormat.js`）。`created_at` 会被转换；`recorded_at` / `retention_until` 是 ISO UTC 原样下发，客户端必须双格式兼容（带 Z → UTC 解析；裸串 → 北京时间），判 Z 要在截小数点之前。
- 注释写"为什么"，不写"是什么"。

### 跨端镜像规则

同一份业务判定存在三处实现，改动必须同步，否则出现"服务端说该弹、客户端不弹"的静默不一致：

| 规则 | 服务端 | Android | 小程序 |
| --- | --- | --- | --- |
| 风险告警是否打断 | `services/riskAlertPolicy.js` | `family/RiskAlertPolicy.kt` | `app.js` |
| 后端地址 | `scripts/cloudflare/hostname.txt` | `GuardConfig.DEFAULT_SERVER_BASE_URL` | `wechat-miniprogram/config.js` |

## 安全设计

1. **Token 鉴权**：随机 24 字节 token 存内存 Map，7 天 TTL，请求头 `X-Auth-Token`。重启即失效。
2. **绑定关系校验**：`requireBoundElder` 兼容 `:elderId` / `:id` / `:userId`，新增路由若换了参数名会静默 403，务必确认被兼容。
3. **密码**：scrypt 加盐，`salt:hash`，登录标识可用用户名或手机号。
4. **SQL 注入**：全程参数化。
5. **上传安全**：multer 落盘名完全由服务端生成，杜绝 `../` 目录穿越；`/upload` 校验 `elderId` 对应 `role='elder'` 账号存在，防孤儿记录；multipart mime 按扩展名推断。
6. **播放鉴权**：Android MediaPlayer 不带自定义头，收听录音走 HMAC URL 签名（`services/signToken.js`），签名覆盖 rid/uid/exp，不可伪造也不可挪用。
7. **WebSocket 稳定性**：每个连接必须挂 `error` 处理器，否则单客户端异常关闭帧会打崩进程。
8. **Android JSON 兜底**：`optString(name,"")` 遇 JSON null 返回字符串 `"null"`，统一用 `util/JsonUtils.kt` 的 `optStringOrEmpty()` / `pickPhone()`；服务端对应用 `COALESCE(NULLIF(mobile,''),phone)`。

### Android 高危坑

- `${'$'}` 是转义字面量不是插值；从别处抄代码会把转义带过去，改完全局 grep 检查。
- 字符串插值紧跟全角标点会被吞，必须写 `${var}`。
- 新增后台 Service 必须在 `object` 单例 init 里覆盖 `ApiClient.init(applicationContext)`，否则界面正常、后台全坏。
- URL 拼接集中在 `ApiClient.buildUrl()`（带 scheme 校验）；`setServerBaseUrl("")` 忽略并保留旧配置。
- 全角→半角归一化：中文输入法下手机号/密码正则必败。
- `MediaRecorder` 分段续录用 `Handler.postDelayed`，防爆栈。
- 长驻服务读配置必须用 `segmentMaxMs()` 这类现读函数，不能读启动快照，否则改了设置不生效。

## 测试

`npm test` 覆盖纯逻辑层，143 个用例：

| 文件 | 覆盖 |
| --- | --- |
| `fraudDetector.test.js` | 诈骗话术规则、分级阈值 |
| `zipWriter.test.js` | ZIP 结构、offset、ZIP64 |
| `signToken.test.js` | 播放签名签发与校验 |
| `regeo.test.js` | 逆地理编码与地址判定 |
| `asr.test.js` | 转写 Provider 降级 |
| `timeFormat.test.js` | 北京时间转换 |
| `riskAlertPolicy.test.js` | 打断判定 |
| `locationSensitivity.test.js` | 敏感地点判定 |

Android 单测：`android/app/src/test/.../LocationHeartbeatPolicyTest.kt`、`LocationPermissionPlanTest.kt`。

`tests/test-wx-login.js` 不在 `tests/**/*.test.js` 匹配范围内（命名不匹配），需手动运行。

## Android 打包（必须遵守）

**凡涉及打包 APK，一律执行仓库根目录的 `build-apk.bat`，禁止直接调用 gradle assemble 或只交付 `app/build/outputs/` 下的产物。**

- `build-apk.bat --nopause` → Release 签名包（**默认用这个**）
- `build-apk.bat debug --nopause` → Debug 测试包
- Agent / 脚本调用**必须带 `--nopause`**：脚本末尾有 `pause`，否则卡死自动化会话
- **默认 Release**：固定 `keystore/guard-release.jks` 签名，用户手机上可直接覆盖升级（数据保留）；Debug 是 debug 签名，与已装 Release 不兼容，覆盖安装会失败，只能卸载重装，仅限本地联调
- **产物**：`dist\AntiFraudGuard-v<版本>-<tag>.apk`，以 dist 下的文件为准交付
- **发版前**：递增 `android/app/build.gradle` 的 `versionCode`，否则系统视为降级拒绝覆盖安装
- **打包后必须验证新代码真的进了包**：Gradle 的 UP-TO-DATE 不可信。用 `zipfile` 读 APK 内 dex 搜索本次新增的中文字符串常量确认。插值文案编译后不留字面量，要选必然保留的常量当标记物；判据优先级 ① cmp 两包 dex 逐字节 ② dexdump 看字节码常量 ③ 时间戳仅参考
- CI（`.github/workflows/build-apk.yml`）push main 时构建 Debug 包并上传 artifact，keystore 不入库

## 脚本编码铁律

**本项目的 `.bat` / `.ps1` 一律 ASCII-only。**

cmd.exe 在 `chcp 65001` 生效**之前**用 GBK 解 UTF-8 字节，中文行被解成乱码并直接变成非法命令（`'xxx' 不是内部或外部命令`）。把 `chcp` 写在中文行之后就会哑火。REM 注释里的中文虽不执行，但 `echo` 里的中文会整行炸。

交付前校验（必须为空）：

```bash
python -c "d=open('脚本路径.bat','rb').read(); print([b for b in d if b>127])"
```

PowerShell 处理中文源码同理（UTF-8 写坏），统一不在脚本里用中文。

## 隧道与部署

已从 cpolar 迁到 Cloudflare named tunnel，域名固定 `guard.chataifree.eu.org`：

```
https://guard.chataifree.eu.org  ->  http://127.0.0.1:3000
wss://guard.chataifree.eu.org
```

选 Cloudflare 的原因：cpolar 服务器需要反连本机，本机入站被拦（`i/o timeout`）；Cloudflare Tunnel 是纯出站模型，天然绕开。

- token 在仓库外 `D:\cloudflared\tunnel-token.txt`（或 `C:\ProgramData\cloudflared\token`）
- 已装为 Windows 服务（Auto + FailureActions），只保留一个连接器实例
- 服务脚本别用裸 `if errorlevel 1` 判成败：`net start` 对已运行返回 2182，那是成功态
- 测隧道加 `--noproxy '*'`，走本机代理反而慢约 40%
- 吞吐方差极大（3.15MB 测到 199KB/s，7.35MB 只有 86~102KB/s），别用单次测量下结论。最慢几次逼近 CF 边缘约 100s 时限，缓解手段是缩短单段录音时长（已从 10 分钟下调到 5 分钟）

详细运维说明见 [cloudflare/README.md](../scripts/cloudflare/README.md)。

## 小程序发布

工具链在 `scripts/mp-publish/`，日常两条命令：

```bat
scripts\mp-publish\publish.bat guard.chataifree.eu.org 1.0.3
```

域名固定后"换域名"流程正常不需要再跑。详见 [mp-publish/README.md](../scripts/mp-publish/README.md)。

## 路线图

`docs/superpowers/plans/2026-10-07-hardening-roadmap.md`，执行状态看 §9。
`docs/superpowers/specs/` 存设计稿，`docs/superpowers/plans/` 存实施计划。