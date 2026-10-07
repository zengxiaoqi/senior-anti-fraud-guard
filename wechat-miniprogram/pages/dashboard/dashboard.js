const app = getApp();

Page({
  data: {
    events: [],
    locations: [],
    isBound: false,
    boundUser: null,
    bindCode: '',
    myMobile: '',
    scanText: '',
    scanning: false,
    scanResult: null
  },

  onShow: function () {
    this.checkBindingStatus();
    this.fetchData();
  },

  // 30s 节流：每次切 tab 不再重复发 3 个请求
  fetchData: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) return;
    const now = Date.now();
    if (now - (this.lastFetch || 0) < 30000) return;
    this.lastFetch = now;
    this.fetchRiskEvents();
    this.fetchLocations();
  },

  checkBindingStatus: function () {
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser,
      myMobile: app.globalData.mobile || ''
    });
  },

  // 填写/修改我的手机号：弹窗输入后交给 app.updateMyMobile 统一处理
  // （全角归一化与格式校验都在 app.js 里，与 App 端同一套逻辑）
  onEditMobileTap: function () {
    const isUpdate = !!this.data.myMobile;
    wx.showModal({
      title: isUpdate ? '修改手机号' : '填写手机号',
      editable: true,
      placeholderText: '请输入11位手机号',
      content: isUpdate ? this.data.myMobile : '',
      success: (res) => {
        if (!res.confirm) return;
        app.updateMyMobile(res.content, () => {
          this.setData({ myMobile: app.globalData.mobile || '' });
        });
      }
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

    app.apiRequest({
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

  goGeofence: function () {
    wx.navigateTo({ url: '/pages/geofence/geofence' });
  },

  onUnbindTap: function () {
    wx.showModal({
      title: '解除绑定',
      content: '确定要解除与老人的绑定吗？',
      success: (res) => {
        if (res.confirm) {
          app.apiRequest({
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

    app.apiRequest({
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

  // ===== AI 风险扫描 =====
  onScanInput: function (e) {
    this.setData({ scanText: e.detail.value });
  },

  onScanTap: function () {
    const text = (this.data.scanText || '').trim();
    if (!text) {
      wx.showToast({ title: '请先粘贴可疑文案', icon: 'none' });
      return;
    }
    this.setData({ scanning: true, scanResult: null });
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/ai/scan`,
      method: 'POST',
      timeout: 10000,
      data: { textContent: text },
      success: (res) => {
        if (res.data && res.data.success) {
          this.setData({ scanResult: res.data.data });
        } else {
          wx.showToast({ title: (res.data && res.data.error) || '扫描失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      },
      complete: () => {
        this.setData({ scanning: false });
      }
    });
  },

  fetchLocations: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) return;

    app.apiRequest({
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
