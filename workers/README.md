# workers/ —— Cloudflare Workers 后端（迁移中）

长者防诈守护系统的 Workers 版后端。与根目录的本地 Express 后端**同仓库同步演进**，
两者对外 HTTP 契约必须完全一致，由仓库根 `tests/contract/` 的契约测试锁定。

迁移计划全文见：[../docs/cloudflare-workers-migration-plan.md](../docs/cloudflare-workers-migration-plan.md)

## 当前进度

- [x] P0 契约冻结（42 个契约用例，本地后端全绿）
- [x] P1 脚手架：wrangler 配置 / D1 schema / DO 骨架 / 健康检查
- [ ] P2 数据层：D1 DAO + tokenAuth + 存量数据迁移脚本
- [ ] P3 REST 路由：auth / events / geofence / ai / evidence
- [ ] P4 录音链路：R2 + ASR + LLM + cron 分析扫描
- [ ] P5 实时推送：GuardHubDO 完整协议
- [ ] P6 灰度切换（自定义域从隧道切到 Worker）
- [ ] P7 退役本地后端

## 首次部署前置步骤（需 CF 账号授权，尚未执行）

```bash
cd workers
npx wrangler login                    # 浏览器授权
npx wrangler d1 create safg-db        # 建 D1，把返回的 database_id 回填 wrangler.jsonc
npx wrangler r2 bucket create safg-recordings
npx wrangler kv namespace create CACHE  # 回填 namespace id
npx wrangler d1 migrations apply safg-db --remote
npx wrangler deploy
```

自定义域在 Dashboard 绑定 `guard.chataifree.eu.org`（与本地后端当前域名一致，
切换时 Android 端与小程序域名白名单零改动）。

## 本地开发

```bash
npm run dev          # wrangler dev，默认 3999 端口
npm run typecheck    # tsc --noEmit
npm run tail         # 线上日志（替代本地 server.log）
```

## 跑契约测试（ Workers 版）

```bash
# 1) 另开一个终端起 wrangler dev（端口 3999）
npm run dev

# 2) 回到仓库根，用同一套用例打 Workers 后端
cd ..
CONTRACT_BASE_URL=http://127.0.0.1:3999 node tests/contract/run.js --no-server
```

## 目录约定

```
src/
  index.ts          # Hono 入口 + fetch/scheduled handler
  routes/           # 与本地 routes/ 一一对应
  db/               # D1 schema + DAO
  services/         # tokenAuth / asr / llmClient / fraudDetector / wechat / recordingStore / zipWriter
  hub/GuardHubDO.ts # WebSocket 推送中枢（Hibernation）
  cron/             # 分析扫描 + 保留策略清理
migrations/         # wrangler d1 migrations
scripts/            # 存量数据迁移（sqlite → D1、uploads → R2）
public/             # 静态管理页（Workers Static Assets）
```
