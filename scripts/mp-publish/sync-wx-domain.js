/**
 * 把微信公众平台的「服务器域名」同步成 config.js 里的 host
 *
 * 用法: node scripts/mp-publish/sync-wx-domain.js
 *
 * 前置：一个已登录公众平台的 Chrome 调试会话，wsUrl 存在 MP_WS_FILE（默认 D:/mp-ci/.ws）
 *
 * 自动完成：进开发设置 → 滚到服务器域名 → 点修改 → 填 5 个输入框 → 保存并提交
 * 最后弹出管理员扫码二维码（路径见启动日志），扫码后自动轮询校验结果。
 */
const fs = require('fs');
const P = require('./paths');

const host = P.readHost();
console.log('待同步域名:', host);

if (!fs.existsSync(P.WS_FILE)) {
  console.error('找不到浏览器调试会话文件:', P.WS_FILE);
  console.error('请先用 --remote-debugging-port 启动 Chrome 并登录公众平台，');
  console.error('把 wsUrl 写入该文件，或用环境变量 MP_WS_FILE 指向它。');
  process.exit(1);
}
const wsUrl = fs.readFileSync(P.WS_FILE, 'utf8').trim();

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
  const page = infos.find((x) => x.type === 'page' && x.url.includes('mp.weixin.qq.com'))
    || infos.find((x) => x.type === 'page');
  if (!page) throw new Error('NO_PAGE');
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

const FILL = `(() => {
  const D = ${JSON.stringify(host)};
  const areas = Array.from(document.querySelectorAll('.url_area')).filter((e) => e.offsetParent !== null);
  const target = {
    0: 'https://tcb-api.tencentcloudapi.com;https://' + D,  // request 合法域名
    1: 'wss://' + D,                                        // socket 合法域名
    2: 'https://' + D,                                      // uploadFile
    3: 'https://' + D,                                      // downloadFile
    6: D,                                                   // DNS 预解析
  };
  const out = [];
  Object.keys(target).forEach((k) => {
    const el = areas[Number(k)];
    if (!el) { out.push([k, 'MISSING']); return; }
    el.focus();
    el.textContent = target[k];
    el.dispatchEvent(new InputEvent('input', { bubbles: true, data: target[k] }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    out.push([k, target[k]]);
  });
  return out;
})()`;

const SUBMIT = `(() => {
  const d = Array.from(document.querySelectorAll('.weui-desktop-dialog'))
    .find((x) => /配置服务器域名/.test(x.textContent || '') && x.offsetParent !== null);
  if (!d) return 'NO_DIALOG';
  const btn = Array.from(d.parentElement.querySelectorAll('button'))
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

  console.log('4/5 填写域名...', JSON.stringify(await ev(FILL)));
  await sleep(1000);

  console.log('5/5 保存并提交...', await ev(SUBMIT));
  await sleep(5000);
  await shot();

  console.log('\n>>> 请用管理员微信扫描二维码确认');
  console.log('>>> 扫码后自动校验，最多等 150 秒\n');

  for (let i = 0; i < 30; i += 1) {
    await sleep(5000);
    if ((await ev(CHECK)) === 'OK') {
      console.log('后台服务器域名已同步为', host);
      return;
    }
    if (i % 4 === 3) console.log(`    仍在等待扫码确认... (${(i + 1) * 5}s)`);
  }
  console.log('等待超时，请确认是否完成扫码；未生效可再跑一次本脚本。');
}

ws = new WebSocket(wsUrl);
ws.addEventListener('open', () => { main().catch((e) => console.error('ERR', e.message)); });
ws.addEventListener('message', (ev2) => {
  const msg = JSON.parse(ev2.data);
  if (msg.id && pending.has(msg.id)) { const r = pending.get(msg.id); pending.delete(msg.id); r(msg); }
});
ws.addEventListener('error', (e) => console.error('无法连接浏览器调试端口:', e.message || e));
setTimeout(() => { try { ws.close(); } catch (e) {} process.exit(0); }, 200000);
