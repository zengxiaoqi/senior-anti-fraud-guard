const app = getApp();

Page({
  data: {
    events: [],
    locations: [],
    isBound: false,
    boundUser: null,
    bindCode: ''
  },

  onShow: function () {
    this.checkBindingStatus();
    this.fetchRiskEvents();
    this.fetchLocations();
  },

  checkBindingStatus: function () {
    const app = getApp();
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser
    });
  },

  onBindCodeInput: function (e) {
    this.setData({ bindCode: e.detail.value });
  },

  onBindTap: function () {
    const app = getApp();
    const bindCode = this.data.bindCode.trim();
    
    if (!bindCode) {
      wx.showToast({ title: '请输入绑定码', icon: 'none' });
      return;
    }
    
    if (!/^\d{6}$/.test(bindCode)) {
      wx.showToast({ title: '绑定码为6位数字', icon: 'none' });
      return;
    }

    wx.request({
      url: `${app.globalData.serverHost}/api/auth/bind`,
      method: 'POST',
      data: { userId: app.globalData.userId, bindCode: bindCode },
      success: (res) => {
        if (res.data.success) {
          app.globalData.isBound = true;
          app.globalData.boundUser = res.data.boundUser;
          this.setData({
            isBound: true,
            boundUser: res.data.boundUser,
            bindCode: ''
          });
          wx.showToast({ title: '绑定成功', icon: 'success' });
        } else {
          wx.showToast({ title: res.data.error || '绑定失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      }
    });
  },

  onUnbindTap: function () {
    const app = getApp();
    wx.showModal({
      title: '解除绑定',
      content: '确定要解除与老人的绑定吗？',
      success: (res) => {
        if (res.confirm) {
          app.globalData.isBound = false;
          app.globalData.boundUser = null;
          this.setData({
            isBound: false,
            boundUser: null
          });
          wx.showToast({ title: '已解除绑定', icon: 'success' });
        }
      }
    });
  },

  fetchRiskEvents: function () {
    const app = getApp();
    if (!app.globalData.isBound) return;
    
    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.boundUser.id}`,
      success: (res) => {
        if (res.data.success) {
          this.setData({ events: res.data.data });
        }
      }
    });
  },

  fetchLocations: function () {
    const app = getApp();
    if (!app.globalData.isBound) return;
    
    wx.request({
      url: `${app.globalData.serverHost}/api/events/location/${app.globalData.boundUser.id}`,
      success: (res) => {
        if (res.data.success) {
          this.setData({ locations: res.data.data });
        }
      }
    });
  }
});
