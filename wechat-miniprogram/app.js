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
    token: null
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
        if (payload.type === 'RISK_ALERT') {
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

    wx.sendSocketMessage({
      data: JSON.stringify({
        type: 'INTERRUPT_CMD',
        targetElderId: this.globalData.boundUser.id,
        message: '微信强打断：子女提醒您立即终止当前异常通话！'
      }),
      success: () => {
        wx.showToast({ title: '已触发远程打断', icon: 'success' });
      },
      fail: () => {
        wx.showToast({ title: '发送失败，请检查连接', icon: 'none' });
      }
    });
  }
});
