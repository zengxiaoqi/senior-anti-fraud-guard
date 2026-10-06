// 轻量 Token 鉴权：解决"明文 userId 即可越权查任意用户数据"的问题
// 机制：wx-login 成功后签发随机 token（内存 Map 存储），子女端后续请求带 X-Auth-Token 头
const crypto = require('crypto');
const db = require('../database/db');

// token -> { userId, createdAt }
const tokens = new Map();
const TOKEN_TTL = 7 * 24 * 60 * 60 * 1000; // 7 天有效期

function issueToken(userId) {
  // 清理过期 token，防止 Map 无限膨胀
  const now = Date.now();
  if (tokens.size > 5000) {
    for (const [k, v] of tokens) {
      if (now - v.createdAt > TOKEN_TTL) tokens.delete(k);
    }
  }
  const token = crypto.randomBytes(24).toString('hex');
  tokens.set(token, { userId, createdAt: now });
  return token;
}

// 校验登录态：必须在请求头携带有效的 X-Auth-Token
function requireFamilyAuth(req, res, next) {
  const token = req.get('X-Auth-Token');
  const record = token && tokens.get(token);
  if (!record || Date.now() - record.createdAt > TOKEN_TTL) {
    if (token) tokens.delete(token);
    return res.status(401).json({ success: false, error: '登录态无效或已过期，请重新登录' });
  }
  req.authUserId = record.userId;
  next();
}

// 数据归属校验：token 用户本人，或与 :elderId 存在绑定关系，才可访问该 elderId 的数据
function requireBoundElder(req, res, next) {
  const elderId = req.params.elderId;
  if (String(req.authUserId) === String(elderId)) return next();
  db.get('SELECT bound_user_id FROM users WHERE id = ?', [req.authUserId], (err, row) => {
    if (err || !row || String(row.bound_user_id) !== String(elderId)) {
      return res.status(403).json({ success: false, error: '无权访问该用户的数据' });
    }
    next();
  });
}

module.exports = { issueToken, requireFamilyAuth, requireBoundElder };
