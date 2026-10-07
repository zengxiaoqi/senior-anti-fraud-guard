// 短时效播放签名
//
// 存在的理由：Android MediaPlayer 播放 m4a 时**不会**携带自定义请求头，
// 所以子女端收听录音没法用 X-Auth-Token 鉴权（带了也会被播放器丢掉）。
// 折中方案：由服务端在录音列表接口（已鉴权）里签发令牌，拼在 URL 查询串上，30 分钟过期。
//
// 令牌结构：base64url(JSON payload) + "." + HMAC-SHA256(payload)
//   payload = { rid: 录音ID, uid: 播放者用户ID, exp: 过期时间戳 }
//
// 关键点：HMAC 覆盖整个 payload，校验时用原始 payload 重算签名并做定时安全比较，
// 因此播放端虽然拿不到 uid，也能真正验证"这个令牌确实是服务端为这条录音签的、且没被改过"。
const crypto = require('crypto');

// 进程内随机密钥：服务重启后旧令牌自动失效，这正是我们想要的
const SECRET = process.env.EVIDENCE_TOKEN_SECRET || crypto.randomBytes(32).toString('hex');
const TTL_MS = parseInt(process.env.PLAY_TOKEN_TTL_MS || String(30 * 60 * 1000), 10);

/** 为指定录音签发播放令牌 */
function sign(recordingId, userId) {
  const payload = {
    rid: Number(recordingId),
    uid: Number(userId),
    exp: Date.now() + TTL_MS
  };
  const encoded = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sig = crypto.createHmac('sha256', SECRET).update(encoded).digest('base64url');
  return { token: `${encoded}.${sig}`, expiresAt: payload.exp };
}

/**
 * 校验播放令牌：签名必须正确、录音ID 必须匹配、且未过期。
 * @param {string} token
 * @param {number} recordingId 期望播放的录音 ID
 * @returns {{ok:true, payload:object}|{ok:false, error:string}}
 */
function verify(token, recordingId) {
  if (!token) return { ok: false, error: '缺少播放令牌，请刷新录音列表' };

  const parts = String(token).split('.');
  if (parts.length !== 2) return { ok: false, error: '播放令牌格式错误' };

  const [encoded, sig] = parts;

  // 1. 先验签名，防止伪造/篡改 payload
  const expected = crypto.createHmac('sha256', SECRET).update(encoded).digest('base64url');
  const a = Buffer.from(String(sig));
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return { ok: false, error: '播放令牌签名无效' };
  }

  // 2. 验签通过后再解 payload
  let payload;
  try {
    payload = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
  } catch (e) {
    return { ok: false, error: '播放令牌内容无法解析' };
  }

  if (!payload || typeof payload.rid !== 'number' || typeof payload.exp !== 'number') {
    return { ok: false, error: '播放令牌字段缺失' };
  }
  if (Date.now() > payload.exp) {
    return { ok: false, error: '播放令牌已过期，请刷新录音列表' };
  }
  if (recordingId != null && Number(payload.rid) !== Number(recordingId)) {
    return { ok: false, error: '播放令牌与录音不匹配' };
  }

  return { ok: true, payload };
}

module.exports = { sign, verify, TTL_MS };
