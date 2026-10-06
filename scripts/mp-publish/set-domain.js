/**
 * 一键改写小程序后端域名（wechat-miniprogram/config.js 是唯一真源）
 *
 * 用法：
 *   node scripts/mp-publish/set-domain.js                 # 自动从 cpolar 日志取最新域名
 *   node scripts/mp-publish/set-domain.js xxx.r25.cpolar.top
 *   node scripts/mp-publish/set-domain.js api.example.com --no-check
 *
 * 三步：确定域名（参数 > cpolar 日志最新一条）→ 改写 config.js → 直连自检
 */
const fs = require('fs');
const https = require('https');
const P = require('./paths');

// 不走代理：本机 HTTP_PROXY 会让出口变成境外 IP，误判隧道不可用
['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy']
  .forEach((k) => delete process.env[k]);

function latestFromLog() {
  if (!fs.existsSync(P.CPOLAR_LOG)) return null;
  const txt = fs.readFileSync(P.CPOLAR_LOG, 'utf8');
  const hits = txt.match(/Tunnel established at https:\/\/([^\s"]+)/g) || [];
  if (!hits.length) return null;
  return hits[hits.length - 1].replace('Tunnel established at https://', '');
}

function writeHost(host) {
  const src = fs.readFileSync(P.CONFIG, 'utf8');
  if (!/const host = '[^']*';/.test(src)) {
    throw new Error('config.js 里没找到 host 定义，格式可能被改过');
  }
  fs.writeFileSync(P.CONFIG, src.replace(/const host = '[^']*';/, `const host = '${host}';`), 'utf8');
  return P.readHost();
}

function check(host) {
  return new Promise((resolve) => {
    const req = https.request(
      { host, port: 443, path: '/', method: 'GET', timeout: 15000 },
      (res) => { res.resume(); resolve({ ok: true, status: res.statusCode }); }
    );
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, err: 'timeout' }); });
    req.on('error', (e) => resolve({ ok: false, err: e.message }));
    req.end();
  });
}

(async () => {
  const argHost = process.argv[2];
  const noCheck = process.argv.includes('--no-check');
  const host = argHost || latestFromLog();

  if (!host) {
    console.error('未能确定域名：既没传参数，cpolar 日志里也没有隧道记录。');
    console.error('日志路径:', P.CPOLAR_LOG);
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
    ? `连通性自检: OK (HTTP ${r.status})`
    : `连通性自检: 失败 (${r.err}) —— 确认本地 3000 端口与隧道是否活着`);
})();
