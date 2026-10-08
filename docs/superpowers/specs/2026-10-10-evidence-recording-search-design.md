# 维权证据页 · 录音检索化设计

日期：2026-10-10
状态：已确认
影响端：Android 子女端（`EvidenceFragment`）+ 服务端（`routes/recordings.js`）

## 背景与问题

「维权证据」页当前把老人全部录音（上限 200 条）一次性拉回，按会话聚合成卡片全量平铺。截图里 17 段录音已经撑出一屏多，真正要紧的结论（检出诈骗）被埋在中间，翻不到也找不到。

同时页面顺序是「录音存证 → 结构化证据包」，权重反了：证据包是出事时报给警方的那份，是本页主体；录音是原始素材，需要主动去翻。

目标：默认只展示最近 3 次会话，其余靠筛选与检索找到；证据包上移到首位。

## 不在本次范围

- 小程序 `pages/evidence` 不改。该页目前根本不渲染录音（只有证据包 JSON），本次新增的筛选参数对它无影响。
- `GET /api/recordings/pack` 的打包逻辑不改。
- 录音的播放、标记为证据、删除、人工复核交互不改。

## 一、页面结构

```
┌─────────────────────────────────┐
│ 📋 维权证据        [一键复制]      │   复制的是证据包，留在顶部
├─────────────────────────────────┤
│ ╭─────────────────────────────╮ │
│ │ 👤 守护对象信息              │ │ ┐
│ │ 💳 可疑扣款流水（2 笔）      │ │   │ 结构化证据包
│ │ ☎️ 可疑通话记录（5 条）      │ │   │ 上移到首位
│ │ 🗺️ 近期位置轨迹（20 条）     │ │   ┘
│ ╰─────────────────────────────╯ │
│ ─────────────────────────────── │
│ 🎙 环境录音存证  [打包下载]       │   录音区下沉到此
│ [🔍 搜索转写内容或地点…         ]   │
│ [近7天] [全部等级] [只看证据]     │
│ ╭─────────────────────────────╮ │
│ │ 会话卡片                    │ │   默认 3 个
│ │ 会话卡片                    │ │
│ │ 会话卡片                    │ │
│ ╰─────────────────────────────╯ │
│      [ 加载更多 ]                │
└─────────────────────────────────┘
```

`打包下载` 按钮跟着录音区走，顶部只留 `一键复制`。按钮与它作用的对象必须在同一视觉区块，否则子女会以为打包的是上面那堆流水。

布局改动：`fragment_family_evidence.xml` 中把 `ll_evidence_container` 移到 `ll_recording_container` 之前，并在录音区顶部插入标题行（含打包按钮）、搜索框、筛选按钮行、「加载更多」按钮。

## 二、服务端接口

改造 `GET /api/recordings/list/:elderId`。既有参数 `evidence`、`group` 语义不变，不破坏现有调用方。

### 查询参数

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `q` | 自由文本 | 搜 `transcript` / `place_name` / `reason` / `fraud_verdict`，`LIKE %q%` |
| `range` | `7d` / `30d` / `all`（默认 `all`） | 按 `recorded_at` 过滤 |
| `level` | `all`（默认）/ `fraud` / `suspect` / `safe` / `untranscribed` | 风险等级 |
| `evidence` | `1` | 沿用现有：只看 `keep_as_evidence = 1` |
| `limit` | 默认 `3`，上限 `20` | **会话**数量，不是录音条数 |
| `offset` | 默认 `0` | 会话偏移 |

### `level` 判定口径

判定必须在服务端定死，客户端不得各自解释：

| `level` | SQL 条件 |
| --- | --- |
| `fraud` | `fraud_status = 'FRAUD'` |
| `suspect` | `fraud_status IN ('SUSPECT','FAILED')` |
| `safe` | `fraud_status = 'SAFE'` |
| `untranscribed` | `transcript_status IN ('SKIPPED','FAILED') OR transcript IS NULL` |

前三档互斥。`PENDING` / `ANALYZING`（研判在途）归入 `all`，不进任何筛选项 —— 单列一个「待研判」对子女没有决策价值。

### 分页必须是 SQL 两步

现有实现是 `SELECT * ... LIMIT 200` 拉全量再在 JS 里 `groupBy(sessionId)`。这种结构无法分页：JS 分组发生在拿到全部行之后，切页仍要全量拉回，流量白费。隧道实测吞吐 86~102KB/s，本项目对流量敏感。

改为：

```sql
-- 1. 按会话聚合并切页
SELECT session_id,
       COUNT(*)         AS segment_count,
       SUM(duration_ms) AS total_duration_ms,
       MIN(recorded_at) AS started_at,
       MAX(id)          AS last_id
FROM recordings
WHERE elder_id = ? <筛选条件>
GROUP BY session_id
ORDER BY last_id DESC
LIMIT ? OFFSET ?

-- 2. 用这一页的 session_id 取明细，数量已被上一步限死
SELECT * FROM recordings
WHERE elder_id = ? AND session_id IN (...)
ORDER BY id DESC
```

排序用 `MAX(id) DESC` 而非 `started_at DESC`：分段是陆续上传的，`MIN(recorded_at)` 虽为开始时间，但用它排序在分批上传时顺序会错乱。`id` 单调递增且与上传顺序一致，行为稳定。

`q` 命中两个位置：`transcript`（内容里提到退款）与 `place_name`（在那个养生馆录的）。均用 `LIKE`，需转义 `%` 与 `_`。

### 响应结构

`group=1` 时保持现有字段，新增三个：

```json
{
  "success": true,
  "data": {
    "sessions": [],
    "totalRecordings": 17,
    "fraudCount": 2,
    "totalSessions": 9,
    "hasMore": true,
    "appliedFilters": { "q": "", "range": "all", "level": "all", "evidence": false }
  }
}
```

`totalSessions` 为筛选后的会话总数，用于判断「加载更多」是否显示。它与 `totalRecordings` 不成固定比例：按地点检索可能命中多个会话的零散分段，前者增而后者减，因此两者各自独立 `COUNT`，不用一个推另一个。

`totalRecordings` 与 `fraudCount` 同样各跑独立 `COUNT`（不带 `LIMIT`）。若沿用「在聚合结果里数」的做法，翻到第二页时标题上的「共 17 段，其中 2 段诈骗」会随翻页跳变。

## 三、筛选控件

沿用项目现有风格（`LinearLayout` + `TextView` + `bg_input`），不引入 Chip / Spinner。

```
[🔍 搜索转写内容或地点                    ]   EditText + bg_input
[近7天] [全部等级] [只看证据]              三个可切换 TextView
```

- 时间范围与风险等级为**循环切换**，非下拉。项目现有控件中从未出现 Spinner，引入会风格不一致；循环切换用两个 TextView 即可表达。点击顺序：近7天 → 近30天 → 全部 → 近7天。
- 选中态：主色底 + 白字；未选中：`bg_input` + 深灰字。
- 「只看证据」为独立开关，不参与循环。

## 四、交互时机

| 动作 | 行为 |
| --- | --- |
| 切换筛选条件 | `offset` 归零，立即请求 |
| 点击「加载更多」 | `offset += 3`，请求下一页，结果**追加**到现有列表 |
| 搜索框输入 | 防抖 500ms 后请求，`offset` 归零 |
| 搜索框清除 | 清空 `q`，重新请求 |
| WS 推来新录音 | 保持筛选条件，`offset` 归零重拉（回到第一页），并 toast「新录音已到，已回到最新列表」 |
| `onResume` | 保持筛选条件与已加载页数，不重置 |

WS 与 `onResume` 的取舍：子女若已翻到第 3 页，新录音会插到最前面，不归零就永远看不到；因此归零，并用 toast 说明是页面主动跳转而非异常。`onResume` 不重置，因为子女翻历史时切去地图看一眼再回来，位置被冲掉很烦。

## 五、边界情况

| 情况 | 处理 |
| --- | --- |
| 筛选后无结果 | 显示「没有符合条件的录音」+「清除筛选条件」按钮。文案须写明是筛选导致，否则子女会以为录音被系统删除 |
| 重复点击「加载更多」 | 按钮置灰 + 文案「加载中…」 |
| 筛选条件变化但旧请求未回 | 每次请求带自增 `reqSeq`，响应回来时若非最新则丢弃，防止快切筛选时旧响应覆盖新结果 |
| 服务端 500 | 保留已有列表不清空，toast「加载失败：<原因>」。清空会让子女以为录音没了 |
| 录音全部被清理（SAFE 到期） | 走「暂无录音存证」空态。**不能**走「没有符合当前筛选条件的录音」—— 此时用户没设任何筛选，那样提示等于谎报状态 |

## 六、顺带修复的现存缺陷

截图暴露两处可读性故障，本次一并修：

1. **「转写内容：`null`」**。`transcript` 为空时不显示该行，而非显示 `null`。现有代码用 `optString("transcript", "")`，服务端返回 JSON `null` 时会取到字符串 `"null"` —— 即 AGENT.MD 第 7 条铁律记录的 `optString` 陷阱。改用 `util/JsonUtils.kt` 的 `optStringOrEmpty()`。

2. **`reasonLabel` 混入坐标串**（「进入敏感地点『曾爷爷常去地点(28.273, 113.062)』」）。

   录音行的 `place_name` 是上传时由客户端带上来的，未经服务端地名推断。修复方式复用 `geofence.js` 的 `/list/:elderId` 已有做法：对 `looksLikeRealAddress()` 判为假的名称调 `geo.describePlace(lat, lng, db, elderId)` 取可读名，仅当 `source === 'geo'`（真实地图地名）时写回 `recordings.place_name`，「常去地点①」这类推断描述只用于本次响应、不覆盖原值。

   注意 `describePlace` 是异步的（要查地图 key 与本地锚点），列表响应不能因此阻塞 —— 与 `geofence.js` 一致，先用原值响应，`Promise.all` 补全后再 UPDATE 返回。坐标缺失（`lat`/`lng` 为 0 或空）的行跳过推断，保持原样。

这两处是本次改动的必要修复：卡片从「全量平铺」变为「默认 3 条 + 检索」后，用户会盯得比以前更仔细，露出的瑕疵更刺眼。

## 七、涉及文件

| 文件 | 改动 |
| --- | --- |
| `services/recordingQuery.js` | **新建**：筛选 WHERE 与分页归一化的纯函数 |
| `routes/recordings.js` | `GET /list/:elderId` 接入筛选与分页；`reasonLabel` 地名补全；引入 `services/regeo.js`（`describePlace` / `looksLikeRealAddress`，该文件目前未引用 regeo） |
| `android/.../res/layout/fragment_family_evidence.xml` | 调换容器顺序，插入标题行、搜索框、筛选按钮行、加载更多按钮 |
| `android/.../family/EvidenceFragment.kt` | 筛选状态管理、请求防抖与 `reqSeq`、分页追加渲染、空态与错误态 |
| `android/.../family/RecordingPlayerCard.kt` | 空 `transcript` 不渲染该行；`optString` 换 `JsonUtils.optStringOrEmpty()` |
| `tests/recordingQuery.test.js` | **新建** |
| `docs/api-reference.md` | 更新 `/list` 参数表与响应字段 |

## 八、测试

筛选与分页的 SQL 构造放在 `services/recordingQuery.js`（新建），导出两个纯函数：

- `buildListWhere(filters)` → `{ sql, params }`，`filters` 为 `{ q, range, level, evidence }`
- `normalizeListPaging(query)` → `{ limit, offset }`

不放在 `routes/recordings.js` 内的理由：`require('../routes/recordings')` 会连带 `database/db.js`，在测试进程里真的打开 `data.sqlite` 连接。`tests/` 现有 143 个用例全是纯函数级、无 DB 依赖（`zipWriter`、`fraudDetector`、`signToken` 等），新代码必须维持这一点，否则测试会污染生产库。

新增 `tests/recordingQuery.test.js`，直接对这两个纯函数断言：

- `range=7d` / `30d` 生成正确的 `recorded_at` 下界；`all` 不加该条件
- `level` 四档各自生成的 WHERE 片段；`PENDING`/`ANALYZING` 不落入任何具体档
- `q` 中的 `%` `_` 被转义，不变成通配符；空 `q` 不产生 `LIKE` 条件
- `limit` 超过 20 被夹到 20；负数与非数字落到默认 3
- `offset` 为负归零
- 未识别的 `level` / `range` 值回落到 `all`，不报错也不返回空结果

Android 侧无法在无设备环境跑 UI 逻辑，本次不新增 Kotlin 单测；`RecordingPlayerCard` 的空 `transcript` 修复依赖人工核对。