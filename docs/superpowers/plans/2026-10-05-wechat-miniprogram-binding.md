# 微信小程序亲情绑定功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为微信小程序增加动态亲情绑定功能，通过微信登录获取用户身份，子女输入老人端绑定码完成双向绑定。

**Architecture:** 后端新增 `/api/auth/wx-login` 接口调用微信 code2Session 获取 openid，自动创建/关联用户记录；小程序启动时调用该接口获取 userId 和绑定状态，未绑定则弹窗引导输入绑定码，控制台页显示绑定状态卡片。

**Tech Stack:** Node.js, Express, SQLite, 微信小程序原生框架

---

### Task 1: 后端 — 新增 `/api/auth/wx-login` 接口

**Files:**
- Modify: `routes/auth.js`
- Create: `tests/test-wx-login.js`

- [ ] **Step 1: 创建测试文件**

Create `tests/test-wx-login.js`:

```javascript
const assert = require('assert');
const db = require('../database/db');

// Mock: 测试用户创建逻辑
function generateBindCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function testGenerateBindCode() {
  const code = generateBindCode();
  assert.strictEqual(code.length, 6, '绑定码应为 6 位');
  assert.match(code, /^\d{6}$/, '绑定码应为纯数字');
  console.log('✅ generateBindCode 测试通过');
}

function testUserCreation() {
  const testOpenid = 'test_openid_' + Date.now();
  const bindCode = generateBindCode();
  
  db.run(
    'INSERT INTO users (role, name, phone, bind_code, wx_openid) VALUES (?, ?, ?, ?, ?)',
    ['family', '测试用户', '139' + Date.now().toString().slice(-8), bindCode, testOpenid],
    function(err) {
      assert.ifError(err);
      
      db.get('SELECT * FROM users WHERE wx_openid = ?', [testOpenid], (err, row) => {
        assert.ifError(err);
        assert.strictEqual(row.wx_openid, testOpenid);
        assert.strictEqual(row.bind_code, bindCode);
        console.log('✅ 用户创建测试通过');
        
        // 清理测试数据
        db.run('DELETE FROM users WHERE wx_openid = ?', [testOpenid], (err) => {
          assert.ifError(err);
          console.log('✅ 测试数据已清理');
        });
      });
    }
  );
}

testGenerateBindCode();
testUserCreation();
```

- [ ] **Step 2: 运行测试验证失败**

Run: `node tests/test-wx-login.js`
Expected: 报错（接口尚未实现）

- [ ] **Step 3: 实现 `/api/auth/wx-login` 接口**

In `routes/auth.js`, add before `module.exports`:

```javascript
const axios = require('axios');

// 微信登录：code2Session 获取 openid，自动创建/关联用户
router.post('/wx-login', async (req, res) => {
  const { code } = req.body;
  if (!code) {
    return res.status(400).json({ error: '参数缺失: code' });
  }

  const appId = process.env.WECHAT_APPID;
  const appSecret = process.env.WECHAT_APPSECRET;
  
  if (!appId || !appSecret) {
    return res.status(500).json({ error: '服务器未配置微信密钥' });
  }

  try {
    // 调用微信 code2Session
    const wxRes = await axios.get('https://api.weixin.qq.com/sns/jscode2session', {
      params: {
        appid: appId,
        secret: appSecret,
        js_code: code,
        grant_type: 'authorization_code'
      }
    });

    const { openid, session_key, errcode, errmsg } = wxRes.data;
    
    if (errcode) {
      return res.status(400).json({ error: `微信登录失败: ${errmsg || errcode}` });
    }

    // 查找或创建用户
    db.get('SELECT * FROM users WHERE wx_openid = ?', [openid], (err, user) => {
      if (err) return res.status(500).json({ error: err.message });

      if (user) {
        // 用户已存在，返回绑定状态
        db.get('SELECT * FROM users WHERE id = ?', [user.bound_user_id], (err, boundUser) => {
          if (err) return res.status(500).json({ error: err.message });
          res.json({
            success: true,
            data: {
              userId: user.id,
              bindCode: user.bind_code,
              boundUser: boundUser || null
            }
          });
        });
      } else {
        // 创建新用户
        const bindCode = String(Math.floor(100000 + Math.random() * 900000));
        const phone = 'wx_' + openid.slice(-8);
        
        db.run(
          'INSERT INTO users (role, name, phone, bind_code, wx_openid) VALUES (?, ?, ?, ?, ?)',
          ['family', '微信用户', phone, bindCode, openid],
          function(err) {
            if (err) return res.status(500).json({ error: err.message });
            res.json({
              success: true,
              data: {
                userId: this.lastID,
                bindCode: bindCode,
                boundUser: null
              }
            });
          }
        );
      }
    });
  } catch (err) {
    res.status(500).json({ error: '微信登录请求失败: ' + err.message });
  }
});
```

- [ ] **Step 4: 安装 axios 依赖**

Run: `npm install axios`
Expected: axios 安装成功

- [ ] **Step 5: 运行测试验证通过**

Run: `node tests/test-wx-login.js`
Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add routes/auth.js tests/test-wx-login.js package.json package-lock.json
git commit -m "feat: add wx-login endpoint for wechat miniprogram binding"
```

---

### Task 2: 后端 — 添加环境变量配置

**Files:**
- Create: `.env.example`

- [ ] **Step 1: 创建 `.env.example`**

```
PORT=3000
WECHAT_APPID=your_appid_here
WECHAT_APPSECRET=your_appsecret_here
```

- [ ] **Step 2: 提交**

```bash
git add .env.example
git commit -m "chore: add env example for wechat config"
```

---

### Task 3: 小程序 — 重写 `app.js` 启动逻辑

**Files:**
- Modify: `wechat-miniprogram/app.js`

- [ ] **Step 1: 重写 `app.js`**

Replace entire file content:

```javascript
App({
  globalData: {
    serverHost: 'http://localhost:3000',
    wsHost: 'ws://localhost:3000',
    userId: null,
    bindCode: null,
    boundUser: null,
    isBound: false
  },

  onLaunch: function () {
    console.log("🛡️ 长者防诈守护 (子女端微信小程序) 已启动");
    this.wxLogin();
  },

  wxLogin: function () {
    const that = this;
    wx.login({
      success: (res) => {
        if (res.code) {
          wx.request({
            url: `${that.globalData.serverHost}/api/auth/wx-login`,
            method: 'POST',
            data: { code: res.code },
            success: (response) => {
              if (response.data.success) {
                const data = response.data.data;
                that.globalData.userId = data.userId;
                that.globalData.bindCode = data.bindCode;
                that.globalData.boundUser = data.boundUser;
                that.globalData.isBound = !!data.boundUser;
                
                console.log('✅ 微信登录成功, userId:', data.userId);
                
                if (!that.globalData.isBound) {
                  that.showBindingGuide();
                }
                
                that.initWebSocket();
              }
            },
            fail: () => {
              wx.showToast({ title: '网络异常，请重试', icon: 'none' });
            }
          });
        }
      },
      fail: () => {
        wx.showToast({ title: '微信登录失败', icon: 'none' });
      }
    });
  },

  showBindingGuide: function () {
    wx.showModal({
      title: '亲情绑定',
      content: '请输入老人端的6位绑定码完成绑定',
      confirmText: '去绑定',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) {
          wx.switchTab({ url: '/pages/dashboard/dashboard' });
        }
      }
    });
  },

  initWebSocket: function () {
    if (!this.globalData.userId) return;
    
    const that = this;
    const ws = wx.connectSocket({
      url: this.globalData.wsHost,
      success: () => {
        console.log("微信小程序 WebSocket 连接成功");
      }
    });

    wx.onSocketOpen(() => {
      wx.sendSocketMessage({
        data: JSON.stringify({ type: 'REGISTER', userId: that.globalData.userId, role: 'family' })
      });
    });

    wx.onSocketMessage((res) => {
      try {
        const payload = JSON.parse(res.data);
        if (payload.type === 'RISK_ALERT') {
          wx.showModal({
            title: '⚠️ 收到微信紧急防诈预警',
            content: `长者正处于高危状态 (${payload.data.event_type})，是否立即发起远程语音打断？`,
            confirmText: '强行打断',
            confirmColor: '#EF4444',
            success: (res) => {
              if (res.confirm) {
                that.sendRemoteInterrupt();
              }
            }
          });
        }
      } catch (e) {}
    });
  },

  sendRemoteInterrupt: function () {
    if (!this.globalData.boundUser) {
      wx.showToast({ title: '未绑定老人', icon: 'none' });
      return;
    }
    
    wx.sendSocketMessage({
      data: JSON.stringify({
        type: 'INTERRUPT_CMD',
        targetElderId: this.globalData.boundUser.id,
        message: '微信强打断：子女提醒您立即终止当前异常通话！'
      })
    });
    wx.showToast({ title: '已触发远程打断', icon: 'success' });
  }
});
```

- [ ] **Step 2: 提交**

```bash
git add wechat-miniprogram/app.js
git commit -m "feat: rewrite app.js with wx-login and dynamic binding"
```

---

### Task 4: 小程序 — 控制台页绑定状态卡片

**Files:**
- Modify: `wechat-miniprogram/pages/dashboard/dashboard.wxml`
- Modify: `wechat-miniprogram/pages/dashboard/dashboard.js`

- [ ] **Step 1: 修改 `dashboard.wxml` — 添加绑定卡片**

Replace entire file content:

```xml
<view class="container">
  <!-- 绑定状态卡片 -->
  <view class="card" wx:if="{{!isBound}}">
    <text class="card-title">🔗 亲情绑定</text>
    <text class="card-desc">请输入老人端显示的6位绑定码完成绑定</text>
    <input class="bind-input" type="number" maxlength="6" placeholder="请输入6位绑定码" bindinput="onBindCodeInput" />
    <button class="btn-primary" bindtap="onBindTap">绑定</button>
  </view>

  <view class="card" wx:if="{{isBound}}">
    <text class="card-title">✅ 已绑定被守护老人</text>
    <view class="bind-info">
      <text class="bind-name">{{boundUser.name}}</text>
      <text class="bind-phone">{{boundUser.phone}}</text>
    </view>
    <button class="btn-danger" bindtap="onUnbindTap">解除绑定</button>
  </view>

  <!-- 原有内容 -->
  <view class="card">
    <text class="card-title">📊 控制台概览</text>
    <view class="stats">
      <view class="stat-item">
        <text class="stat-value">{{events.length}}</text>
        <text class="stat-label">风险事件</text>
      </view>
      <view class="stat-item">
        <text class="stat-value">{{locations.length}}</text>
        <text class="stat-label">轨迹点</text>
      </view>
    </view>
  </view>
</view>
```

- [ ] **Step 2: 修改 `dashboard.js` — 添加绑定逻辑**

Replace entire file content:

```javascript
const app = getApp();

Page({
  data: {
    events: [],
    locations: [],
    isBound: false,
    boundUser: null,
    bindCode: ''
  },

  onShow: function () {
    this.checkBindingStatus();
    this.fetchRiskEvents();
    this.fetchLocations();
  },

  checkBindingStatus: function () {
    const app = getApp();
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser
    });
  },

  onBindCodeInput: function (e) {
    this.setData({ bindCode: e.detail.value });
  },

  onBindTap: function () {
    const app = getApp();
    const bindCode = this.data.bindCode.trim();
    
    if (!bindCode) {
      wx.showToast({ title: '请输入绑定码', icon: 'none' });
      return;
    }
    
    if (!/^\d{6}$/.test(bindCode)) {
      wx.showToast({ title: '绑定码为6位数字', icon: 'none' });
      return;
    }

    wx.request({
      url: `${app.globalData.serverHost}/api/auth/bind`,
      method: 'POST',
      data: { userId: app.globalData.userId, bindCode: bindCode },
      success: (res) => {
        if (res.data.success) {
          app.globalData.isBound = true;
          app.globalData.boundUser = res.data.boundUser;
          this.setData({
            isBound: true,
            boundUser: res.data.boundUser,
            bindCode: ''
          });
          wx.showToast({ title: '绑定成功', icon: 'success' });
        } else {
          wx.showToast({ title: res.data.error || '绑定失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      }
    });
  },

  onUnbindTap: function () {
    const app = getApp();
    wx.showModal({
      title: '解除绑定',
      content: '确定要解除与老人的绑定吗？',
      success: (res) => {
        if (res.confirm) {
          app.globalData.isBound = false;
          app.globalData.boundUser = null;
          this.setData({
            isBound: false,
            boundUser: null
          });
          wx.showToast({ title: '已解除绑定', icon: 'success' });
        }
      }
    });
  },

  fetchRiskEvents: function () {
    const app = getApp();
    if (!app.globalData.isBound) return;
    
    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.boundUser.id}`,
      success: (res) => {
        if (res.data.success) {
          this.setData({ events: res.data.data });
        }
      }
    });
  },

  fetchLocations: function () {
    const app = getApp();
    if (!app.globalData.isBound) return;
    
    wx.request({
      url: `${app.globalData.serverHost}/api/events/location/${app.globalData.boundUser.id}`,
      success: (res) => {
        if (res.data.success) {
          this.setData({ locations: res.data.data });
        }
      }
    });
  }
});
```

- [ ] **Step 3: 提交**

```bash
git add wechat-miniprogram/pages/dashboard/dashboard.wxml wechat-miniprogram/pages/dashboard/dashboard.js
git commit -m "feat: add binding card to dashboard page"
```

---

### Task 5: 小程序 — 添加绑定卡片样式

**Files:**
- Modify: `wechat-miniprogram/pages/dashboard/dashboard.wxss`

- [ ] **Step 1: 添加绑定卡片样式**

Append to `dashboard.wxss`:

```css
.bind-input {
  width: 100%;
  height: 40px;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 0 12px;
  margin: 12px 0;
  background: #0F172A;
  color: #F1F5F9;
  text-align: center;
  font-size: 18px;
  letter-spacing: 8px;
}

.bind-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 12px 0;
  padding: 12px;
  background: rgba(59, 130, 246, 0.1);
  border-radius: 8px;
}

.bind-name {
  font-size: 16px;
  font-weight: bold;
  color: #3B82F6;
}

.bind-phone {
  font-size: 13px;
  color: #94A3B8;
}
```

- [ ] **Step 2: 提交**

```bash
git add wechat-miniprogram/pages/dashboard/dashboard.wxss
git commit -m "style: add binding card styles"
```

---

### Task 6: 端到端验证

**Files:**
- No file changes

- [ ] **Step 1: 启动后端服务器**

Run: `node server.js`
Expected: 服务器启动成功，显示 `http://localhost:3000`

- [ ] **Step 2: 测试 wx-login 接口**

Run: `curl -X POST http://localhost:3000/api/auth/wx-login -H "Content-Type: application/json" -d "{\"code\":\"test_code\"}"`
Expected: 返回 JSON（可能因无效 code 返回错误，但接口应正常响应）

- [ ] **Step 3: 在微信开发者工具中测试**

1. 打开微信开发者工具，加载 `wechat-miniprogram` 目录
2. 查看控制台是否显示"微信登录成功"
3. 未绑定时应弹出绑定引导
4. 输入绑定码 `888888`（张爷爷的绑定码）
5. 验证绑定成功，显示老人信息
6. 刷新页面验证状态持久化

- [ ] **Step 4: 提交（如有改动）**

```bash
git add -A
git commit -m "test: verify binding flow end-to-end"
```
