# API 参考

服务基址：`http://localhost:3000`（生产走 Cloudflare Tunnel `https://guard.chataifree.eu.org`）

## 鉴权模型

| 中间件 | 作用 |
| --- | --- |
| 无 | 老人设备上报类接口，以 `elderId` 标识设备信任（`/events/report`、`/geofence/elder/:elderId`、`/recordings/upload`、老人端登记/绑定码/设置） |
| `requireFamilyAuth` | 请求头必须带 `X-Auth-Token`，token 由 `/auth/login`、`/auth/register`、`/auth/wx-login` 签发，有效期 7 天 |
| `requireBoundElder` | 登录用户本人，或与其存在 `bound_user_id` 绑定关系，才可访问该 elderId 的数据。兼容路径参数名 `:elderId` / `:id` / `:userId` |

---

## 认证 `/api/auth`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/wx-login` | 微信 `code2Session` 登录，自动建号 | 无 |
| POST | `/register` | 子女端账号密码注册（`username` 存 `phone` 字段，`phone` 真实号码存 `mobile`） | 无 |
| POST | `/login` | 账号密码登录，标识可用用户名或手机号 | 无 |
| POST | `/profile-mobile` | 补录/修改本人真实手机号 | Token |
| POST | `/bind` | 用 6 位 `bindCode` 双向绑定亲情关系 | Token |
| POST | `/unbind` | 解除绑定（事务） | Token |
| GET | `/user/:id` | 查询本人/绑定老人信息（含 `bound_name`、`display_phone`） | Token + 绑定校验 |
| POST | `/elder-register` | 老人端首次登记/换机认领；带 `previousPhone` 时保住原 `elderId` | 无 |
| POST | `/elder-bind-code` | 查询（不带 `bindCode`）或刷新绑定码 | 无 |
| GET | `/elder-settings?elderId=` | 读取云端防护规则 | 无 |
| POST | `/elder-settings` | 保存防护规则（逐项 sanitize + 增量合并） | 无 |

`elder-register` / `elder-bind-code` / `elder-settings` 的响应统一走 `replyElderState()`，一次带全 `bindCode` + `boundFamily` + `guardSettings`，让新设备登记完立刻是完整状态。

### 防护规则字段（`guard_settings` JSON）

| 字段 | 范围 | 默认 |
| --- | --- | --- |
| `callThresholdMinutes` | 1~240 | 15 |
| `paymentThreshold` | 1~1000000 | 500 |
| `recordingMaxSegments` | 1~6 | 3 |
| `recordingAutoUpload` | bool | true |
| `homeAwayRadiusMeters` | 100~5000 | 500 |
| `stayMoveMeters` | 20~1000 | 100 |
| `homeStayMinutes` | 5~240 | 40 |
| `homeLat` / `homeLng` | 经纬度 | — |
| `trustedCallNumbersJson` / `highRiskPackages` | JSON 字符串，长度 ≤ 20000 | — |

---

## 事件 `/api/events`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/report` | 上报风险事件；服务端按事件类型归一化 `severity`，并落 `payments` / `locations`、广播 WS、触发微信模板消息 | 无 |
| GET | `/list/:elderId` | 风险事件历史 | Token + 绑定校验 |
| GET | `/location/:elderId` | 位置轨迹（最近 10 条，服务端补全地名） | Token + 绑定校验 |

### 事件类型与强制级别

`EVENT_SEVERITY_OVERRIDE` 是服务端唯一权威，不采信客户端自报值（客户端把 HIGH 误报成 LOW 会静默吞掉推送）。

| 类型 | 强制级别 | 是否弹打断框 |
| --- | --- | --- |
| `SOS` | 客户端自报 | 是（任何级别） |
| `COERCION_RISK` | HIGH | 是 |
| `PAYMENT_RISK` | HIGH | 是 |
| `CALL_RISK` | HIGH | 是 |
| `GEOFENCE_DWELL` | MEDIUM | 否 |
| `LOCATION_RISK` | 客户端自报 | 否 |
| `CALL_STAT` | LOW | 否 |
| `GEOFENCE_RECORDING` | 客户端自报 | 否（守护系统正常动作） |
| `LOCATION_UPDATE` | LOW | 否（心跳级） |
| `GEOFENCE_EXIT` / `DEVICE_ONLINE` | 客户端自报 | 否 |

判定逻辑见 `services/riskAlertPolicy.js`，Android `family/RiskAlertPolicy.kt` 与小程序 `app.js` 镜像同一份规则，改动必须三处同步。

---

## 电子围栏 `/api/geofence`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | `/elder/:elderId` | 老人端拉启用中的围栏 | 无 |
| GET | `/list/:elderId` | 子女端查看（含停用项） | Token |
| POST | `/add` | `{name, latitude, longitude, radius?, dwellMinutes?}`；半径 50~2000 默认 200，停留阈值 0~720 | Token |
| POST | `/update` | `{id, name?, radius?, enabled?, dwellMinutes?}` | Token |
| POST | `/delete` | `{id}` | Token |

写操作固定作用于当前登录用户绑定的老人，不接受任意 `elderId`。名称若是坐标串会被替换为可读地名。

---

## 录音存证 `/api/recordings`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/upload` | multipart 上传单段录音（≤60MB，限 1 个音频文件）；落盘 `uploads/recordings/` 并算 SHA-256，随后异步转写 + AI 研判 | 无（需 `elderId` 且账号存在） |
| GET | `/list/:elderId` | 录音列表（≤200 条）；`?evidence=1` 只看证据，`?group=1` 按会话聚合；每条签发播放 token | Token + 绑定校验 |
| GET | `/stream/:id?token=` | 流式播放（支持 Range），token 为 HMAC 签名 | 签名校验 |
| GET | `/pack/:elderId` | 打包下载报警材料（ZIP + manifest）；`?scope=evidence` 只打证据 | Token + 绑定校验 |
| POST | `/:id/review` | 人工复核，推翻 AI 判定 | Token |
| DELETE | `/:id` | 删除记录与文件 | Token |
| GET | `/status/:elderId` | 老人端当前是否在录音（内存态） | Token + 绑定校验 |

链路细节见 [录音存证链路实现说明.md](录音存证链路实现说明.md)。

---

## 证据导出 `/api/evidence`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | `/export/:elderId` | 聚合导出《反诈报案维权证据包》：资金流水 + 通话风险 + 轨迹 + 录音清单 | Token + 绑定校验 |

## AI 鉴诈 `/api/ai`

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/scan` | 关键词规则匹配（`根治`/`磁疗`/`高额收益`/`解冻基金`/`公检法` 等），返回红黄绿风险评级 | Token |

## 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 存活探针，App 在登记/绑定前用它判断后端可达 |

---

## WebSocket 协议

连接 `ws://localhost:3000`（生产 `wss://guard.chataifree.eu.org`），JSON 消息。

### 客户端 → 服务端

| type | 字段 | 说明 |
| --- | --- | --- |
| `REGISTER` | `userId`, `role` | 注册身份。老人端上线时补发离线期间缓存的打断指令（最近 5 条，带 `offlineQueued: true`） |
| `INTERRUPT_CMD` | `targetElderId`, `message`, `fromUser?` | 子女端发起远程亲情打断；老人端离线则入队 |
| `RECORDING_STATE` | `state`(`STARTED`/`SEGMENT`/`STOPPED`), `reason`, `place`, `segmentIndex`, `sessionId` | 老人端上报录音运行态 |
| `RECORDING_STOP_CMD` | `targetElderId`, `fromUser?` | 子女端远程停止录音 |

### 服务端 → 客户端

| type | 场景 |
| --- | --- |
| `REGISTER_ACK` | 注册成功 |
| `INTERRUPT_ACK` | 打断指令投递结果（`success` / `offline`） |
| `EMERGENCY_INTERRUPT` | 老人端收到强打断（含 `fromUser`、`fromPhone`、`alertTitle`、`alertMessage`） |
| `RISK_ALERT` | 风险事件广播，data 内含 `interruptible` 判定 |
| `RECORDING_STOP_ACK` | 远程停止录音结果 |
| `RECORDING_STATE` | 录音运行态同步给子女端 |
| `RECORDING_STOP` | 老人端收到停止指令 |
| `RECORDING_UPLOADED` / `RECORDING_ANALYZED` / `RECORDING_REVIEWED` / `RECORDING_DELETED` | 录音生命周期事件 |

### 连接表注意点

- `clients` 是 `Map<userId, WebSocket>`，同一 userId 新连接覆盖旧连接。
- `close` 回调只在 `clients.get(userId) === ws` 时才删除，否则网络抖动下旧连接的 close 会误删新连接注册记录，导致服务端误判离线而丢弃打断指令。
- 每个连接必须挂 `error` 处理器，否则单客户端异常关闭帧会打崩进程。