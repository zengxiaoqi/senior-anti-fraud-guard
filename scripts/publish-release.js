#!/usr/bin/env node
/**
 * 发布一个 App 升级版本。
 *
 *   node scripts/publish-release.js --note "修复录音上传中断"
 *   node scripts/publish-release.js --apk dist/AntiFraudGuard-v1.8.0-release.apk --note "..."
 *   node scripts/publish-release.js --upload          # 顺带传到 Cloudflare R2（生产 Workers 后端）
 *
 * 它只做三件事：
 *   1. 从 android/app/build.gradle 读 versionCode / versionName（唯一事实源）；
 *   2. 算出 APK 的大小与 SHA-256，写进 dist/releases.json 供服务端读取；
 *   3. 可选地把 APK + 清单传到 R2，让生产 Workers 后端也能分发。
 *
 * 为什么 versionCode 必须来自 build.gradle：它是"要不要升级"的唯一判据，
 * 而文件名里只有 versionName。1.10.0 与 1.9.0 按字符串比会得到
 * 1.10.0 < 1.9.0 的错误结论，按 code 比才永远正确。人手写这个数字迟早写错，
 * 所以从构建脚本里读，不允许命令行覆盖。
 *
 * SHA-256 的用途不只是"防篡改"：老人手机在弱网下下载 24MB 极易中途被掐断，
 * 文件被截断后仍能被当成完整文件安装 —— 装到一半报"解析包错误"，
 * 用户只会以为 App 坏了。下载后先校验再安装，截断的文件当场重试。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const GRADLE_FILE = path.join(ROOT, 'android', 'app', 'build.gradle');
const DIST_DIR = path.join(ROOT, 'dist');
const RELEASES_JSON = path.join(DIST_DIR, 'releases.json');

function parseArgs(argv) {
  const out = { upload: false, note: '', apk: '', minCode: 0 };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--upload') out.upload = true;
    else if (a === '--note') out.note = argv[++i] || '';
    else if (a === '--apk') out.apk = argv[++i] || '';
    else if (a === '--min-code') out.minCode = parseInt(argv[++i], 10) || 0;
    else if (a === '--help' || a === '-h') out.help = true;
  }
  return out;
}

/** 从 build.gradle 里抠出 versionCode / versionName */
function readVersion() {
  if (!fs.existsSync(GRADLE_FILE)) {
    throw new Error('找不到 android/app/build.gradle');
  }
  const src = fs.readFileSync(GRADLE_FILE, 'utf8');
  const code = src.match(/versionCode\s+(\d+)/);
  const name = src.match(/versionName\s+"([^"]+)"/);
  if (!code || !name) {
    throw new Error('build.gradle 里没读到 versionCode / versionName');
  }
  return { versionCode: parseInt(code[1], 10), versionName: name[1] };
}

/** 在 dist/ 里找本次要发布的 APK（未指定时按 versionName 精确匹配 release 包） */
function resolveApk(args, versionName) {
  if (args.apk) {
    const p = path.resolve(ROOT, args.apk);
    if (!fs.existsSync(p)) throw new Error(`指定的 APK 不存在: ${args.apk}`);
    return p;
  }
  const want = path.join(DIST_DIR, `AntiFraudGuard-v${versionName}-release.apk`);
  if (!fs.existsSync(want)) {
    const all = fs.existsSync(DIST_DIR)
      ? fs.readdirSync(DIST_DIR).filter((f) => f.endsWith('.apk')).sort()
      : [];
    throw new Error(
      `找不到 dist/AntiFraudGuard-v${versionName}-release.apk。\n` +
        `请先在 android/app/build.gradle 里 bump versionCode/versionName，再跑 build-apk.bat。\n` +
        `dist 现有 APK: ${all.join(', ') || '（空）'}`
    );
  }
  return want;
}

function sha256Of(file) {
  const h = crypto.createHash('sha256');
  h.update(fs.readFileSync(file));
  return h.digest('hex');
}

function main() {
  const args = parseArgs(process.argv);
  if (args.help) {
    console.log(
      '用法: node scripts/publish-release.js [--apk <file>] [--note "更新说明"] [--min-code N] [--upload]'
    );
    return;
  }

  const ver = readVersion();
  const apkPath = resolveApk(args, ver.versionName);
  const fileName = path.basename(apkPath);
  const sizeBytes = fs.statSync(apkPath).size;

  console.log(`版本: ${ver.versionName} (versionCode ${ver.versionCode})`);
  console.log(`文件: dist/${fileName}  ${(sizeBytes / 1024 / 1024).toFixed(2)} MB`);
  console.log('计算 SHA-256 ...');
  const sha256 = sha256Of(apkPath);

  const entry = {
    versionCode: ver.versionCode,
    versionName: ver.versionName,
    fileName,
    sizeBytes,
    sha256,
    changelog: args.note || '优化体验与修复已知问题',
    minSupportedCode: args.minCode || 0,
    publishedAt: new Date().toISOString()
  };

  // 历史只留最近 5 个版本：回滚时要能取到上一个包，
  // 但 R2 里堆几十个 24MB 的 APK 没意义，存储与操作次数都不是免费的。
  let releases = { latest: entry, history: [] };
  if (fs.existsSync(RELEASES_JSON)) {
    try {
      const old = JSON.parse(fs.readFileSync(RELEASES_JSON, 'utf8'));
      const history = [old.latest, ...(Array.isArray(old.history) ? old.history : [])]
        .filter((r) => r && r.versionCode !== entry.versionCode)
        // 同一个 versionCode 重新发布（改说明/换包）时旧条目整体让位
        .slice(0, 5);
      releases = { latest: entry, history };
    } catch (e) {
      console.warn('[warn] 旧发布清单解析失败，本次将覆盖重写:', e.message);
    }
  }

  fs.mkdirSync(DIST_DIR, { recursive: true });
  fs.writeFileSync(RELEASES_JSON, JSON.stringify(releases, null, 2), 'utf8');
  console.log(`\n已写入 dist/releases.json`);
  console.log(`  sha256: ${sha256}`);
  console.log(`  说明:   ${entry.changelog}`);
  if (entry.minSupportedCode > 0) {
    console.log(`  最低可用版本: versionCode >= ${entry.minSupportedCode}（更低的将被强制升级）`);
  }

  if (!args.upload) {
    console.log('\n本地后端已可读到新版本。生产（Cloudflare Workers）还需要传到 R2：');
    console.log(`  node scripts/publish-release.js --apk dist/${fileName} --note "${entry.changelog}" --upload`);
    return;
  }

  uploadToR2(apkPath, fileName);
}

/**
 * 上传到 R2。
 *
 * 用 wrangler CLI 而不是在脚本里手写 R2 API：签名逻辑繁琐且容易过期，
 * CLI 直接复用 workers/wrangler.jsonc 里已有的 bucket 绑定与登录态。
 */
function uploadToR2(apkPath, fileName) {
  const workersDir = path.join(ROOT, 'workers');
  // 刻意不走 node_modules/.bin/wrangler.cmd（那需要 shell:true 起 cmd.exe，
  // 在 Agent 沙箱里会 EBUSY 或直接挂住——实测卡了 20 分钟不输出）。
  // 直接用 node 跑 wrangler 的 JS 入口，绕开 cmd.exe。
  const wranglerJs = path.join(workersDir, 'node_modules', 'wrangler', 'bin', 'wrangler.js');
  const bucket = 'safg-recordings';
  // --remote 必须显式给：wrangler 默认操作的是本地 Miniflare 模拟实例，
  // 不加就会"上传成功"但生产上根本读不到 —— 这种成功提示极具欺骗性。
  const commands = [
    ['r2', 'object', 'put', `${bucket}/releases/${fileName}`, '--file', apkPath, '--remote'],
    ['r2', 'object', 'put', `${bucket}/releases/latest.json`, '--file', RELEASES_JSON, '--remote']
  ];

  console.log('\n上传到 Cloudflare R2 ...');
  for (const cmdArgs of commands) {
    console.log(`  wrangler ${cmdArgs.slice(0, 3).join(' ')} ...`);
    const r = spawnSync(process.execPath, [wranglerJs, ...cmdArgs], {
      cwd: workersDir,
      stdio: 'inherit',
      env: process.env
    });
    if (r.error || r.status !== 0) {
      console.error('\n[失败] wrangler 上传未成功。可能原因：');
      console.error('  1. 未登录（先跑: cd workers && npx wrangler login）');
      console.error('  2. 沙箱/代理环境不允许 spawn（可手工执行下面两条命令）');
      console.error(`     cd workers && wrangler r2 object put ${bucket}/releases/${fileName} --file ../dist/${fileName} --remote`);
      console.error(`     cd workers && wrangler r2 object put ${bucket}/releases/latest.json --file ../dist/releases.json --remote`);
      process.exitCode = 1;
      return;
    }
  }
  console.log('\n生产后端已可分发新版本：');
  console.log('  GET https://guard.chataifree.eu.org/api/app-update/latest?versionCode=<当前>');
}

try {
  main();
} catch (e) {
  console.error(`\n[错误] ${e.message}`);
  process.exitCode = 1;
}
