const express = require('express');
const router = express.Router();
const db = require('../database/db');
const axios = require('axios');
const crypto = require('crypto');
const { issueToken, requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const {
  sanitizeGuardSettings, extractHomeCleared,
  pickFamilyWritable, mergeFamilySettings
} = require('../services/guardSettingsPolicy');
const bindings = require('../services/bindings');

// 由 server.js 注入的回调集合（setHub）
let hubRef = {};

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

      // 多人绑定：boundFamilies = 全部 ACTIVE 守护人（主守护人在前）；
      // boundFamily 兼容字段 = 主守护人单对象（旧客户端只认它）。
      // 未绑定时明确回 null/空数组，客户端据此清掉本地的残留缓存，
      // 而不是"字段缺失"——两者在客户端要走的处理路径不同。
      bindings.listFamiliesOfElder(db, elderId, (err2, fams) => {
        if (err2) {
          console.error('replyElderState 查询守护人列表失败:', err2.message);
          fams = [];
        }
        const list = (fams || []).map((f) => ({
          id: f.id,
          name: f.name,
          phone: f.mobile || f.phone, // phone 在子女端存的是用户名，真实号码在 mobile；统一抹平
          isPrimary: !!f.is_primary
        }));
        payload.boundFamilies = list;
        payload.boundFamily = list.length ? list[0] : null;
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

/**
 * 子女视角绑定列表：全部 ACTIVE 老人（最早绑定在前）。
 * 登录/绑定/用户信息回执统一从这里取 boundUsers 数组；
 * boundUser 兼容字段 = 第一条（最早绑定的老人，与 family.bound_user_id 缓存同源）。
 */
function fetchBoundElders(userId, cb) {
  bindings.listEldersOfFamily(db, userId, (err, rows) => {
    if (err) return cb(err);
    cb(null, (rows || []).map((r) => ({
      id: r.id,
      name: r.name,
      phone: r.phone,
      bindCode: r.bind_code,
      isPrimary: !!r.is_primary,
      boundAt: r.created_at
    })));
  });
}

/** 给登录/关联回执补上 boundUsers 数组（boundUser 主字段由调用方自带） */
function withBoundUsers(data, userId, cb) {
  fetchBoundElders(userId, (err, list) => {
    // 查询失败不阻塞登录：列表降级为空数组，boundUser 主字段照常返回
    if (err) console.warn('⚠️ boundUsers 查询失败，按空列表处理:', err.message);
    data.boundUsers = err ? [] : list;
    cb(data);
  });
}

/**
 * 子女端登录/微信关联成功后的会话回执（/wx-login、/login、replyWxSession 共用）。
 * 回执形状：
 *  - boundUser：兼容字段 = 最早绑定的老人全行（旧客户端唯一认的字段）
 *  - boundUsers：全部 ACTIVE 老人列表（多人绑定的列表来源，新客户端优先读它）
 *  - mobile / mobileMissing / token：与旧版一致；extra 可注入 hasPassword 等
 */
function replyFamilySession(res, user, extra = {}) {
  fetchBoundElders(user.id, (err, list) => {
    if (err) {
      console.warn('⚠️ 会话回执 boundUsers 查询失败，按空列表处理:', err.message);
      list = [];
    }
    const primary = list.length ? list[0] : null;
    const finish = (boundUser) => {
      res.json({
        success: true,
        data: Object.assign({
          userId: user.id,
          bindCode: user.bind_code,
          boundUser: boundUser || null,
          boundUsers: list,
          // 一并下发本人手机号与是否缺失，省得小程序/App 再请求一次就能渲染"改手机号"入口
          mobile: user.mobile || '',
          mobileMissing: !user.mobile,
          token: issueToken(user.id)
        }, extra)
      });
    };
    if (!primary) return finish(null);
    db.get('SELECT id, role, name, phone, mobile FROM users WHERE id = ?', [primary.id], (err2, row) => {
      finish(err2 ? null : (row || null));
    });
  });
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
    // 子女视角附上全量绑定列表：客户端 onResume 刷新时同步多老人列表。
    // ⚠️ 只有目标是 family 账号才附 bound_elders —— 目标是 elder 时这个
    // 方向的查询恒为空数组，而客户端把「显式空数组」当成清空会话，
    // 会把子女端的绑定缓存误清掉（v1.8.0 发版验收实证）。宁缺勿错。
    if (user.role === 'family') {
      fetchBoundElders(user.id, (err2, list) => {
        const data = Object.assign({}, user, { bound_elders: err2 ? [] : (list || []) });
        res.json({ success: true, data });
      });
    } else {
      res.json({ success: true, data: user });
    }
  });
});

// 子女视角：我绑定的全部老人（多人绑定列表页数据源，含失联状态摘要）
router.get('/my-elders', requireFamilyAuth, (req, res) => {
  const userId = req.authUserId;
  db.all(
    `SELECT e.id, e.name, e.phone, e.bind_code, b.is_primary, b.created_at AS bound_at,
            lv.state AS liveness_state, lv.last_report_at AS last_report_at
       FROM bindings b
       JOIN users e ON e.id = b.elder_id
       LEFT JOIN elder_liveness lv ON lv.elder_id = e.id
      WHERE b.family_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.id ASC`,
    [userId],
    (err, rows) => {
      if (err) return res.status(500).json({ error: err.message });
      res.json({
        success: true,
        data: (rows || []).map((r) => ({
          id: r.id,
          name: r.name,
          phone: r.phone,
          bindCode: r.bind_code,
          isPrimary: !!r.is_primary,
          boundAt: r.bound_at,
          livenessState: r.liveness_state || null,
          lastReportAt: r.last_report_at || null
        }))
      });
    }
  );
});

// 绑定亲情账号（userId 一律以登录态 token 身份为准，防止伪造）。
// 两种凭据任传其一：bindCode（老人端 6 位绑定码）或 phone（老人登记手机号）。
// 手机号绑定是便利入口：elder 的真实号统一存 phone 列，mobile 兼容匹配历史数据；
// 必须限定 role='elder'，否则子女账号的 username（存 phone 列）可能被误命中。
//
// 多人绑定语义（docs/multi-binding-design.md）：追加式而非覆盖式——
// 子女可绑多个老人、老人可被多个子女绑，重复绑同一对幂等成功；
// 容量上限防滥用（services/bindings.js 常量）。
router.post('/bind', requireFamilyAuth, (req, res) => {
  const userId = req.authUserId;
  const { bindCode } = req.body;
  // 手机号归一化：全角数字→半角 + 去空白/连字符（中文输入法全角是高频坑，服务端兜一层）
  const phone = String((req.body && req.body.phone) || '')
    .trim()
    .replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xFEE0))
    .replace(/[\s\-－]/g, '');

  if (!bindCode && !phone) {
    return res.status(400).json({ error: '参数缺失' });
  }
  if (phone && !MOBILE_RE.test(phone)) {
    return res.status(400).json({ error: '手机号格式不正确（需 11 位大陆手机号）' });
  }

  // 查找符合凭据的目标用户。绑定码路径也必须限定 role='elder'：
  // 子女的绑定码是给别人绑自己（family）用的，不是绑定凭据，误命中会造成 family↔family 非法绑定
  const lookup = (cb) => {
    if (bindCode) {
      db.get("SELECT * FROM users WHERE bind_code = ? AND role = 'elder'", [bindCode], cb);
    } else {
      db.get("SELECT * FROM users WHERE role = 'elder' AND (phone = ? OR mobile = ?)", [phone, phone], cb);
    }
  };
  lookup((err, targetUser) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!targetUser) {
      // 错误文案区分两种方式，别让"手机号没登记"被误读成"绑定码输错了"
      return res.status(404).json({ error: bindCode ? '绑定码无效' : '未找到该手机号登记的老人账号' });
    }
    if (targetUser.id === userId) {
      return res.status(400).json({ error: '不能绑定自己的账号' });
    }

    // 容量上限：双侧都查，防一个老人被无限绑/一个子女无限囤老人
    bindings.countFamiliesOfElder(db, targetUser.id, (err, famCount) => {
      if (err) return res.status(500).json({ error: err.message });
      if (famCount >= bindings.MAX_FAMILIES_PER_ELDER) {
        return res.status(409).json({ error: `该老人的守护人已达上限（${bindings.MAX_FAMILIES_PER_ELDER} 位）` });
      }
      bindings.countEldersOfFamily(db, userId, (err, elderCount) => {
        if (err) return res.status(500).json({ error: err.message });
        if (elderCount >= bindings.MAX_ELDERS_PER_FAMILY) {
          return res.status(409).json({ error: `你已绑定 ${bindings.MAX_ELDERS_PER_FAMILY} 位老人，达到上限` });
        }

        bindings.addBinding(db, targetUser.id, userId, (err2, result) => {
          if (err2) return res.status(500).json({ error: err2.message });
          console.log(`👨‍👩‍👧 子女 [ID: ${userId}] 绑定老人 [ID: ${targetUser.id}]（${result.already ? '重复绑定幂等' : '新增'}，主守护人: ${result.isPrimary ? '是' : '否'}）`);

          // 响应形状：boundUser 兼容字段=本次绑定的对象（只回安全字段，不吐 password_hash/bind_code）；
          // boundList=登录用户当前全部 ACTIVE 老人（多老人切换的列表来源）
          const safeTarget = {
            id: targetUser.id,
            role: targetUser.role,
            name: targetUser.name,
            phone: targetUser.phone,
            mobile: targetUser.mobile || null
          };
          fetchBoundElders(userId, (err3, list) => {
            if (err3) list = [];
            res.json({
              success: true,
              message: result.already ? '该老人已在你的守护列表中' : '亲情绑定成功',
              boundUser: safeTarget,
              boundList: list || []
            });
          });
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
          replyFamilySession(res, user, {
            // 是否账密账号：false = 微信自动建的空账号，小程序据此显示"关联 App 账号"入口
            hasPassword: !!user.password_hash
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
                    boundUsers: [],
                    // 新建号必然没有手机号，客户端据此提示补录
                    mobile: '',
                    mobileMissing: true,
                    hasPassword: false,
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
            data: { userId, bindCode, mobile, mobileMissing: false, boundUser: null, boundUsers: [], token: issueToken(userId) }
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

  // 手机号全局唯一：不能与任何账号的 mobile 撞，也不能占用他人已登记的号码。
  // elder 账号的真实号存 phone 列（mobile 恒 NULL），必须一并比对，
  // 否则把老人 App 端登记的号填进"我的手机号"能静默通过 —— 老人端紧急回拨会拨给自己的号
  db.get(
    'SELECT id, role FROM users WHERE (mobile = ? OR (role = ? AND phone = ?)) AND id != ?',
    [mobile, 'elder', mobile, userId],
    (err, dup) => {
      if (err) return res.status(500).json({ error: err.message });
      if (dup) {
        const isElderDup = dup.role === 'elder';
        return res.status(409).json({
          error: isElderDup
            ? '这是老人端登记的手机号，请填写子女自己的号码（回拨号码不能与老人重复）'
            : '该手机号已被其他账号使用'
        });
      }

      db.run('UPDATE users SET mobile = ? WHERE id = ?', [mobile, userId], function(err) {
        if (err) return res.status(500).json({ error: err.message });
        if (this.changes === 0) return res.status(404).json({ error: '账号不存在' });

        console.log(`📱 用户 [ID: ${userId}] 已完善手机号: ${mobile}`);
        res.json({ success: true, mobile });
      });
    }
  );
});

// ── 微信端关联已有 App 端账密账号 ─────────────────────────────
// 背景：微信登录自动建号是独立空账号（phone=wx_xxx、mobile 空），子女在
// App 端注册过的话，两边手机号/绑定关系完全割裂。这里用「用户名+密码」
// 验证身份后把 wx_openid 挂到 App 账号上：微信端从此复用同一身份，
// 手机号、绑定关系、绑定码全部同步，不用重复填。
//
// 凭据错误返回 403 而不是 401：小程序请求封装把 401 一律当"登录态失效
// 静默重登录"，凭据错误走 401 会被吞掉，用户只看到转圈。
router.post('/wx-link', requireFamilyAuth, (req, res) => {
  const username = cleanIdentifier(req.body && req.body.username);
  const password = req.body && req.body.password;
  if (!username || !password) {
    return res.status(400).json({ error: '参数缺失: username / password' });
  }
  const userId = req.authUserId;

  db.get('SELECT * FROM users WHERE id = ?', [userId], (err, me) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!me) return res.status(404).json({ error: '账号不存在' });
    if (me.password_hash) {
      return res.status(409).json({ error: '当前已经是 App 端账号，无需关联' });
    }
    if (!me.wx_openid) {
      return res.status(409).json({ error: '当前不是微信登录账号，无需关联' });
    }

    db.get('SELECT * FROM users WHERE (phone = ? OR mobile = ?) AND password_hash IS NOT NULL', [username, username], (err, target) => {
      if (err) return res.status(500).json({ error: err.message });
      // 与 /login 同口径：不区分"账号不存在"和"密码错误"，防探测
      if (!target) return res.status(403).json({ error: '用户名或密码错误' });
      const [salt, hash] = String(target.password_hash).split(':');
      let calc;
      try {
        calc = crypto.scryptSync(String(password), salt, 64).toString('hex');
      } catch (e) {
        return res.status(500).json({ error: '密码校验失败' });
      }
      if (calc !== hash) return res.status(403).json({ error: '用户名或密码错误' });
      if (target.wx_openid) {
        return res.status(409).json({ error: '该 App 账号已关联过微信，直接用微信登录即可' });
      }
      // 多人绑定后不再限制"两端绑了不同老人"：绑定集合做并集合并（同 elder 去重），
      // 原来的 409 拦截会让用户被迫来回解绑，而合并语义天然无冲突。

      // 1. 绑定集合迁移：占位微信号的全部 ACTIVE 绑定整体并进 App 账号（UNIQUE 去重，
      //    同 elder 两端都绑过只保留 App 账号那条），随后占位号绑定行删除、
      //    无主老人自动补选主守护人。事务语义在 migrateBindings 内部保证。
      bindings.migrateBindings(db, userId, target.id, (migErr) => {
        if (migErr) return res.status(500).json({ error: migErr.message });

        // 2. 身份合并 + 派生列重算（独立事务，只写 users 表）
        const stmts = [
          // 微信身份挂到 App 账号上：微信端从此复用同一身份
          ['UPDATE users SET wx_openid = ? WHERE id = ?', [me.wx_openid, target.id]],
          // ⭐ elder.bound_user_id 全量重算（主守护人，无主则取最早绑定）。
          // 覆盖所有被迁移涉及的老人：原来指占位号、指第三方主守护人、NULL 的都归位。
          // 这条取代了旧版"无条件回指兜底 UPDATE"——表结构变成 bindings 后，
          // 回指错人的形态不复存在，但重算必须覆盖到才行（2026-10-19 事故教训）。
          [`UPDATE users SET bound_user_id = COALESCE(
              (SELECT family_id FROM bindings WHERE elder_id = users.id AND status = 'ACTIVE' AND is_primary = 1 LIMIT 1),
              (SELECT family_id FROM bindings WHERE elder_id = users.id AND status = 'ACTIVE' ORDER BY id LIMIT 1)
            )
            WHERE role = 'elder' AND id IN (SELECT elder_id FROM bindings WHERE family_id = ? AND status = 'ACTIVE')`,
            [target.id]],
          // family.bound_user_id = App 账号最早绑定的老人
          [`UPDATE users SET bound_user_id = (
              SELECT elder_id FROM bindings WHERE family_id = users.id AND status = 'ACTIVE' ORDER BY id LIMIT 1
            )
            WHERE id = ?`,
            [target.id]],
          // 占位微信账号废弃：清 openid 防止再次被登录，绑定缓存一并清掉（行已迁移）
          ['UPDATE users SET wx_openid = NULL, bound_user_id = NULL WHERE id = ?', [userId]]
        ];

        db.serialize(() => {
          db.run('BEGIN TRANSACTION');
          const step = (i) => {
            if (i >= stmts.length) {
              return db.run('COMMIT', (err) => {
                if (err) return res.status(500).json({ error: err.message });
                console.log(`🔗 微信账号 [ID: ${userId}] 已关联 App 账号 [ID: ${target.id}]（绑定集合已合并）`);
                replyWxSession(res, target.id);
              });
            }
            db.run(...stmts[i], (err) => {
              if (err) {
                db.run('ROLLBACK');
                return res.status(500).json({ error: err.message });
              }
              step(i + 1);
            });
          };
          step(0);
        });
      });
    });
  });
});

// 关联成功后的会话回执：与 /login 下发字段完全一致，客户端整体替换登录态
function replyWxSession(res, userId) {
  db.get('SELECT * FROM users WHERE id = ?', [userId], (err, user) => {
    if (err || !user) return res.status(500).json({ error: '关联成功但回读失败' });
    replyFamilySession(res, user, { hasPassword: !!user.password_hash });
  });
}

// 修改密码（需登录态）：旧密码验证通过后换新密码。
// 只服务 App 子女端账密账号；微信登录建号的账号 password_hash 为空，
// 没有旧密码可验证，直接引导（前端也不给这类账号显示改密入口）。
router.post('/change-password', requireFamilyAuth, (req, res) => {
  const oldPassword = req.body && req.body.oldPassword;
  const newPassword = req.body && req.body.newPassword;
  if (!oldPassword || !newPassword) {
    return res.status(400).json({ error: '参数缺失: oldPassword / newPassword' });
  }
  if (String(newPassword).length < 6) {
    return res.status(400).json({ error: '新密码至少 6 位' });
  }

  const userId = req.authUserId;
  db.get('SELECT id, password_hash FROM users WHERE id = ?', [userId], (err, user) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!user) return res.status(404).json({ error: '账号不存在' });
    if (!user.password_hash) {
      return res.status(400).json({ error: '当前账号未设置密码（微信登录账号无需密码）' });
    }

    const [salt, hash] = String(user.password_hash).split(':');
    let calc;
    try {
      calc = crypto.scryptSync(String(oldPassword), salt, 64).toString('hex');
    } catch (e) {
      return res.status(500).json({ error: '密码校验失败' });
    }
    if (calc !== hash) {
      return res.status(401).json({ error: '旧密码不正确' });
    }

    const newSalt = crypto.randomBytes(16).toString('hex');
    const newHash = crypto.scryptSync(String(newPassword), newSalt, 64).toString('hex');
    db.run('UPDATE users SET password_hash = ? WHERE id = ?', [`${newSalt}:${newHash}`, userId], (err) => {
      if (err) return res.status(500).json({ error: err.message });
      console.log(`🔑 用户 [ID: ${userId}] 已修改密码`);
      res.json({ success: true, message: '密码已修改' });
    });
  });
});

// 忘记密码重置：用户名（或手机号）+ 注册时绑定的手机号双重匹配后直接设置新密码。
// 项目未接短信通道，这里用"账号 + 绑定手机号"作为身份凭据；
// 匹配范围限定 password_hash 非空的账密账号，微信账号没有可登录的用户名，不在范围内。
router.post('/reset-password', (req, res) => {
  const username = cleanIdentifier(req.body && req.body.username);
  const mobile = String((req.body && req.body.phone) || '').trim();
  const newPassword = req.body && req.body.newPassword;
  if (!username || !mobile || !newPassword) {
    return res.status(400).json({ error: '参数缺失: username / phone / newPassword' });
  }
  if (String(newPassword).length < 6) {
    return res.status(400).json({ error: '新密码至少 6 位' });
  }
  if (!MOBILE_RE.test(mobile)) {
    return res.status(400).json({ error: '手机号格式不正确（需 11 位大陆手机号）' });
  }

  db.get(
    'SELECT id FROM users WHERE (phone = ? OR mobile = ?) AND mobile = ? AND password_hash IS NOT NULL',
    [username, username, mobile],
    (err, user) => {
      if (err) return res.status(500).json({ error: err.message });
      // 不区分"账号不存在"和"手机号不匹配"，避免帮攻击者探测账号是否注册过
      if (!user) return res.status(404).json({ error: '账号与手机号不匹配，无法重置' });

      const salt = crypto.randomBytes(16).toString('hex');
      const hash = crypto.scryptSync(String(newPassword), salt, 64).toString('hex');
      db.run('UPDATE users SET password_hash = ? WHERE id = ?', [`${salt}:${hash}`, user.id], (err) => {
        if (err) return res.status(500).json({ error: err.message });
        console.log(`🔑 用户 [ID: ${user.id}] 已通过绑定手机号重置密码`);
        res.json({ success: true, message: '密码已重置，请使用新密码登录' });
      });
    }
  );
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

    // 真实手机号与是否已完善随回执下发：客户端据此提示补录（缺号会导致老人端一键拨号失效）
    replyFamilySession(res, user);
  });
});

// 解除亲情绑定：userId 一律以登录态 token 身份为准
// 多人绑定语义：只解除 (自己, elderId) 这一条，不再清除对方的整条绑定。
// elderId 缺省时仅当只绑了一位老人才允许（保持旧客户端不传参的兼容），
// 多绑定时必须显式指定解哪一位，否则 400——绝不静默替用户做选择。
router.post('/unbind', requireFamilyAuth, (req, res) => {
  const userId = req.authUserId;
  const elderIdParam = parseInt(req.body && req.body.elderId, 10) || 0;

  bindings.listEldersOfFamily(db, userId, (err, elders) => {
    if (err) return res.status(500).json({ error: err.message });
    const list = elders || [];

    if (list.length === 0) {
      // 与旧版口径一致：未绑定也返回成功（幂等）。
      // ⚠️ 必须显式回 boundList: []：客户端把「字段缺失」当旧服务端降级路径（保留本地缓存），
      // 只有显式空数组才会清掉本地列表 —— 否则解绑后本地仍展示那位老人（2026-10-26 事故）。
      return res.json({ success: true, message: '已解除绑定', boundList: [] });
    }

    let targetElderId = elderIdParam;
    if (!targetElderId) {
      if (list.length > 1) {
        return res.status(400).json({ error: '你绑定了多位老人，请指定要解除绑定的老人' });
      }
      targetElderId = list[0].id;
    }
    if (!list.some((e) => e.id === targetElderId)) {
      // 幂等：该老人已经解绑（或本来就没绑定）不算失败。
      // 两台设备各点一次、或用户重复点，回 403 会让客户端误判「解绑失败」而保留
      // 本地陈旧项 —— 正是「解绑后仍在绑定列表里」的来源之一。
      return fetchBoundElders(userId, (err4, list2) => {
        res.json({ success: true, message: '已解除绑定', boundList: err4 ? [] : (list2 || []) });
      });
    }

    bindings.removeBinding(db, targetElderId, userId, (err2) => {
      if (err2) return res.status(500).json({ error: err2.message });
      console.log(`💔 子女 [ID: ${userId}] 已解除与老人 [ID: ${targetElderId}] 的绑定`);
      fetchBoundElders(userId, (err3, list2) => {
        res.json({ success: true, message: '已解除绑定', boundList: err3 ? [] : (list2 || []) });
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

// ──────────────────────────────────────────
//  子女端守护设置（带鉴权）
//
//  与上面 POST /elder-settings 的区别：
//    上面那条零鉴权，任何知道 elderId 的人都能改老人的守护规则
//    （路线图 G15，Phase 4 待办）。它是老人端自己用的通道，
//    因为老人端本就没有账号密码。
//    这条给子女端用，elderId 只从路径参数取，且必须通过绑定关系校验。
//
//  为什么"篡改老人的家基准"必须拦住：改家基准会直接让「离家 / 停留」
// 告警失效 —— 而这正是子女发现老人被骗的第一道信号。
// 属于可被恶意利用的静默降级，不能留成无鉴权接口。
//
//  路径参数是必需的而非命名风格问题：requireBoundElder
//  （services/tokenAuth.js）从 req.params 取值，取不到直接 400。
// ──────────────────────────────────────────

router.get('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);

  db.get("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });
    res.json({ success: true, settings: parseGuardSettings(elder.guard_settings) });
  });
});

router.post('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);

  const raw = req.body && req.body.settings;
  // 顺序不可调换：homeCleared 必须在 sanitize 之前取出，
  // sanitize 之后它已被丢弃（见 extractHomeCleared 的注释）
  const homeCleared = extractHomeCleared(raw);
  const clean = pickFamilyWritable(sanitizeGuardSettings(raw));

  if (!Object.keys(clean).length && !homeCleared) {
    return res.status(400).json({ error: '没有可保存的配置项' });
  }

  db.get("SELECT guard_settings FROM users WHERE id = ? AND role = 'elder'", [elderId], (err, elder) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!elder) return res.status(404).json({ error: '老人账号不存在' });

    const merged = mergeFamilySettings(
      parseGuardSettings(elder.guard_settings), clean, homeCleared);

    db.run('UPDATE users SET guard_settings = ? WHERE id = ?',
      [JSON.stringify(merged), elderId], (err) => {
        if (err) return res.status(500).json({ error: err.message });
        console.log(`👪 子女端 [ID: ${elderId}] 守护规则已更新: ${JSON.stringify(merged)}`);
        // 通知老人端立刻重算。没通知也没关系：老人端有 30 分钟拉取兜底，
        // 且 GuardConfig 是 SharedPreferences，服务下次启动自然读到新值。
        if (typeof hubRef.notifyElderSettingsChanged === 'function') {
          hubRef.notifyElderSettingsChanged(elderId, merged);
        }
        res.json({ success: true, settings: merged });
      });
  });
});

module.exports = router;
// setHub 同 recordings.js：让路由能回调推送，但不反向依赖 server。
// 老人端离线时不缓存这条指令 —— 配置没有时效性，
// 缓存一份陈旧配置会在上线时覆盖掉更新的值。
module.exports.setHub = (hub) => { hubRef = hub || {}; };
