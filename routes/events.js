const express = require('express');
const router = express.Router();
const db = require('../database/db');
const wechatNotify = require('../services/wechat');
const { requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const { toBeijing, toBeijingRows } = require('../services/timeFormat');
const { shouldInterrupt } = require('../services/riskAlertPolicy');
const { evaluateLiveness, extractLivenessThresholds, parseSqliteTime } = require('../services/elderLiveness');
const { isSensitiveLocation } = require('../services/locationSensitivity');
const geo = require('../services/regeo');

let broadcastHandler = null;
router.setBroadcastHandler = (handler) => {
  broadcastHandler = handler;
};

// Phase 1 新增事件类型（1-7）识别：
//   COERCION_RISK  通话中前台切到高危 App（远程控制/支付类）→ 恒 HIGH，必推送
//   CALL_STAT      通话结束统计（时长/陌生判定/峰值风险）→ 恒 LOW，只记录不推送
//   GEOFENCE_DWELL 围栏内停留超阈值 → 恒 MEDIUM
// 服务端按事件类型归一化 severity，不盲信客户端自报值：
// 客户端把 HIGH 误报成 LOW 会静默吞掉推送，这是漏报方向最危险的 bug
const EVENT_SEVERITY_OVERRIDE = {
  COERCION_RISK: 'HIGH',
  CALL_STAT: 'LOW',
  GEOFENCE_DWELL: 'MEDIUM',
  // 老人端危险检测（跌倒）：恒 HIGH。
  // 老人无法决定自己的严重程度 —— 就算客户端代码算出了"较低置信度"，
  // 也不该降级，否则真摔了反而没人管。
  FALL_RISK: 'HIGH'
};

// 上报风险感知事件
router.post('/report', (req, res) => {
  const { elderId, eventType, severity, details } = req.body;

  if (!elderId || !eventType || !severity) {
    return res.status(400).json({ error: '缺失必要参数' });
  }

  const effSeverity = EVENT_SEVERITY_OVERRIDE[eventType] || String(severity).toUpperCase();

  const detailsStr = typeof details === 'object' ? JSON.stringify(details) : details;

  const stmt = db.prepare(`INSERT INTO risk_events (elder_id, event_type, severity, details) VALUES (?, ?, ?, ?)`);
  stmt.run([elderId, eventType, effSeverity, detailsStr], function(err) {
    if (err) return res.status(500).json({ error: err.message });
    
    const eventId = this.lastID;
    const eventData = {
      id: eventId,
      elder_id: elderId,
      event_type: eventType,
      severity: effSeverity,
      details: details || {},
      created_at: toBeijing(new Date()),
      // 客户端据此决定是否弹「强打断」确认框。
      // 以前只按事件类型无条件弹，于是 LOCATION_UPDATE 这种心跳级上报也弹
      // "长者正处于高危状态"，把真高危告警淹在噪声里（告警疲劳）。
      // 老客户端不认这个字段、行为不变，所以这是纯增量。
      interruptible: shouldInterrupt(eventType, effSeverity)
    };

    // 1. 如果是大额支付，插入 payments 存证表
    if (eventType === 'PAYMENT_RISK' && details && details.amount) {
      db.run(`INSERT INTO payments (elder_id, amount, payee_name, payee_account, order_no)
              VALUES (?, ?, ?, ?, ?)`,
              [elderId, details.amount, details.payee_name || '未知商户', details.payee_account || '未知卡号', details.order_no || `ORD_${Date.now()}`]);
    }

    // 2. 如果包含位置变化，插入 locations 轨迹表
    if (details && details.latitude && details.longitude) {
      // 注意用 effSeverity 而非 severity：客户端可能自报 LOW，而事件类型表
      // 已把它归一化成 HIGH（见 EVENT_SEVERITY_OVERRIDE）。用自报值会让
      // COERCION_RISK 这类真高危在轨迹上显示成不敏感。
      const isSensitive = isSensitiveLocation(eventType, effSeverity, details);
      // 老人端只会传 "GPS 位置 (lat, lng)" 这种坐标串，不是真实地名。
      // 子女端看轨迹就是为了判断"老人在哪"，坐标串毫无意义 → 服务端补全地名。
      const rawAddress = String(details.address || '').trim();
      const needGeo = !geo.looksLikeRealAddress(rawAddress);
      db.run(`INSERT INTO locations (elder_id, latitude, longitude, address, is_sensitive)
              VALUES (?, ?, ?, ?, ?)`,
              [elderId, details.latitude, details.longitude,
               rawAddress || geo.coordFallback(details.latitude, details.longitude), isSensitive],
              function (err2) {
                if (err2) return;
                // 异步补全：查到地名后 UPDATE 那一行，不阻塞接口响应
                if (needGeo) geo.enrichLocation(this.lastID, details.latitude, details.longitude, db);
              });
    }

    // 2.5 告警详情里的坐标补地名（与 locations 表同一套"写后异步补全"模式）：
    // 子女端告警页读的是 risk_events.details，而老人端上报的 details 只有
    // "latitude: 28.27; longitude: 113.06" 这种数字 —— 守护人要的是"人在哪"，
    // 不是经纬度。入库后异步逆地理，查到真名把 location_name 合并回 details 行。
    // 刻意用 reverseGeocode 而非 describePlace：只在查到**真名**时才回写，
    // "常去地点①"这类本地推断描述不落库污染原始上报（读时兜底展示由 /list 负责）。
    if (details && details.latitude && details.longitude) {
      const baseDetails = typeof details === 'object' ? details : {};
      const hasRealName =
        geo.looksLikeRealAddress(baseDetails.location_name) ||
        geo.looksLikeRealAddress(baseDetails.address);
      if (!hasRealName) {
        geo.reverseGeocode(details.latitude, details.longitude)
          .then((name) => {
            if (!name || /^坐标\s/.test(name)) return; // 坐标兜底没有信息量，不回写
            db.run(
              'UPDATE risk_events SET details = ? WHERE id = ?',
              [JSON.stringify({ ...baseDetails, location_name: name }), eventId],
              function () {
                if (this.changes > 0) console.log(`📍 告警 #${eventId} 地点已补全: ${name}`);
              }
            );
          })
          .catch(() => { /* 补全失败不影响事件入账 */ });
      }
    }

    // 3. 触发 WebSocket 实时广播给子女端 App
    if (broadcastHandler) {
      broadcastHandler(elderId, {
        type: 'RISK_ALERT',
        data: eventData
      });
    }

    // 4. 触发微信模板消息推送给子女微信（多人绑定：全部守护人各自收到）
    if (effSeverity === 'HIGH') {
      db.all(
        `SELECT f.id, f.name AS family_name, f.wx_openid, e.name AS elder_name
           FROM bindings b
           JOIN users f ON f.id = b.family_id
           JOIN users e ON e.id = b.elder_id
          WHERE b.elder_id = ? AND b.status = 'ACTIVE'`,
        [elderId],
        (err, fams) => {
          if (err || !fams) return;
          for (const familyUser of fams) {
            const openId = familyUser.wx_openid || `openid_family_${familyUser.id}`;
            wechatNotify.sendAntiFraudAlert(openId, {
              title: `⚠️ 长者防诈紧急预警: ${eventType}`,
              // 旧实现引用了不存在的 bound_name 字段，永远退化为"守护对象"；这里取真实老人名
              elderName: familyUser.elder_name || '守护对象',
              severity: '高危状态',
              description: JSON.stringify(details).substring(0, 50)
            });
          }
        }
      );
    }

    res.json({ success: true, eventId, message: '风险事件已记录并触发微信推送' });
  });
});

// 查询指定老人的历史风险事件（需登录态 + 绑定关系校验；/report 为老人设备上报，保持开放）
router.get('/list/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;
  const limit = req.query.limit || 20;

  db.all(`SELECT * FROM risk_events WHERE elder_id = ? ORDER BY id DESC LIMIT ?`, [elderId, limit], (err, rows) => {
    if (err) return res.status(500).json({ error: err.message });
    const formatted = toBeijingRows(rows).map(r => ({
      ...r,
      details: r.details ? JSON.parse(r.details) : {}
    }));

    // 读时自愈：写时补全可能因地图服务当时不可用而失败，历史数据更没有 location_name。
    // 拉列表时对"有坐标但没真名"的事件现场补一次；只有 geo/db（真名）来源才回写库，
    // "常去地点①"这类推断描述只进响应不落库 —— 与 /location 的回写口径一致。
    Promise.all(formatted.map(async (r) => {
      const d = r.details;
      if (!d || typeof d !== 'object' || !d.latitude || !d.longitude) return r;
      if (geo.looksLikeRealAddress(d.location_name)) return r;
      try {
        const { name, source } = await geo.describePlace(d.latitude, d.longitude, db, elderId);
        r.details = { ...d, location_name: name };
        if (source === 'geo' || source === 'db') {
          db.run('UPDATE risk_events SET details = ? WHERE id = ?',
            [JSON.stringify(r.details), r.id], () => {});
        }
      } catch (e) { /* 补全失败保持原样，不影响列表返回 */ }
      return r;
    })).then(() => {
      res.json({ success: true, data: formatted });
    }).catch(() => {
      res.json({ success: true, data: formatted });
    });
  });
});

// 查询最新位置轨迹（需登录态 + 绑定关系校验）
//
// 应对策略与 Workers 版完全一致（workers/src/routes/events.ts 同名路由）：
// 默认仍 10 条（小程序 / 旧客户端契约不变），传 ?limit=200 让 Android 轨迹页
// 看到 7 天全貌；上限 300 防止拉爆。逆地理补全只给"库里不是真地名"的点，
// 且最多 20 个（几百条心跳逐个猜名字会把配额和响应时间一起吃光）。
const LOCATION_MAX_LIMIT = 300;
const LOCATION_GEO_BUDGET = 20;

router.get('/location/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;
  const raw = Number(req.query.limit);
  const limit = Number.isFinite(raw) && raw > 0 ? Math.min(Math.floor(raw), LOCATION_MAX_LIMIT) : 10;
  db.all(`SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT ?`, [elderId, limit], (err, rows) => {
    if (err) return res.status(500).json({ error: err.message });
    // created_at 库里是 UTC，统一转北京时间再下发（否则子女端显示差 8 小时）
    const out = toBeijingRows(rows || []);
    // 地址兜底顺序：库里真实地名 → 地图逆地理编码 → 最近锚点/常去地点 → 坐标
    // 前两者可能失败或没配 key，后两者是纯本地推断，保证界面上永远不会只剩数字。
    let geoBudget = LOCATION_GEO_BUDGET;
    Promise.all(out.map(r => {
      if (geo.looksLikeRealAddress(r.address)) return Promise.resolve({ ...r, place_source: 'db' });
      if (geoBudget <= 0) return Promise.resolve({ ...r, place_source: 'none' });
      geoBudget--;
      return geo.describePlace(r.latitude, r.longitude, db, elderId)
        .then(({ name, source }) => {
          // 推断出的"常去地点①/XX附近"是描述而非真名，不要写回库覆盖原始上报
          if (source === 'geo' || source === 'db') {
            geo.enrichLocation(r.id, r.latitude, r.longitude, db);
          }
          return { ...r, address: name, place_source: source };
        })
        .catch(() => r);
    })).then((merged) => {
      res.json({ success: true, data: merged });
    }).catch(() => {
      res.json({ success: true, data: out });
    });
  });
});

// 查询老人的守护在线状态（子女端控制台 Hero 卡与失联横幅的数据源）
//
// 为什么要单独一个接口而不是让客户端从 /list 自己算：
//   - 阈值是服务端配置（guard_settings.liveness），客户端不该各自硬编码一份；
//   - "从未上报过"和"3 分钟前上报过"在事件列表里长得几乎一样
//     （列表为空 vs 列表有一条），但语义完全相反，必须由服务端明确回答；
//   - 子女端要在**打开控制台的第一屏**就回答"老人现在联系得上吗"，
//     拉 20 条事件再在客户端推算既费流量又容易算错。
router.get('/status/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;

  db.get('SELECT guard_settings FROM users WHERE id = ?', [elderId], (err, user) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!user) return res.status(404).json({ error: '老人不存在' });

    const thresholds = extractLivenessThresholds(user.guard_settings);

    // 最后一次**老人端**上报：事件 / 轨迹 / 录音三表取最大值。
    // 口径与扫描器一致（含排除服务端自造的失联告警），
    // 否则 /status 会显示"刚刚上报过"而扫描器仍判失联 —— 两个界面互相打脸。
    // 每条分支写成"倒序取 1 条"而非 MAX(created_at)：带 event_type 过滤时
    // MAX 会退化成扫完该老人全部历史行，每轮扫描都跑，行数涨了就打爆 D1 额度。
    db.get(
      `SELECT MAX(ts) AS last_ts FROM (
         SELECT (SELECT created_at FROM risk_events
                  WHERE elder_id = ? AND event_type NOT IN ('ELDER_SILENT', 'ELDER_OFFLINE', 'ELDER_RECONNECTED')
                  ORDER BY created_at DESC LIMIT 1) AS ts
         UNION ALL
         SELECT (SELECT created_at FROM locations WHERE elder_id = ? ORDER BY created_at DESC LIMIT 1)
         UNION ALL
         SELECT (SELECT created_at FROM recordings WHERE elder_id = ? ORDER BY created_at DESC LIMIT 1)
       )`,
      [elderId, elderId, elderId],
      (err2, row) => {
        if (err2) return res.status(500).json({ error: err2.message });
        const lastTs = parseSqliteTime(row && row.last_ts);
        const minutesSince = lastTs === null ? null : Math.floor((Date.now() - lastTs) / 60000);
        const { state, thresholds: t } = evaluateLiveness(minutesSince, thresholds);

        db.get('SELECT * FROM elder_liveness WHERE elder_id = ?', [elderId], (err3, lv) => {
          if (err3) return res.status(500).json({ error: err3.message });
          res.json({
            success: true,
            data: {
              state,
              minutes_since_report: minutesSince,
              last_report_at: lastTs === null ? null : toBeijing(new Date(lastTs)),
              warn_minutes: t.warnMinutes,
              offline_minutes: t.offlineMinutes,
              alerted_state: (lv && lv.alerted_state) || null
            }
          });
        });
      }
    );
  });
});

module.exports = router;
