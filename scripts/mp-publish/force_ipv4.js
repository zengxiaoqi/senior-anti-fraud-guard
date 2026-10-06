// 强制 Node 的所有 DNS 解析只返回 IPv4（解决 miniprogram-ci 走临时 IPv6 导致 IP 白名单失效的问题）
// 用法: node --require ./force_ipv4.js upload.js
const dns = require('dns');
const origLookup = dns.lookup;

function patched(hostname, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  if (typeof options === 'number') {
    options = { family: options };
  }
  const opts = Object.assign({}, options || {});
  const wantAll = !!opts.all;
  const allOpts = Object.assign({}, opts, { all: true, family: 0 });

  return origLookup.call(dns, hostname, allOpts, (err, addresses) => {
    if (err) return callback(err);
    const list = Array.isArray(addresses) ? addresses : [addresses];
    const v4 = list.filter((a) => a.family === 4);
    const chosen = v4.length ? v4 : list;
    if (wantAll) return callback(null, chosen);
    return callback(null, chosen[0].address, chosen[0].family);
  });
}

dns.lookup = patched;
if (dns.promises && dns.promises.lookup) {
  dns.promises.lookup = function (hostname, options) {
    return new Promise((resolve, reject) => {
      patched(hostname, options, (err, a, f) => {
        if (err) return reject(err);
        if (options && options.all) return resolve(a);
        resolve({ address: a, family: f });
      });
    });
  };
}
