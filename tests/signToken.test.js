// 播放令牌（signToken）安全性回归测试
//
// 为什么这组测试优先级高：Android MediaPlayer 播 m4a 不带自定义请求头，
// 所以整条"子女端收听录音"的鉴权**完全押在这一个令牌上**。
// 令牌一旦可伪造/可串用，等于任何人都能下载他人的现场录音（含老人语音）。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

// 必须在 require 之前设置：SECRET 与 TTL_MS 都是模块加载时读一次的
process.env.EVIDENCE_TOKEN_SECRET = 'test-secret-do-not-use-in-production';
process.env.PLAY_TOKEN_TTL_MS = '1800000'; // 30 分钟

const { sign, verify, TTL_MS } = require('../services/signToken');
const SECRET = 'test-secret-do-not-use-in-production';

// ──────────────────────────────────────────────
//  1. 正常路径
// ──────────────────────────────────────────────

test('sign → verify 往返成功，payload 原样返回', () => {
  const { token } = sign(42, 7);
  const r = verify(token, 42);
  assert.equal(r.ok, true);
  assert.equal(r.payload.rid, 42);
  assert.equal(r.payload.uid, 7);
  assert.equal(typeof r.payload.exp, 'number');
});

test('sign 返回的 expiresAt 与 TTL_MS 一致', () => {
  const before = Date.now();
  const { token, expiresAt } = sign(1, 1);
  assert.ok(expiresAt >= before + TTL_MS - 50);
  assert.ok(expiresAt <= Date.now() + TTL_MS + 50);
  assert.equal(verify(token, 1).ok, true);
});

test('recordingId 传 null 表示只验签不校验归属（列表接口预签名场景）', () => {
  const { token } = sign(99, 3);
  assert.equal(verify(token, null).ok, true);
  assert.equal(verify(token, 98).ok, false, '显式传错 ID 时必须拒绝');
});

// ──────────────────────────────────────────────
//  2. 攻击面：伪造
// ──────────────────────────────────────────────

test('篡改 payload 里的 rid（拿别人的令牌改录音号）必须验签失败', () => {
  const { token } = sign(1, 1);
  const [encoded, sig] = token.split('.');
  const forged = Buffer.from(JSON.stringify({ rid: 999, uid: 1, exp: Date.now() + 999999 }), 'utf8')
    .toString('base64url');
  const r = verify(`${forged}.${sig}`, 999);
  assert.equal(r.ok, false);
  assert.match(r.error, /签名无效/);
});

test('伪造 payload 但用攻击者自己的合法签名去撞库不可行', () => {
  const payload = Buffer.from(JSON.stringify({ rid: 1, uid: 1, exp: Date.now() + 999999 }), 'utf8')
    .toString('base64url');
  const badSig = crypto.createHmac('sha256', 'wrong-secret').update(payload).digest('base64url');
  assert.equal(verify(`${payload}.${badSig}`, 1).ok, false);
});

test('改动签名字符（长度不变）必须被 timingSafeEqual 拒绝', () => {
  const { token } = sign(5, 5);
  const [encoded, sig] = token.split('.');
  const flipped = (sig[0] === 'a' ? 'b' : 'a') + sig.slice(1);
  const r = verify(`${encoded}.${flipped}`, 5);
  assert.equal(r.ok, false);
  assert.match(r.error, /签名无效/);
});

test('签名长度不一致时不进入 timingSafeEqual（防 ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH）', () => {
  const { token } = sign(5, 5);
  const [encoded] = token.split('.');
  const r = verify(`${encoded}.short`, 5);
  assert.equal(r.ok, false);
  assert.match(r.error, /签名无效/);
});

// ──────────────────────────────────────────────
//  3. 攻击面：串用与过期
// ──────────────────────────────────────────────

test('为 A 录音签发的令牌不能播放 B 录音（防串用）', () => {
  const { token } = sign(100, 1);
  const r = verify(token, 101);
  assert.equal(r.ok, false);
  assert.match(r.error, /与录音不匹配/);
});

test('过期令牌必须拒绝', () => {
  // 用一个早已过期的 exp 重新签发（密钥已知，仅用于构造测试样本）
  const payload = Buffer.from(JSON.stringify({ rid: 1, uid: 1, exp: Date.now() - 1 }), 'utf8')
    .toString('base64url');
  const sig = crypto.createHmac('sha256', SECRET).update(payload).digest('base64url');
  const r = verify(`${payload}.${sig}`, 1);
  assert.equal(r.ok, false);
  assert.match(r.error, /已过期/);
});

// ──────────────────────────────────────────────
//  4. 畸形输入：绝不能抛异常（未捕获异常会打断整个请求）
// ──────────────────────────────────────────────

test('畸形令牌一律返回 ok:false 而非抛异常', () => {
  const cases = [
    undefined, '', 'null', '...', 'a.b.c', 'only-one-part',
    'aGVsbG8.dm90YWw',                       // 合法 base64url 但签名错
    Buffer.from('not json').toString('base64url') + '.AAAA',
  ];
  for (const t of cases) {
    const r = verify(t, 1);
    assert.equal(r.ok, false, `输入 ${JSON.stringify(t)} 应被拒绝`);
    assert.equal(typeof r.error, 'string');
    assert.ok(r.error.length > 0, '必须给出可读错误');
  }
});

test('缺令牌时错误信息指向"刷新列表"（用户可执行的提示）', () => {
  const r = verify(undefined, 1);
  assert.equal(r.ok, false);
  assert.match(r.error, /刷新录音列表/);
});

test('签名有效但 payload 缺字段时拒绝（字段完整性校验）', () => {
  const payload = Buffer.from(JSON.stringify({ uid: 1 }), 'utf8').toString('base64url');
  const sig = crypto.createHmac('sha256', SECRET).update(payload).digest('base64url');
  const r = verify(`${payload}.${sig}`, 1);
  assert.equal(r.ok, false);
  assert.match(r.error, /字段缺失/);
});

test('TTL_MS 导出与配置一致', () => {
  assert.equal(TTL_MS, 1800000);
});