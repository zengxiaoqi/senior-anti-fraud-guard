/**
 * 上传小程序代码（基于微信官方 miniprogram-ci）
 *
 * 用法：
 *   node scripts/mp-publish/upload.js 1.0.0 "版本说明"
 *
 * 前置条件：
 *   1. 上传密钥：默认 D:/mp-ci/private.key，可用 MP_KEY_PATH 指向别处
 *      （密钥刻意放在项目外：本项目是 git 仓库，放进来容易被 git add 提交）
 *   2. 公众平台「IP 白名单」里有本机公网 IPv4（当前 120.228.150.63）
 *
 * 两个必要的坑位修复，勿删：
 *   1. 清代理环境变量：miniprogram-ci 依赖 get-proxy，读到 HTTP_PROXY 后会走 sing-box
 *      出去，源 IP 变成本机 IPv6，微信报 invalid ip(-10008)。
 *   2. 强制 IPv4：本机 IPv4 稳定，IPv6 是临时地址会轮换，白名单填 IPv6 过几天就失效。
 */
const fs = require('fs');
const P = require('./paths');

// 修复1：清代理
['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy']
  .forEach((k) => delete process.env[k]);

// 修复2：强制 IPv4
require('./force_ipv4');

const ci = P.loadCI();

const version = process.argv[2] || '1.0.0';
const desc = process.argv[3] || '长者防诈亲情守护系统';

if (!fs.existsSync(P.KEY)) {
  console.error('缺少上传密钥:', P.KEY);
  console.error('到 mp.weixin.qq.com → 开发 → 开发管理 → 开发设置');
  console.error('下载「小程序代码上传密钥」，或设置环境变量 MP_KEY_PATH 指向该文件。');
  process.exit(1);
}

const project = new ci.Project({
  appid: P.APPID,
  type: 'miniProgram',
  projectPath: P.MINI,
  privateKeyPath: P.KEY,
  ignores: ['node_modules/**/*', '*.md'],
});

(async () => {
  try {
    console.log('开始上传...');
    console.log('  AppID :', P.APPID);
    console.log('  版本  :', version);
    console.log('  目录  :', P.MINI);
    console.log('  域名  :', P.readHost());

    const result = await ci.upload({
      project,
      version,
      desc,
      setting: {
        es6: true,
        es7: true,
        minify: true,
        codeProtect: false,
        autoPrefixWXSS: true,
        minifyWXSS: true,
        minifyWXML: true,
      },
      onProgressUpdate: (msg) => console.log('  >', msg),
    });

    console.log('\n上传成功:', JSON.stringify(result));
  } catch (e) {
    console.error('\n上传失败:', e.message);
    if (/ip/i.test(e.message)) {
      console.error('多半是 IP 白名单没配，去公众平台把本机公网 IPv4 加上。');
    }
    process.exit(1);
  }
})();
