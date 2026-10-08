/**
 * 改写小程序后端域名（wechat-miniprogram/config.js 是唯一真源）
 *
 * 用法：
 *   node scripts/mp-publish/set-domain.js                  # 自动取 scripts/cloudflare/hostname.txt
 *   node scripts/mp-publish/set-domain.js guard.example.com
 *   node scripts/mp-publish/set-domain.js guard.example.com --no-check
 *
 * 三步：确定域名（参数 > hostname.txt）→ 改写 config.js → 直连自检
 *
 * 变更说明：原先「从 cpolar 日志抓最新隧道域名」的兜底已移除 —— cpolar 免费版每次
 * 重启都换域名，才需要不断同步；换成 Cloudflare named tunnel 后域名永久固定，
 * 再扯日志只会把偶发的临时域名误写进真源。
 */
const fs = require('fs');
const https = require('https');
const dns = require('dns');
const P = require('./paths');

// 不走代理：本机 HTTP_PROXY 会让出口变成境外 IP，误判隧道不可用
['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy']
  .forEach((k) => delete process.env[k]);

// 本机上级 DNS（路由器 192.168.1.1）会对新域名返回空应答，
// 但公网 DNS（阿里/腾讯/114/CF）全部正常。自检若依赖本机解析器会误报
// "隧道不通"，所以显式指定公共 DNS 解析，再用 IP 直连 + Host 头验证。
const PUBLIC_DNS = ['223.5.5.5', '1.1.1.1'];

async function resolveVia(host) {
  const r = new dns.promises.Resolver();
  r.setServers(PUBLIC_DNS);
  const addrs = await r.resolve4(host);
  return addrs[0];
}

function check(host) {
  return new Promise(async (resolve) => {
    let ip;
    try {
      ip = await resolveVia(host);
    } catch (e) {
      resolve({ ok: false, err: `DNS 解析失败(${PUBLIC_DNS.join('/')})：${e.message}` });
      return;
    }
    const req = https.request(
      {
        host: ip, port: 443, path: '/api/health', method: 'GET',
        timeout: 20000,
        servername: host,                      // SNI，否则证书校验失败
        headers: { Host: host },               // 靠它匹配 Cloudflare ingress 规则
        rejectUnauthorized: true,
      },
      (res) => {
        let body = '';
        res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ ok: true, status: res.statusCode, ip, body: body.slice(0, 120) }));
      }
    );
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, ip, err: 'timeout' }); });
    req.on('error', (e) => resolve({ ok: false, ip, err: e.message }));
    req.end();
  });
}

function fromTunnelConfig() {
  return P.tunnelHostname();
}

function writeHost(host) {
  const src = fs.readFileSync(P.CONFIG, 'utf8');
  if (!/const host = '[^']*';/.test(src)) {
    throw new Error('config.js 里没找到 host 定义，格式可能被改过');
  }
  fs.writeFileSync(P.CONFIG, src.replace(/const host = '[^']*';/, `const host = '${host}';`), 'utf8');
  return P.readHost();
}

(async () => {
  const argHost = process.argv[2];
  const noCheck = process.argv.includes('--no-check');
  const host = argHost || fromTunnelConfig();

  if (!host) {
    console.error('未能确定域名：既没传参数，cloudflared 配置里也没有 hostname。');
    console.error('配置文件:', P.TUNNEL_CFG);
    console.error('用法: node set-domain.js <域名>');
    process.exit(1);
  }
  if (!/^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(host)) {
    console.error('域名格式可疑:', host);
    process.exit(1);
  }

  const written = writeHost(host);
  console.log('config.js 已更新 ->', P.CONFIG);
  console.log('  host       =', written);
  console.log('  serverHost = https://' + written);
  console.log('  wsHost     = wss://' + written);

  if (noCheck) return;
  const r = await check(host);
  console.log(r.ok
    ? `连通性自检: OK (HTTP ${r.status}, edge ${r.ip})\n  响应体: ${r.body}`
    : `连通性自检: 失败 (${r.err}) —— 确认本地 3000 端口与隧道是否活着`);
})();
