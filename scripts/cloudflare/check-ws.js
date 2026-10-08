/**
 * 验证 wss 能否穿过 Cloudflare Tunnel。
 *
 * 本机上级 DNS（路由器）对隧道域名返回空应答，所以必须自己指定公共 DNS
 * 解析，再用 ws 的 lookup 钩子直连 IP、靠 servername 传 SNI。
 *
 * 用法: node scripts/cloudflare/check-ws.js [host]
 */
const dns = require('dns');
const WebSocket = require('ws');

const HOST = process.argv[2] || require('../mp-publish/paths').tunnelHostname();
const PUBLIC_DNS = ['223.5.5.5', '1.1.1.1'];

(async () => {
  if (!HOST) {
    console.error('未指定域名，也没能从 scripts/cloudflare/hostname.txt 读到。');
    process.exit(1);
  }
  const resolver = new dns.promises.Resolver();
  resolver.setServers(PUBLIC_DNS);

  let ip;
  try {
    const ips = await resolver.resolve4(HOST);
    ip = ips[0];
  } catch (e) {
    console.error(`DNS 解析失败(${PUBLIC_DNS.join('/')}): ${e.message}`);
    process.exit(1);
  }
  console.log(`域名 ${HOST} -> ${ip} (via ${PUBLIC_DNS[0]})`);

  // Node 的 lookup 钩子：opts.all 为真时期望返回数组，否则返回字符串地址。
  // 只兼容一种会报 "Invalid IP address: undefined"。
  const forceIp = (hostname, opts, cb) => {
    if (opts && opts.all) return cb(null, [{ address: ip, family: 4 }]);
    return cb(null, ip, 4);
  };

  const ws = new WebSocket(`wss://${HOST}`, {
    lookup: forceIp,
    servername: HOST,
  });

  const done = (ok, detail) => {
    console.log(ok ? `WS OK: ${detail}` : `WS FAIL: ${detail}`);
    try { ws.close(); } catch (e) { /* noop */ }
    process.exit(ok ? 0 : 1);
  };

  ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'REGISTER', userId: 1, role: 'family' }));
    setTimeout(() => done(true, '握手成功并已发送 REGISTER'), 1500);
  });
  ws.on('message', (m) => console.log('收到服务端消息:', String(m).slice(0, 200)));
  ws.on('error', (e) => done(false, e.message));
  ws.on('unexpected-response', (_req, res) => done(false, `HTTP ${res.statusCode}`));

  setTimeout(() => done(false, '10s 内未完成握手'), 10000);
})();
