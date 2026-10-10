/**
 * 契约测试启动器：用一份临时数据库起本地后端，跑 tests/contract 下全部用例，跑完清理。
 *
 * 为什么不直接打 3000 端口的生产实例：契约测试会真实写库（注册账号、上传录音、
 * 建围栏），打生产库等于往线上灌垃圾数据。这里用独立的 data.contract-test.sqlite，
 * 每次跑都是干净库。对 Workers 后端时，把 CONTRACT_BASE_URL 指向 wrangler dev
 * 并加 --no-server 参数即可复用同一套用例。
 */
const { spawn } = require('child_process');
const fs = require('fs');
const net = require('net');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const DB_PATH = path.join(ROOT, 'data.contract-test.sqlite');
const PORT = Number(process.env.CONTRACT_PORT || 3999);
const NO_SERVER = process.argv.includes('--no-server'); // 已有人在跑（如 wrangler dev）

function rmDb() {
  for (const f of [DB_PATH, DB_PATH + '-journal', DB_PATH + '-wal', DB_PATH + '-shm']) {
    try {
      fs.unlinkSync(f);
    } catch (e) {
      /* 不存在就算了 */
    }
  }
}

function waitPort(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const sock = net.connect(port, '127.0.0.1');
      sock.once('connect', () => {
        sock.destroy();
        resolve();
      });
      sock.once('error', () => {
        sock.destroy();
        if (Date.now() > deadline) return reject(new Error(`端口 ${port} 等待超时`));
        setTimeout(tick, 300);
      });
    };
    tick();
  });
}

function runTests() {
  return new Promise((resolve) => {
    // CONTRACT_FILES=auth,events 之类可只跑指定用例（P3 起 Workers 逐组路由灰度接入时用）
    const only = (process.env.CONTRACT_FILES || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    const targets = only.length
      ? only.map((f) => path.join('tests', 'contract', `${f}.test.js`))
      : [path.join('tests', 'contract', '*.test.js')];
    const child = spawn(
      process.execPath,
      // --test-force-exit：WS 契约用例建的长连接会让 node:test 挂起不退出
      ['--test', '--test-concurrency=1', '--test-force-exit', ...targets],
      {
        cwd: ROOT,
        stdio: 'inherit',
        env: {
          ...process.env,
          CONTRACT_BASE_URL: process.env.CONTRACT_BASE_URL || `http://127.0.0.1:${PORT}`
        }
      }
    );
    child.on('exit', (code) => resolve(code || 0));
  });
}

(async () => {
  let server = null;
  if (!NO_SERVER) {
    rmDb();
    console.log(`[contract] 启动本地后端（PORT=${PORT}, DB=data.contract-test.sqlite）...`);
    server = spawn(process.execPath, ['server.js'], {
      cwd: ROOT,
      stdio: ['ignore', 'ignore', 'pipe'],
      env: { ...process.env, PORT: String(PORT), DB_PATH }
    });
    server.stderr.on('data', (d) => process.stderr.write(d));
    await waitPort(PORT);
    console.log('[contract] 后端就绪，开始跑契约测试\n');
  }

  const code = await runTests();

  if (server) {
    server.kill();
    rmDb();
  }
  console.log(`\n[contract] 契约测试退出码: ${code}`);
  process.exit(code);
})();
