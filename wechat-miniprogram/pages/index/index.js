const app = getApp();

Page({
  data: {
    isBound: false,
    boundUser: null,
    events: [],
    lastFetch: 0
  },

  onShow: function () {
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser
    });
    if (app.globalData.isBound && app.globalData.boundUser) {
      this.fetchRiskEvents();
    }
  },

  fetchRiskEvents: function () {
    // 30s 节流，避免每次切 tab 高频请求
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;

    // ✅ 修复：原来引用了不存在的 globalData.elderId，统一改用 boundUser.id
    const elderId = app.globalData.boundUser.id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/list/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const formatted = (res.data.data || []).map(item => ({
            ...item,
            // 可读化详情，替代原来的 JSON.stringify
            detailsStr: this.formatDetails(item.details)
          }));
          this.setData({ events: formatted, lastFetch: now });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取风险事件失败', icon: 'none' });
      }
    });
  },

  // 把 details 对象拼成「key: value；key: value」的可读文本
  formatDetails: function (details) {
    if (!details) return '';
    if (typeof details === 'string') return details;
    try {
      return Object.keys(details).map(k => `${k}: ${details[k]}`).join('；');
    } catch (e) {
      return '';
    }
  },

  // 未绑定时引导去控制台绑定
  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  },

  onInterruptTap: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) {
      wx.showToast({ title: '请先绑定老人', icon: 'none' });
      return;
    }
    app.sendRemoteInterrupt();
  }
});
