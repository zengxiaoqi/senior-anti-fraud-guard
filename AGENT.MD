# AGENT.MD — 长者防诈亲情守护系统

## 项目概述

**长者防诈亲情守护系统** 是一个全栈应用，通过行为感知、实时干预和证据留存三位一体的闭环设计，保护老年人免受电信诈骗、保健品骗局等威胁。

- **技术栈**: Node.js + Express + SQLite + WebSocket
- **端口**: 3000 (HTTP & WebSocket)
- **数据库**: SQLite (`data.sqlite`)

## 快速启动

```bash
npm start          # 生产模式
npm run dev        # 开发模式（相同命令）
```

环境变量（可选）:
- `PORT` — 服务端口（默认 3000）
- `WECHAT_APPID` / `WECHAT_APPSECRET` — 微信小程序登录
- `WX_APP_ID` / `WX_APP_SECRET` / `WX_TEMPLATE_ID` — 微信模板消息推送

## 项目结构

```
├── server.js              # 入口：Express + WebSocket 服务器
├── database/
│   └── db.js              # SQLite 连接 + 表结构初始化 + 预置数据
├── routes/
│   ├── auth.js            # 认证：微信登录、账号密码注册/登录、绑定/解绑
│   ├── events.js          # 风险事件上报与查询、位置轨迹
│   ├── evidence.js        # 证据包导出（反诈报案材料）
│   └── ai.js              # AI 鉴诈分析（关键词匹配）
├── services/
│   ├── tokenAuth.js       # Token 鉴权中间件（内存 Map，7天有效期）
│   └── wechat.js          # 微信模板消息推送服务
├── public/
│   └── index.html         # Web 管理面板（子女端）
├── wechat-miniprogram/    # 微信小程序（子女端）
├── android/               # Android 原生 App（老人端守护）
├── tests/
│   └── test-wx-login.js  # 微信登录测试
└── data.sqlite            # SQLite 数据库文件
```

## 核心 API

### 认证模块 `/api/auth`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/wx-login` | 微信 code2Session 登录 | 无 |
| POST | `/register` | 账号密码注册（子女端） | 无 |
| POST | `/login` | 账号密码登录（子女端） | 无 |
| POST | `/bind` | 绑定亲情账号（通过 bindCode） | Token |
| POST | `/unbind` | 解除亲情绑定 | Token |
| GET | `/user/:id` | 查询用户信息 | Token + 绑定校验 |
| POST | `/elder-bind-code` | 老人端绑定码同步 | 无（elderId 标识） |

### 事件模块 `/api/events`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/report` | 上报风险事件（老人端设备） | 无（elderId 标识） |
| GET | `/list/:elderId` | 查询风险事件历史 | Token + 绑定校验 |
| GET | `/location/:elderId` | 查询位置轨迹 | Token + 绑定校验 |

### 证据模块 `/api/evidence`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/export/:elderId` | 导出反诈报案证据包 | Token + 绑定校验 |

### AI 模块 `/api/ai`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/scan` | AI 鉴诈分析（关键词匹配） | Token |

## WebSocket 协议

客户端连接 `ws://localhost:3000`，消息格式为 JSON：

### 客户端注册
```json
{ "type": "REGISTER", "userId": 1, "role": "elder" }
```

### 子女端远程打断指令
```json
{ "type": "INTERRUPT_CMD", "targetElderId": 1, "message": "爸！别转账！" }
```

### 服务端推送（风险告警）
```json
{ "type": "RISK_ALERT", "data": { "id": 1, "elder_id": 1, "event_type": "CALL_RISK", ... } }
```

## 数据库表结构

### `users` — 用户表
- `id` INTEGER PK
- `role` TEXT — `elder` | `family`
- `name` TEXT
- `phone` TEXT UNIQUE
- `bind_code` TEXT UNIQUE — 6位绑定码
- `bound_user_id` INTEGER — 绑定对象 ID
- `wx_openid` TEXT — 微信 OpenID
- `password_hash` TEXT — scrypt 哈希（`salt:hash`）
- `created_at` DATETIME

### `risk_events` — 风险事件表
- `id` INTEGER PK
- `elder_id` INTEGER FK → users.id
- `event_type` TEXT — `CALL_RISK` | `PAYMENT_RISK` | `LOCATION_RISK`
- `severity` TEXT — `LOW` | `MEDIUM` | `HIGH`
- `details` TEXT (JSON)
- `created_at` DATETIME

### `locations` — 位置轨迹表
- `id` INTEGER PK
- `elder_id` INTEGER FK
- `latitude` / `longitude` REAL
- `address` TEXT
- `is_sensitive` INTEGER (0/1)
- `created_at` DATETIME

### `payments` — 支付存证表
- `id` INTEGER PK
- `elder_id` INTEGER FK
- `amount` REAL
- `payee_name` / `payee_account` TEXT
- `order_no` TEXT UNIQUE
- `created_at` DATETIME

## 预置测试数据

| ID | 角色 | 姓名 | 手机号 | 绑定码 |
|----|------|------|--------|--------|
| 1 | elder | 张爷爷 | 13900001111 | 888888 |
| 2 | family | 张强(儿子) | 13888889999 | 999999 |

## 安全设计

1. **Token 鉴权**: 登录后签发随机 token（内存 Map），请求头 `X-Auth-Token` 校验
2. **绑定关系校验**: `requireBoundElder` 确保只能访问绑定老人的数据
3. **密码安全**: scrypt 加盐哈希存储
4. **SQL 注入防护**: 全程使用参数化查询
5. **WebSocket 稳定性**: 每个连接挂 `error` 处理器，防止单客户端异常打崩进程

## 开发规范

- CommonJS 模块规范（`require` / `module.exports`）
- 路由按模块拆分到 `routes/` 目录
- 服务层独立到 `services/` 目录
- 数据库操作统一使用 `db.prepare()` 或参数化查询
- 日志使用 `console.log` + emoji 前缀标识级别
