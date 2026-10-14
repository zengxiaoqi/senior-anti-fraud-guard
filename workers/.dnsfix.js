/**
 * 临时救急：本机 DNS 把 api.cloudflare.com 污染到假 IP（27.124.x / 23.248.x，自签证书），
 * 导致 wrangler 所有命令 "fetch failed"。这里在进程内把该域名钉到可用的 CF 边缘 IP。
 *
 * 用完即删；网络恢复后不再需要。
 */
const dns = require('dns');

const PINNED = {
  'api.cloudflare.com': '104.17.111.184'
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
