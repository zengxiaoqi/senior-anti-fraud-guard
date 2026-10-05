const express = require('express');
const router = express.Router();
const db = require('../database/db');
const axios = require('axios');
const crypto = require('crypto');

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
    db.serialize(() => {
      db.run('BEGIN TRANSACTION');
      db.run("UPDATE users SET bound_user_id = ? WHERE id = ?", [targetUser.id, userId], function(err) {
        if (err) {
          db.run('ROLLBACK');
          return res.status(500).json({ error: err.message });
        }
        db.run("UPDATE users SET bound_user_id = ? WHERE id = ?", [userId, targetUser.id], function(err) {
          if (err) {
            db.run('ROLLBACK');
            return res.status(500).json({ error: err.message });
          }
          db.run('COMMIT');
          res.json({ success: true, message: '亲情绑定成功', boundUser: targetUser });
        });
      });
    });
  });
});

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
    const wxRes = await axios.get('https://api.weixin.qq.com/sns/jscode2session', {
      params: {
        appid: appId,
        secret: appSecret,
        js_code: code,
        grant_type: 'authorization_code'
      },
      timeout: 5000
    });

    const { openid, session_key, errcode, errmsg } = wxRes.data;

    if (errcode) {
      return res.status(400).json({ error: `微信登录失败: ${errmsg || errcode}` });
    }

    db.get('SELECT * FROM users WHERE wx_openid = ?', [openid], (err, user) => {
      try {
        if (err) return res.status(500).json({ error: err.message });

        if (user) {
          db.get('SELECT * FROM users WHERE id = ?', [user.bound_user_id], (err, boundUser) => {
            try {
              if (err) return res.status(500).json({ error: err.message });
              res.json({
                success: true,
                data: {
                  userId: user.id,
                  bindCode: user.bind_code,
                  boundUser: boundUser || null
                }
              });
            } catch (e) {
              console.error('[wx-login] boundUser query error:', e);
              res.status(500).json({ error: '查询绑定用户失败' });
            }
          });
        } else {
          const bindCode = String(crypto.randomInt(100000, 1000000));
          const phone = 'wx_' + openid.slice(-8);

          db.run(
            'INSERT INTO users (role, name, phone, bind_code, wx_openid) VALUES (?, ?, ?, ?, ?)',
            ['family', '微信用户', phone, bindCode, openid],
            function(err) {
              try {
                if (err) return res.status(500).json({ error: err.message });
                const self = this;
                res.json({
                  success: true,
                  data: {
                    userId: self.lastID,
                    bindCode: bindCode,
                    boundUser: null
                  }
                });
              } catch (e) {
                console.error('[wx-login] insert error:', e);
                res.status(500).json({ error: '创建用户失败' });
              }
            }
          );
        }
      } catch (e) {
        console.error('[wx-login] db error:', e);
        res.status(500).json({ error: '数据库操作失败' });
      }
    });
  } catch (err) {
    console.error('[wx-login] Error:', err);
    res.status(500).json({ error: '微信登录请求失败，请稍后重试' });
  }
});

// 解除亲情绑定
router.post('/unbind', (req, res) => {
  const { userId } = req.body;
  if (!userId) {
    return res.status(400).json({ error: '参数缺失: userId' });
  }

  db.serialize(() => {
    db.run('BEGIN TRANSACTION');

    db.get('SELECT bound_user_id FROM users WHERE id = ?', [userId], (err, row) => {
      if (err) {
        db.run('ROLLBACK');
        return res.status(500).json({ error: err.message });
      }

      if (!row) {
        db.run('ROLLBACK');
        return res.status(404).json({ error: '用户不存在' });
      }

      const boundUserId = row.bound_user_id;

      db.run('UPDATE users SET bound_user_id = NULL WHERE id = ?', [userId], (err) => {
        if (err) {
          db.run('ROLLBACK');
          return res.status(500).json({ error: err.message });
        }

        if (boundUserId) {
          db.run('UPDATE users SET bound_user_id = NULL WHERE id = ?', [boundUserId], (err) => {
            if (err) {
              db.run('ROLLBACK');
              return res.status(500).json({ error: err.message });
            }
            db.run('COMMIT');
            res.json({ success: true, message: '已解除绑定' });
          });
        } else {
          db.run('COMMIT');
          res.json({ success: true, message: '已解除绑定' });
        }
      });
    });
  });
});

module.exports = router;
