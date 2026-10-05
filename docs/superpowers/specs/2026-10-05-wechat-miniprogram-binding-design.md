# 微信小程序亲情绑定功能设计文档

**日期**: 2026-10-05
**状态**: 已批准

## 1. 背景

当前微信小程序硬编码 `elderId: 1`、`familyId: 2`、`boundElderName: '张爷爷'`，假设绑定关系已存在。App 端（Android）已有完整的 6 位绑定码生成/显示能力，后端已有 `POST /api/auth/bind` 双向绑定接口。

本设计为小程序增加动态绑定能力：通过微信登录获取真实用户身份，子女输入老人端绑定码完成双向绑定。

## 2. 架构与数据流

```
小程序启动 → wx.login() 获取 code
    → 调 POST /api/auth/wx-login { code }
    → 后端调微信 code2Session API 获取 openid
    → 用 openid 查找或创建用户记录，生成 bind_code
    → 返回 { userId, bindCode, boundUser }
    → 存入 app.globalData
    → 若未绑定，弹出引导弹窗
```

绑定流程：
```
子女输入 6 位绑定码 → 调 POST /api/auth/bind { userId, bindCode }
    → 后端查找目标用户，执行双向绑定
    → 返回 { success, boundUser }
    → 更新 globalData 和 UI
```

## 3. 后端改动

### 3.1 新增接口：`POST /api/auth/wx-login`

**请求**:
```json
{ "code": "wx.login() 返回的临时 code" }
```

**响应**:
```json
{
  "success": true,
  "data": {
    "userId": 2,
    "bindCode": "999999",
    "boundUser": { "id": 1, "name": "张爷爷", "phone": "138****0001" }
  }
}
```

**逻辑**:
1. 从环境变量读取 `WECHAT_APPID` 和 `WECHAT_APPSECRET`
2. 调用微信 `https://api.weixin.qq.com/sns/jscode2session` 获取 `openid`
3. 用 openid 查找用户，不存在则创建新用户（生成 6 位 bind_code）
4. 查询 `bound_user_id` 获取绑定状态
5. 返回 userId、bindCode、boundUser

### 3.2 环境变量

| 变量名 | 说明 |
|--------|------|
| `WECHAT_APPID` | 微信小程序 AppID |
| `WECHAT_APPSECRET` | 微信小程序 AppSecret |

### 3.3 复用现有接口

`POST /api/auth/bind` — 无需改动，接收 `{ userId, bindCode }`，执行双向绑定。

## 4. 小程序改动

### 4.1 `app.js`

- `globalData` 移除硬编码的 `elderId`、`familyId`、`boundElderName`
- 新增 `userId`、`bindCode`、`boundUser`、`isBound`
- `onLaunch` 中调用 `wx.login()`，成功后调 `/api/auth/wx-login`
- 未绑定则 `wx.showModal` 引导用户输入绑定码
- WebSocket 注册使用动态 `userId`

### 4.2 `pages/dashboard/dashboard.wxml`

顶部新增绑定状态卡片：
- **未绑定**: 显示绑定码输入框 + 「绑定」按钮
- **已绑定**: 显示「已绑定被守护老人：[name]」+ 解绑按钮

### 4.3 `pages/dashboard/dashboard.js`

- `onShow` 时检查绑定状态，更新 UI
- `onBindTap`: 获取输入的绑定码，调 `/api/auth/bind`
- `onUnbindTap`: 清除本地绑定状态（`globalData.boundUser = null`），后续可增加后端解绑接口

### 4.4 `app.json`

无需改动（不新增页面）。

## 5. 错误处理

| 场景 | 处理方式 |
|------|----------|
| wx-login 失败 | Toast: "网络异常，请重试" |
| code2Session 失败 | Toast: "微信登录失败" |
| 绑定码无效 | Toast: "绑定码无效，请确认后重试" |
| 绑定码为空 | Toast: "请输入绑定码" |
| 网络请求失败 | Toast: "网络异常，请重试" |

## 6. 测试计划

### 6.1 后端测试

- 用 mock code 测试 `/api/auth/wx-login` 接口
- 验证 openid 不存在时自动创建用户
- 验证返回的 bindCode 格式正确（6 位数字）
- 验证绑定状态查询正确

### 6.2 小程序测试

- 在开发者工具中模拟未绑定状态，验证弹窗引导
- 输入正确绑定码，验证绑定成功
- 输入错误绑定码，验证错误提示
- 绑定成功后刷新页面，验证状态持久化

## 7. 文件清单

| 文件 | 操作 |
|------|------|
| `routes/auth.js` | 新增 `/api/auth/wx-login` 接口 |
| `wechat-miniprogram/app.js` | 重写 onLaunch，移除硬编码 |
| `wechat-miniprogram/pages/dashboard/dashboard.wxml` | 新增绑定状态卡片 |
| `wechat-miniprogram/pages/dashboard/dashboard.js` | 新增绑定/解绑逻辑 |
| `.env.example` | 新增微信配置示例 |
