# 多人绑定功能 · 实施完成概览

**设计稿**：`docs/multi-binding-design.md`　**状态**：P1 / P2 / P3 全部实施完成并通过验证，**未发版**（上线步骤见文末）。

## 做了什么

把原来 `users.bound_user_id` 双向两列的 1:1 绑定模型升级为 `bindings` 多对多表，实现「一个子女绑多位老人、一位老人被多个子女守护」，三端（本地 Express、CF Workers、Android、小程序）同步改造，旧客户端零改动兼容。

## 各端落地

### P1 服务端（双后端对齐）
- 新表 `bindings`（UNIQUE(elder_id,family_id) + 双索引防 D1 扫描计费；迁移 `0004_bindings.sql` 幂等回填存量）
- 绑定改追加式（上限 5/5）、解绑软删 + 主守护人自动轮换；`bound_user_id` 降级为主守护人派生缓存继续双写
- 鉴权（`requireBoundElder`/`canAccessElder`）改查 bindings；WS 与微信订阅消息按 bindings 全量 fanout（节流键=老人×家庭）；远程打断仅主守护人
- wx-link 绑定集合合并，2026-10-19「回指丢失」事故形态被表结构自然消灭；新增 `GET /api/auth/my-elders`
- 顺手修复：HIGH 告警模板消息 `bound_name` 恒显示「守护对象」的旧 bug（改 JOIN elder 取真名）

### P2 Android 子女端
- `GuardConfig.boundElderId` 语义不变（=当前选中），新增 `boundElders` 列表 + `switchElder()`；全部现有 Fragment URL 拼接**零改动**
- Dashboard 顶部切换条（≥2 位老人才显示）；绑定/解绑/登录回执解析多绑定列表
- `compileReleaseKotlin` 通过

### P3 小程序
- `app.js`：`boundUsers` + `currentElderId` + `getCurElder()` 统一收口；`boundUser` 兼容字段恒=当前选中，老页面零改动
- dashboard 切换条；多绑定时解绑先 ActionSheet 选人；geofence/map/index/evidence/guide 全部改读 `getCurElder()`
- 全部改动文件 `node --check` 通过

### 老人端（Android）
- 首页绑定卡片支持列表展示：「已绑定守护人：A（主要联系人）、B、C ✅」；紧急回拨仍只拨主守护人

## 验证结果

| 项 | 结果 |
| --- | --- |
| 本地契约测试 | **74/74**（含 9 条新增多绑定用例） |
| Workers 契约测试 | 72/74（2 项为本地 R2 无 APK 的既有环境性失败，与基线一致） |
| 非契约单测 | 210/210 |
| Workers typecheck | 通过 |
| Android compileReleaseKotlin | 通过 |
| 小程序语法 | node --check 全绿 |

## 上线待办（发版时执行）

1. `cd workers && npx wrangler d1 migrations apply safg-db --remote`（先对账生产回填行数 = 双向对数），再 `wrangler deploy`
2. Android 发版五件套（versionCode 先对账 `dist/releases.json` 占号，构建走 `build-apk.bat --nopause`）
3. 真机实测：双微信号各绑同一老人，验证订阅消息 fanout；多绑定切换后告警/轨迹/围栏/证据按当前对象展示

## ✅ 发版完成（2026-10-20 v1.8.0 / versionCode 54）

- **Workers 已上线**：迁移 0004 回填 182 行（=双向对数，每位老人恰 1 位主守护人）；deploy 两次（初版 + hotfix 3be0fd05）
- **发版验收揪出高危回归并同版热修**：`/user/:id` 对 elder 角色也回 `bound_elders`（恒空数组）→ Android Dashboard 把它喂给绑定列表解析 → 「显式空数组=清会话」→ 控制台变「未绑定」。已双端修复 + 契约回归用例（本地 75/75、生产 75/75）
- **模拟器 E2E 全真链路通过**：API 造双绑定 → UI 登录 → 切换条显示 → chip 切换 hero 跟随 → onResume 后列表保持。测试账号 `mbtest80701`（含测试爷爷/奶奶双绑定）留在生产库可直接体验
- **下载验收**：/latest 秒生效；旧版 53 → hasUpdate:true；18 块分块下载端到端 sha256 一致（ac286e83…）；整包预热 10.4MB/s
- **小程序 v1.7.0 已上传**微信后台（人工设体验版/提审待办）；订阅消息 fanout 双微信号实测待办
