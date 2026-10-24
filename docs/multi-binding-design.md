# 多人绑定（多子女 ↔ 多老人）前后端设计

> 2026-10-20 · 状态：设计稿（未实施）
> 目标：支持「同一个子女绑定多个老人」与「同一个老人被多个子女绑定」，同时保证存量客户端与生产数据平滑过渡。

---

## 1. 背景

当前绑定模型是 `users.bound_user_id` 的**双向两列 1:1 结构**（elder.bound_user_id ↔ family.bound_user_id）。真实场景下「多子女共同守护一位老人」「子女照顾两位老人」都是刚需，1:1 模型装不下。

## 2. 现状盘点：bound_user_id 的全部触点

改造前必须知道每一条读/写路径，以下为本次调研确认的完整清单：

### 本地后端（server.js + routes/ + services/）

| 触点 | 用途 |
| --- | --- |
| `database/db.js` | 建表、预置数据（1↔2 互绑） |
| `services/tokenAuth.js` `requireBoundElder` | 数据归属校验（子女只能查绑定老人的数据） |
| `routes/auth.js` `/bind` `/unbind` | 覆盖式双向 UPDATE |
| `routes/auth.js` `/wx-link` | 微信账号关联时的绑定迁移 + 老人侧无条件回指兜底（2026-10-19 事故修复点） |
| `routes/auth.js` `replyElderState` | 老人端回执中的 boundFamily 单对象 |
| `routes/auth.js` `/login` `/wx-login` | 登录回执 boundUser 单对象 |
| `routes/events.js` | HIGH 事件推送前查守护人 |
| `routes/geofence.js` `evidence.js` `recordings.js` | 按绑定关系取 elderId / 拼 family 信息 |
| `services/elderPresenceScanner.js` | 失联扫描按 bound_user_id 找通知对象 |

### Workers 后端（生产，域名见 `scripts/cloudflare/hostname.txt`）

| 触点 | 用途 |
| --- | --- |
| `migrations/0001_init.sql` | D1 建表 |
| `src/db/types.ts` | UserRow 含 bound_user_id |
| `src/db/dao/users.ts` | `bindPair` / `unbind` / `replyElderState` / `getUserWithBound` |
| `src/db/dao/events.ts` `getBoundUserId` | HIGH 事件推送前查守护人 |
| `src/services/tokenAuth.ts` `canAccessElder` | 鉴权 |
| `src/routes/auth.ts` | 与本地 auth.js 逐条对齐的同一套端点 |
| `src/routes/geofence.ts` `getBoundElderId` | 写操作限定绑定老人 |
| `src/routes/evidence.ts` | 导出单据拼 family 信息 |
| `src/hub/GuardHubDO.ts` | `/notify` 定向推送、`notifyBoundFamily`（WS + 订阅消息）、`lookupFamilyPhone`（警报页回拨号码）——**全按 `elder.bound_user_id` 单人查找** |
| `src/cron/presenceSweep.ts` | `WHERE role='elder' AND bound_user_id IS NOT NULL` 筛失联扫描对象 |

### 客户端

| 端 | 触点 |
| --- | --- |
| Android 子女端 | `GuardConfig.boundElderId/Name/Phone`（单值缓存）；ApiClient 登录/绑定时写缓存；Dashboard/Track/Alerts/Evidence/Geofence 等全部 Fragment 拼 URL 用 `boundElderId`；FamilyWebSocketManager REGISTER 带 `targetElderId` |
| Android 老人端 | 登记回执展示 boundFamily（守护人姓名/手机号）、告警回拨子女 |
| 小程序 | `app.js globalData.boundUser` 单对象；dashboard/geofence 页直接 `boundUser.id` |

**关键结论**：旧客户端与服务端的兼容边界是 **API 响应形状**（`boundUser` / `boundFamily` 字段），不是 DB 列。只要服务端回执继续带「主守护人」形态的 boundUser，旧版 App/小程序不改一行代码也能正常工作。

## 3. 总体方案决策

| # | 决策 | 一句话理由 |
| --- | --- | --- |
| D1 | 新建 `bindings` 关联表承载多对多，`users.bound_user_id` 降级为「主守护人」派生缓存 | 多对多必须有一等公民表；保留旧列做缓存让未迁移读路径与旧数据不炸 |
| D2 | 绑定语义从「覆盖式」改「追加式」，带容量上限 | 覆盖式是多绑定事故之源；上限防滥用 |
| D3 | 推送（WS + 订阅消息）改为按 bindings 查全量守护人逐个 fanout | 多子女收不到告警 = 漏报，比什么都严重 |
| D4 | 客户端采用「当前守护对象」切换模型：`GuardConfig.boundElderId` 语义不变，仍表示**当前选中**的老人，新增 `boundElders` 列表 | 全部 Fragment 已按 boundElderId 拼 URL，切换对象=改一个值+刷新，改动面最小 |
| D5 | WS 推送改为服务端按 family userId 主动路由，payload 携带 elderId，客户端按「是否在我绑定列表内」过滤 | 服务端 fanout 本来就知道 family id；客户端不再依赖单一 targetElderId 注册 |
| D6 | 双后端同步改：`routes/*` 与 `workers/src/routes/*` 双写 + 契约测试双跑 | 项目铁律 |

## 4. 数据库设计

### 4.1 新表 `bindings`

```sql
CREATE TABLE IF NOT EXISTS bindings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,          -- role='elder' 的 users.id
  family_id INTEGER NOT NULL,         -- role='family' 的 users.id
  is_primary INTEGER NOT NULL DEFAULT 0,  -- 主守护人：告警回拨/旧字段回填的来源
  status TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / REMOVED（软删，审计用）
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(elder_id, family_id)
);
CREATE INDEX IF NOT EXISTS idx_bindings_elder  (elder_id, status);
CREATE INDEX IF NOT EXISTS idx_bindings_family (family_id, status);
```

- **D1 计费铁律**：新表必须建索引（上表已含），判活/推送查询全部走索引前缀，避免全表扫描。
- `status` 用软删：解绑后保留审计行，重绑恢复。查询一律带 `status='ACTIVE'`。
- `is_primary`：每个 elder 至多一条 `is_primary=1`。绑定时若该老人还没有主守护人则自动设为主；解绑主守护人时把最早 ACTIVE 的其余绑定提升为主。
- 容量上限（应用层校验，常量可配）：每个老人 ≤ **5** 个守护人，每个子女 ≤ **5** 位老人。

### 4.2 迁移与回填

- **Workers**：新增 `workers/migrations/000X_bindings.sql`（顺序号按现有迁移递增），`wrangler d1 migrations apply --remote`。
- **本地**：`database/db.js` 加 `CREATE TABLE IF NOT EXISTS` + 启动时一次性回填：扫描所有 `bound_user_id` 非空的双向对，`INSERT OR IGNORE` 进 bindings（ elder 侧为主，is_primary=1）。回填幂等（靠 UNIQUE），可重复执行。
- **`users.bound_user_id` 双写期语义**：始终等于该用户「主绑定」的对端 id（子女→主老人；老人→主守护人）。所有绑定写入点同步维护它。彻底切换读路径后（二期之后）再考虑废弃。
- 双向回指兜底逻辑（wx-link 事故修复的那条 `UPDATE ... WHERE bound_user_id = ? AND role='elder'`）在多绑定语义下改为 `INSERT OR IGNORE INTO bindings`，不再有「回指留在废弃占位号」的形态——占位账号废弃时 DELETE 其 bindings 行即可。

## 5. 后端 API 设计（双后端）

### 5.1 现有端点改造

| 端点 | 变化 |
| --- | --- |
| `POST /api/auth/bind` | 覆盖式 → 追加式。校验：目标必须是 elder；双方容量上限；不能重复绑同一对（幂等成功）。响应新增 `boundList`（子女视角=其全部老人；老人视角=其全部守护人），`boundUser` 保留=本次绑定的对象（向后兼容）。 |
| `POST /api/auth/unbind` | 语义变为「解除自己与 `:elderId`（body 传入，兼容缺省=唯一绑定）这一条」。删除 bindings 行（软删）；若解的是主守护人，提升下一位；同步回填双方 bound_user_id。**不再清除对方整条绑定**。 |
| `POST /api/auth/wx-link` | 微信占位账号废弃时：`DELETE bindings WHERE family_id=占位id` + 把其绑定行 `UPDATE family_id=App账号`（UNION 去重靠 UNIQUE 冲突则保留原行）。删除「覆盖式迁移」逻辑。 |
| `replyElderState`（本地+Workers 两份） | 新增 `boundFamilies: [{id,name,phone,isPrimary}]`；`boundFamily` 保留=主守护人。 |
| `/login` `/wx-login` 回执 | 新增 `boundUsers: [...]` 数组；`boundUser` 保留=主老人。 |
| `requireBoundElder` / `canAccessElder` | 改查 `bindings`：`SELECT 1 FROM bindings WHERE family_id=? AND elder_id=? AND status='ACTIVE'`（本人访问自己仍放行）。 |

### 5.2 新增端点

```
GET /api/auth/my-elders          # 子女视角：我绑定的全部老人（含每人活跃摘要：最后活动时间/未读告警数）
GET /api/auth/elder/:elderId/families   # 老人/子女视角：该老人的全部守护人（需绑定关系鉴权）
POST /api/auth/bindings/primary  # 设置主守护人（仅主守护人或老人端可操作，二期可选）
```

- `my-elders` 的活跃摘要复用现有「活动性取多表 MAX」的判活标量子查询（LIMIT 1 倒序索引），一条 SQL 出全列表，避免 N+1。

### 5.3 guard_settings

维持 elder 维度共享（`users.guard_settings`），任一 ACTIVE 守护人均可读改，增量合并机制不变。二期若要「仅主守护人可改高危项」，在 `pickFamilyWritable` 处按 is_primary 分叉即可，本次不做。

## 6. 推送与后台任务改造

### 6.1 WS 实时推送（GuardHubDO / 本地 hub）

- `notifyBoundFamily(elderId, payload)`：
  `SELECT family_id FROM bindings WHERE elder_id=? AND status='ACTIVE'` → 逐个 `sessions.get(String(family_id))` 发送。
- `/notify` 内部端点同理，从单目标改循环，`delivered` 改为 `deliveredCount`。
- payload 统一携带 `elderId` + `elderName`（多老人场景子女端必须知道是谁的事）。
- `deliverInterrupt`（远程打断）：发给**主守护人**；主守护人不在线则顺延下一位。多子女同时打断会互相打架，收敛到主守护人。

### 6.2 微信订阅消息

- `maybeSubscribeAlert` 按 fanout 列表逐个发送；**节流窗口从「按老人」改为「按 (老人, 家庭) 二元组」**，避免多子女场景下任何一家的模板消息额度被放大消耗。
- 失联告警（presenceSweep / elderPresenceScanner）：`WHERE role='elder' AND EXISTS(SELECT 1 FROM bindings b WHERE b.elder_id=users.id AND b.status='ACTIVE')`；告警发全量守护人，按各自节流。

### 6.3 其他

- `lookupFamilyPhone`（老人警报页一键回拨）：返回**主守护人**真实号码（mobile||phone，仍排除 wx_ 占位号），接口返回 `{name, phone, families: [...]}` 备用。
- 证据导出单据 family 信息：主守护人署名，附全部守护人列表。

## 7. 客户端设计

### 7.1 Android 子女端

**数据层**（改动核心，一处收敛）：

- `GuardConfig` 新增：
  ```kotlin
  var boundElders: List<BoundElder>   // 全量绑定列表，来自 boundUsers/boundList
  var currentElderIndex: Int          // 当前选中的老人在列表中的下标
  ```
- `boundElderId/Name/Phone` 改为**派生属性**：`get() = boundElders.getOrNull(currentElderIndex) ?: 默认值`。setter 保留兼容（赋值时定位列表中对应项）。这样 **全部现有 Fragment 的 URL 拼接零改动**。
- 登录/绑定/`/auth/user` 回执解析处统一走新解析器：优先读 `boundUsers` 数组，缺失（旧服务端）时退回单 `boundUser`。

**UI**：

- Dashboard 头部加「守护对象切换条」：单绑定时不显示（保持一屏一件事）；多绑定时显示横向 chip 列表，点击切换 → 更新 `currentElderIndex` + 广播刷新（复用 onResume 刷新机制）。
- 「我」页新增「守护对象管理」：列表展示全部绑定老人（姓名/手机号/最后活跃），支持解绑某一位、绑定新老人（现有 GuideFragment 绑定流程复用，bind 成功后回列表）。
- WS（FamilyWebSocketManager）：REGISTER 不再依赖单一 `targetElderId` 过滤，收到 payload 按 `elderId ∈ boundElders` 判断处理；切换当前老人不重连 WS（这是 D5 的直接收益）。

**老人端**：登记回执的守护人卡片支持列表展示（主守护人置顶标「主要联系人」）；紧急回拨仍拨主守护人。

### 7.2 小程序

- `app.js globalData.boundUser` 保留（=主老人），新增 `boundUsers` 数组 + `currentElderId`；dashboard/geofence 等页从「直接读 boundUser.id」改为读 `currentElderId`（`getCurElder()` 帮助函数统一收口）。
- 切换交互与 Android 同构：多绑定时 dashboard 顶部出切换条。
- 模板消息订阅授权：多老人共用一次授权（订阅消息按模板计，不按老人计），无需改。

### 7.3 兼容矩阵

| 客户端版本 | 服务端新版 | 结果 |
| --- | --- | --- |
| 旧 App/旧小程序 | 新 | `boundUser` 仍=主守护人，一切照旧；多人数据存在但看不到第二个人（已知限制，随发版消除） |
| 新 | 旧服务端 | 新字段缺失走单对象回退，无崩溃路径 |

## 8. 边界场景清单

| 场景 | 处理 |
| --- | --- |
| 子女绑第二个老人 | 追加行；bound_user_id 回填（若此前未绑则设为主） |
| 老人被第二个子女绑 | 追加行；老人侧 bound_user_id 不动（已是主守护人） |
| 解绑唯一绑定 | 与现状一致：两端清空 |
| 解绑主守护人（老人还有其他子女） | 提升最早 ACTIVE 者为主；回填老人 bound_user_id |
| 子女已绑 A 又绑 B 再解绑 A | 子女 bound_user_id 回填为 B |
| wx-link（微信号关联 App 账号） | 绑定集合合并（同 elder 去重）；占位账号 bindings 全删；**不再需要无条件回指兜底 UPDATE**（表结构使然），但迁移代码须写测试钉死 |
| 老人换号找回（elder-register previousPhone） | bindings 按 elder_id 挂账号，天然不受影响 |
| 老人端绑定码刷新 | 不影响绑定关系（码只在绑定时用） |
| 子女换手机号 | 不影响（绑定挂 userId） |
| 容量上限 | 409 + 明确文案「该老人守护人已满 5 位」 |

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P1 服务端 | bindings 表 + 迁移回填、bind/unbind/wx-link 改造、鉴权切换、推送 fanout、my-elders；本地 + Workers 双写 | 契约测试双后端各跑（现有基线 59/61 / 61/61 不回退，新增多绑定用例全绿）；wrangler d1 migrations apply --remote 后对账生产回填行数 = 双向对数 |
| P2 Android 子女端 | 守护对象列表 + 切换条 + 管理页 + WS 过滤改造 | 真机/模拟器双老人切换全功能回归（告警/轨迹/围栏/证据按当前对象展示）；快速编译检查用 gradle-8.7 compileReleaseKotlin |
| P3 小程序 + 老人端 | 小程序切换条、老人端守护人列表展示、订阅消息多守护人实测 | 小程序预览 + 真机收订阅消息（两个微信号各绑一老人验证 fanout） |

发版注意事项（项目惯例）：versionCode 先对账 dist/releases.json 占号；构建走 build-apk.bat --nopause；发布后 /latest 有 KV 5min 缓存，秒生效需删 `release:latest` 键；P1 只动 workers 时才需 wrangler deploy。

## 10. 测试要点

1. **契约测试**（tests/，双后端）：追加式 bind、容量上限 409、解绑主守护人后主守护人轮换、wx-link 两端绑同一老人的去重、boundUser 兼容字段恒存在。
2. **推送 E2E**：两个子女 WS 同时在线，老人上报 HIGH 事件 → 双端都收到且 payload 带 elderId；一方离线 → 其订阅消息到达。
3. **鉴权**：子女 A 不可访问未绑定老人 B 的数据（bindings 查不到 = 403）；解绑后立即 403。
4. **失联扫描**：老人有两个守护人，失联时两家都收告警，互不重复节流。
5. **回填幂等**：migrations 重复执行不产生重复行（UNIQUE 约束 + OR IGNORE）。

## 11. 风险与开放问题

- **隐私告知**：多子女 = 多人可见位置/录音/支付存证。建议 P2 起在绑定成功回执与老人端守护人卡片上明确提示「已有 N 位守护人」，本期不做老人端确认流（老人端无账号体系，绑定码本身即授权凭据）。
- **订阅消息额度**：多守护人 fanout 会线性放大模板消息用量，上线后观察月消耗，必要时对非主守护人降级为仅 WS 推送。
- **open question（待用户拍板）**：
  1. 非主守护人是否允许远程打断老人端？（当前设计：只有主守护人可以）
  2. 主守护人能否移除其他守护人（踢人）？当前设计：本期只有「自己解绑自己」，踢人放二期。
  3. 容量上限 5/5 是否合适？
