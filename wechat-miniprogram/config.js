/**
 * 全局配置 —— 后端地址的「唯一真源」
 *
 * 换域名时只改下面 host 一处即可，全站请求 / WebSocket / 后台域名同步脚本
 * 都会跟着走。也可以用 D:\mp-ci\set-domain.js 自动改写，无需手改本文件。
 *
 * 注意：
 *   - host 只填「域名」，不要带协议（https:// / wss:// 由下面自动拼接）
 *   - 换成正式域名后，必须同步在微信公众平台
 *     「开发 → 开发管理 → 开发设置 → 服务器域名」配置，否则真机会拦截请求
 */
const host = '34752ef2.r25.cpolar.top';

module.exports = {
  /** 纯域名，供后台域名同步脚本 / DNS 预解析配置使用 */
  host,
  /** 业务请求基址 */
  serverHost: `https://${host}`,
  /** WebSocket 基址 */
  wsHost: `wss://${host}`,
  /** 请求超时（毫秒） */
  requestTimeout: 10000,
};
