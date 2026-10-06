// 通过 CDP 直接操作已登录的 Chrome（agent-browser 守护进程挂掉时的备选通道）
// 用法:
//   node cdp.js eval   <wsUrl> <jsFile>
//   node cdp.js shot   <wsUrl> <outPng>
//   node cdp.js click  <wsUrl> <linkText>
//   node cdp.js url    <wsUrl>
const fs = require('fs');
const [cmd, wsUrl, arg] = process.argv.slice(2);

let ws, sid = null;
let msgId = 100;
const pending = new Map();
function send(method, params, sessionId) {
  const id = msgId++;
  const p = { id, method, params: params || {} };
  if (sessionId) p.sessionId = sessionId;
  ws.send(JSON.stringify(p));
  return new Promise((r) => pending.set(id, r));
}

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

async function main() {
  const pageUrl = await attach();
  if (cmd === 'url') { console.log(pageUrl); return; }

  if (cmd === 'eval') {
    const expr = fs.readFileSync(arg, 'utf8');
    const r = await send('Runtime.evaluate',
      { expression: expr, returnByValue: true, awaitPromise: true }, sid);
    const res = r.result && r.result.result;
    console.log(res ? JSON.stringify(res.value) : JSON.stringify(r.error || r));
    return;
  }

  if (cmd === 'click') {
    const expr = `(() => {
      const a = Array.from(document.querySelectorAll('a,button'))
        .find(e => e.textContent.trim() === ${JSON.stringify(arg)});
      if (!a) return 'NOT_FOUND';
      a.click();
      return 'CLICKED';
    })()`;
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true }, sid);
    console.log(r.result && r.result.result && r.result.result.value);
    return;
  }

  if (cmd === 'shot') {
    const r = await send('Page.captureScreenshot', { format: 'png' }, sid);
    const b64 = r.result && r.result.data;
    if (!b64) { console.log('SHOT_FAILED', JSON.stringify(r.error || '')); return; }
    fs.writeFileSync(arg, Buffer.from(b64, 'base64'));
    console.log('saved', arg, fs.statSync(arg).size, 'bytes');
    return;
  }
  console.log('unknown cmd');
}

ws = new WebSocket(wsUrl);
ws.addEventListener('open', () => { main().catch((e) => console.error('ERR', e.message)); });
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const r = pending.get(m.id); pending.delete(m.id); r(m); }
});
ws.addEventListener('error', (e) => console.error('ws error', e.message || e));
setTimeout(() => { try { ws.close(); } catch (e) {} process.exit(0); }, 25000);
