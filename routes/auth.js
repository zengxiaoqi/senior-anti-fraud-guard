const express = require('express');
const router = express.Router();
const db = require('../database/db');
const axios = require('axios');
const crypto = require('crypto');
const { issueToken, requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');

// 中国大陆手机号格式校验
const MOBILE_RE = /^1[3-9]\d{9}$/;

// 清洗用户输入：去除首尾空白 + 粘贴夹带的零宽字符/软连字符等不可见字符
// （这些字符 trim 去不掉，会导致"看着没错却报格式错误"）
function cleanIdentifier(s) {
  return String(s || '')
    .trim()
    .replace(/[\u200B-\u200D\uFEFF\u2060\u00AD]/g, '');
}

/** 生成 6 位不冲突的绑定码 */
function generateUniqueBindCode(cb) {
  const code = String(crypto.randomInt(100000, 1000000));
  db.get('SELECT id FROM users WHERE bind_code = ?', [code], (err, row) => {
    if (err) return cb(err);
    if (row) return generateUniqueBindCode(cb);
    cb(null, code);
  });
}

/**
 * 老人端账号状态统一回执。
 *
 * 为什么所有登记分支都必须走这里：老人换手机（新设备 / 重装）时本地 SharedPreferences
 * 是全新的，绑定码、守护人姓名手机号、防护规则阈值全部为空。如果登记接口只回
 * elderId + bindCode，客户端就只能靠二次请求补齐，而那一次失败或慢了就永远显示
 * "未绑定" —— 用户的真实状态其实是服务端 bound_user_id 有值。
 *
 * 所以这里把"服务端此刻知道的关于这个老人的全部事实"一次带全：
 * bindCode（绑定码）+ boundFamily（守护人）+ guardSettings（防护规则）。
 * 新设备登记完立刻就是完整的，不需要再赌一次网络往返。
 */
function replyElderState(res, elderId, extra = {}) {
  db.get('SELECT id, name, phone, bind_code, bound_user_id, guard_settings FROM users WHERE id = ?',
    [elderId], (err, elder) => {
      if (err) return res.status(500).json({ error: err.message });
      if (!elder) return res.status(404).json({ error: '老人账号不存在' });

      const payload = Object.assign({
        success: true,
        elderId: elder.id,
        bindCode: elder.bind_code || '',
        name: elder.name,
        phone: elder.phone
      }, extra);

      // 未绑定时明确回 null，客户端据此清掉本地的残留缓存，
      // 而不是"字段缺失"——两者在客户端要走的处理路径不同。
      if (!elder.bound_user_id) {
        payload.boundFamily = null;
        payload.guardSettings = parseGuardSettings(elder.guard_settings);
        return res.json(payload);
      }
      db.get('SELECT id, name, phone, mobile FROM users WHERE id = ?', [elder.bound_user_id], (err, fam) => {
        if (err) return res.status(500).json({ error: err.message });
        // phone 在子女端存的是用户名，真实号码在 mobile；统一抹平让客户端只认一个字段
        if (fam) fam.phone = fam.mobile || fam.phone;
        payload.boundFamily = fam || null;
        payload.guardSettings = parseGuardSettings(elder.guard_settings);
        res.json(payload);
      });
    });
}

/**
 * 解析 guard_settings：脏数据一律当"没有配置"处理，
 * 不能让一行坏 JSON 把整个登记接口变成 500 —— 配置丢了还能用默认值，
 * 接口报错则连账号都登不上。
 */
function parseGuardSettings(raw) {
  if (!raw) return null;
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return (obj && typeof obj === 'object') ? obj : null;
  } catch (e) {
    console.warn('⚠️ guard_settings 解析失败，按无配置处理:', raw.slice(0, 120));
    return null;
  }
}

/** 归一化防护规则配置：逐项做类型与范围校验，非法值一律丢弃用默认值 */
const GUARD_SETTING_BOUNDS = {
  callThresholdMinutes: { min: 1, max: 240, def: 15 },
  paymentThreshold:     { min: 1, max: 1000000, def: 500 },
  recordingMaxSegments: { min: 1, max: 6, def: 3 },
  recordingAutoUpload:  { bool: true, def: true },
  // Phase 1：位置阈值（1-10）与家基准（1-9，子女端显式设置后随配置通道下发）
  homeAwayRadiusMeters: { min: 100, max: 5000, def: 500 },
  stayMoveMeters:       { min: 20, max: 1000, def: 100 },
  homeStayMinutes:      { min: 5, max: 240, def: 40 },
  homeLat:              { min: -90, max: 90 },
  homeLng:              { min: -180, max: 180 }
};

function sanitizeGuardSettings(input) {
  const out = {};
  if (!input || typeof input !== 'object') return out;

  // 字符串型配置（1-5 信任列表 / 1-1 高危 App 远程规则）：只做类型与长度校验，
  // 内容原样透传 —— 客户端解析失败有自己的兜底，不在传输层做深度解析卡死
  for (const key of ['trustedCallNumbersJson', 'highRiskPackages']) {
    const v = input[key];
    if (typeof v === 'string' && v.length <= 20000 && v.trimStart().startsWith('[')) {
      out[key] = v;
    }
  }

  for (const [key, bound] of Object.entries(GUARD_SETTING_BOUNDS)) {
    if (input[key] === undefined || input[key] === null) continue;
    if (bound.bool) {
      if (typeof input[key] === 'boolean') out[key] = input[key];
      continue;
    }
    const num = Number(input[key]);
    if (!Number.isFinite(num)) continue;
    out[key] = Math.min(bound.max, Math.max(bound.min, num));
  }
  return out;
}

// 获取当前用户或老人/子女绑定状态（需登录态，且只能查本人或绑定老人的信息）
router.get('/user/:id', requireFamilyAuth, requireBoundElder, (req, res) => {
  const userId = req.params.id;
  db.get(`SELECT u.*, b.name as bound_name, b.phone as bound_phone,
                 CASE WHEN u.mobile IS NOT NULL AND u.mobile != '' THEN u.mobile
                      WHEN u.phone IS NOT NULL AND u.phone != '' THEN u.phone
                      ELSE '' END as display_phone
          FROM users u
          LEFT JOIN users b ON u.bound_user_id = b.id
          WHERE u.id = ?`, [userId], (err, user) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!user) return res.status(404).json({ error: '用户不存在' });
    // SQLite 的 NULL 会被序列化成 JSON null，Android 端 optString() 会读成字符串 "null"，
    // 导致界面显示「老人手机 null」。这里统一抹平成空串，客户端只需判空。
    if (user.mobile === null) user.mobile = '';
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
                  // 一并下发本人手机号与是否缺失，省得小程序/App 再请求一次就能渲染"改手机号"入口
                  mobile: user.mobile || '',
                  mobileMissing: !user.mobile,
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
                    // 新建号必然没有手机号，客户端据此提示补录
                    mobile: '',
                    mobileMissing: true,
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
// phone（真实手机号）为必填，存入 mobile 字段（phone 字段被 username 占用）
router.post('/register', (req, res) => {
  const { password, name, phone } = req.body;
  const username = cleanIdentifier(req.body.username);
  if (!username || !password || !phone) {
    return res.status(400).json({ error: '参数缺失: username / password / phone' });
  }
  if (!/^[A-Za-z0-9_]{4,20}$/.test(username)) {
    return res.status(400).json({ error: '用户名需为 4-20 位字母、数字或下划线' });
  }
  if (String(password).length < 6) {
    return res.status(400).json({ error: '密码至少 6 位' });
  }
  const mobile = String(phone).trim();
  if (!MOBILE_RE.test(mobile)) {
    return res.status(400).json({ error: '手机号格式不正确（需 11 位大陆手机号）' });
  }

  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  const passwordHash = `${salt}:${hash}`;
  const bindCode = String(crypto.randomInt(100000, 1000000));

  db.get('SELECT id FROM users WHERE phone = ?', [username], (err, row) => {
    if (err) return res.status(500).json({ error: err.message });
    if (row) return res.status(409).json({ error: '该用户名已被注册' });

    // 真实手机号不能与其他账号重复
    db.get('SELECT id, role FROM users WHERE mobile = ? OR (role = ? AND phone = ?)', [mobile, 'elder', mobile], (err, dup) => {
      if (err) return res.status(500).json({ error: err.message });
      if (dup) return res.status(409).json({ error: '该手机号已被其他账号使用' });

      db.run(
        'INSERT INTO users (role, name, phone, mobile, bind_code, password_hash) VALUES (?, ?, ?, ?, ?, ?)',
        ['family', (name && String(name).trim()) || '守护人', username, mobile, bindCode, passwordHash],
        function(err) {
          if (err) return res.status(500).json({ error: err.message });
          const userId = this.lastID;
          console.log(`✅ App 子女端新注册用户 [ID: ${userId}, 用户名: ${username}, 手机号: ${mobile}]`);
          res.json({
            success: true,
            data: { userId, bindCode, mobile, mobileMissing: false, boundUser: null, token: issueToken(userId) }
          });
        }
      );
    });
  });
});

// 完善/修改本人手机号（需登录态）
// 背景：注册时手机号已是必填，但历史账号（微信登录自动建号、早期版本注册）
//       的 mobile 可能为空，导致老人端紧急警报无法一键拨给子女，这里提供补录入口。
router.post('/profile-mobile', requireFamilyAuth, (req, res) => {
  const mobile = cleanIdentifier(req.body && req.body.phone);
  if (!mobile) {
    return res.status(400).json({ error: '参数缺失: phone' });
  }
  if (!MOBILE_RE.test(mobile)) {
    return res.status(400).json({ error: '手机号格式不正确（需 11 位大陆手机号）' });
  }

  const userId = req.authUserId;

  // 手机号全局唯一：不能与任何账号的 mobile 撞，也不能占用他人已登记的号码
  db.get('SELECT id FROM users WHERE mobile = ? AND id != ?', [mobile, userId], (err, dup) => {
    if (err) return res.status(500).json({ error: err.message });
    if (dup) return res.status(409).json({ error: '该手机号已被其他账号使用' });

    db.run('UPDATE users SET mobile = ? WHERE id = ?', [mobile, userId], function(err) {
      if (err) return res.status(500).json({ error: err.message });
      if (this.changes === 0) return res.status(404).json({ error: '账号不存在' });

      console.log(`📱 用户 [ID: ${userId}] 已完善手机号: ${mobile}`);
      res.json({ success: true, mobile });
    });
  });
});

// 账号密码登录（App 子女端）：支持用户名或手机号任一方式，签发与 wx-login 同一套 Token
router.post('/login', (req, res) => {
  const { password } = req.body;
  const identifier = cleanIdentifier(req.body.username); // 兼容用户名或手机号
  if (!identifier || !password) {
    return res.status(400).json({ error: '参数缺失: username / password' });
  }

  // phone 字段存用户名，mobile 字段存真实手机号，两者都可以作为登录标识
  db.get('SELECT * FROM users WHERE (phone = ? OR mobile = ?) AND password_hash IS NOT NULL', [identifier, identifier], (err, user) => {
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
            // 真实手机号与是否已完善：客户端据此提示补录（缺号会导致老人端一键拨号失效）
            mobile: user.mobile || '',
            mobileMissing: !user.mobile,
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

// 老人端账号激活/注册：长辈端 App 首次使用时提交姓名+手机号，服务器创建（或找回）elder 账号
// elderId 缺省 0；已激活过的设备带 elderId 回来则为修改资料（改名/改手机号）
//
// previousPhone（可选）是"这台设备上一次登记过的手机号"，只在 elderId=0 时用于识别换号：
// 老人换了手机号 + 本地会话已丢（卸载重装/退出登录清会话）时，仅凭新号会新建账号，
// 导致绑定关系、历史录音、风险事件全部孤立，子女端再也看不到这个老人。
// 带上旧号就能定位到原账号，把号换过去，而不是另起一个。
router.post('/elder-register', (req, res) => {
  const elderId = parseInt(req.body && req.body.elderId, 10) || 0;
  const name = (req.body && req.body.name ? String(req.body.name) : '').trim();
  const mobile = (req.body && req.body.phone ? String(req.body.phone) : '').trim();
  const previousPhone = cleanIdentifier(req.body && req.body.previousPhone);

  // 姓名允许为空：只要手机号能命中已有 elder 账号（找回/重装/换机场景），
  // 就沿用服务器上的原姓名（下方各 UPDATE 都用 COALESCE(NULLIF(?,''), name) 兜底），
  // 这样长辈端"已绑定过再进入"只需填手机号、不用把姓名再敲一遍。
  // 只有"全新手机号 + 空姓名"才拒绝（见 createFreshAccount），防止建出无名账号。
  if (!MOBILE_RE.test(mobile)) {
    return res.status(400).json({ error: '手机号格式不正确（需 11 位大陆手机号）' });
  }

  // ── 已激活设备：更新资料 ──
  if (elderId > 0) {
    db.get("SELECT id, bind_code FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
      if (err) return res.status(500).json({ error: err.message });
      if (!elder) return res.status(404).json({ error: '老人账号不存在' });

      db.get('SELECT id FROM users WHERE (mobile = ? OR (role = ? AND phone = ?)) AND id != ?', [mobile, 'elder', mobile, elderId], (err, dup) => {
        if (err) return res.status(500).json({ error: err.message });
        if (dup) return res.status(409).json({ error: '该手机号已被其他账号使用' });

        db.run('UPDATE users SET name = COALESCE(NULLIF(?, \'\'), name), phone = ? WHERE id = ?', [name, mobile, elderId], (err) => {
          if (err) return res.status(500).json({ error: err.message });
          console.log(`🧓 老人端 [ID: ${elderId}] 资料已更新: ${name} / ${mobile}`);
          replyElderState(res, elderId);
        });
      });
    });
    return;
  }

  // ── 首次激活 ──

  // ── 换号找回（必须优先于按新号认领） ──
  // 设备带着 previousPhone 回来，说明它是一台"记得自己老号"的设备，
  // 这个身份线索比新号更可信 —— 若先按新号去认领，一旦新号已被别人占用，
  // 就会把别人的账号当成自己的返回（冒用他人绑定关系），所以这里要抢在前面。
  if (previousPhone && previousPhone !== mobile && MOBILE_RE.test(previousPhone)) {
    db.get("SELECT id, bind_code FROM users WHERE role = 'elder' AND (phone = ? OR mobile = ?)", [previousPhone, previousPhone], (err, prev) => {
      if (err) return res.status(500).json({ error: err.message });
      // 旧号查无此账号 → 没有可继承的身份，按全新号正常注册
      if (!prev) return activateByNewPhone(name, mobile);

      // 新号已被别的账号占用：不能抢占，如实报错让用户去用原账号
      db.get('SELECT id FROM users WHERE (mobile = ? OR phone = ?) AND id != ?', [mobile, mobile, prev.id], (err, dup) => {
        if (err) return res.status(500).json({ error: err.message });
        if (dup) return res.status(409).json({ error: '该手机号已被其他账号使用' });

        // 复用原账号：只改号，bound_user_id / bind_code / 历史记录一律不动
        db.run("UPDATE users SET name = COALESCE(NULLIF(?, ''), name), phone = ?, mobile = NULL WHERE id = ?", [name, mobile, prev.id], (err) => {
          if (err) return res.status(500).json({ error: err.message });
          console.log(`📞 老人端换号找回 [ID: ${prev.id}] ${previousPhone} → ${mobile}（保留绑定关系与历史记录）`);
          replyElderState(res, prev.id, { recoveredByPhoneChange: true });
        });
      });
    });
    return;
  }

  activateByNewPhone(name, mobile);

  /** 按新号激活：号已存在则认领原账号（卸载重装/换机），否则新建 */
  function activateByNewPhone(name, mobile) {
    // 手机号已存在且是 elder 账号 → 视为账号找回（如卸载重装/换机），直接返回原账号
    db.get("SELECT id, bind_code FROM users WHERE role = 'elder' AND (phone = ? OR mobile = ?)", [mobile, mobile], (err, existing) => {
      if (err) return res.status(500).json({ error: err.message });

      if (existing) {
        db.run('UPDATE users SET name = COALESCE(NULLIF(?, \'\'), name) WHERE id = ?', [name, existing.id], (err) => {
          if (err) return res.status(500).json({ error: err.message });
          console.log(`🧓 老人端账号找回 [ID: ${existing.id}] 手机号: ${mobile}`);
          // 换机/重装走这里：绑定关系与防护规则本来就在服务端账号上，原样带回
          replyElderState(res, existing.id, { recoveredExistingAccount: true });
        });
        return;
      }

      createFreshAccount(name, mobile);
    });
  }

  /** 新建 elder 账号（手机号确认为全新）：先校验占用，再生成不冲突的绑定码 */
  function createFreshAccount(name, mobile) {
    // 全新账号必须留名：空名账号会让子女端、紧急拨号、录音列表全是空白记录。
    // 找回路径（手机号命中已有账号）不受此限 —— COALESCE 会沿用服务器上的原姓名
    if (!name) return res.status(400).json({ error: '新账号需要填写老人姓名' });
    db.get('SELECT id FROM users WHERE mobile = ? OR phone = ?', [mobile, mobile], (err, dup) => {
      if (err) return res.status(500).json({ error: err.message });
      if (dup) return res.status(409).json({ error: '该手机号已被其他账号使用' });

      generateUniqueBindCode((err, bindCode) => {
        if (err) return res.status(500).json({ error: err.message });
        db.run(
          "INSERT INTO users (role, name, phone, bind_code) VALUES ('elder', ?, ?, ?)",
          [name, mobile, bindCode],
          function(err) {
            if (err) return res.status(500).json({ error: err.message });
            const newId = this.lastID;
            console.log(`🧓 老人端新账号激活 [ID: ${newId}, 姓名: ${name}, 手机号: ${mobile}, 绑定码: ${bindCode}]`);
            replyElderState(res, newId);
          }
        );
      });
    });
  }
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

  db.get("SELECT id, bind_code, bound_user_id FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });

    // 统一返回：绑定码 + 已绑定守护人（姓名/手机号）+ 防护规则，供长辈端界面展示
    // 与 /elder-register 共用 replyElderState，保证两条路径拿到的字段完全一致 ——
    // 之前这里自己拼一份、那里拼一份，两边字段不同就会表现为"某些页面有数据某些没有"
    const replyWithFamily = (bindCode) => {
      replyElderState(res, elderId, { bindCode });
    };

    // 仅查询：返回服务器当前绑定码
    if (!newCode) {
      return replyWithFamily(elder.bind_code || '');
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
        replyWithFamily(newCode);
      });
    });
  });
});

// 老人端防护规则配置（通话预警时长 / 支付预警金额 / 录音段数 / 自动上传）的云端同步。
//
// 为什么以前不在这儿：这些配置只存本机 SharedPreferences，于是"老人换手机"这件事
// 会静默把它们打回默认值 —— 用户在老手机上设的"通话超 10 分钟就告警"，新手机上
// 无声失效，界面上还一切正常。这跟绑定关系是同一类问题：凡是"用户在意的设置"，
// 事实源都必须在服务端账号上，而不是本机。
//
// 信任模型与 /api/events/report 一致（老人端免登录，以 elderId 标识）。
// GET  → 拉取配置（换机后自动恢复）
// POST → 保存配置（改设置时立刻上行，避免"改了只在本机生效"的错觉）
router.get('/elder-settings', (req, res) => {
  const elderId = parseInt(req.query.elderId, 10);
  if (!elderId || elderId <= 0) return res.status(400).json({ error: '参数缺失: elderId' });

  db.get("SELECT id, guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });
    res.json({ success: true, settings: parseGuardSettings(elder.guard_settings) });
  });
});

router.post('/elder-settings', (req, res) => {
  const elderId = parseInt(req.body && req.body.elderId, 10);
  if (!elderId || elderId <= 0) return res.status(400).json({ error: '参数缺失: elderId' });

  // 逐项校验范围，非法的直接丢掉而不是整包拒绝：老人改了某个值就想保存，
  // 不能因为另一个字段脏了就整次保存失败（那会让人以为设置没生效而反复重试）
  const clean = sanitizeGuardSettings(req.body && req.body.settings);
  if (Object.keys(clean).length === 0) {
    return res.status(400).json({ error: '没有可保存的配置项' });
  }

  db.get("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });

    // 增量合并而不是整体覆盖：多端（老人端 App / 未来子女端代改）可以各改各的字段，
    // 整体覆盖会把对方刚改的项无声冲掉。
    const merged = Object.assign({}, parseGuardSettings(elder.guard_settings) || {}, clean);

    db.run('UPDATE users SET guard_settings = ? WHERE id = ?',
      [JSON.stringify(merged), elderId], (err) => {
        if (err) return res.status(500).json({ error: err.message });
        console.log(`⚙️ 老人端 [ID: ${elderId}] 防护规则已同步: ${JSON.stringify(merged)}`);
        res.json({ success: true, settings: merged });
      });
  });
});

module.exports = router;
