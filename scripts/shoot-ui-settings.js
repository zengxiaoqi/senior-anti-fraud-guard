#!/usr/bin/env node
/**
 * 在设备上截图核对「字体大小 / 深浅主题」是否真的生效。
 *
 *   node scripts/shoot-ui-settings.js              # 截图深色+浅色+超大字体
 *   node scripts/shoot-ui-settings.js --install    # 先装上 dist 里最新的 release 包
 *
 * 为什么需要它：主题与字体是**全局**改动，编译通过不代表界面正确 ——
 * 布局里曾有 200 多处硬编码颜色，漏改一处的表现是"这一块在浅色模式下还是深色底白字"。
 * 只有把四张截图摆在一起对比，才能确认没有漏网之鱼。
 *
 * 截图输出：dist/screens/*.png
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const PKG = 'com.antifraud.guard';
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'dist', 'screens');

const CANDIDATE_ADB = [
  'D:\\Android\\platform-tools\\adb.exe',
  path.join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb.exe'),
  path.join(process.env.ANDROID_SDK_ROOT || '', 'platform-tools', 'adb.exe')
];

function resolveAdb() {
  for (const p of CANDIDATE_ADB) {
    if (p && fs.existsSync(p)) return p;
  }
  const r = spawnSync('adb', ['version'], { encoding: 'utf8' });
  return r.error ? null : 'adb';
}

const adb = resolveAdb();
const log = (...a) => console.log(...a);

function sh(args) {
  if (!adb) return { ok: false, out: '', err: 'adb 不可用' };
  const r = spawnSync(adb, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  return { ok: !r.error && r.status === 0, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function dumpUi() {
  const file = '/sdcard/__afg_ui.xml';
  // 先删旧文件再 dump：uiautomator 在界面刚切换后偶发 "could not get idle state"
  // 而静默失败，cat 会读回上一次的旧布局，脚本就会对着过期界面找按钮
  sh(['shell', 'rm', '-f', file]);
  for (let i = 0; i < 3; i++) {
    sh(['shell', 'uiautomator', 'dump', file]);
    const r = sh(['shell', 'cat', file]);
    if (r.out.includes('<node')) return r.out;
    await sleep(800);
  }
  return '';
}

function findExact(xml, key) {
  for (const n of (xml.match(/<node[^>]*>/g) || [])) {
    const tm = n.match(/text="([^"]*)"/);
    const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!tm || !bm || tm[1] !== key) continue;
    const [, x1, y1, x2, y2] = bm.map(Number);
    return { cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2) };
  }
  return null;
}

// 按钮可能在滚动区下方：找不到就上滑再找，最多 4 次。
// 起点 y 不能贴屏幕底边(1600)：全面屏手势下"从底边上滑"是返回桌面，
// 会直接把 App 退到后台 —— 必须从内容区中部起手。
async function tapText(key) {
  for (let i = 0; i < 4; i++) {
    const xml = await dumpUi();
    const n = findExact(xml, key);
    if (n) {
      sh(['shell', 'input', 'tap', String(n.cx), String(n.cy)]);
      return true;
    }
    sh(['shell', 'input', 'swipe', '540', '1400', '540', '700', '400']);
    await sleep(900);
  }
  log(`   找不到「${key}」`);
  return false;
}

async function shoot(name) {
  const remote = `/sdcard/__afg_${name}.png`;
  sh(['shell', 'screencap', '-p', remote]);
  const local = path.join(OUT, `${name}.png`);
  const r = spawnSync(adb, ['pull', remote, local], { encoding: 'utf8' });
  const ok = !r.error && fs.existsSync(local);
  log(`   ${ok ? '✓' : '✗'} ${name}.png`);
  return ok;
}

async function main() {
  const doInstall = process.argv.includes('--install');
  fs.mkdirSync(OUT, { recursive: true });

  // 找 dist 里最新的 release 包
  const apks = fs.readdirSync(path.join(ROOT, 'dist'))
    .filter((f) => /^AntiFraudGuard-v.*-release\.apk$/.test(f))
    .sort();
  const apk = apks[apks.length - 1];

  if (doInstall && apk) {
    log(`安装 ${apk} …`);
    const r = sh(['install', '-r', path.join(ROOT, 'dist', apk)]);
    log('   ' + (r.out || r.err).split('\n').slice(-1)[0]);
  }

  log('启动 App（MainActivity 未 exported，只能从角色选择页进）…');
  sh(['shell', 'am', 'force-stop', PKG]);
  await sleep(800);
  sh(['shell', 'am', 'start', '-n', `${PKG}/.RoleSelectActivity`]);
  await sleep(2500);

  // 设备上可能停在老人端/子女端，先看当前界面有没有「⚙️ 设置」
  let xml = await dumpUi();
  if (!findExact(xml, '⚙️ 设置')) {
    const elder = findExact(xml, '老人端');
    if (elder) {
      sh(['shell', 'input', 'tap', String(elder.cx), String(elder.cy)]);
      await sleep(3500);
    }
  }

  log('进入设置页…');
  if (!await tapText('⚙️ 设置')) { log('✗ 没进到设置页'); return 1; }
  await sleep(2500);
  await shoot('1-settings-dark-standard');

  log('切到浅色主题…');
  await tapText('浅色');
  await sleep(2500);
  await shoot('2-settings-light-standard');

  log('切到超大字体…');
  await tapText('超大');
  await sleep(2500);
  await shoot('3-settings-light-xlarge');

  log('切回深色主题…');
  await tapText('深色');
  await sleep(2500);
  await shoot('4-settings-dark-xlarge');

  log('返回首页看整体…');
  sh(['shell', 'input', 'keyevent', 'KEYCODE_BACK']);
  await sleep(2000);
  await shoot('5-home-dark-xlarge');

  log(`\n截图已保存到 ${OUT}`);
  log('请逐一核对：同一屏在深色/浅色下文字是否都清晰；超大档是否只是字变大而没有挤成一团。');
  return 0;
}

main().then((code) => { console.log('\n退出码 ' + code); }).catch((e) => { console.error(e); });
