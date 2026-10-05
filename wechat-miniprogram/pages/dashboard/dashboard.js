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
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser
    });
  },

  onBindCodeInput: function (e) {
    this.setData({ bindCode: e.detail.value });
  },

  onBindTap: function () {
    const bindCode = this.data.bindCode.trim();
    
    if (!bindCode) {
      wx.showToast({ title: '请输入绑定码', icon: 'none' });
      return;
    }
    
    if (!/^\d{6}$/.test(bindCode)) {
      wx.showToast({ title: '绑定码为6位数字', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '绑定中...' });

    wx.request({
      url: `${app.globalData.serverHost}/api/auth/bind`,
      method: 'POST',
      timeout: 10000,
      data: { userId: app.globalData.userId, bindCode },
      success: (res) => {
        wx.hideLoading();
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
        wx.hideLoading();
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      }
    });
  },

  onUnbindTap: function () {
    wx.showModal({
      title: '解除绑定',
      content: '确定要解除与老人的绑定吗？',
      success: (res) => {
        if (res.confirm) {
          wx.request({
            url: `${app.globalData.serverHost}/api/auth/unbind`,
            method: 'POST',
            timeout: 10000,
            data: { userId: app.globalData.userId },
            success: (res) => {
              if (res.data.success) {
                app.globalData.isBound = false;
                app.globalData.boundUser = null;
                this.setData({
                  isBound: false,
                  boundUser: null
                });
                wx.showToast({ title: '已解除绑定', icon: 'success' });
              } else {
                wx.showToast({ title: res.data.error || '解除绑定失败', icon: 'none' });
              }
            },
            fail: () => {
              wx.showToast({ title: '网络异常，请重试', icon: 'none' });
            }
          });
        }
      }
    });
  },

  fetchRiskEvents: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) return;

    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.boundUser.id}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ events: res.data.data });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取风险事件失败', icon: 'none' });
      }
    });
  },

  fetchLocations: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) return;

    wx.request({
      url: `${app.globalData.serverHost}/api/events/location/${app.globalData.boundUser.id}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ locations: res.data.data });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取位置信息失败', icon: 'none' });
      }
    });
  }
});
