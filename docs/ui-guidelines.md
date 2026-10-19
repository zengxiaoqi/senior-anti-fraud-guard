# UI 设计规范（Agent 开发/设计必读）

本文件是本项目 UI 风格的**唯一约束真源**。任何新增页面、改造现有界面、写事件文案/颜色/布局的 Agent 都必须遵守；与本文冲突的旧代码在改造时应顺手收敛到本文口径，不做一次性全量翻新。

2026-10-10 全页面 UI 审查（`docs/ui-audit-all-pages.html`）沉淀，来源是实际踩过的坑，不是理论最佳实践。
2026-10-19 起新增第 8 章微信小程序端规范（首页主次重排 + 轨迹页对齐 + 坐标系纠偏沉淀），App 端章节（1-7）与小程序章节（8）并读。

## 0. 设计基调：两套受众，两套密度

- **老人端**：大字号、高对比、大触控目标、一步一个动作。老人看不清小字，也完成不了多步操作。
- **子女端**：信息密度可以高，但**每一屏的第一眼必须先回答"有没有要紧事"**（计数总览、Hero 状态卡、高危色条），明细永远排在结论后面。
- 防诈告警链路的总原则：**误报比漏报更危险**——误报几次子女就集体静音，真告警也一起被忽略。所以危险类 UI 必须有：老人反悔窗口（确认页倒计时）+ 告警节流 + 分级呈现，而不是把所有事件都渲染成红色恐慌。

## 1. 架构铁律（违反 = 静默失效，最难查）

1. **新 Activity 必须继承 `BaseActivity`**（`EmergencyAlertActivity` 例外）。否则全局字体三档缩放（`UiPrefs.fontScale`）在该页静默失效——页面能跑，但老人端设置里"大字体"对它无效。
2. **`setTheme` 必须在 `super.onCreate` 之前**，且与 `AppCompatDelegate.setDefaultNightMode` 同步（见 `UiPrefs.applyNightMode`）。只做其一，弹窗/对话框配色会与页面割裂。
3. **切主题必须重启进程**：`?attr` shape drawable 有 Resources 级缓存，`recreate()` 清不掉，首页卡片会残留旧色。重启前 SharedPreferences 必须 `commit()`（`apply()` 异步落盘，`exit(0)` 会丢写入）。
4. **程序化构建的界面数据变化后必须重建**（`xxxBuilt` + `refreshXxxIfBuilt()` + `onResume` 触发）。直接改控件引用会与重建逻辑互相覆盖。
5. **新页面取色/取字一律走语义层**：布局用 `?attr/xxx`，代码用 `UiPrefs.color/textColor/dimColor/cardColor/bgColor`。不允许绕过。

## 2. 颜色规范

颜色分两类，判定标准是"它表达什么"：

| 类别 | 用法 | 来源 |
| --- | --- | --- |
| **角色色**（文字/卡片/底色/输入框/分割线，以及"危险容器""警告容器"这类底色） | 必须随深浅主题变化 | `attrs.xml` 语义属性：`appBg / appCard / appCardAlt / appText / appTextDim / appDivider / appInputBg / appInputText`，容器类 `appDangerBgSoft/TextStrong/TextDim`、`appWarnBgSoft/TextStrong/TextDim`（共 14 个） |
| **状态色**（高危红/警示黄/正常绿、品牌蓝） | 刻意硬编码，深浅背景下都要保持可辨识 | 红 `#EF4444`、黄 `#F59E0B`、绿 `#10B981`；严重度颜色统一调 `EventLabels.severityColor()`，不要自己写映射 |

- **禁止**在布局/drawable/代码里硬编码背景色、文字色（历史遗留的 200 多处硬编码是深色模式一度失效的根因）。新增硬编码 = 给未来的自己埋雷。
- 判断口诀：**换个主题这颜色还该长这样吗？** 该 → 角色色走 `?attr`；不该（告警红就是红）→ 状态色硬编码。
- 容器底色（如"正在录音存证"的浅粉卡）是角色色不是状态色：写死 `#FEF2F2` 在深色主题下是一张刺眼的亮卡（实际踩过）。

## 3. 事件文案：唯一事实源 `util/EventLabels.kt`

- 事件类型名（`eventName`）、严重度名（`severityName`）、严重度颜色（`severityColor`）、同类判定（`sameKind`）、详情人话化（`formatDetails`）、相对时间（`shortLocalTime`）、日期分组头（`dayHeader`）、"今日零点"（`todayStartMillis`）全部收敛在 EventLabels。
- **禁止**在 DashboardFragment / AlertsFragment / RiskLogActivity 各自维护映射——历史上已漂移出"📍 敏感地点停留过久" vs "长时间停留"这种不一致。
- 内部枚举名（`APP_CRASH`、`LOCATION_UPDATE`）**绝不能原样展示给用户**，必须过 `eventName` 映射。
- 事件详情**人话化**：坐标（latitude/longitude、"GPS 位置 (x, y)"）不出现在列表行，位置展示用服务端补全的 `location_name`；键值对不要原样平铺，一律走 `formatDetails`。原始 JSON 只进详情弹窗供查证。
- `formatDetails(d, forElder)`：同一份 details 两种读法。**老人端自看传 `forElder=true`**（"您当时没有回应"），**守护人视角不传**（"老人未响应确认"）。只维护一份映射，避免又漂移出两套文案。
- 列表行不出现的三类值（已在 `formatDetails` 里统一拦掉）：坐标串、包名（`com.eg.android.AlipayGphone`）、超过 60 字符的长文本（通知原文 `raw_text`、崩溃堆栈 `stack`）。

## 4. 告警与列表的呈现规则

- **文案与主按钮按事件类型分化**：失联、跌倒场景"远程打断"必然失败，无效按钮会让子女从"立刻打电话"退化成"点一下试试"。走 `AlertPresentation.of()`，失联/跌倒给"拨打电话/查看轨迹"，诈骗类才给"远程打断"。
- **列表分组 + 聚合**：告警类列表按「今天 / 昨天 / M月d日」分组（`dayHeader`）；连续同类型同级别事件合并成一张卡显示「×N」（判据 `EventLabels.sameKind`），点开看每条时间点——不让高频心跳事件把真告警冲成流水账，也不丢明细。
- **计数总览用"今日"口径**而非全量累计（全量只单调增长，很快失去"要不要现在看"的信号）；无告警时计数降灰（`UiPrefs.dimColor`），有告警才亮状态色。
- **高危视觉锚点**：卡片左侧色条与严重度文字同色（`EventLabels.severityColor` + tint），扫一眼颜色即可定位高危。
- **时效性**：告警类页面 `onResume` 必须 `force` 刷新（告警的价值全在时效，节流旧数据等于骗人）；普通页面可保留 30s 节流。
- **上面这几条对老人端「我的风险记录」同样适用**（2026-10-11 对齐）：老人端数据源是本机的 `RiskEventDbHelper`（epoch 毫秒），分组/计数走 `EventLabels.dayHeader(millis)` / `todayStartMillis()`，合并判据走 `sameKind(type, sev, type, sev)` 重载——与子女端 `sameKind(JSONObject, JSONObject)` 是同一判据，两端不许各算一套。
- **老人端详情弹窗要给"该怎么办"**（`EventLabels.elderAdvice`）：只告诉老人"发生过高危事件"没有用，他要的是下一步做什么。日常类记录（位置更新、心跳）不给建议，硬凑一条等于把真建议稀释掉。

## 5. 布局与组件

- **触控目标 ≥ 48dp**（chip 类统一 44dp 高度以上）；老人端整页字号随全局三档缩放，不要写死 textSize 的"绝对排版"。
- **横向 LinearLayout 绝不塞"按钮 + 整句话"**——小屏会挤出界/换行错位，按钮独占一行或放卡片尾部固定列。
- 复用既有组件规格（色条卡片、chip、分组头 `item_event_header`），**不要为新页面另造一套相似但不同的样式**；确实要新样式，先想清楚它是否该回灌成公共组件。
- 相对时间统一 `EventLabels.shortLocalTime`（今天 HH:mm / 昨天 HH:mm / MM-dd HH:mm / yyyy-MM-dd），不要在页面里再写 SimpleDateFormat。

## 6. 交互红线

- **高影响操作必须二次确认**（紧急打断、清除风险记录）。误触的心理成本远大于多点一次。
- **告警 Activity 的回调由长驻 Service 持有**（页面随时会被回收）；全屏告警页要吞掉返回手势（无意识手势会吞掉告警）。
- **空状态要说话**：列表为空时给出下一步指引（"尚未绑定老人，请先到控制台完成绑定"），不留白屏；加载失败显示真实原因，不吞错误。
- **危险检测 UI 必须有反悔窗口**：老人端疑似跌倒先弹 60s 确认页（"我没事"取消上报），不是直接打扰子女。

## 7. 新页面 Checklist

- [ ] 继承 `BaseActivity`；`setTheme` 在 `super.onCreate` 前
- [ ] 所有颜色走 `?attr` 语义属性 / `UiPrefs`；状态色用 `EventLabels.severityColor`
- [ ] 事件类文案全部来自 `EventLabels`，无自建映射、无裸枚举名
- [ ] 列表有：分组头、空状态文案、onResume 强刷（若时效敏感）
- [ ] 触控目标 ≥ 48dp；横向布局没有"按钮+长句"
- [ ] 高影响操作有二次确认；老人端文案是大字号可读的白话
- [ ] 服务端已有真名字段（`location_name` 等）时，界面展示真名而非坐标

## 8. 微信小程序端（`wechat-miniprogram/pages/*`）

2026-10-19 首页主次重排 + 轨迹页对齐 App 端改造沉淀。与 App 端共用设计基调（第 0 章）与告警呈现规则（第 4 章），以下是端差异与本次踩坑。

### 8.1 样式架构与颜色

- **样式三层**：`app.wxss` 全局兜底（`.card / .card-title / .card-desc / .btn-primary / .btn-secondary / .btn-danger`）→ 页面 wxss 同名覆盖 → 页面新增类。页面 wxss 是**页面私有**的，删"死样式"前先确认 wxml 没引用。
- **没有 `?attr` 主题系统**：小程序当前只有深色一套（page `#0F172A`），角色色直接用既定 hex——卡片 `rgba(30,41,59,0.7)`、正文 `#F8FAFC`、次级文字 `#94A3B8`、弱文字 `#64748B`、分割线 `rgba(255,255,255,0.08)`。
- **状态色与 App 端同值**（可硬编码）：红 `#EF4444`、黄 `#F59E0B`、绿 `#10B981`、品牌蓝 `#3B82F6`（文字态 `#60A5FA`）。判定口诀同第 2 章：随主题该变的（将来做浅色主题）别写死。
- 按压态一律 `hover-class`（配合 `hover-stay-time`），不要指望 CSS `:active`。

### 8.2 文案与插值（架构级坑）

- **文案真源是 `utils/eventLabels.js`**（与 Android `EventLabels` 同一套规则）：`eventName / shortLocalTime / dayHeader / todayStartMillis / sameKind / formatDetails / parseTime`。裸枚举名（`LOCATION_UPDATE`）禁止上屏，时间统一 `parseTime`（D1 的 UTC 裸串要补 Z，js 版已处理）。
- ⭐ **WXML 插值里不做字符串拼接**（含中文相连的模板串）：三元+拼接在 WXML 里"紧跟中文会被吞"且不报错。所有组合文案在 JS 拼好 `setData`（本次 `notifyDesc`、`summaryLine1/2/3`、`emptyText` 都是这么做的），WXML 只绑单值变量。
- **摘要/说明文案要压缩**：长说明压成短行；超长地名/地址截断（18 字 + `…`）；多条摘要合并成 `A · B · C` 顿分行。数据更新出口统一（如 `updateSummary()`），三份异步数据谁先到都重算，避免口径分裂。

### 8.3 页面结构：主次分层（2026-10-19 首页重排原则）

- **一屏一件事**：绑定态第一视觉焦点 = hero 主卡（守护对象 + 概览 chips 合并）；未绑定态主任务 = 绑定卡。
- **低频设置收纳成单行 entry**（值 + 行内描边小胶囊按钮），不再各占一张大卡配大按钮（"我的手机号"模式）。
- **低频危险操作降级**：解除绑定 = hero 角落灰色描边小胶囊，不是大红实心按钮（防误触 + 不抢焦点）。
- **按钮按状态分级**：告警通知未开启 = `btn-primary`（必须点），已开启 = `btn-secondary`（次要续订入口）。
- **统计卡可点即筛选**：点击统计卡过滤列表、再点同卡取消；⭐ **计数永远按全量算，只过滤列表**——筛选后卡片数字跟着变，"高危 2"的注意力锚点就没了。两端（App AlertsFragment）口径一致：今日高危 / 今日需留意 / 近 7 天。
- **筛选空态要给退路**："「今日高危」筛选下暂无事件，再点一次上方计数卡取消筛选"，不能让用户以为数据丢了。

### 8.4 导航与地图

- **tabBar 页跳转必须 `wx.switchTab`**（`navigateTo` 跳 tabBar 直接报错）；普通页才用 `navigateTo`。
- ⭐ **坐标系铁律**：服务端落库是 **WGS-84**（老人端 GPS），微信 `map` 组件 / `wx.chooseLocation` 是 **GCJ-02**。显示前必须 `wgs2gcj`（markers/polyline/circles 逐点），`chooseLocation` 选点入库前必须 `gcj2wgs`——不转则偏 50~500 米（围栏"老人真进了圈不触发"）。转换统一走 `utils/geo.js`（与 Android `GeoConverter` 同算法，禁自写第二份）。从轨迹行直接设围栏用服务端 WGS 原值入库，无需转换。
- **地图页交互对齐 App 端 TrackFragment**：今天/近 7 天分段（**默认"近 7 天"**——早间"今天"必为空，会被当成功能坏了）；列表行点击定位地图（`scale` 拉近到 16）；行内设为/取消围栏（未设 = 蓝描边，已设 = 琥珀色"已设围栏·取消"，取消必须二次确认）。
- **地图上的悬浮控件 = 纯图标玻璃按钮**（白色半透明圆角方块 + CSS border 画图标），不用文字胶囊压地图（突兀），不用 Unicode 字形（⤢/⛶ 缺字变豆腐块——App 端同款教训）。图标状态切换可用 `transform: rotate` 过渡。
- 地图轨迹连线：两点间隔 > 20km 断开成多段（GPS 漂移/跨城不画假直线）；围栏圈生效 = 琥珀、停用 = 灰。

### 8.5 小程序新页面 Checklist

- [ ] 事件文案/时间/分组全走 `utils/eventLabels.js`；组合文案在 JS 拼好进 WXML（插值无拼接）
- [ ] 颜色：角色色用既定 hex 常量、状态色与 App 同值；按压态用 `hover-class`
- [ ] 主次分层：hero 卡 + 单行 entry 收纳低频设置 + 危险操作降级描边胶囊；按钮随状态换 primary/secondary
- [ ] 列表/统计卡可点：tabBar 页用 `switchTab`；筛选态空文案带退路指引；计数不随筛选变化
- [ ] 碰坐标必过 `utils/geo.js`（显示 wgs2gcj、chooseLocation 入库 gcj2wgs）；轨迹连线 20km 断开
- [ ] 高影响操作二次确认（删除围栏/解绑/打断）；地图悬浮控件用 CSS 图标不用文字/字形
