const https = require('https');
const http = require('http');

class WeChatNotifyService {
  constructor() {
    this.appId = process.env.WX_APP_ID || '';
    this.appSecret = process.env.WX_APP_SECRET || '';
    this.accessToken = null;
    this.tokenExpiresAt = 0;
    this.templateId = process.env.WX_TEMPLATE_ID || '';
  }

  isConfigured() {
    return !!(this.appId && this.appSecret && this.templateId);
  }

  request(options, postData = null) {
    return new Promise((resolve, reject) => {
      const mod = options.protocol === 'https:' ? https : http;
      const req = mod.request(options, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          try { resolve(JSON.parse(data)); }
          catch (e) { resolve(data); }
        });
      });
      req.on('error', reject);
      if (postData) req.write(postData);
      req.end();
    });
  }

  async getAccessToken() {
    if (this.accessToken && Date.now() < this.tokenExpiresAt) {
      return this.accessToken;
    }

    if (!this.isConfigured()) {
      this.accessToken = `MOCK_ACCESS_TOKEN_${Date.now()}`;
      this.tokenExpiresAt = Date.now() + 7200 * 1000;
      return this.accessToken;
    }

    const url = `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${this.appId}&secret=${this.appSecret}`;
    const res = await this.request(new URL(url));

    if (res.access_token) {
      this.accessToken = res.access_token;
      this.tokenExpiresAt = Date.now() + (res.expires_in - 300) * 1000;
      return this.accessToken;
    }
    throw new Error(`获取 AccessToken 失败: ${res.errmsg || '未知错误'}`);
  }

  async sendAntiFraudAlert(familyOpenId, alertData) {
    try {
      if (!this.isConfigured()) {
        console.log(`📲 [微信推送服务-模拟] 已向子女 [${familyOpenId}] 发送防诈提醒: ${alertData.title}`);
        return { success: true, message: '模拟推送成功（未配置微信参数）' };
      }

      const token = await this.getAccessToken();

      const payload = {
        touser: familyOpenId,
        template_id: this.templateId,
        page: 'pages/dashboard/dashboard',
        miniprogram_state: process.env.NODE_ENV || 'formal',
        lang: 'zh_CN',
        data: {
          thing1: { value: alertData.title || '长者高危行为预警' },
          thing2: { value: alertData.elderName || '守护对象' },
          phrase3: { value: alertData.severity || '极高风险' },
          thing4: { value: (alertData.description || '检测到高危行为').substring(0, 20) },
          time5: { value: new Date().toLocaleString('zh-CN') }
        }
      };

      const res = await this.request({
        hostname: 'api.weixin.qq.com',
        path: `/cgi-bin/message/subscribe/send?access_token=${token}`,
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      }, JSON.stringify(payload));

      if (res.errcode === 0) {
        console.log(`📲 [微信推送服务] 成功向 [${familyOpenId}] 发送订阅消息`);
        return { success: true, message: '微信通知已成功送达' };
      } else {
        throw new Error(`微信 API 错误: ${res.errcode} - ${res.errmsg}`);
      }
    } catch (e) {
      console.error('微信通知发送失败:', e.message);
      return { success: false, error: e.message };
    }
  }

  async sendPaymentAlert(familyOpenId, paymentData) {
    return this.sendAntiFraudAlert(familyOpenId, {
      title: '大额扣款预警',
      elderName: paymentData.elderName,
      severity: '高危状态',
      description: `检测到 ¥${paymentData.amount} 扣款，商户：${paymentData.payee}`
    });
  }

  async sendCallAlert(familyOpenId, callData) {
    return this.sendAntiFraudAlert(familyOpenId, {
      title: '可疑通话预警',
      elderName: callData.elderName,
      severity: '中高风险',
      description: `陌生号码 ${callData.number} 通话超 ${callData.duration} 分钟`
    });
  }
}

module.exports = new WeChatNotifyService();
