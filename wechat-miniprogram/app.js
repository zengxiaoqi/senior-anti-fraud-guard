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
    const that = this;
    wx.login({
      success: (res) => {
        if (res.code) {
          wx.request({
            url: `${that.globalData.serverHost}/api/auth/wx-login`,
            method: 'POST',
            data: { code: res.code },
            success: (response) => {
              if (response.data.success) {
                const data = response.data.data;
                that.globalData.userId = data.userId;
                that.globalData.bindCode = data.bindCode;
                that.globalData.boundUser = data.boundUser;
                that.globalData.isBound = !!data.boundUser;
                
                console.log('✅ 微信登录成功, userId:', data.userId);
                
                if (!that.globalData.isBound) {
                  that.showBindingGuide();
                }
                
                that.initWebSocket();
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
    
    const that = this;
    const ws = wx.connectSocket({
      url: this.globalData.wsHost,
      success: () => {
        console.log("微信小程序 WebSocket 连接成功");
      }
    });

    wx.onSocketOpen(() => {
      wx.sendSocketMessage({
        data: JSON.stringify({ type: 'REGISTER', userId: that.globalData.userId, role: 'family' })
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
                that.sendRemoteInterrupt();
              }
            }
          });
        }
      } catch (e) {}
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
      })
    });
    wx.showToast({ title: '已触发远程打断', icon: 'success' });
  }
});
