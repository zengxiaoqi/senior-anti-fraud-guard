const express = require('express');
const router = express.Router();
const db = require('../database/db');
const wechatNotify = require('../services/wechat');
const { requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const { toBeijing, toBeijingRows } = require('../services/timeFormat');
const geo = require('../services/regeo');

let broadcastHandler = null;
router.setBroadcastHandler = (handler) => {
  broadcastHandler = handler;
};

// 上报风险感知事件
router.post('/report', (req, res) => {
  const { elderId, eventType, severity, details } = req.body;

  if (!elderId || !eventType || !severity) {
    return res.status(400).json({ error: '缺失必要参数' });
  }

  const detailsStr = typeof details === 'object' ? JSON.stringify(details) : details;

  const stmt = db.prepare(`INSERT INTO risk_events (elder_id, event_type, severity, details) VALUES (?, ?, ?, ?)`);
  stmt.run([elderId, eventType, severity, detailsStr], function(err) {
    if (err) return res.status(500).json({ error: err.message });
    
    const eventId = this.lastID;
    const eventData = {
      id: eventId,
      elder_id: elderId,
      event_type: eventType,
      severity,
      details: details || {},
      created_at: toBeijing(new Date())
    };

    // 1. 如果是大额支付，插入 payments 存证表
    if (eventType === 'PAYMENT_RISK' && details && details.amount) {
      db.run(`INSERT INTO payments (elder_id, amount, payee_name, payee_account, order_no)
              VALUES (?, ?, ?, ?, ?)`,
              [elderId, details.amount, details.payee_name || '未知商户', details.payee_account || '未知卡号', details.order_no || `ORD_${Date.now()}`]);
    }

    // 2. 如果包含位置变化，插入 locations 轨迹表
    if (details && details.latitude && details.longitude) {
      const isSensitive = severity === 'HIGH' || severity === 'MEDIUM' ? 1 : 0;
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

    // 3. 触发 WebSocket 实时广播给子女端 App
    if (broadcastHandler) {
      broadcastHandler(elderId, {
        type: 'RISK_ALERT',
        data: eventData
      });
    }

    // 4. 触发微信模板消息推送给子女微信
    if (severity === 'HIGH') {
      db.get("SELECT bound_user_id FROM users WHERE id = ?", [elderId], (err, row) => {
        if (row && row.bound_user_id) {
          db.get("SELECT * FROM users WHERE id = ?", [row.bound_user_id], (err, familyUser) => {
            if (familyUser) {
              const openId = familyUser.wx_openid || `openid_family_${familyUser.id}`;
              wechatNotify.sendAntiFraudAlert(openId, {
                title: `⚠️ 长者防诈紧急预警: ${eventType}`,
                elderName: familyUser.bound_name || '守护对象',
                severity: '高危状态',
                description: JSON.stringify(details).substring(0, 50)
              });
            }
          });
        }
      });
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
    res.json({ success: true, data: formatted });
  });
});

// 查询最新位置轨迹（需登录态 + 绑定关系校验）
router.get('/location/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;
  db.all(`SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT 10`, [elderId], (err, rows) => {
    if (err) return res.status(500).json({ error: err.message });
    // created_at 库里是 UTC，统一转北京时间再下发（否则子女端显示差 8 小时）
    const out = toBeijingRows(rows || []);
    // 地址兜底顺序：库里真实地名 → 地图逆地理编码 → 最近锚点/常去地点 → 坐标
    // 前两者可能失败或没配 key，后两者是纯本地推断，保证界面上永远不会只剩数字。
    // 用 Promise.all 并发处理，10 条一次性返回。
    Promise.all(out.map(r => {
      if (geo.looksLikeRealAddress(r.address)) return Promise.resolve({ ...r, place_source: 'db' });
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

module.exports = router;
