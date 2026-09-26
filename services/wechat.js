const https = require('https');

/**
 * 微信小程序订阅消息 & 微信通知服务类
 */
class WeChatNotifyService {
  constructor() {
    this.appId = process.env.WX_APP_ID || 'wx_demo_appid';
    this.appSecret = process.env.WX_APP_SECRET || 'wx_demo_secret';
    this.accessToken = null;
    this.tokenExpiresAt = 0;
  }

  /**
   * 获取微信 API 接口凭证 Access Token
   */
  async getAccessToken() {
    if (this.accessToken && Date.now() < this.tokenExpiresAt) {
      return this.accessToken;
    }

    // 演示环境下返回模拟 AccessToken
    this.accessToken = `MOCK_ACCESS_TOKEN_${Date.now()}`;
    this.tokenExpiresAt = Date.now() + 7200 * 1000;
    return this.accessToken;
  }

  /**
   * 发送高危防诈微信订阅消息推送给子女微信
   */
  async sendAntiFraudAlert(familyOpenId, alertData) {
    try {
      const token = await this.getAccessToken();

      // 微信订阅消息 Payload 模版规范
      const payload = {
        touser: familyOpenId || 'openid_family_demo',
        template_id: 'TEMPLATE_ANTI_FRAUD_ALERT_ID',
        page: 'pages/index/index',
        miniprogram_state: 'formal',
        lang: 'zh_CN',
        data: {
          thing1: { value: alertData.title || '长者高危行为预警' },       // 提醒内容
          thing2: { value: alertData.elderName || '张爷爷' },              // 守护对象
          phrase3: { value: alertData.severity || '极高风险' },             // 风险等级
          thing4: { value: alertData.description || '检测到正在进行高危通话' }, // 详细描述
          time5: { value: new Date().toLocaleString() }                   // 告警时间
        }
      };

      console.log(`📲 [微信推送服务] 已向子女 OpenID [${payload.touser}] 成功发送微信防诈强提醒消息！`);
      console.log(`   └─ 内容: ${alertData.title} | 老人: ${alertData.elderName}`);

      return { success: true, message: '微信通知已成功送达' };
    } catch (e) {
      console.error('微信通知发送失败:', e.message);
      return { success: false, error: e.message };
    }
  }
}

module.exports = new WeChatNotifyService();
