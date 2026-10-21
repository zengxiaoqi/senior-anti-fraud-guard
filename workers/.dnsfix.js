/**
 * 临时救急：本机 DNS 把 api.cloudflare.com 污染到假 IP（27.124.x / 23.248.x，自签证书），
 * 导致 wrangler 所有命令 "fetch failed"。这里在进程内把该域名钉到可用的 CF 边缘 IP。
 *
 * 用完即删；网络恢复后不再需要。
 */
const dns = require('dns');

// 2026-10-21 补充：本机无 IPv6 出口，undici（wrangler 底层 fetch）拿到 AAAA
// 记录先连就 ConnectTimeoutError（dash.cloudflare.com/oauth2/token 刷新 token 时必踩）。
// 全局 IPv4 优先，不用逐域名钉。
try { dns.setDefaultResultOrder('ipv4first'); } catch (_) {}

const PINNED = {
  'api.cloudflare.com': '104.17.111.184',
  // dash.cloudflare.com 与 api 同属 CF 边缘（anycast，按 SNI 路由），A 记录可能同样被污染，一并钉住
  'dash.cloudflare.com': '104.17.111.184'
};

const origLookup = dns.lookup;
dns.lookup = function lookup(hostname, options, cb) {
  console.error('[dnsfix] lookup 被调用:', hostname, JSON.stringify(options), typeof (cb || options));
  const pinned = PINNED[hostname];
  if (pinned) {
    if (typeof options === 'function') {
      cb = options;
      options = {};
    }
    const family = options && options.family ? options.family : 4;
    if (typeof cb === 'function') {
      if (options && options.all) {
        process.nextTick(() => cb(null, [{ address: pinned, family }]));
      } else {
        process.nextTick(() => cb(null, pinned, family));
      }
      return {};
    }
    return origLookup.call(this, hostname, options, cb);
  }
  return origLookup.apply(this, arguments);
};

const origPromisesLookup = dns.promises.lookup;
dns.promises.lookup = function (hostname, options) {
  const pinned = PINNED[hostname];
  if (pinned) {
    return Promise.resolve({ address: pinned, family: 4 });
  }
  return origPromisesLookup.call(this, hostname, options);
};

console.error('[dnsfix] api.cloudflare.com -> 104.17.111.184 (绕过本机 DNS 污染)');
