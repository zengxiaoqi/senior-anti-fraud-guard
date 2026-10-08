/**
 * 把微信公众平台的「服务器域名」同步成 config.js 里的 host
 *
 * 用法: node scripts/mp-publish/sync-wx-domain.js
 *
 * 前置：一个已登录公众平台的 Chrome 调试会话。请用固定端口启动：
 *         scripts\mp-publish\open-wx-console.bat   （默认端口 9222）
 *       脚本会自动向 /json/version 取最新 wsUrl 并写回 MP_WS_FILE，无需手工复制。
 *
 * 为什么必须固定端口：Chrome 若不以 --remote-debugging-port 启动（或端口被别的实例
 * 占用），会退化成随机临时端口，wsUrl 形如 ws://127.0.0.1:49930/... ，会话一结束
 * 就失效 —— 这正是过去手工保存 wsUrl 反复失败的根因。
 *
 * 自动完成：进开发设置 → 滚到服务器域名 → 点修改 → 填 5 个输入框 → 保存并提交
 * 最后弹出管理员扫码二维码（路径见启动日志），扫码后自动轮询校验结果。
 */
const fs = require('fs');
const http = require('http');
const P = require('./paths');

const host = P.readHost();
console.log('待同步域名:', host);

function httpGetJson(url, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(body)); } catch (e) { reject(new Error('bad json')); }
      });
    });
    req.on('error', reject);
    req.setTimeout(timeout, () => req.destroy(new Error('timeout')));
  });
}

function portFromWs(u) {
  const m = /^wss?:\/\/[^:/]+:(\d+)\//.exec(u || '');
  return m ? Number(m[1]) : null;
}

/** 探测本地调试端口拿回有效 wsUrl，并写回 WS_FILE 刷新过期记录 */
async function resolveWsUrl() {
  let saved = null;
  try { saved = fs.readFileSync(P.WS_FILE, 'utf8').trim(); } catch (e) { /* 无历史会话 */ }

  const ports = [];
  [portFromWs(saved), Number(process.env.MP_DEBUG_PORT || 9222)]
    .forEach((p) => { if (p && !ports.includes(p)) ports.push(p); });

  if (saved) console.log('历史会话记录:', saved);

  for (const port of ports) {
    try {
      const info = await httpGetJson(`http://127.0.0.1:${port}/json/version`);
      if (info && info.webSocketDebuggerUrl) {
        try { fs.writeFileSync(P.WS_FILE, `${info.webSocketDebuggerUrl}\n`); } catch (e) { /* 只读盘 */ }
        console.log(`调试会话已就绪（端口 ${port}），已刷新 ${P.WS_FILE}`);
        return info.webSocketDebuggerUrl;
      }
    } catch (e) { /* 换下一个端口 */ }
  }
  return null;
}

let ws, sid = null, msgId = 100;
const pending = new Map();
function send(method, params, sessionId) {
  const id = msgId++;
  const p = { id, method, params: params || {} };
  if (sessionId) p.sessionId = sessionId;
  ws.send(JSON.stringify(p));
  return new Promise((r) => pending.set(id, r));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function attach() {
  const t = await send('Target.getTargets');
  const infos = (t.result && t.result.targetInfos) || [];
  const pages = infos.filter((x) => x.type === 'page');
  if (!pages.length) throw new Error('NO_PAGE');

  const page = pages.find((x) => x.url.includes('mp.weixin.qq.com')) || pages[0];
  if (!page.url.includes('mp.weixin.qq.com')) {
    console.log('当前标签页列表（没有公众平台页面）：');
    pages.forEach((p) => console.log('   -', p.url || '(about:blank)'));
    console.log('请在该 Chrome 里打开并登录 https://mp.weixin.qq.com 后重跑本脚本。');
    throw new Error('NOT_LOGGED_IN');
  }
  console.log('附着页面:', page.url);
  const att = await send('Target.attachToTarget', { targetId: page.targetId, flatten: true });
  sid = att.result.sessionId;
  await send('Runtime.enable', {}, sid);
  await send('Page.enable', {}, sid);
  return page.url;
}

async function ev(expr) {
  const r = await send('Runtime.evaluate',
    { expression: expr, returnByValue: true, awaitPromise: true }, sid);
  const res = r.result && r.result.result;
  return res ? res.value : null;
}

const gotoDev = (token) =>
  `location.href='https://mp.weixin.qq.com/wxamp/devprofile/get_profile?token=${token}&lang=zh_CN';'ok'`;

const SCROLL = `(() => {
  const label = Array.from(document.querySelectorAll('*')).find((e) =>
    e.children.length === 0 && /^request合法域名$/.test((e.textContent || '').trim()));
  if (!label) return 'NO_LABEL';
  label.scrollIntoView({ block: 'center' });
  window.scrollBy(0, -120);
  return 'SCROLLED';
})()`;

const CLICK_EDIT = `(() => {
  const a = Array.from(document.querySelectorAll('a,button'))
    .find((e) => (e.textContent || '').trim() === '修改' && e.offsetParent !== null);
  if (!a) return 'NOT_FOUND';
  a.click();
  return 'CLICKED';
})()`;

// 弹窗定位：不要死守 class 名。实测同一弹窗在不同渲染批次下 class 可能是
// weui-desktop-dialog / weui-desktop-dialog_self / ..._wrp，改用「文案 + 可见」
// 来认，并按文本长度升序取最内层的那个，避免命中外层 wrapper。
const DIALOG_ANY = `(() => {
  const l = Array.from(document.querySelectorAll('[class*=dialog]'))
    .filter((x) => /配置服务器域名/.test(x.textContent || '') && x.offsetParent !== null)
    .sort((a, b) => (a.textContent || '').length - (b.textContent || '').length);
  return l[0] || null;
})()`;

const DIALOG_WITH_AREAS = `(() => {
  const l = Array.from(document.querySelectorAll('[class*=dialog]'))
    .filter((x) => /配置服务器域名/.test(x.textContent || '')
      && x.offsetParent !== null
      && x.querySelectorAll('.url_area').length >= 5)
    .sort((a, b) => (a.textContent || '').length - (b.textContent || '').length);
  return l[0] || null;
})()`;

// 选中弹窗里第 n 个 url_area 的全部内容（为 Input.insertText 覆盖做准备）
const selectArea = (n) => `(() => {
  const d = ${DIALOG_WITH_AREAS};
  if (!d) return 'NO_DIALOG';
  const el = Array.from(d.querySelectorAll('.url_area'))[${n}];
  if (!el) return 'NO_AREA';
  el.focus();
  const r = document.createRange();
  r.selectNodeContents(el);
  const s = window.getSelection();
  s.removeAllRanges();
  s.addRange(r);
  return 'SELECTED';
})()`;

const readArea = (n) => `(() => {
  const d = ${DIALOG_WITH_AREAS};
  if (!d) return 'NO_DIALOG';
  const el = Array.from(d.querySelectorAll('.url_area'))[${n}];
  return el ? (el.textContent || '').trim() : 'NO_AREA';
})()`;

// 弹窗当前可见文案，用于判断扫码确认的成败（'CLOSED' 表示弹窗已不在）
const DIALOG_STATE = `(() => {
  const d = ${DIALOG_ANY};
  if (!d) return 'CLOSED';
  return (d.textContent || '').replace(/\\s+/g, ' ').slice(0, 120);
})()`;

/**
 * 填写 5 个域名输入框。
 *
 * 为什么不用 `el.textContent = ...` 或 `document.execCommand('insertText')`：
 * 这些 .url_area 是 contenteditable DIV，直接改 DOM 文本不会同步到页面自身的数据
 * 模型 —— 视觉上填好了、截图看着也对，点「保存并提交」送出去的仍是旧值，
 * 结果配额照扣、域名纹丝不动（本机实测踩过两次）。
 * Input.insertText 走 CDP 原生输入通道，等同输入法上屏，能真正被框架采纳。
 */
const TARGETS = {
  0: `https://tcb-api.tencentcloudapi.com;https://${host}`, // request 合法域名
  1: `wss://${host}`,                                       // socket 合法域名
  2: `https://${host}`,                                     // uploadFile
  3: `https://${host}`,                                     // downloadFile
  6: host,                                                  // DNS 预解析
};

/** 单个字段：先整段上屏，不成再逐字符打最后核实。
 *  实测整段 insertText 会偶发丢失（5 项里漏 2 项），提交残缺数据会白扣配额。 */
async function fillOne(idx, want) {
  const backoff = [0, 300, 800];
  for (let attempt = 0; attempt < backoff.length; attempt += 1) {
    const sel = await ev(selectArea(idx));
    if (sel !== 'SELECTED') return `SELECT_FAIL(${sel})`;
    if (backoff[attempt]) await sleep(backoff[attempt]);

    if (attempt < 2) {
      await send('Input.insertText', { text: want }, sid);
    } else {
      // 退化为逐字符敲击，模拟真实键盘
      for (const ch of want) {
        await send('Input.dispatchKeyEvent', { type: 'char', text: ch }, sid);
      }
    }
    await sleep(250);
    const got = await ev(readArea(idx));
    if (got === want) return attempt === 0 ? 'OK' : `OK(attempt${attempt + 1})`;
    if (attempt === backoff.length - 1) return `MISMATCH: ${got}`;
  }
  return 'FAILED';
}

async function fillAreas() {
  const out = [];
  for (const key of Object.keys(TARGETS)) {
    out.push([key, await fillOne(Number(key), TARGETS[key])]);
    await sleep(200);
  }
  // 不要在这里 blur / 点空白：实测 any blur 会让配置弹窗直接收起（ trap-focus 设计）
  return out;
}

const SUBMIT = `(() => {
  const d = ${DIALOG_ANY};
  if (!d) return 'NO_DIALOG';
  const root = d.closest('[class*=dialog__wrp]') || d.parentElement || d;
  const btn = Array.from(root.querySelectorAll('button, a'))
    .find((b) => /保存并提交/.test((b.textContent || '').trim()) && b.offsetParent !== null);
  if (!btn) return 'NO_BTN';
  btn.click();
  return 'SUBMITTED';
})()`;

const CHECK = `(() => (document.body.textContent || '').includes(${JSON.stringify(host)}) ? 'OK' : 'PENDING')()`;

async function shot() {
  const r = await send('Page.captureScreenshot', { format: 'png' }, sid);
  const b64 = r.result && r.result.data;
  if (!b64) return false;
  fs.writeFileSync(P.QR_OUT, Buffer.from(b64, 'base64'));
  console.log('二维码已保存:', P.QR_OUT, fs.statSync(P.QR_OUT).size, 'bytes');
  return true;
}

async function main() {
  const url = await attach();
  const m = url.match(/token=(\d+)/);
  // token 每次登录都会变，优先从当前页面 URL 提取
  const token = m ? m[1] : '';
  if (!token) {
    console.error('当前页面不是公众平台页面，无法提取 token，请先登录后重试。当前:', url);
    return;
  }
  console.log('当前 token:', token);

  console.log('1/5 打开开发设置...');
  await ev(gotoDev(token));
  await sleep(7000);

  console.log('2/5 定位服务器域名区...', await ev(SCROLL));
  await sleep(1500);

  console.log('3/5 点击修改...', await ev(CLICK_EDIT));
  await sleep(5000);

  console.log('4/5 填写域名...');
  const filled = await fillAreas();
  console.log('    ', JSON.stringify(filled));
  const bad = filled.filter((x) => !/^OK/.test(String(x[1])));
  if (bad.length) {
    console.error('填写校验未通过，已中止以免白扣配额:', JSON.stringify(bad));
    return;
  }
  await sleep(1000);

  console.log('5/5 保存并提交...', await ev(SUBMIT));
  await sleep(5000);
  await shot();

  console.log('\n>>> 请用管理员微信扫描二维码确认');
  console.log('>>> 扫码后自动校验；等待期间**不会**刷新页面\n');

  // 铁律：等待扫码期间绝不能导航/刷新。重载会销毁待确认的二维码弹窗，
  // 表现为「扫了也白扫、配额照扣」。轮询只读弹窗文案，靠它消失来判断确认完成。
  let lastState = await ev(DIALOG_STATE);
  for (let i = 0; i < 60; i += 1) {
    await sleep(3000);
    const st = await ev(DIALOG_STATE);
    if (st !== lastState) {
      console.log('二维码状态变化 ->', st);
      await shot();
      lastState = st;
    }
    if (st === 'CLOSED') {
      console.log('配置弹窗已关闭，核对页面真实域名 ...');
      await ev(gotoDev(token));
      await sleep(6000);
      const ok = await ev(CHECK);
      console.log(ok === 'OK'
        ? `后台服务器域名已同步为 ${host}`
        : `弹窗已关闭但域名尚未生效（${ok}）。请再跑一次本脚本，或直接对页面截图人工核对。`);
      return;
    }
    if (i % 10 === 9) console.log(`    仍在等待扫码确认... (${(i + 1) * 3}s)`);
  }
  console.log('等待超时（二维码可能已过期），请再跑一次本脚本。');
}

async function start() {
  const wsUrl = await resolveWsUrl();

  if (!wsUrl) {
    console.error('\n[FAIL] 连不上浏览器调试端口。');
    console.error('  已尝试端口 9222（及历史会话里记录的端口），均无响应。');
    console.error('  解决办法：运行 scripts\\mp-publish\\open-wx-console.bat');
    console.error('  它会用固定端口 9222 拉起一个专用 Chrome 实例，登录公众平台后再跑本脚本。');
    console.error('  （Chrome 必须真正活着且未被其它实例抢占该 profile。）');
    process.exit(1);
  }

  ws = new WebSocket(wsUrl);
  ws.addEventListener('open', () => { main().catch((e) => console.error('ERR', e.message)); });
  ws.addEventListener('message', (e2) => {
    const msg = JSON.parse(e2.data);
    if (msg.id && pending.has(msg.id)) {
      const r = pending.get(msg.id);
      pending.delete(msg.id);
      r(msg);
    }
  });
  ws.addEventListener('error', (e) => console.error('WebSocket 错误:', e.message || e));
  setTimeout(() => { try { ws.close(); } catch (e) { /* ignore */ } process.exit(0); }, 300000);
}

start().catch((e) => { console.error('启动失败:', e.message); process.exit(1); });
