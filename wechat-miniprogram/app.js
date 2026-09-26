// 微信小程序全局入口逻辑
App({
  globalData: {
    serverHost: 'http://localhost:3000',
    wsHost: 'ws://localhost:3000',
    elderId: 1,
    familyId: 2,
    boundElderName: '张爷爷'
  },

  onLaunch: function () {
    console.log("🛡️ 长者防诈守护 (子女端微信小程序) 已启动");
    this.initWebSocket();
  },

  initWebSocket: function() {
    const that = this;
    const ws = wx.connectSocket({
      url: this.globalData.wsHost,
      success: () => {
        console.log("微信小程序 WebSocket 连接成功");
      }
    });

    wx.onSocketOpen(() => {
      // 注册当前用户为子女端 ID: 2
      wx.sendSocketMessage({
        data: JSON.stringify({ type: 'REGISTER', userId: that.globalData.familyId, role: 'family' })
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

  sendRemoteInterrupt: function() {
    wx.sendSocketMessage({
      data: JSON.stringify({
        type: 'INTERRUPT_CMD',
        targetElderId: this.globalData.elderId,
        message: '微信强打断：子女提醒您立即终止当前异常通话！'
      })
    });
    wx.showToast({ title: '已触发远程打断', icon: 'success' });
  }
});
