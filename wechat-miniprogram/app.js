App({
  globalData: {
    serverHost: 'http://localhost:3000',
    wsHost: 'ws://localhost:3000',
    userId: null,
    bindCode: null,
    boundUser: null,
    isBound: false
  },

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

    wx.offSocketOpen();
    wx.offSocketMessage();
    wx.offSocketError();
    wx.offSocketClose();

    wx.connectSocket({
      url: this.globalData.wsHost,
      success: () => {
        console.log("微信小程序 WebSocket 连接成功");
      }
    });

    wx.onSocketOpen(() => {
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
      console.log('WebSocket 已断开，3秒后重连...');
      setTimeout(() => this.initWebSocket(), 3000);
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
      fail: () => {
        wx.showToast({ title: '发送失败，请检查连接', icon: 'none' });
      }
    });
    wx.showToast({ title: '已触发远程打断', icon: 'success' });
  }
});
