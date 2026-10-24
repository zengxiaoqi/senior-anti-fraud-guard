/**
 * 全局配置 —— 后端地址的「唯一真源」
 *
 * 换域名时只改下面 host 一处即可，全站请求 / WebSocket / 后台域名同步脚本
 * 都会跟着走。也可以用 scripts/mp-publish 的换域名工具自动改写，无需手改本文件。
 *
 * 注意：
 *   - host 只填「域名」，不要带协议（https:// / wss:// 由下面自动拼接）
 *   - 换成正式域名后，必须同步在微信公众平台
 *     「开发 → 开发管理 → 开发设置 → 服务器域名」配置，否则真机会拦截请求
 *
 * 本机/平台私有配置（gitignore，不进版本库）：
 *   - 复制 config.local.example.js 为 config.local.js，按需填写 subscribeTemplateId
 *   - config.local.js 存在时其导出项覆盖本文件同名字段；不存在则全部走默认值
 */
const host = 'guard.chataifree.eu.org';

/** 本地私有覆盖（订阅消息模板 ID 等平台资产放这里，不进 git） */
let localConfig = {};
try {
  localConfig = require('./config.local.js') || {};
} catch (e) {
  // 未创建 config.local.js 时使用默认值
}

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
   * 模板为公共模板「监控告警通知」，字段位 thing1=告警内容 / time2=告警时间 /
   * thing3=备注（服务端 wxSubscribe 已对应）。
   *
   * 属于平台私有资产，**不要写进本文件**：复制 config.local.example.js 为
   * config.local.js（已 gitignore）后填写。未配置时订阅消息链路静默降级，
   * WS 在线推送不受影响。
   */
  subscribeTemplateId: localConfig.subscribeTemplateId || '',

  /**
   * 构建版本标记：显示在 AI 扫描卡片上，用于真机排查时一眼确认
   * 跑的是哪个包（上传的版本不会自动变成体验版/线上版，历史教训）。
   * 每次发版记得同步改这里。
   */
  buildVersion: '1.8.9',
};
