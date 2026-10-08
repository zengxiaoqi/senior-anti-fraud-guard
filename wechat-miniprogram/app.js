// 后端地址统一由 config.js 提供，换域名只改那一个文件
const config = require('./config');

App({
  globalData: {
    serverHost: config.serverHost,
    wsHost: config.wsHost,
    userId: null,
    bindCode: null,
    boundUser: null,
    isBound: false,
    token: null,
    // 本人真实手机号：登录时由服务端下发，改号后回写，供"修改手机号"入口渲染
    mobile: '',
    mobileMissing: true
  },

  // WebSocket 重连状态（指数退避，防止服务端宕机时无限重试）
  _wsReconnectAttempts: 0,
  _wsReconnectTimer: null,

  onLaunch: function () {
    console.log("🛡️ 长者防诈守护 (子女端微信小程序) 已启动");
    this.wxLogin();
  },

  wxLogin: function () {
    wx.login({
      success: (res) => {
        if (res.code) {
          wx.request({
            url: `${this.globalData.serverHost}/api/auth/wx-login`,
            method: 'POST',
            data: { code: res.code },
            timeout: 10000,
            success: (response) => {
              if (response.data.success) {
                const data = response.data.data;
                this.globalData.userId = data.userId;
                this.globalData.bindCode = data.bindCode;
                this.globalData.boundUser = data.boundUser;
                this.globalData.isBound = !!data.boundUser;
                // 服务端签发的登录态，后续所有请求通过 X-Auth-Token 携带
                this.globalData.token = data.token || null;
                // 手机号与是否缺失随登录态一起下发，避免为了渲染入口再多发一次请求
                this.globalData.mobile = data.mobile || '';
                this.globalData.mobileMissing = !!data.mobileMissing || !data.mobile;

                console.log('✅ 微信登录成功, userId:', data.userId);

                if (!this.globalData.isBound) {
                  this.showBindingGuide();
                }

                this.initWebSocket();
              } else {
                wx.showToast({ title: response.data.message || '登录失败', icon: 'none' });
              }
            },
            fail: () => {
              wx.showToast({ title: '网络异常，请重试', icon: 'none' });
            }
          });
        }
      },
      fail: () => {
        wx.showToast({ title: '微信登录失败', icon: 'none' });
      }
    });
  },

  // 统一请求封装：自动携带 X-Auth-Token；401 时静默重登录一次
  apiRequest: function (options) {
    const app = this;
    wx.request(Object.assign({}, options, {
      header: Object.assign(
        { 'X-Auth-Token': this.globalData.token || '' },
        options.header || {}
      ),
      success: (res) => {
        if (res.statusCode === 401) {
          console.warn('登录态失效，正在静默重新登录...');
          app.wxLogin();
          if (options.fail) {
            options.fail({ errMsg: 'request:ok 登录态失效，已自动重新登录' });
          }
          return;
        }
        if (options.success) options.success(res);
      },
      fail: options.fail
    }));
  },

  showBindingGuide: function () {
    wx.showModal({
      title: '亲情绑定',
      content: '请输入老人端的6位绑定码完成绑定',
      confirmText: '去绑定',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) {
          wx.switchTab({ url: '/pages/dashboard/dashboard' });
        }
      }
    });
  },

  /**
   * 填写/修改本人手机号。
   *
   * 与 App 端同一套服务端接口（/api/auth/profile-mobile），行为保持一致：
   * 换号后老人端紧急警报才能拨到新号码，否则会一直打给已经用不了的旧号。
   *
   * @param {string} phone 新手机号
   * @param {function} onDone 成功回调（供页面刷新界面）
   */
  updateMyMobile: function (phone, onDone) {
    // 中文输入法默认打全角，服务端 /^1[3-9]\d{9}$/ 必判失败 —— 先归一化
    const normalized = String(phone || '').replace(/[０-９]/g, (c) =>
      String.fromCharCode(c.charCodeAt(0) - 0xFEE0)
    ).replace(/[\s-－]/g, '');

    if (!/^1[3-9]\d{9}$/.test(normalized)) {
      wx.showToast({ title: '手机号格式不正确（需11位）', icon: 'none' });
      return;
    }
    if (normalized === this.globalData.mobile) {
      wx.showToast({ title: '号码没有变化', icon: 'none' });
      return;
    }

    this.apiRequest({
      url: `${this.globalData.serverHost}/api/auth/profile-mobile`,
      method: 'POST',
      data: { phone: normalized },
      success: (res) => {
        if (res.data && res.data.success) {
          this.globalData.mobile = normalized;
          this.globalData.mobileMissing = false;
          wx.showToast({ title: '✅ 手机号已更新', icon: 'success' });
          if (onDone) onDone(normalized);
        } else {
          wx.showToast({ title: (res.data && res.data.error) || '保存失败', icon: 'none' });
        }
      },
      fail: () => wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
    });
  },

  /**
   * 「这条告警值不值得打断子女」的判定。
   *
   * 服务端对每一条风险事件都广播 RISK_ALERT，之前这里收到就弹
   * 「长者正处于高危状态 (LOCATION_UPDATE)」。而老人端定位上报是心跳级的
   * （每次 onLocationChanged 一条、severity 恒 LOW），于是子女被噪声弹窗糊满，
   * 真高危反而被淹掉 —— 告警疲劳，且是漏报方向。
   *
   * 与 services/riskAlertPolicy.js / Android family/RiskAlertPolicy.kt 同一把尺子，
   * 三处规则必须同步。
   */
  shouldInterruptAlert: function (data) {
    const d = data || {};
    // 优先用服务端算好的判定，保证各端同一把尺子；字段缺失时本地兜底重算
    if (d.interruptible !== undefined) return !!d.interruptible;
    const INTERRUPTIBLE = ['SOS', 'PAYMENT_RISK', 'COERCION_RISK', 'CALL_RISK'];
    const type = String(d.event_type || '').trim().toUpperCase();
    if (INTERRUPTIBLE.indexOf(type) === -1) return false;
    if (type === 'SOS') return true;   // 老人主动求助，任何级别都必须送达
    return String(d.severity || '').trim().toUpperCase() === 'HIGH';
  },

  initWebSocket: function () {
    if (!this.globalData.userId) return;

    // 避免重复注册监听 / 重复建连
    wx.offSocketOpen();
    wx.offSocketMessage();
    wx.offSocketError();
    wx.offSocketClose();
    if (this._wsReconnectTimer) {
      clearTimeout(this._wsReconnectTimer);
      this._wsReconnectTimer = null;
    }

    wx.connectSocket({ url: this.globalData.wsHost });

    wx.onSocketOpen(() => {
      console.log('WebSocket 已连接');
      // 连接成功后重置退避计数
      this._wsReconnectAttempts = 0;
      wx.sendSocketMessage({
        data: JSON.stringify({ type: 'REGISTER', userId: this.globalData.userId, role: 'family' })
      });
    });

    wx.onSocketMessage((res) => {
      try {
        const payload = JSON.parse(res.data);
        if (payload.type === 'RISK_ALERT' && this.shouldInterruptAlert(payload.data)) {
          // 只对值得打断的高危事件弹窗；定位心跳等 LOW 事件静默入列表。
          // 挡在这里而不是继续往下走：AlertDialog/showModal 会把上一个盖住，
          // 连续弹的结果是真高危事件反而看不到。
          wx.showModal({
            title: '⚠️ 收到微信紧急防诈预警',
            content: `长者正处于高危状态 (${payload.data.event_type})，是否立即发起远程语音打断？`,
            confirmText: '强行打断',
            confirmColor: '#EF4444',
            success: (res) => {
              if (res.confirm) {
                this.sendRemoteInterrupt();
              }
            }
          });
        }

        // 紧急打断的服务端回执：如实反馈送达结果，避免"假成功"误导子女
        if (payload.type === 'INTERRUPT_ACK') {
          if (payload.success) {
            wx.showToast({ title: '✅ 已送达老人手机', icon: 'success' });
          } else if (payload.offline) {
            wx.showModal({
              title: '⚠️ 老人手机当前离线',
              content: '打断指令已暂存，老人端守护应用一恢复联网就会立即弹出全屏警报。若情况紧急，请直接电话联系老人。',
              showCancel: false,
              confirmText: '知道了'
            });
          } else {
            wx.showToast({ title: payload.message || '发送失败', icon: 'none' });
          }
        }
      } catch (e) {
        console.warn('WebSocket 消息解析失败:', e);
      }
    });

    wx.onSocketError((err) => {
      console.error('WebSocket 错误:', err);
    });

    wx.onSocketClose(() => {
      // 指数退避重连：3s → 6s → 12s ... 上限 60s，服务端恢复后自动追平
      const delay = Math.min(3000 * Math.pow(2, this._wsReconnectAttempts), 60000);
      this._wsReconnectAttempts += 1;
      console.log(`WebSocket 已断开，${delay / 1000}s 后重连...`);
      this._wsReconnectTimer = setTimeout(() => this.initWebSocket(), delay);
    });
  },

  sendRemoteInterrupt: function () {
    if (!this.globalData.boundUser) {
      wx.showToast({ title: '未绑定老人', icon: 'none' });
      return;
    }

    // 发送结果一律以服务端 INTERRUPT_ACK 回执为准（见 onSocketMessage）
    wx.sendSocketMessage({
      data: JSON.stringify({
        type: 'INTERRUPT_CMD',
        targetElderId: this.globalData.boundUser.id,
        message: '微信强打断：子女提醒您立即终止当前异常通话！'
      }),
      fail: () => {
        wx.showToast({ title: '连接已断开，正在重连，请稍后重试', icon: 'none' });
      }
    });
  }
});
