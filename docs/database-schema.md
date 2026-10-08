# 数据库结构

SQLite，路径由 `DB_PATH` 覆盖，默认 `data.sqlite`（生产/本地，含真实数据，不进 git）。
演示库 `data.demo.sqlite` 由 `npm run seed` 生成（纯假数据）。

建表与迁移在 `database/db.js`：表结构用 `CREATE TABLE IF NOT EXISTS`，新增字段用 `ALTER TABLE ADD COLUMN` 并容忍 `duplicate column` 错误。

## `users` — 用户与亲情绑定

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `role` | TEXT | `elder` \| `family` |
| `name` | TEXT | |
| `phone` | TEXT UNIQUE | **登录用户名**（子女端注册时存这里，不是手机号） |
| `mobile` | TEXT | 真实手机号（`/register` 的 `phone` 参数），全局唯一性在应用层校验 |
| `bind_code` | TEXT UNIQUE | 6 位绑定码 |
| `bound_user_id` | INTEGER | 绑定的另一端 ID，双向 |
| `wx_openid` | TEXT | 微信 OpenID |
| `password_hash` | TEXT | scrypt，`salt:hash` |
| `guard_settings` | TEXT | 防护规则 JSON 云端副本（按 elderId 存） |
| `created_at` | DATETIME | |

`phone` 与 `mobile` 语义分裂是有历史原因的：老字段被登录用户名占用。取值一律用 `COALESCE(NULLIF(mobile,''), phone)`。

`guard_settings` 必须落库的原因：阈值类配置以前只存本机 SharedPreferences，老人换手机/重装就静默回到默认值，用户在老手机上调好的规则无声失效。解析失败按"无配置"处理，不能 500。

## `risk_events` — 风险行为感知事件

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `elder_id` | INTEGER FK → users.id | |
| `event_type` | TEXT | 见 api-reference.md 事件类型表 |
| `severity` | TEXT | `LOW` \| `MEDIUM` \| `HIGH`，由服务端按类型归一化 |
| `details` | TEXT | JSON |
| `created_at` | DATETIME | |

## `locations` — 地理轨迹

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `elder_id` | INTEGER FK | |
| `latitude` / `longitude` | REAL | |
| `address` | TEXT | 服务端逆地理编码补全，失败退化为坐标串 |
| `is_sensitive` | INTEGER | 0/1，用归一化后的 severity 判定 |
| `created_at` | DATETIME | |

## `payments` — 支付存证

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `elder_id` | INTEGER FK | |
| `amount` | REAL | |
| `payee_name` / `payee_account` | TEXT | |
| `order_no` | TEXT UNIQUE | |
| `created_at` | DATETIME | |

由 `POST /api/events/report` 在 `event_type = PAYMENT_RISK` 且带 `details.amount` 时自动写入。

## `geofences` — 敏感地点围栏

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `elder_id` | INTEGER FK | |
| `name` | TEXT | 坐标串会被替换为可读地名 |
| `latitude` / `longitude` | REAL | |
| `radius` | INTEGER | 米，50~2000 默认 200 |
| `dwell_minutes` | INTEGER | 停留告警阈值，0 = 只录音不告警，上限 720 |
| `enabled` | INTEGER | 0/1 |
| `created_at` | DATETIME | |

## `recordings` — 环境录音存证

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK | |
| `elder_id` | INTEGER FK | |
| `session_id` | TEXT | 一次连续录音（可含多段） |
| `segment_index` | INTEGER | 第几段 |
| `reason` | TEXT | `SOS` / `GEOFENCE` |
| `place_name` | TEXT | 围栏名称（GEOFENCE 时有值） |
| `file_name` | TEXT | 服务端生成的落盘文件名（`uploads/recordings/`） |
| `original_name` | TEXT | 老人端原始文件名 |
| `duration_ms` / `size_bytes` | INTEGER | |
| `sha256` | TEXT | 内容摘要，取证完整性校验 + 重传幂等去重 |
| `mime_type` | TEXT | 默认 `audio/mp4` |
| `recorded_at` | DATETIME | 录音开始时间（UTC ISO） |
| `latitude` / `longitude` / `address` | REAL / TEXT | 录音时位置，对齐"录音-位置-风险事件" |
| `transcript` | TEXT | 转写文本 |
| `transcript_status` | TEXT | `PENDING`/`DONE`/`FAILED`/`SKIPPED` |
| `transcript_error` | TEXT | |
| `fraud_status` | TEXT | `PENDING`/`ANALYZING`/`FRAUD`/`SUSPECT`/`SAFE`/`FAILED` |
| `fraud_score` | REAL | |
| `fraud_verdict` | TEXT | 研判结论文本 |
| `fraud_labels` | TEXT | JSON 数组，命中话术标签 |
| `suspect_role` | TEXT | `caller` / `callee` / `both` |
| `keep_as_evidence` | INTEGER | 0/1，仅 FRAUD 长期保留 |
| `retention_until` | DATETIME | 到期自动清理时间 |
| `cleanup_reason` | TEXT | 为何被清理 |
| `reviewed_by_family` | INTEGER | 子女端人工复核过（推翻 AI 判定） |
| `created_at` | DATETIME | |

索引：`idx_recordings_elder(elder_id, id DESC)`、`idx_recordings_session(session_id, segment_index)`、`idx_recordings_status(elder_id, fraud_status)`。

### 证据保留策略

| 结论 | 处理 |
| --- | --- |
| `FRAUD` | 永久保留 |
| `SUSPECT` / 转写失败 | 临时保留 14 天（`RECORDING_SAFE_RETENTION_DAYS`），等子女端复核 |
| `SAFE` | 临时保留，到期清理 |
| 人工复核过 | 不清理 |

"分析不了"不等于没问题：ASR 失败按 `SUSPECT` 处理，不能当 `SAFE` 直接清掉。

## 预置数据

`database/db.js` 在 `users` 为空时写入：老人 `id=1`（张爷爷，`bind_code=888888`）、子女 `id=2`（张强，`bind_code=999999`），互绑，外加一条初始位置。

演示库 `npm run seed` 用另一组固定码方便评审：老人 `246810` / 子女 `135791`，含 5 条风险事件、4 条位置、2 条支付。

## 隐私红线

`data.sqlite`、`uploads/`、`*.apk`、`android/keystore/`、`.env`、`data.demo.sqlite` 全部在 `.gitignore` 内。录音文件按扩展名额外拦截（`*.m4a`/`*.mp4`/`*.bin` 等）——老人语音一旦进入 git 历史，仅靠后续删除删不掉。