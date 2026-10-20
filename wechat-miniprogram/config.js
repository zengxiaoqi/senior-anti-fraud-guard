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
const host = 'guard.chataifree.eu.org';

module.exports = {
  /** 纯域名，供后台域名同步脚本 / DNS 预解析配置使用 */
  host,
  /** 业务请求基址 */
  serverHost: `https://${host}`,
  /** WebSocket 基址 */
  wsHost: `wss://${host}`,
  /** 请求超时（毫秒） */
  requestTimeout: 10000,

  /**
   * 高危告警订阅消息模板 ID。
   *
   * 小程序切后台约 5 秒即挂起、WebSocket 断开——离线告警靠微信订阅消息触达。
   * 当前模板：公共模板 867「监控告警通知」（场景说明"老人风险告警"），
   * 字段位 thing1=告警内容 / time2=告警时间 / thing3=备注（服务端 wxSubscribe 已对应）。
   * 未填或未配服务端 secret 时整条链路静默降级，WS 在线推送不受影响。
   */
  subscribeTemplateId: 'BpY-KKC9RwBhac7BhkXWa8OJexrdE3NDgyXa-RRKI7o',

  /**
   * 构建版本标记：显示在 AI 扫描卡片上，用于真机排查时一眼确认
   * 跑的是哪个包（上传的版本不会自动变成体验版/线上版，历史教训）。
   * 每次发版记得同步改这里。
   */
  buildVersion: '1.7.2',
};
