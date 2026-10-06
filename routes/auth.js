const express = require('express');
const router = express.Router();
const db = require('../database/db');
const axios = require('axios');
const crypto = require('crypto');
const { issueToken, requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');

// 获取当前用户或老人/子女绑定状态（需登录态，且只能查本人或绑定老人的信息）
router.get('/user/:id', requireFamilyAuth, requireBoundElder, (req, res) => {
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

// 绑定亲情账号 (通过 bind_code)：userId 一律以登录态 token 身份为准，防止伪造
router.post('/bind', requireFamilyAuth, (req, res) => {
  const userId = req.authUserId;
  const { bindCode } = req.body;
  if (!bindCode) {
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
                  boundUser: boundUser || null,
                  token: issueToken(user.id)
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
                    boundUser: null,
                    token: issueToken(self.lastID)
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

// 账号密码注册（App 子女端）：username 存入唯一 phone 字段，密码 scrypt 加盐哈希
router.post('/register', (req, res) => {
  const { username, password, name } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '参数缺失: username / password' });
  }
  if (!/^[A-Za-z0-9_]{4,20}$/.test(username)) {
    return res.status(400).json({ error: '用户名需为 4-20 位字母、数字或下划线' });
  }
  if (String(password).length < 6) {
    return res.status(400).json({ error: '密码至少 6 位' });
  }

  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  const passwordHash = `${salt}:${hash}`;
  const bindCode = String(crypto.randomInt(100000, 1000000));

  db.get('SELECT id FROM users WHERE phone = ?', [username], (err, row) => {
    if (err) return res.status(500).json({ error: err.message });
    if (row) return res.status(409).json({ error: '该用户名已被注册' });

    db.run(
      'INSERT INTO users (role, name, phone, bind_code, password_hash) VALUES (?, ?, ?, ?, ?)',
      ['family', (name && String(name).trim()) || '守护人', username, bindCode, passwordHash],
      function(err) {
        if (err) return res.status(500).json({ error: err.message });
        const userId = this.lastID;
        console.log(`✅ App 子女端新注册用户 [ID: ${userId}, 用户名: ${username}]`);
        res.json({
          success: true,
          data: { userId, bindCode, boundUser: null, token: issueToken(userId) }
        });
      }
    );
  });
});

// 账号密码登录（App 子女端）：签发与 wx-login 同一套 Token
router.post('/login', (req, res) => {
  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '参数缺失: username / password' });
  }

  db.get('SELECT * FROM users WHERE phone = ?', [username], (err, user) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!user || !user.password_hash) {
      return res.status(401).json({ error: '用户名或密码错误' });
    }

    const [salt, hash] = String(user.password_hash).split(':');
    let calc;
    try {
      calc = crypto.scryptSync(String(password), salt, 64).toString('hex');
    } catch (e) {
      return res.status(500).json({ error: '密码校验失败' });
    }
    if (calc !== hash) {
      return res.status(401).json({ error: '用户名或密码错误' });
    }

    db.get('SELECT * FROM users WHERE id = ?', [user.bound_user_id], (err, boundUser) => {
      try {
        if (err) return res.status(500).json({ error: err.message });
        res.json({
          success: true,
          data: {
            userId: user.id,
            bindCode: user.bind_code,
            boundUser: boundUser || null,
            token: issueToken(user.id)
          }
        });
      } catch (e) {
        res.status(500).json({ error: '查询绑定用户失败' });
      }
    });
  });
});

// 解除亲情绑定：userId 一律以登录态 token 身份为准
router.post('/unbind', requireFamilyAuth, (req, res) => {
  const userId = req.authUserId;

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

// 老人端绑定码同步：服务器是绑定码唯一事实源
// 不带 bindCode → 查询该老人当前绑定码；带 bindCode（6 位数字）→ 刷新为指定码
// 信任模型与 /api/events/report 一致（老人端免登录，以 elderId 标识）
router.post('/elder-bind-code', (req, res) => {
  const elderId = parseInt(req.body && req.body.elderId, 10);
  const newCode = req.body && req.body.bindCode ? String(req.body.bindCode) : null;

  if (!elderId || elderId <= 0) {
    return res.status(400).json({ error: '参数缺失: elderId' });
  }
  if (newCode && !/^\d{6}$/.test(newCode)) {
    return res.status(400).json({ error: '绑定码需为 6 位数字' });
  }

  db.get("SELECT id, bind_code FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });

    // 仅查询：返回服务器当前绑定码
    if (!newCode) {
      return res.json({ success: true, bindCode: elder.bind_code || '' });
    }

    // 刷新：先确保新码不与其他用户冲突
    db.get('SELECT id FROM users WHERE bind_code = ? AND id != ?', [newCode, elderId], (err, conflict) => {
      if (err) return res.status(500).json({ error: err.message });
      if (conflict) {
        return res.status(409).json({ error: '该绑定码已被占用，请重新生成' });
      }
      db.run('UPDATE users SET bind_code = ? WHERE id = ?', [newCode, elderId], (err) => {
        if (err) return res.status(500).json({ error: err.message });
        console.log(`🔑 老人端 [ID: ${elderId}] 绑定码已同步刷新为 ${newCode}`);
        res.json({ success: true, bindCode: newCode });
      });
    });
  });
});

module.exports = router;
