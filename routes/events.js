const express = require('express');
const router = express.Router();
const db = require('../database/db');
const wechatNotify = require('../services/wechat');

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
      created_at: new Date().toISOString()
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
      db.run(`INSERT INTO locations (elder_id, latitude, longitude, address, is_sensitive)
              VALUES (?, ?, ?, ?, ?)`,
              [elderId, details.latitude, details.longitude, details.address || '未知位置', isSensitive]);
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

// 查询指定老人的历史风险事件
router.get('/list/:elderId', (req, res) => {
  const elderId = req.params.elderId;
  const limit = req.query.limit || 20;

  db.all(`SELECT * FROM risk_events WHERE elder_id = ? ORDER BY id DESC LIMIT ?`, [elderId, limit], (err, rows) => {
    if (err) return res.status(500).json({ error: err.message });
    const formatted = rows.map(r => ({
      ...r,
      details: r.details ? JSON.parse(r.details) : {}
    }));
    res.json({ success: true, data: formatted });
  });
});

// 查询最新位置轨迹
router.get('/location/:elderId', (req, res) => {
  const elderId = req.params.elderId;
  db.all(`SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT 10`, [elderId], (err, rows) => {
    if (err) return res.status(500).json({ error: err.message });
    res.json({ success: true, data: rows });
  });
});

module.exports = router;
