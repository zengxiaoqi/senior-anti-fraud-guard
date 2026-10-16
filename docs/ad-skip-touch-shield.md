# 开屏广告触摸防护罩（Touch Shield）设计

> 2026-10-16 定稿并实施。关联代码：`service/AdSkipAccessibilityService.kt`。

## 背景与目标

现有开屏广告拦截（`AdSkipAccessibilityService`）在识别到「跳过」按钮后用
`ACTION_CLICK` 点掉。广告从出现到被点掉之间有 ~100-500ms 的可见窗口，这个
窗口内老人误触屏幕（乱点/手抖）会命中广告体，直接跳转第三方落地页。

目标：**在确认是开屏广告的瞬间，用全屏触摸防护罩吸收一切误触**；跳过点击
完成后撤罩。只防触摸，不改变广告显示本身。

## 为什么不做"完全不显示"

彻底不让广告渲染只有网络层拦截一条路（本地 VpnService 按域名/SNI 拦截广告
SDK 请求）。否决理由：域名/SNI 列表维护成本高；穿山甲重度依赖 HTTPDNS 绕过
DNS 层；误杀导致整个 app 打不开；VpnService 独占设备唯一 VPN 通道。不做。

## 摇一摇的边界（明确无解，写进注释）

广告 SDK 读加速度传感器，第三方 app 无权限掐掉别的 app 的传感器事件，触摸
罩挡不住摇晃跳转。缓解 = 现有时序（广告出现 ~100-200ms 内点掉）+ 家属引导
用户开 MIUI/HyperOS 系统级「禁止摇一摇广告」开关。

## 方案

### 罩子本体

- **`TYPE_ACCESSIBILITY_OVERLAY`**：无障碍服务专用窗口类型，无需
  `SYSTEM_ALERT_WINDOW` 悬浮窗权限，服务上下文 `WindowManager.addView` 即可。
  z 序在普通 app 之上、系统 UI（通知横幅/来电界面/导航栏）之下。
- Layout：`MATCH_PARENT × MATCH_PARENT` + `FLAG_NOT_FOCUSABLE`（不抢焦点，
  返回键/导航手势不受影响）；**不加** `FLAG_NOT_TOUCHABLE`——罩子就是要吃触摸。
- 内容：半透明黑遮罩 + 居中圆角 pill「正在跳过广告…」。文案/颜色全部走资源
  （`strings.xml` / `colors.xml`），不硬编码。
- 触摸吸收：根 View `setOnTouchListener { _, _ -> true }`，单击/长按全消费。

### 触发条件（收得极窄，误罩率≈0）

在 `handleWindow()` 内，**窄匹配 + 尺寸闸 + 冷却 + 熔断全部通过、马上要点击**
时才罩。即"确证这是开屏广告"才罩。不进 handleWindow 的窗口（自家 app 除自检
页、systemui/settings、开关关闭、熔断期）一律不罩。

自检页 `AdSkipTestActivity` 沿用现有唯一例外：**只点不罩**，保持真机目测验证
链路干净。

### 撤罩时序（三重保险）

| 时机 | 动作 |
| --- | --- |
| 点击成功后 `postDelayed 600ms` | 正常路径，给广告 finish 留时间 |
| 任意 `TYPE_WINDOW_STATE_CHANGED` | 立即撤，广告页已切换 |
| 兜底超时 4 秒 | Handler runnable 带 token，重复触发先 removeCallbacks 再重挂 |

防御：`onServiceConnected` 先 removeViewOrNull 清残留（服务重绑场景）；
`addView` 自包 try-catch，**个别 ROM 限制 overlay 时静默降级为现状纯点击，
绝不能让罩子把服务搞崩**。

### 幂等与竞态

- 罩显示期间同一广告的内容事件再进 handleWindow：`showShield` 幂等，已显示
  只刷新超时 deadline，不重复 addView。
- 1.5s 点击冷却命中的重复事件直接 return（现状），不影响罩子的存活——罩子
  的存活由三重撤罩机制保证。
- `performAction(ACTION_CLICK)` 是节点级注入，不经过触摸事件分发，罩子不会
  挡住自己的跳过点击。

### 已知边界

- 分屏下罩住整个 display 而非半屏：罕见场景，接受。
- 罩最多存活 4 秒，遮挡来电/通知横幅的时间可忽略。

## 参数

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `SHIELD_TIMEOUT_MS` | 4000 | 罩子兜底存活上限 |
| `SHIELD_REMOVE_DELAY_MS` | 600 | 点击成功后的撤罩延迟 |

## 改动清单

- `service/AdSkipAccessibilityService.kt`：罩子管理、`handleWindow` 挂钩、
  静态 `instance` 引用与 `debugPreviewShield()`（自检页预览入口）
- `AdSkipTestActivity` + 布局：+「预览触摸防护罩（4 秒）」按钮（服务未连接
  时给出提示文案）
- `res/values/strings.xml`：+`ad_skip_shield_text`「正在跳过广告…」
- `res/values/colors.xml`：+`ad_shield_scrim`（黑色 55%）与 pill 底色/文字色
- 匹配逻辑（`AdSkipMatcher`）、单测、服务端：零改动

## 实测记录（2026-10-17，MuMu 模拟器 900x1600，v1.7.34 release 包）

### 真实第三方开屏广告端到端 ✅

MuMu 游戏中心（com.mumu.store）弹出真实开屏广告「跳 过」，服务全链路：

```
00:43:18.552  触摸防护罩已显示
00:43:18.565  已跳过开屏广告: com.mumu.store 「跳 过」   ← 13ms
00:43:18.746  触摸防护罩已撤除                            ← 194ms（广告页切走触发）
```

罩子随广告出现、广告被点掉立即撤除，正好覆盖误触危险窗口。

### 自检页预览 ✅

「预览触摸防护罩（4 秒）」按钮：显示 → **4.009s** 超时自动撤除，视觉为
全屏 55% 黑遮罩 + 居中白底 pill「正在跳过广告…」，状态栏不受影响
（TYPE_ACCESSIBILITY_OVERLAY z 序在系统 UI 之下，实测正确）。

### 实测抓出的 bug（已修）

⭐ **罩子会触发自己的撤罩事件**：`TYPE_ACCESSIBILITY_OVERLAY` 窗口一出现
就产生一条 `TYPE_WINDOW_STATE_CHANGED`（packageName=自家包），最初版本
"任意窗口切换即撤罩"的规则把罩子在自己显示后 114ms 就撤掉了。修复：自家
包名的窗口事件不作为撤罩信号——罩子只罩第三方窗口，自家窗口真到前台时
广告必然已结束，跳过撤除无副作用。

### 模拟器测试环境备忘

- MuMu 上 `uiautomator dump` 每次执行都会把无障碍服务解绑重绑（日志表现为
  连续多条"服务已连接"），测试期间别把 dump 与功能验证混在同一时序里。
- 自检页 tvResult 显隐会让下方按钮上下漂移 ~234px，adb `input tap` 前必须
  现取坐标，不能复用上一次 dump 的值。

## 验证

1. `AdSkipMatcherTest` 全绿（逻辑未动，回归确认）；
2. `build-apk.bat --nopause` 构建，release 包上模拟器真跑启动路径；
3. `AdSkipTestActivity`：罩出现 → 罩上乱点不透传 → 按钮被点掉 → ~600ms 撤罩；
4. 真实带开屏广告的 app 回归：半透明罩一闪而过，误触不再拉起落地页。
