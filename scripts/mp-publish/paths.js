/**
 * 发布工具链的公共路径与配置
 *
 * 设计原则：
 *   - 能推导的一律相对推导（本项目内），不写死绝对路径，换机器/换目录直接可用
 *   - 不能推导的（密钥、浏览器调试会话、隧道日志）走环境变量，并给出本机默认兜底
 *
 * 可用环境变量：
 *   MP_KEY_PATH    小程序上传密钥（默认 D:/mp-ci/private.key，刻意放在项目外避免误提交）
 *   MP_WS_FILE     Chrome 调试会话 wsUrl 文件（默认 D:/mp-ci/.ws）
 *   MP_TUNNEL_CFG  cloudflared 配置文件（默认 D:/cloudflared/config.yml）
 *   MP_QR_OUT      扫码二维码输出路径（默认系统临时目录）
 *   MP_CI_MODULES  miniprogram-ci 所在 node_modules（找不到时兜底）
 *
 * 关于域名：
 *   本项目已迁移到 Cloudflare named tunnel，公网域名固定不变，
 *   记录在 wechat-miniprogram/config.js 的 host 里，作为唯一真源。
 *   旧的 cpolar 方案每次重启都换域名，靠解析日志兜底的 CPOLAR_LOG
 *   已随之退役，仅保留常量以免外部脚本引用报错。
 */
const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '..', '..');          // 项目根
const MINI = path.join(ROOT, 'wechat-miniprogram');        // 小程序目录

module.exports = {
  ROOT,
  MINI,
  CONFIG: path.join(MINI, 'config.js'),
  APPID: 'wxbed895d80664cd65',

  KEY: process.env.MP_KEY_PATH || 'D:/mp-ci/private.key',
  WS_FILE: process.env.MP_WS_FILE || 'D:/mp-ci/.ws',
  TUNNEL_CFG: process.env.MP_TUNNEL_CFG || 'D:/cloudflared/config.yml',
  // Fixed public hostname. Token-managed tunnels keep their routing rules in
  // the Zero Trust cloud, so there is no local config.yml to read from -
  // this single-line file is the local record of the fixed hostname.
  TUNNEL_HOST: path.join(ROOT, 'scripts', 'cloudflare', 'hostname.txt'),
  QR_OUT: process.env.MP_QR_OUT
    || path.join(process.env.TEMP || process.env.TMP || ROOT, 'mp-domain-qr.png'),

  /** 隧道绑定的公网域名：hostname.txt > cloudflared config.yml（失败返回 null） */
  tunnelHostname() {
    try {
      const txt = fs.readFileSync(this.TUNNEL_HOST, 'utf8').trim();
      if (txt) return txt.split(/\s+/)[0];
    } catch (e) { /* fall through to config.yml */ }
    try {
      const yml = fs.readFileSync(this.TUNNEL_CFG, 'utf8');
      const m = yml.match(/^\s*-\s*hostname:\s*(__\w+__|[^\s#]+)/m);
      if (m && !/^__/.test(m[1])) return m[1];
      return null;
    } catch (e) {
      return null;
    }
  },

  /** 读取 config.js 里配置的域名（唯一真源） */
  readHost() {
    const src = fs.readFileSync(path.join(MINI, 'config.js'), 'utf8');
    const m = src.match(/const host = '([^']*)';/);
    if (!m) throw new Error('config.js 里没找到 host 定义');
    return m[1];
  },

  /** 加载 miniprogram-ci：优先项目依赖，找不到再回退到 MP_CI_MODULES */
  loadCI() {
    try {
      return require('miniprogram-ci');
    } catch (e) {
      const alt = process.env.MP_CI_MODULES || 'D:/mp-ci/node_modules';
      try {
        return require(path.join(alt, 'miniprogram-ci'));
      } catch (e2) {
        throw new Error(
          `找不到 miniprogram-ci。请在本项目执行 npm i -D miniprogram-ci，`
          + `或设置环境变量 MP_CI_MODULES 指向已安装它的 node_modules（当前尝试：${alt}）`
        );
      }
    }
  },
};
