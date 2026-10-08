// ASR 降级纪律回归测试
//
// 为什么这组测试不可省：asr.js 的失败如果被当成"没问题"，
// 录音就会走 SAFE 分支 → keep_as_evidence=0 → 14天后被 recordingCleanup 删掉。
// 也就是说老人被骗的现场录音会因为"转写失败"被静默销毁。
// routes/recordings.js:223-256 是第二道防线（降级为 SUSPECT），
// 但第一道防线就在 asr.transcribe 的返回值上 —— 必须保证它绝不谎报成功。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ASR_PATH = require.resolve('../services/asr');

const ASR_KEYS = ['ASR_PROVIDER', 'ASR_BASE_URL', 'ASR_API_KEY', 'ASR_MODEL',
  'TENCENT_SECRET_ID', 'TENCENT_SECRET_KEY', 'TENCENT_ASR_ENGINE_TYPE'];

/**
 * 以指定环境变量重新加载 asr 模块。
 *
 * 注意 asr.js 存在"半静态"读取：
 *   - PROVIDER 在模块加载时读一次（asr.js:24）
 *   - 而 isConfigured() 里的密钥是**调用时**实时读 process.env（asr.js:28-37）
 * 因此这里设完环境变量后不能立刻还原，否则 isConfigured() 会看到空环境。
 * 每次调用都会把这 7 个键整体重置（未指定的删除），所以不还原也不会串味。
 */
function loadAsr(env) {
  for (const k of ASR_KEYS) {
    if (env[k] === undefined) delete process.env[k];
    else process.env[k] = env[k];
  }
  delete require.cache[ASR_PATH];
  return require(ASR_PATH);
}

// 写一个真实存在的临时音频文件（内容随意，只为通过 existsSync 检查）
const tmpFile = path.join(os.tmpdir(), `asr-test-${process.pid}.m4a`);
fs.writeFileSync(tmpFile, Buffer.from('ID3fake-m4a-payload'));

// ──────────────────────────────────────────────
//  1. 未配置 Provider
// ──────────────────────────────────────────────

test('ASR_PROVIDER 未配置时返回 SKIPPED（绝不能当成 DONE）', async () => {
  const asr = loadAsr({});
  const r = await asr.transcribe(tmpFile);
  assert.equal(r.ok, false);
  assert.equal(r.status, 'SKIPPED');
  assert.match(r.error, /ASR_PROVIDER=none/);
  assert.equal(r.text, undefined, 'SKIPPED 不得带 text，否则会被误当成空转写');
});

test('未配置时 isConfigured=false 且 providerName=none', () => {
  const asr = loadAsr({});
  assert.equal(asr.isConfigured(), false);
  assert.equal(asr.providerName(), 'none');
});

// ──────────────────────────────────────────────
//  2. 配置不完整
// ──────────────────────────────────────────────

test('whisper 缺少 API key 时返回 SKIPPED 并说明缺配置', async () => {
  const asr = loadAsr({ ASR_PROVIDER: 'whisper' });
  assert.equal(asr.isConfigured(), false);
  const r = await asr.transcribe(tmpFile);
  assert.equal(r.status, 'SKIPPED');
  assert.match(r.error, /配置不完整/);
  assert.doesNotMatch(r.error, /ASR_PROVIDER=none/, '有 provider 但缺 key，提示要区分开');
});

test('whisper 只配 BASE_URL 缺 API_KEY 仍判为未配置', () => {
  const asr = loadAsr({ ASR_PROVIDER: 'whisper', ASR_BASE_URL: 'https://example.com/v1' });
  assert.equal(asr.isConfigured(), false);
});

test('tencent 三个密钥缺一不可', () => {
  assert.equal(loadAsr({ ASR_PROVIDER: 'tencent' }).isConfigured(), false);
  assert.equal(loadAsr({
    ASR_PROVIDER: 'tencent',
    TENCENT_SECRET_ID: 'id',
    TENCENT_SECRET_KEY: 'key',
  }).isConfigured(), false);
  assert.equal(loadAsr({
    ASR_PROVIDER: 'tencent',
    TENCENT_SECRET_ID: 'id',
    TENCENT_SECRET_KEY: 'key',
    TENCENT_ASR_ENGINE_TYPE: '16k_zh',
  }).isConfigured(), true);
});

// ──────────────────────────────────────────────
//  3. 已配置但文件不在（刚被清理）
// ──────────────────────────────────────────────

test('已配置但录音文件不存在时返回 FAILED 而非 SKIPPED', async () => {
  const asr = loadAsr({
    ASR_PROVIDER: 'whisper',
    ASR_BASE_URL: 'https://example.invalid/v1',
    ASR_API_KEY: 'test-key',
  });
  const r = await asr.transcribe(path.join(os.tmpdir(), 'definitely-missing-file.m4a'));
  assert.equal(r.ok, false);
  assert.equal(r.status, 'FAILED');
  assert.match(r.error, /不存在|已被清理/);
});

test('已配置但文件不存在时不会发起网络请求（配置校验先于 existsSync 之外的所有事）', async () => {
  const asr = loadAsr({
    ASR_PROVIDER: 'whisper',
    ASR_BASE_URL: 'http://127.0.0.1:1/v1',
    ASR_API_KEY: 'test-key',
  });
  const started = Date.now();
  const r = await asr.transcribe(path.join(os.tmpdir(), 'missing-again.m4a'));
  assert.equal(r.status, 'FAILED');
  assert.ok(Date.now() - started < 3000, '不应因为连不上而等待超时');
});

// ──────────────────────────────────────────────
//  4. 未知 Provider：已知弱点
// ──────────────────────────────────────────────

test('未知 Provider 不会崩，但错误文案会被误读为"缺密钥"', async () => {
  const asr = loadAsr({ ASR_PROVIDER: 'not-a-real-provider' });
  assert.equal(asr.isConfigured(), false);
  const r = await asr.transcribe(tmpFile);
  assert.equal(r.status, 'SKIPPED');
  // 已知弱点：asr.js:56-59 只区分 PROVIDER==='none' 与"其他"，
  // 所以拼错的 provider 名会被报成"缺 API 密钥"，排查时会误导。
  // 断言当前行为，若将来修好文案这条会提醒更新。
  assert.match(r.error, /配置不完整/);
  assert.doesNotMatch(r.error, /not-a-real-provider/, '当前实现不回显具体 provider 名');
});

// ──────────────────────────────────────────────
//  5. 全局不变量：绝不抛异常、绝不谎报成功
// ──────────────────────────────────────────────

test('transcribe 在任意配置下都不抛异常', async () => {
  const configs = [
    {},
    { ASR_PROVIDER: 'whisper' },
    { ASR_PROVIDER: 'tencent', TENCENT_SECRET_ID: 'a', TENCENT_SECRET_KEY: 'b', TENCENT_ASR_ENGINE_TYPE: 'c' },
    { ASR_PROVIDER: '???' },
    { ASR_PROVIDER: 'WHISPER', ASR_BASE_URL: 'https://example.invalid/v1', ASR_API_KEY: 'k' },
  ];
  for (const cfg of configs) {
    const asr = loadAsr(cfg);
    const r = await asr.transcribe(tmpFile);
    assert.ok(r && typeof r === 'object', `${JSON.stringify(cfg)} 应返回对象`);
    assert.ok(['DONE', 'FAILED', 'SKIPPED'].includes(r.status), `非法 status: ${r.status}`);
  }
});

test('返回结构始终含 ok / status / error（ok=false 时必有 error 文案）', async () => {
  const asr = loadAsr({});
  const r = await asr.transcribe(tmpFile);
  assert.equal(typeof r.ok, 'boolean');
  assert.equal(typeof r.status, 'string');
  assert.ok(r.error && r.error.length > 0);
});

test('状态与 ok 的一致性：非 DONE 必为 ok=false', async () => {
  for (const cfg of [{}, { ASR_PROVIDER: 'whisper', ASR_BASE_URL: 'https://x/v1', ASR_API_KEY: 'k' }]) {
    const asr = loadAsr(cfg);
    const r = await asr.transcribe(path.join(os.tmpdir(), 'nope.m4a'));
    if (r.status !== 'DONE') {
      assert.equal(r.ok, false, `status=${r.status} 时 ok 必须为 false`);
    }
  }
});

test('未配置状态下 durationMs 等运行期字段不应出现（保持 SKIPPED 语义干净）', async () => {
  const asr = loadAsr({});
  const r = await asr.transcribe(tmpFile);
  assert.equal(r.durationMs, undefined);
  assert.equal(r.engine, undefined);
});

test.after(() => {
  for (const k of ASR_KEYS) delete process.env[k];
  try { fs.unlinkSync(tmpFile); } catch { /* ignore */ }
});