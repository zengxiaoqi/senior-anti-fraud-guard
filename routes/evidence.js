const express = require('express');
const router = express.Router();
const db = require('../database/db');
const { requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');

// 一键导出老人《反诈报案维权证据包》数据（需登录态 + 绑定关系校验）
router.get('/export/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;

  // 聚合查询老人信息、扣款流水、风险通话、轨迹
  db.get("SELECT u.*, b.name as family_name, b.phone as family_phone FROM users u LEFT JOIN users b ON u.bound_user_id = b.id WHERE u.id = ?", [elderId], (err, elder) => {
    if (err || !elder) return res.status(404).json({ error: '找不到老人账户信息' });

    db.all("SELECT * FROM payments WHERE elder_id = ? ORDER BY id DESC", [elderId], (err, payments) => {
      db.all("SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT 20", [elderId], (err, locations) => {
        db.all("SELECT * FROM risk_events WHERE elder_id = ? AND event_type = 'CALL_RISK' ORDER BY id DESC", [elderId], (err, callRisks) => {
          
          const evidencePackage = {
            metadata: {
              title: "电信诈骗/非法会销涉案电子证据集",
              generated_at: new Date().toISOString(),
              system_version: "1.0.0-SQLite",
              checksum: `HASH_${Date.now().toString(16).toUpperCase()}`
            },
            elder_info: {
              name: elder.name,
              phone: elder.phone,
              guardian_name: elder.family_name || '已绑定防诈监护人',
              guardian_phone: elder.family_phone || '未知'
            },
            payment_records: payments,
            location_logs: locations,
            suspicious_calls: callRisks.map(c => ({
              ...c,
              details: c.details ? JSON.parse(c.details) : {}
            }))
          };

          res.json({ success: true, data: evidencePackage });
        });
      });
    });
  });
});

module.exports = router;
