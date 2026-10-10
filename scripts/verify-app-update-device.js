#!/usr/bin/env node
/**
 * 真机一键验证 App 内自升级。
 *
 *   node scripts/verify-app-update-device.js            # 全流程验证
 *   node scripts/verify-app-update-device.js --install  # 先装上 dist 里最新的 release 包再测
 *   node scripts/verify-app-update-device.js --no-tap   # 只确认弹窗出现，不点「立即升级」
 *
 * 为什么需要它：升级链路里任何一环断掉，用户看到的都是"点了没反应"，
 * 而服务端日志一切正常。只有真的在设备上走一遍——弹窗是否出现、
 * 进度是否推进、系统安装器是否被拉起——才算验证过。
 *
 * 注意：adb 直接用 spawnSync 调 adb.exe，不走 cmd.exe（沙箱里 cmd 会 EBUSY）。
 */
const fs = require('fs');
const path = require('path');
const https = require('https');
const { spawnSync } = require('child_process');

const PKG = 'com.antifraud.guard';
const BASE = 'https://guard.chataifree.eu.org';
const ROOT = path.resolve(__dirname, '..');

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
  if (!r.error) return 'adb';
  return null;
}

const adb = resolveAdb();

function sh(args, opts = {}) {
  if (!adb) return { ok: false, out: '', err: 'adb 不可用' };
  const r = spawnSync(adb, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, ...opts });
  return { ok: !r.error && r.status === 0, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}

const log = (...a) => console.log(...a);
const bar = (s) => log('\n' + '─'.repeat(60) + `\n${s}\n` + '─'.repeat(60));

function httpGet(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { rejectUnauthorized: false }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return httpGet(res.headers.location).then(resolve, reject);
      }
      let buf = [];
      res.on('data', (d) => buf.push(d));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(buf) }));
    }).on('error', reject);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 界面抓取 ────────────────────────────────────────────────
// 用 uiautomator dump 读当前界面：弹窗文案、按钮坐标都要从真实的视图树拿，
// 猜坐标在不同分辨率/ROM 上必错。

let dumpFallback = false;

function dumpUi() {
  const file = '/sdcard/__afg_wd.xml';
  if (!dumpFallback) {
    sh(['shell', 'uiautomator', 'dump', file]);
    const r = sh(['shell', 'cat', file]);
    if (r.out.includes('<node')) return r.out;
    dumpFallback = true;
  }
  const r2 = sh(['exec-out', 'uiautomator', 'dump', '/dev/tty']);
  return r2.out.includes('<node') ? r2.out : '';
}

/** 找文本包含 key 的节点，返回 { text, cx, cy } */
function findNode(xml, key) {
  if (!xml) return null;
  const nodes = xml.match(/<node[^>]*>/g) || [];
  for (const n of nodes) {
    const tm = n.match(/text="([^"]*)"/);
    const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!tm || !bm) continue;
    if (!tm[1].includes(key)) continue;
    const [, x1, y1, x2, y2] = bm.map(Number);
    return { text: tm[1], cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2) };
  }
  return null;
}

/** 找可勾选的开关节点（系统设置页里的 Switch） */
function findSwitch(xml) {
  const nodes = xml.match(/<node[^>]*>/g) || [];
  for (const n of nodes) {
    if (!/checkable="true"/.test(n)) continue;
    const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!bm) continue;
    const [, x1, y1, x2, y2] = bm.map(Number);
    return { cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2), raw: n };
  }
  return null;
}

/** 文本完全相等才算命中（按钮文案很短，包含匹配会误点到长句子） */
function findExact(xml, key) {
  if (!xml) return null;
  for (const n of (xml.match(/<node[^>]*>/g) || [])) {
    const tm = n.match(/text="([^"]*)"/);
    const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!tm || !bm || tm[1] !== key) continue;
    const [, x1, y1, x2, y2] = bm.map(Number);
    return { text: tm[1], cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2) };
  }
  return null;
}

function screenText(xml) {
  const texts = [];
  for (const n of (xml.match(/<node[^>]*>/g) || [])) {
    const tm = n.match(/text="([^"]*)"/);
    if (tm && tm[1]) texts.push(tm[1]);
  }
  return texts.join(' | ');
}

// ── 主流程 ──────────────────────────────────────────────────

async function main() {
  const argv = process.argv.slice(2);
  const doInstall = argv.includes('--install');
  const noTap = argv.includes('--no-tap');

  bar('1/6  连接设备');
  if (!adb) {
    log('✗ 找不到 adb。请确认 SDK platform-tools 在 D:\\Android\\platform-tools');
    return 1;
  }
  const dev = sh(['devices', '-l']);
  const online = dev.out.split('\n').filter((l) => /\sdevice(\s|$)/.test(l) && !l.startsWith('List'));
  if (!online.length) {
    log('✗ 没有已连接的设备。请用 USB 连接并开启「USB 调试」，或：');
    log('   手机：开发者选项 → 无线调试 → 使用配对码配对');
    log(`   然后：${adb} connect <手机IP>:<端口>`);
    return 1;
  }
  log('✓ 设备：', online[0].trim());

  if (doInstall) {
    const distDir = path.join(ROOT, 'dist');
    const apks = fs.readdirSync(distDir)
      .filter((f) => f.endsWith('-release.apk'))
      .sort((a, b) => fs.statSync(path.join(distDir, b)).mtimeMs - fs.statSync(path.join(distDir, a)).mtimeMs);
    if (!apks.length) return log('✗ dist 里没有 release 包'), 1;
    const apk = path.join(distDir, apks[0]);
    log(`   安装 ${apks[0]} …`);
    const r = sh(['install', '-r', apk]);
    log(r.ok ? '✓ 安装完成' : '✗ 安装失败：' + r.out + r.err);
    if (!r.ok) return 1;
  }

  bar('2/6  读取设备上的版本');
  const dumpsys = sh(['shell', 'dumpsys', 'package', PKG]);
  const codeM = dumpsys.out.match(/versionCode=(\d+)/);
  const nameM = dumpsys.out.match(/versionName=([\d.]+)/);
  const deviceCode = codeM ? Number(codeM[1]) : 0;
  const deviceName = nameM ? nameM[1] : '未知';
  if (!deviceCode) {
    log('✗ 读不到 versionCode，应用可能没装：', dumpsys.out.slice(0, 200) || dumpsys.err);
    return 1;
  }
  log(`✓ 设备版本：v${deviceName}（versionCode ${deviceCode}）`);
  if (deviceCode < 16) {
    log('\n⚠ 这台设备是 v1.7.4 之前的版本，里面没有升级代码，不可能自己升上来。');
    log('  必须手动装一次：手机浏览器打开 https://guard.chataifree.eu.org/api/app-update/download');
    log(`  或重跑本脚本加 --install（adb install -r）`);
    return 1;
  }

  bar('3/6  问服务端：这台设备该不该收到升级提示');
  const meta = JSON.parse((await httpGet(`${BASE}/api/app-update/latest?versionCode=${deviceCode}`)).body.toString());
  log('   服务端回应：', JSON.stringify({
    hasUpdate: meta.hasUpdate,
    reason: meta.reason,
    latest: meta.latest && { v: meta.latest.versionName, code: meta.latest.versionCode }
  }));
  if (!meta.hasUpdate) {
    log('\n✓ 设备已是最新版，查询链路正常（客户端能问、服务端能答、判定正确）。');
    log('  要验证「下载→安装」这一段，需要先发一个更高的 versionCode：');
    log('    build.gradle versionCode +1 → build-apk.bat → node scripts/publish-release.js --upload');
    log('  发完重跑本脚本。');
    return 0;
  }

  bar('4/6  拉起老人端主页，触发启动静默检查');
  // 先替用户把「允许安装未知应用」开了：这是系统开关，真机上必须手动点一次，
  // 自动化验证里用 appops 代劳，否则脚本会卡在授权引导那一步。
  sh(['shell', 'appops', 'set', PKG, 'REQUEST_INSTALL_PACKAGES', 'allow']);
  sh(['logcat', '-c']);
  // 先强制停止：上一轮留在屏幕上的对话框会污染视图树，导致新一轮读到假界面
  sh(['shell', 'am', 'force-stop', PKG]);
  await sleep(1500);
  // MainActivity / SettingsActivity 都没有 exported=true，adb 直接 am start 会被
  // 系统以 SecurityException 拒绝（实测如此）。只能从导出的启动入口 RoleSelectActivity 进，
  // 再点「老人端」进主页，让 onCreate 里的启动检查自然跑起来。
  sh(['shell', 'am', 'start', '-n', `${PKG}/.RoleSelectActivity`]);
  await sleep(4000);
  let enter = dumpUi();
  const elder = findNode(enter, '老人端');
  if (elder) {
    sh(['shell', 'input', 'tap', String(elder.cx), String(elder.cy)]);
    await sleep(5000);
  }

  let xml = dumpUi();
  log('   当前界面：', screenText(xml).slice(0, 300) || '(读不到视图树)');
  let found = findNode(xml, '发现新版本') || findNode(xml, '必须升级');

  // 兜底：启动检查受"同一版本一天一次"节流限制，验证时可能今天已经提示过了。
  // 设置页的「检查更新」按钮走的是手动检查，不受节流影响，一定能出弹窗。
  if (!found) {
    log('   启动检查没弹窗（可能今天已提示过），改走设置页手动检查 …');
    // 老人端首页是横向分页的：设置入口「⚙️ 更多设置」在第 2 页，
    // 得先左右翻页才看得到（这就是 UI 自动化必须先读真实视图树的原因）。
    let moreBtn = null;
    for (let i = 0; i < 3 && !moreBtn; i++) {
      moreBtn = findNode(dumpUi(), '更多设置');
      if (moreBtn) break;
      sh(['shell', 'input', 'swipe', '900', '1000', '200', '1000', '400']);
      await sleep(1500);
    }
    if (moreBtn) {
      sh(['shell', 'input', 'tap', String(moreBtn.cx), String(moreBtn.cy)]);
      log('   → 进入设置页');
      await sleep(3500);
    } else {
      log('   → 首页上没找到「更多设置」：', screenText(dumpUi()).slice(0, 160));
    }
    // 「版本与升级」卡片在设置页最底部，ScrollView 里默认看不见，得往下滑才找得到
    let btn = null;
    for (let i = 0; i < 5 && !btn; i++) {
      const sxml = dumpUi();
      btn = findNode(sxml, '检查更新');
      if (btn) break;
      sh(['shell', 'input', 'swipe', '540', '1800', '540', '500', '400']);
      await sleep(1500);
    }
    if (btn) {
      sh(['shell', 'input', 'tap', String(btn.cx), String(btn.cy)]);
      await sleep(4000);
      xml = dumpUi();
      found = findNode(xml, '发现新版本') || findNode(xml, '必须升级');
    } else {
      log('   设置页上没找到「检查更新」：', screenText(dumpUi()).slice(0, 200));
    }
  }

  if (!found) {
    log('\n✗ 没等到「发现新版本」弹窗。可能原因：');
    log('   · 同一版本今天已经提示过（节流：一天一次）→ 换台设备或改系统日期');
    log('   · 启动检查没跑到 → 看下面 logcat');
    printLogcat();
    return 1;
  }
  log('✓ 弹窗已出现：', found.text);

  if (noTap) {
    log('\n（--no-tap：跳过点击）');
    return 0;
  }

  bar('5/6  点「立即升级」，盯下载进度');
  const btn = findNode(xml, '立即升级');
  if (!btn) return log('✗ 找不到「立即升级」按钮'), 1;
  sh(['shell', 'input', 'tap', String(btn.cx), String(btn.cy)]);
  log(`   已点击 (${btn.cx}, ${btn.cy})`);

  // 「允许安装未知应用」这一步只能由用户（或系统设置页）授予，adb 也代劳不了：
  // 很多 ROM/模拟器根本没有 appops 命令（实测这台上 appops 直接 not found）。
  // 所以这里完全按真实用户的路线走：点「去设置」→ 打开开关 → 返回，
  // 返回后 App 的 onResume 会自动接着下载。
  await sleep(3000);
  let afterTap = dumpUi();
  if (screenText(afterTap).includes('需要开启一次安装授权')) {
    log('   → 出现授权引导，走系统设置页开开关');
    const goSet = findNode(afterTap, '去设置');
    if (!goSet) return log('✗ 找不到「去设置」按钮'), 1;
    sh(['shell', 'input', 'tap', String(goSet.cx), String(goSet.cy)]);
    await sleep(3500);

    const sw = findSwitch(dumpUi());
    if (sw) {
      sh(['shell', 'input', 'tap', String(sw.cx), String(sw.cy)]);
      log('   → 已打开「允许来自此来源的应用」');
      await sleep(1500);
    } else {
      log('   → 设置页没找到开关，界面：', screenText(dumpUi()).slice(0, 200));
    }
    sh(['shell', 'input', 'keyevent', 'KEYCODE_BACK']);
    await sleep(3000);
  }

  let lastPct = -1;
  let sawInstaller = false;
  const deadline = Date.now() + 8 * 60 * 1000;

  while (Date.now() < deadline) {
    await sleep(5000);
    const cur = dumpUi();
    const txt = screenText(cur);
    const pctM = txt.match(/已下载\s*(\d+)%/);
    if (pctM) {
      const p = Number(pctM[1]);
      if (p !== lastPct) {
        log(`   ${new Date().toLocaleTimeString('zh-CN')}  进度 ${p}%`);
        lastPct = p;
      }
      continue;
    }
    if (txt.includes('下载失败') || txt.includes('校验失败')) {
      log('✗ 界面报错：', txt.slice(0, 200));
      printLogcat();
      return 1;
    }
    // 下载对话框消失后，看前台是不是系统安装器
    const focus = sh(['shell', 'dumpsys', 'window']).out;
    const fm = focus.match(/mCurrentFocus=.*?([a-zA-Z0-9._]+)\/([a-zA-Z0-9._]+)/);
    const topPkg = fm ? fm[1] : '';
    if (topPkg && topPkg !== PKG) {
      log(`✓ 已拉起系统界面：${topPkg}/${fm[2]}`);
      sawInstaller = /packageinstaller|installer|package-archive|huawei|miui|oppo|vivo/i.test(topPkg + fm[2])
        ? true
        : null;
      break;
    }
  }

  bar('6/6  在系统安装器里点「安装」，确认版本真的变了');
  log(`   点击前版本：v${deviceName}（${deviceCode}）`);

  let finalCode = deviceCode;
  const installDeadline = Date.now() + 2 * 60 * 1000;
  while (Date.now() < installDeadline) {
    const cur = dumpUi();
    // 系统安装器的确认按钮：中文 ROM 是「安装」，英文是 Install，
    // 部分 ROM 先过一屏安全扫描（「继续」/「确定」/「允许」）
    // 必须精确匹配：用包含匹配会把「需要开启一次安装授权」的说明文字也当成按钮点，
    // 结果是反复点对话框正文、什么都没发生（实测踩过）。
    // 覆盖安装时系统按钮是「更新」（首次安装才是「安装」），两个都得认
    const go = findExact(cur, '更新') || findExact(cur, '安装') || findExact(cur, 'Install') ||
      findExact(cur, '继续') || findExact(cur, '确定');
    if (go) {
      log(`   点「${go.text}」 (${go.cx}, ${go.cy})`);
      sh(['shell', 'input', 'tap', String(go.cx), String(go.cy)]);
    }
    await sleep(6000);
    const ds = sh(['shell', 'dumpsys', 'package', PKG]);
    const m = ds.out.match(/versionCode=(\d+)/);
    if (m && Number(m[1]) !== deviceCode) {
      finalCode = Number(m[1]);
      break;
    }
    // 安装器可能已经自己装完并退回桌面
    const focus = sh(['shell', 'dumpsys', 'window']).out;
    const fm = focus.match(/mCurrentFocus=.*?([a-zA-Z0-9._]+)\//);
    if (!go && fm && fm[1] === PKG) break;
  }

  // 只认"版本确实往上走了"：临时把清单写成更高 code 来验证时，
  // 装上去的包的真实 code 可能低于清单值（比如清单写 18、包其实是 17），
  // 这时候仍然说明下载+安装+校验整条链路是通的。
  const expected = meta.latest && Number(meta.latest.versionCode);
  if (finalCode > deviceCode) {
    log(`\n✅ 完整链路通过：${deviceCode} → ${finalCode}（清单声明 ${expected}）`);
    log('   查版本 → 弹窗 → 下载 → SHA-256 校验 → FileProvider → 系统安装 → 版本真的变了');
  } else if (finalCode === deviceCode) {
    log(`\n✗ 安装界面拉起了，但版本仍停在 ${deviceCode}。`);
    log('  可能是系统安装器还需要人工确认（国产 ROM 的风险确认页），或用户取消了。');
  } else {
    log(`\n? 版本变成了 ${finalCode}（期望 ${expected}），请人工核对。`);
  }
  printLogcat();
  return finalCode > deviceCode ? 0 : 1;
}

function printLogcat() {
  const lc = sh(['logcat', '-d', '-v', 'time', '-s', 'AppUpdate:*', 'AppUpdateUi:*']);
  if (lc.out) {
    log('\n--- 设备日志 (AppUpdate) ---');
    log(lc.out.split('\n').slice(-40).join('\n'));
  }
}

main().then((code) => {
  console.log('\n退出码:', code);
}).catch((e) => {
  console.error('脚本异常:', e.message);
});
