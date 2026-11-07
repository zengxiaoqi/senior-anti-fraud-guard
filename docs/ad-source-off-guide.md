# 广告「源头关闭」指引（家属端）方案

> 2026-11-07 定稿并实施。借鉴 [android-ad-cleaner（肉呆去广告）](https://github.com/rddmwmddr/android-ad-cleaner)（MIT 许可）的源头治理思路；关联代码：`routes/adguide.js` / `workers/src/routes/adguide.ts` / 小程序 `pages/adguide/`。

## 背景与目标

现有开屏广告拦截（`AdSkipAccessibilityService`，见 `ad-skip-touch-shield.md`）是**运行时兜底**：广告出现后抢在误触前点掉。天花板天然很低——

- 识别→点击之间有 100~500ms 误触窗口；
- 「摇一摇跳转」无解（第三方拿不到传感器事件）；
- 通知渠道广告、信息流、负一屏、商店推广位一概管不了；
- 文案匹配宁漏勿错，漏点率不低。

android-ad-cleaner 证明了另一条路：**广告在系统/App 自带的开关里本来就能关掉**。关一次、永久生效、老人零操作，误触/摇一摇/通知广告一起消失。对老人场景，源头治理价值远高于运行时竞态。

**本期目标**：把该项目 40+ 机型实测沉淀的「品牌 → 入口路径 → 关哪些开关」知识提炼成结构化数据，在家属端（小程序）做一个指引页，子女照着在老人手机上点几分钟完成源头关闭。

**本期不做**（后续可选）：

- `pm suspend` 挂起广告 SDK 包——需要 shell 权限（Android 11+ 无线调试配对或桌面端 USB），流程复杂且老人机（如华为 ALP-AL00，Android 10）不支持无线调试；
- 自动化拨开关引擎移植（Python → Kotlin 重写，成本高收益低）。

## 品牌自动匹配（2026-11-07 二期实现）

老人端 APK 随 `LOCATION_UPDATE` 心跳上报设备信息，家属端打开指引页自动选中品牌，省掉"看品牌"一步：

- **上报**：`ApiClient.reportRiskEvent` 增加可选顶层字段 `device_brand` / `device_model`（截断 64 字符），仅 `LocationGuardService.reportNormalLocation` 传入 `Build.BRAND` / `Build.MODEL`。品牌型号终身不变，服务端比对后**只在值变化时 UPDATE users**，心跳捎带零成本；不带这两个字段的旧版上报行为不变。
- **存储**：`users` 表新增 `device_brand` / `device_model` TEXT 列（本地 `database/db.js` ALTER；Workers 走 `migrations/0005_device_info.sql`，**部署时需 `wrangler d1 migrations apply safg-db --remote`**）。
- **读回**：`/api/auth/user/:id` 本来就 `SELECT u.*`，加列后自动带出，无需改接口。
- **匹配**：小程序 `adguide.js` 的 `autoMatchBrand()`：`getCurElder().id` → 拉 `/user/:id` → brand+model 小写拼接后按数据里各品牌的 `match` 关键词包含匹配（honor 在 huawei 之前，旧华为机报 `HUAWEI` 正确落到华为；iQOO 落 vivo）。匹配中自动选中品牌并显示绿色提示行；匹配不中（APK 未升级 / 尚无心跳 / 小众品牌）静默保持手动选择。
- **生效条件**：老人端 APK 升级（要发版）+ 完成一次定位心跳上报后，家属端即自动选中。之前未升级时页面行为与一期完全一致。

## 内容来源与版权

- 数据蒸馏自 android-ad-cleaner `docs/清单/`（小米-HyperOS / OPPO-ColorOS / vivo-OriginOS / 荣耀-MagicOS / 华为-HarmonyOS / 三星-OneUI / 魅族-Flyme，共 7 品牌）。
- 上游 MIT 许可，允许复制修改；页面底部保留来源署名与上游链接。
- 内容做**减法蒸馏**：只保留家属在老人手机上手动可操作的路径与开关名，去掉 adb 命令、脚本差异长文。原则：一屏一节、路径写全、每节 ≤ 8 个开关条目。

## 数据模型

```jsonc
{
  "version": "2026-11-07",
  "source": { "name": "android-ad-cleaner", "url": "https://github.com/rddmwmddr/android-ad-cleaner", "license": "MIT" },
  "brands": [
    {
      "id": "xiaomi",                    // 稳定 ID，路由与前端匹配用
      "name": "小米 / Redmi / POCO",      // 家属看得到的名字
      "os": "MIUI / HyperOS",            // 系统名（入口提示用）
      "match": ["xiaomi", "redmi", "poco"], // 后续端上报品牌时的小写匹配词
      "sections": [
        {
          "title": "系统广告总闸",
          "path": "设置 → 隐私与安全 → 更多安全设置 → 广告服务",
          "note": "找不到时在设置首页搜索「广告服务」",
          "off":  ["个性化广告推荐", "系统工具广告"],  // 要关闭的开关
          "keep": ["天气预警", "地震预警"]            // 明确保留（误关会丢功能）
        }
      ]
    }
  ],
  "universal": [ /* 不分品牌的通用建议（营销通知渠道、快应用等） */ ]
}
```

- `off` / `keep` 语义沿用上游清单符号：🔴 关掉 → `off`；🟢 保留 → `keep`；⚠️ 注意 → `note`。
- 数据文件为纯 JSON，**双份存放**：`routes/adguide-data.json`（本地 Node 后端）与 `workers/src/routes/adguide-data.json`（Workers，esbuild 原生支持 JSON import）。内容必须逐字节一致，由契约测试兜底比对。

## 服务端契约

`GET /api/adguide/brands` —— 公开只读，不挂鉴权（无数据归属、无隐私，家属未登录也能看）。

```jsonc
{
  "success": true,
  "data": {
    "version": "…",
    "source": { "name": "…", "url": "…", "license": "MIT" },
    "brands": [ { "id": "…", "name": "…", "os": "…" /* 列表页只要这三字段 */ } ],
    "universal": [ … ]
  }
}
```

`GET /api/adguide/brands/:id` —— 单品牌详情（含 sections 全量）；404 = 未知品牌。

双后端实现：

- 本地 Node：`routes/adguide.js`（Express），`server.js` 挂 `/api/adguide`。
- Workers：`workers/src/routes/adguide.ts`（Hono），`workers/src/index.ts` 挂 `/api/adguide`。
- 契约测试：`tests/contract/adguide.contract.test.js`，双后端各跑（本地必跑；Workers 侧按基线流程复跑）。

## 小程序设计

- 新页 `pages/adguide/`（非 tabBar 页），入口：防护指南页顶部卡片「关闭老人手机里的广告」。
- **单页两态**：
  1. 品牌选择态：顶部通用建议 + 品牌 chips（家属在老人手机「设置 → 关于手机」看品牌后点选）；
  2. 步骤详情态：分节卡片，每节显示路径（`path`，可长按复制）、`off` 清单（红色调，逐条带勾选框让家属勾进度）、`keep` 清单（灰色调，"别误关"）、`note`（黄色提示）。
- 勾选进度仅存页面内存（本期不落库）；顶部显示「已完成 x / 共 y 项」。
- UI 走现有暗色主题 token（`#0F172A` 底、卡片 `#1E293B` 系），文案样式复用 `guide.wxss` 的卡片/列表风格。
- 失败兜底：接口拉不到时展示内嵌精简文案 + 引导去上游仓库看清单（不白屏）。

## 验收标准

1. 契约测试双后端通过（本地全量绿；Workers 侧复跑绿）。
2. 数据双份逐字节一致（测试断言）。
3. 7 个品牌每个至少 3 个 section；`off` 条目与上游清单同名。
4. 小程序 CLI preview 编译通过；指引页可在预览码真机打开。
5. 页面含来源署名（MIT 合规）。

## 已知边界

- 内容按上游 2026-11-05 (v0.3.2) 清单蒸馏，ROM 大版本更新可能使路径漂移——`note` 里对易变入口写明搜索直达兜底。
- 营销通知渠道关闭（每 App 逐渠道）操作量大，本期只给方法论 + 高频 App（商店/浏览器/系统工具），不做逐机型渠道清单。
- 家属勾选进度不持久化，退出即清。
