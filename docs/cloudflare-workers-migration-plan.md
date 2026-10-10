# Cloudflare Workers 迁移计划（workers/ 子目录工程）

> 立项时间：2026-10-08 ｜ 状态：规划
> 目标：把 `senior-anti-fraud-guard` 的 Node.js 后端（Express + sqlite3 + multer + ws，约 4000 行）
> 迁移为**本仓库内的 `workers/` 子目录**（monorepo），部署在 Cloudflare Workers 免费层。
> 演进期内本地后端与 Workers 后端并存、以契约测试锁住同一套 API 同步演进；
> 最终退役家内 Windows 服务器 + watchdog + 计划任务 + cloudflared 隧道整套自托管设施。

---

## 0. 为什么是"重写"而不是"搬运"

Cloudflare 免费算力是 Workers（V8 隔离环境），**不运行标准 Node 长驻进程**。现有后端的四类
基础设施在 Workers 上没有对应物，必须换实现：

| 现有实现（本地工程） | Workers 上的替代 | 改动量 |
|---|---|---|
| Express 路由（routes/ 6 文件 28 个端点） | Hono（Workers 原生路由框架） | 大 |
| sqlite3 原生模块（database/db.js） | D1（CF 托管 SQLite） | 大 |
| multer + 本地 `uploads/` 磁盘落盘 | R2 对象存储 | 中 |
| `ws` 服务端 + 内存在线表（server.js） | Durable Objects WebSocket | 中 |
| tokenAuth.js 内存 Map（7 天 TTL） | D1 `auth_tokens` 表 | 小 |
| recordingCleanup.js `setInterval` | Cron Triggers | 小 |
| asr.js / llmClient.js / wechat.js 裸 http(s) 模块 | `fetch` + Web Crypto | 中 |
| zipWriter.js fs 流式打包 | 手工 STORE 式 zip + R2 流拼接 | 中 |
| express.static(public/) | Workers Static Assets | 小 |

利好：**对外 HTTP 契约完全不变**。Android 端 `GuardConfig.DEFAULT_SERVER_BASE_URL` 仍指向
`https://guard.chataifree.eu.org`，域名 DNS 已托管在 CF，直接把该域名绑定为 Worker 的
Custom Domain 即可——**客户端零改动、小程序域名白名单零改动**。

---

## 1. 子目录定位（monorepo）

- 新后端落位：**本仓库新增 `workers/` 子目录**（不另开仓库），与本地后端同步演进
- 选 monorepo 而非独立仓库的理由：
  - **契约同步演进**：API 契约测试放仓库根，同一套用例既能打本地后端也能打 Worker，
    改接口时两个实现必须同时过测，天然防止契约漂移
  - 演进期内问题单、文档、版本号一体管理，不用跨仓对齐
  - 退役本地后端时只是一次目录清理，`workers/` 顺势成为唯一后端；将来若真想独立成仓，
    `git filter-repo` 随时可拆，成本极低
- 注意：旧 Node 代码与新 Workers 代码**不共享运行时模块**（回调式 sqlite3 vs D1、
  http 模块 vs fetch，没有可复用的交集），"同步演进"靠的是契约测试锁 API，不是共享代码
- 技术栈：TypeScript（推荐）或 JS、Hono、D1、R2、Durable Objects、KV、Cron Triggers

### 目标目录结构

```
senior-anti-fraud-guard/            # 现有仓库
├── server.js / routes/ / services/ # 现有本地后端（演进期内保持可用）
├── tests/                          # 现有单元测试
├── tests/contract/                 # ★ API 契约测试（对两个后端跑同一套用例）
├── docs/                           # 本计划文档所在
└── workers/                        # ★ 新后端（Cloudflare Workers）
    ├── wrangler.jsonc              # D1/R2/DO/KV/Cron/custom domain 绑定
    ├── src/
    │   ├── index.ts                # Hono 入口 + fetch handler + scheduled handler
    │   ├── routes/                 # 与旧 routes/ 一一对应：auth/events/geofence/recordings/ai/evidence
    │   ├── db/
    │   │   ├── schema.sql          # D1 建表（自旧 db.js 翻译）
    │   │   └── dao/                # 每张表一个 DAO，全部走 prepare().bind() 异步 API
    │   ├── services/
    │   │   ├── tokenAuth.ts        # X-Auth-Token → D1 auth_tokens 查表
    │   │   ├── asr.ts              # 腾讯云 ASR：fetch WS 出站 + Web Crypto 签名
    │   │   ├── llmClient.ts        # chat/completions → fetch
    │   │   ├── fraudDetector.ts    # 纯逻辑，几乎原样移植
    │   │   ├── wechat.ts           # api.weixin.qq.com → fetch；access_token 缓存进 KV
    │   │   ├── recordingStore.ts   # uploads 磁盘 → R2（key: recordings/{elderId}/{sessionId}/...）
    │   │   └── zipWriter.ts        # STORE 式 zip，R2 流拼接，不落盘
    │   ├── hub/
    │   │   └── GuardHubDO.ts       # Durable Object：WS 注册表 + notifyFamily/INTERRUPT_CMD 等
    │   └── cron/
    │       ├── analyzeSweep.ts     # 每分钟扫 PENDING 录音做转写+研判（见 §4 决策）
    │       └── cleanupSweep.ts     # 每日保留策略清理（对应 recordingCleanup.js）
    ├── migrations/                 # wrangler d1 migrations
    ├── scripts/
    │   ├── migrate-sqlite.ts       # 旧 data.sqlite → D1（wrangler d1 execute --file）
    │   └── upload-to-r2.ts         # 旧 uploads/recordings → R2，回填 r2_key
    ├── tests/                      # workers 内部单测（vitest-pool-workers）
    └── public/                     # 静态管理页（Workers Static Assets 托管）
```

### 同步演进的约束（写入 AGENT.MD）

- **改 API 必须双写**：任何人（包括 Agent）修改请求/响应结构时，必须同时改
  `routes/*`（本地）与 `workers/src/routes/*`（Workers），并更新 `tests/contract/`；
  只改一边视为未完成
- 契约测试对两个后端各跑一遍：本地后端起 4040 端口跑一轮，`wrangler dev` 跑一轮
- 新增功能默认先在本地后端实现验证，再移植到 Workers；Workers 不做实验性接口

---

## 2. 数据层：D1（schema + 存量迁移）

### 2.1 表结构

6 张表原样翻译进 D1（`users` / `risk_events` / `locations` / `payments` / `geofences` /
`recordings`），保留全部 CHECK 约束、索引（`idx_recordings_elder/session/status`）与
`guard_settings` JSON 文本列。旧 db.js 里"ALTER TABLE 兜底补列"的历史包袱**不迁移**，
D1 直接以最终形态建表。

新增 1 张表：

```sql
CREATE TABLE auth_tokens (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL   -- ms，对应原 7 天 TTL
);
```

### 2.2 访问层要点

- sqlite3 的回调式 API → D1 `env.DB.prepare(sql).bind(...).all()/first()/run()`，全部异步；
  原 `db.serialize` 初始化逻辑删除。
- 老人端账号 `elder-register` 带 `previousPhone` 的换号分支、`COALESCE(NULLIF(mobile,''),phone)`
  等 SQL 原样保留（见项目 MEMORY 铁律）。
- D1 免费额度：5GB 存储 / 每天 500 万行读 / 10 万行写。本系统单家庭日读写量远低于此。

### 2.3 存量迁移

1. 用 `scripts/migrate-sqlite.ts` 把本地 `data.sqlite` 全表导出为 INSERT 语句 SQL 文件
   （注意单引号转义、时间戳格式保持原样——`created_at` 带时区语义的约定不许变）。
2. `wrangler d1 execute <db> --file=migrations/xxx.sql` 导入。
3. **校验**：对每张表跑 `SELECT COUNT(*)` + 抽样 10 行与旧库 diff；`users` 表必须核对
   老人 `elder_id=12`（真机在用账号）的 bind_code、bound_user_id、guard_settings 三项。

---

## 3. 对象存储：R2

- bucket：`safg-recordings`
- key 规则：`recordings/{elderId}/{sessionId}/seg{index}-{ts}.m4a`（沿用"按 elderId 分层 +
  二次校验不跑出前缀"的安全思路，R2 层天然隔离）
- `recordings` 表 `file_name` 列语义改为存 R2 key（列名不动，客户端只经服务端中转拿流，
  不感知存储差异）
- `recordingStore.js` 的 `exists/size/readStream/unlink` 四个操作 → R2 `head/get/delete`
- `/recordings/stream/:id` 支持 Range 请求（R2 `get({ range })`），播放器拖进度条依赖它
- `/recordings/pack/:elderId` 导出：zipWriter 重写为 STORE 方式（不压缩，只拼 local file
  header + R2 流），5 分钟单段 ~7MB 在 128MB 隔离体内安全
- 免费额度：10GB 存储。当前存量仅 125MB；**注意 FRAUD 录音永久保留，长期会逼近上限**，
  迁移后加一条水位告警（cron 每日统计 R2 用量，超 8GB 时通过 WS/微信订阅消息提醒子女端）

---

## 4. 录音分析链路（本工程最关键的设计决策）

现状（**2026-10-08 复核后修正**）：`routes/recordings.js` 里 `processRecordingAsync`
是 **fire-and-forget**（不 await），上传接口落盘+入库后立即回
`{success, data:{id, fileName, sha256, sizeBytes, duplicated}}`，ASR 转写与 LLM 研判在后台跑，
完成后走 WS `RECORDING_ANALYZED` 推给子女端。**也就是说：当前语义本来就是"上传快速应答 +
异步分析"，方案 B 不需要改任何客户端代码。**

Workers 免费层约束：单请求 CPU 时间 10ms（I/O 等待不计费，转发/流式不受影响）、
出站并发连接 6、子请求 50 个/请求。ASR（1 条 WS 连接）+ LLM（1 次 fetch）都在限额内。

**决策：Workers 版采用方案 B（上传只落 R2 + 记 PENDING 立即应答，cron 捞队列做分析）。**

- 与本地后端语义完全一致，客户端零改动；录制段上传窗口缩短为纯传输时长，正好解决
  现在 CF 边缘 ~100s 时限逼近的痛点。
- 为什么不用 `ctx.waitUntil` 内联跑分析：free 计划 waitUntil 有约 30s 的墙钟限制，
  而 5 分钟录音的 ASR 流式转写常常超过这个时长，会被静默掐断（表现为"永远停在 PENDING"）。
- cron 每分钟扫 `transcript_status='PENDING'`（已有 `idx_recordings_pending` 索引）。
- ASR 音频上行走 CF 边缘到腾讯云，不再是家宽，预计比现在 29~95s 的瓶颈段显著缩短。
- 铁律延续：ASR 失败标 `SUSPECT` 而非放行；保留策略（FRAUD 永久 / 其余 14 天）原样移植
  进 `cleanupSweep.ts`。

---

## 5. 实时推送：Durable Object

现有协议（server.js）：客户端连上后发 `{type:'REGISTER', userId, role}`，服务端回
`REGISTER_ACK`；业务侧推送 `RECORDING_ANALYZED` 等；命令下发 `INTERRUPT_CMD` /
`RECORDING_STOP_CMD`；状态上报 `RECORDING_STATE`。

- 新建 `GuardHubDO`：单实例（`idFromName('global')`），内部维护 `Map<userId, WebSocket>`。
- 启用 **WebSocket Hibernation**（`acceptWebSocket` + `webSocketMessage/webSocketClose`
  处理器）：空闲连接不占活跃计费，免费额度内可持续挂机。
- 业务路由通过 `env.GUARD_HUB.get(id).fetch()` 调 DO 的内部 HTTP 接口
  `POST /notify {elderId, payload}`，DO 内查绑定关系后转发给 bound_user_id 的 WS——
  `notifyBoundFamily` 的绑定查询语义原样保留。
- `INTERRUPT_CMD` 转发给 elderId 对应连接；elder 离线时返回明确的离线态给子女端（现有行为）。
- DO 用 SQLite storage 存 token→ws 的注册映射（hibernation 恢复后仍可定向推送）。

---

## 6. 鉴权与外部服务

- **tokenAuth**：内存 Map → D1 `auth_tokens`。`requireFamilyAuth` 中间件改 async，
  查表校验 TTL；过期/登出物理删除行。注意原实现"每次请求一次内存查"变"每次一次 D1 读"，
  日读量仍远低于免费额度。`requireBoundElder` 的 `:elderId/:id/:userId` 三参兼容**原样保留**
  （项目铁律：新路由参数名必须被兼容，否则静默 403）。
- **wechat.ts**：token 获取与订阅消息发 nil → fetch；access_token（7200s 有效）缓存进
  KV namespace（免费 10 万读/天）。
- **llmClient.ts**：chat/completions 调用改 fetch，超时用 `AbortSignal.timeout()`。
- 全部外呼集中在 `src/services/`，沿用旧工程"URL 拼接集中管理"的约束。

## 7. 入口与静态资源

- `wrangler.jsonc`：`assets = { directory: "./public" }` 托管管理页；
  `routes` 自定义域绑定 `guard.chataifree.eu.org`（该域 zone 已在本 CF 账号，加一条
  Custom Domain 即可，无需改 DNS）。
- Cron Triggers：`*/1 * * * *`（analyzeSweep，方案 B 启用时）+ `0 4 * * *`（cleanupSweep）。
- 观测：`wrangler tail --format pretty` 替代 server.log；错误率接 Workers Analytics（免费自带）。

---

## 8. 实施阶段与验收

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| ~~P0 契约冻结~~ ✅ **已完成 2026-10-08** | 28 个端点的请求/响应契约固化进 `tests/contract/`（5 个文件 42 个用例），本地后端全绿作基线。运行：`npm run test:contract`（自动起临时库 `data.contract-test.sqlite` + 3999 端口，跑完清理） | ✅ 42/42 通过（契约点：账号/绑定/换号找回/防护规则同步、事件 severity 归一化、围栏越权、录音上传幂等/流式/打包/复核、证据包 checksum） |
| ~~P1 脚手架~~ ✅ **已完成 2026-10-08** | `workers/` 子目录 + wrangler 配置 + D1 schema（7 张表）+ GuardHubDO 骨架 + 健康检查 | ✅ `npm run typecheck` 通过；`wrangler dev --local` 起来后 `GET /api/health` 返回 `{success:true,service:"workers"}`；未迁移接口统一 501 |
| ~~P2 数据层~~ ✅ **已完成 2026-10-08** | D1 DAO 全量（users/events/geofence/recordings 四模块）+ D1 版 tokenAuth（内存 Map → `auth_tokens` 表，登录后立刻读强一致所以不进 KV）+ 迁移脚本 `scripts/export-sqlite.py`（sqlite→INSERT dump）与 `scripts/upload-to-r2.mjs`（录音→R2 + file_name 回填，dry-run/--local/--remote 三模式）。自检端点 `POST /api/_smoke/db` 在真实 D1 里把全部 DAO 跑一遍读写删 | ✅ 导出 372 行导入本地 D1 逐表对账一致；smoke 全绿（含 sha 幂等、转写失败→SUSPECT、token 签发/吊销）；`npm run typecheck` 通过。录音存量 17 段文件在 `uploads/recordings/`（脚本已修正路径） |
| ~~P3 REST 路由~~ ✅ **已完成 2026-10-09** | auth/events/geofence/ai/evidence 五组路由移植成 Hono；配套移植 timeFormat/riskAlertPolicy/locationSensitivity/placeGuess/regeo 五个纯逻辑服务（regeo 的坐标缓存从内存 Map 换成 KV，key 走 env 变量）；鉴权中间件保持三态参数名兼容。启用 `nodejs_compat`（node:crypto 的 scrypt 密码哈希/SHA-256 checksum）+ `@types/node`。contract runner 加 `CONTRACT_FILES` 过滤；新增 `scripts/reset-contract-d1.sql`（本地 D1 状态持久，跑契约前先清，对齐本地 runner 全新临时库语义） | ✅ Workers 31/31 绿（`CONTRACT_BASE_URL=... node --test tests/contract/{auth,events,geofence,ai-evidence}...`）；本地后端回归 42/42 绿 |
| P4 录音链路 | R2 存取 + stream/pack/review/delete + ASR/LLM 分析（waitUntil 异步，语义对齐本地 fire-and-forget） | ✅ 2026-10-09 recordings 契约 11 用例对 Workers 全绿 |
| P5 WS 推送 | GuardHubDO + Hibernation + notifyFamily；events 路由补 RISK_ALERT 广播；assets 改 run_worker_first（WS 走根路径必须 Worker 先行） | ✅ 2026-10-09 ws 契约 5 用例对两个后端全绿；全量 47/47 双后端对齐 |
| P6 灰度切换 | ✅ **已完成 2026-10-09**：CF 资源开通（D1 `safg-db`/KV `CACHE`/R2 `safg-recordings`）→ 远程 D1 导入 507 行逐表对账一致 → R2 传 18/18 录音 + file_name 回填 → `wrangler deploy`（自定义域 + cron 生效，版本 20e8ad47）→ 删除旧隧道 DNS 记录后 Custom Domain 绑定成功。验证：`/api/health` 200（db:ok，直连 1.3s）、登录路径 401 结构正确（D1+scrypt）、静态页 200、`wss://guard.chataifree.eu.org/` REGISTER→REGISTER_ACK 全通。本地服务+隧道保留做热备（回滚=CNAME guard → `fdc8e0d1-...cfargotunnel.com` 开橙云，秒级） | 小程序真机全回归：登录/绑定/事件/围栏/录音/推送/证据导出（待真机验证） |
| P7 退役 | 观察一周无回退后：停本地计划任务 `AntiFraudGuardBackend`、卸载 cloudflared 服务、清理 cpolar；`workers/` 成为唯一后端，`server.js` 一族标记 deprecated（保留一个版本周期后删除） | 本地零常驻进程；契约测试只对 Worker 跑；文档更新 |

**P6 回退开关**：Android `GuardConfig.migrateServerUrlOnce()` 只迁失效地址、不碰手填地址
（已有机制）。灰度期内如 Worker 出问题，在 CF 后台把 Custom Domain 指回原隧道 Worker/
回源规则即可，或让用户端暂改服务器地址。

## 9. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| R2 免费 10GB 被 FRAUD 永久录音吃满 | 上传失败 | 每日 cron 水位告警；远期考虑对超旧 FRAUD 降档（转冷备需用户确认） |
| D1 与 sqlite3 行为差异（如 DATETIME 精度/时区） | 时间显示错乱 | 迁移校验脚本逐表 diff；沿用"`recorded_at` ISO UTC 原样下发、客户端双格式兼容"的既有约定 |
| Workers 10ms CPU 对大 JSON/zip 拼装的隐性消耗 | 偶发超限 1102 | pack 接口压测；必要时 zip 分片导出 |
| ASR WS 出站经 CF 边缘的连通性 | 转写失败率高 | P4 先用单段灰盒验证；失败标 SUSPECT 的兜底已有 |
| 免费层 10 万请求/天 | 理论上够 | 上线后看 Analytics 实际曲线（当前单家庭远低于此） |
| 微信小程序域名白名单 | 切换不可用 | 域名不变，无需重新同步；P6 前用 check-ws.js 再确认一次 |

## 10. 明确不做的事

- 不在本工程内做 Android/小程序任何改动（方案 B 二期才涉及）。
- 不迁移 `data.demo.sqlite` 与演示 seed 流程（演示场景继续用本地工程起服务）。
- 不迁 `uploads/` 里已过保留期、按策略本应清理的文件。
