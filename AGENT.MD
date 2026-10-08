# AGENT.MD — 长者防诈亲情守护系统

行为感知 → 实时干预 → 证据留存 三位一体闭环，保护老年人免受电信诈骗、保健品会销、养老投资骗局威胁。

技术栈：Node.js + Express + SQLite + WebSocket（CommonJS，零构建）。端口 3000，生产域名 `guard.chataifree.eu.org`。

三端共用一套后端：老人端 Android App（无感感知 + 告警）、子女端 Android App 与微信小程序（地图轨迹、强提醒打断、远程停止录音、一键打包报警材料）。

## 快速启动

```bash
npm start            # 生产/本地（data.sqlite）
npm run start:demo   # 演示（data.demo.sqlite，纯假数据，自动生成）
npm test             # 143 个用例
```

环境变量写项目根 `.env`（模板 `.env.example`，不配置也能跑，缺配置只降级不崩）。

## 目录结构

```
server.js              # 入口：Express + WebSocket + WS 连接表 + 离线打断队列
database/db.js         # SQLite 连接、建表、字段迁移、预置数据
routes/                # auth / events / geofence / recordings / evidence / ai
services/              # 鉴权、ASR、诈骗研判、逆地理编码、录音落盘与清理、ZIP、签名、时间格式化
android/               # 老人端 + 子女端 Android（Kotlin）
wechat-miniprogram/    # 子女端微信小程序
public/index.html      # Web 管理面板
tests/                 # node --test 单元测试
scripts/               # 种子数据、常驻看门狗、Cloudflare 隧道、小程序发布
docs/                  # 全部文档
```

## 铁律

违反下面几条会造成**静默失效**，是最难查的 bug 类型。细节见对应文档。

1. **打包 APK 一律走根目录 `build-apk.bat --nopause`**，禁止直接 gradle assemble 或只交付 `app/build/outputs/` 产物。默认 Release（固定签名可覆盖升级保数据），发版前 bump `versionCode`，打包后必须读 APK 内 dex 搜新增字符串常量验证新代码真进了包（Gradle UP-TO-DATE 不可信）。
2. **`.bat` / `.ps1` 一律 ASCII-only**。cmd.exe 在 `chcp` 生效前用 GBK 解 UTF-8 字节，中文行会变成非法命令。交付前用 `python -c "..."` 校验无非 ASCII 字节。
3. **用户配置的事实源必须在服务端**（按 `elderId` 存 `users.guard_settings`）。只存本机的配置在换机/重装后静默回到默认值。
4. **服务端不采信客户端自报的 `severity`**，按事件类型归一化（`EVENT_SEVERITY_OVERRIDE` / `riskAlertPolicy`），且告警打断规则在服务端、Android、小程序三处镜像，改动必须同步。
5. **跨端镜像常量只有一个真源**：后端域名在 `scripts/cloudflare/hostname.txt`、`GuardConfig.DEFAULT_SERVER_BASE_URL`、`wechat-miniprogram/config.js` 三处保持一致。
6. **时间统一在 API 出口转北京时间**（`services/timeFormat.js`），但 `recorded_at` / `retention_until` 是 ISO UTC 原样下发，客户端需双格式兼容。
7. **`phone` 字段存登录用户名，`mobile` 存真实手机号**。取值一律 `COALESCE(NULLIF(mobile,''), phone)`；Android 侧统一走 `util/JsonUtils.kt`，因为 `optString(name,"")` 遇 JSON null 会返回字符串 `"null"`。
8. **WebSocket 每个连接必须挂 `error` 处理器**；`clients` Map 的 `close` 回调只在 `clients.get(userId) === ws` 时才删除，否则网络抖动会误删新连接注册记录。
9. **录音证据从严**：FRAUD 永久保留，SAFE/SUSPECT/转写失败保留 14 天等人工复核。"分析不了"≠ 没问题，ASR 失败按 SUSPECT 处理。
10. **敏感数据不入库**：`data.sqlite`、`uploads/`、`*.apk`、`android/keystore/`、`.env`、`data.demo.sqlite` 均在 `.gitignore`，录音按扩展名单独拦截。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 产品与技术方案（痛点、架构、合规设计） |
| [docs/api-reference.md](docs/api-reference.md) | REST API 清单、鉴权模型、事件类型与打断判定、WebSocket 协议 |
| [docs/database-schema.md](docs/database-schema.md) | 六张表结构、证据保留策略、隐私红线 |
| [docs/development-guide.md](docs/development-guide.md) | 环境、命令、环境变量、代码规范、安全设计、Android 坑、测试、打包、隧道 |
| [docs/录音存证链路实现说明.md](docs/录音存证链路实现说明.md) | 分段录音 → 上传 → 转写 → AI 研判 → 子女端收听完整链路 |
| [scripts/cloudflare/README.md](scripts/cloudflare/README.md) | 隧道运维、吞吐基线、DNS 坑、脚本编码 |
| [scripts/mp-publish/README.md](scripts/mp-publish/README.md) | 小程序上传、公众平台域名同步、contenteditable 陷阱 |
| [docs/superpowers/plans/2026-10-07-hardening-roadmap.md](docs/superpowers/plans/2026-10-07-hardening-roadmap.md) | 加固路线图，状态看 §9 |

被 gitignore 的 `.workbuddy/memory/MEMORY.md` 记录了跨会话踩坑铁律，改动前值得一读。

## 文档维护约定

本文件与上表所有引用文档由各 agent 自行维护，没有专人同步：

- **改了代码就顺手改对应文档**，两者同一次提交里落地，不留"文档待补"。
- 文档内容以**代码为准**。发现文档与代码不符时，改代码的同时改文档，不要只改文档去迁就现实。
- 新增模块 / 接口 / 表字段时，先想清楚它属于上表哪份文档，**归位到已有的文档里，不要另起新文件**。
- AGENT.MD 只放跨端通用的铁律与索引；单个模块的细节归到该模块自己的文档。铁律本身不轻易增删（增删需要理由），细则随时可以更新。