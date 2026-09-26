const express = require('express');
const router = express.Router();
const db = require('../database/db');

// 获取当前用户或老人/子女绑定状态
router.get('/user/:id', (req, res) => {
  const userId = req.params.id;
  db.get(`SELECT u.*, b.name as bound_name, b.phone as bound_phone 
          FROM users u 
          LEFT JOIN users b ON u.bound_user_id = b.id 
          WHERE u.id = ?`, [userId], (err, user) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!user) return res.status(404).json({ error: '用户不存在' });
    res.json({ success: true, data: user });
  });
});

// 绑定亲情账号 (通过 bind_code)
router.post('/bind', (req, res) => {
  const { userId, bindCode } = req.body;
  if (!userId || !bindCode) {
    return res.status(400).json({ error: '参数缺失' });
  }

  // 查找符合 bind_code 的目标用户
  db.get("SELECT * FROM users WHERE bind_code = ?", [bindCode], (err, targetUser) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!targetUser) return res.status(404).json({ error: '绑定码无效' });

    // 双向绑定
    db.run("UPDATE users SET bound_user_id = ? WHERE id = ?", [targetUser.id, userId], function(err) {
      if (err) return res.status(500).json({ error: err.message });
      
      db.run("UPDATE users SET bound_user_id = ? WHERE id = ?", [userId, targetUser.id], function(err) {
        if (err) return res.status(500).json({ error: err.message });
        res.json({ success: true, message: '亲情绑定成功', boundUser: targetUser });
      });
    });
  });
});

module.exports = router;
