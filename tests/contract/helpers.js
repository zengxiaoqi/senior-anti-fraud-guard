/**
 * API 契约测试公共辅助。
 *
 * 这套用例的意义：迁移 Workers 期间，本地后端（Express）与 Workers 后端（Hono）
 * 必须产出**完全相同**的请求/响应契约。用例只依赖 HTTP 行为，不依赖实现，
 * 因此对两个后端是同一份代码 —— 改契约就必须两边同时过测。
 */
const BASE = process.env.CONTRACT_BASE_URL || 'http://127.0.0.1:3999';

/** 随机大陆手机号（11 位）：并发用例之间互不冲突 */
function randMobile() {
  const tail = String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
  return '139' + tail;
}

/** 随机用户名：4-20 位字母数字下划线 */
function randUsername() {
  return 'u' + Math.random().toString(36).slice(2, 10);
}

async function request(method, urlPath, { body, token, headers = {}, raw = false } = {}) {
  const opts = { method, headers: { ...headers } };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  if (token) opts.headers['X-Auth-Token'] = token;

  const res = await fetch(BASE + urlPath, opts);
  if (raw) return res;
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch (e) {
    json = { __notJson: text.slice(0, 200) };
  }
  return { status: res.status, headers: res.headers, body: json, text };
}

const api = {
  get: (p, o) => request('GET', p, o),
  post: (p, o) => request('POST', p, o),
  del: (p, o) => request('DELETE', p, o),
  /** multipart 上传（老人端录音上报走的就是这个） */
  async upload(urlPath, fields, filePath, fileName, mime = 'audio/mp4') {
    const fs = require('fs');
    const fd = new FormData();
    const blob = new Blob([fs.readFileSync(filePath)], { type: mime });
    fd.append('file', blob, fileName);
    for (const [k, v] of Object.entries(fields)) fd.append(k, String(v));
    const res = await fetch(BASE + urlPath, { method: 'POST', body: fd });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch (e) {
      json = { __notJson: text.slice(0, 200) };
    }
    return { status: res.status, headers: res.headers, body: json };
  }
};

/** 注册一个子女端账号，返回 { userId, token, bindCode, username, mobile } */
async function registerFamily(overrides = {}) {
  const username = overrides.username || randUsername();
  const mobile = overrides.mobile || randMobile();
  const res = await api.post('/api/auth/register', {
    body: { username, password: 'test123456', phone: mobile, name: '契约测试守护人', ...overrides }
  });
  return { res, username, mobile };
}

/** 老人端激活账号，返回 { res, mobile } */
async function registerElder(overrides = {}) {
  const mobile = overrides.mobile || randMobile();
  const res = await api.post('/api/auth/elder-register', {
    body: { elderId: 0, name: overrides.name || '契约测试老人', phone: mobile, ...overrides }
  });
  return { res, mobile };
}

/** 建一对"已绑定"的 子女 + 老人，后续用例直接拿去用 */
async function setupBoundPair() {
  const fam = await registerFamily();
  if (fam.res.status !== 200) throw new Error('子女端注册失败: ' + JSON.stringify(fam.res.body));
  const token = fam.res.body.data.token;

  const elder = await registerElder();
  if (elder.res.status !== 200) throw new Error('老人端激活失败: ' + JSON.stringify(elder.res.body));
  const elderId = elder.res.body.elderId;
  const bindCode = elder.res.body.bindCode;

  const bind = await api.post('/api/auth/bind', { body: { bindCode }, token });
  if (bind.status !== 200) throw new Error('亲情绑定失败: ' + JSON.stringify(bind.body));

  return {
    family: { userId: fam.res.body.data.userId, token, bindCode: fam.res.body.data.bindCode },
    elder: { elderId, bindCode, mobile: elder.mobile }
  };
}

/** 断言：北京时间格式（不带 Z，形如 "2026-10-08 22:33:11"） */function assertBeijingTime(actual, label) {
  if (typeof actual !== 'string') {
    throw new Error(`${label} 应为时间字符串，实际: ${JSON.stringify(actual)}`);
  }
  if (/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}/.test(actual) === false) {
    throw new Error(`${label} 时间格式不符: ${actual}`);
  }
}

/**
 * 生成一份确定性的"录音样本"用于上传契约测试。
 * 内容不需要是真音频：本套用例只校验 HTTP 契约（落库/去重/列表/流式/打包），
 * ASR 失败与否不影响契约，且服务端对转写失败有 SUSPECT 兜底。
 */
function sampleAudioPath(name = 'contract-sample.m4a') {
  const fs = require('fs');
  const os = require('os');
  const path = require('path');
  const p = path.join(os.tmpdir(), name);
  if (!fs.existsSync(p)) {
    const buf = Buffer.alloc(64 * 1024);
    for (let i = 0; i < buf.length; i++) buf[i] = (i * 31 + 7) & 0xff;
    fs.writeFileSync(p, buf);
  }
  return p;
}

/** 计算文件 sha256（与服务端存证摘要口径一致） */
function sha256File(filePath) {
  const fs = require('fs');
  const crypto = require('crypto');
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

module.exports = {
  BASE,
  api,
  randMobile,
  randUsername,
  registerFamily,
  registerElder,
  setupBoundPair,
  assertBeijingTime,
  sampleAudioPath,
  sha256File
};
