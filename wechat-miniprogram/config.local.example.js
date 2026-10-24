/**
 * 本机私有配置模板（不进版本库）
 *
 * 用法：复制本文件为 config.local.js，按需填写下面字段。
 * config.local.js 的导出项会覆盖 config.js 的同名字段。
 */
module.exports = {
  /**
   * 高危告警订阅消息模板 ID（微信公众平台 → 订阅消息 → 我的模板）。
   * 不配则订阅消息链路静默降级，WebSocket 在线推送不受影响。
   */
  subscribeTemplateId: '',
};
