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

  // 事件类型 → 可读标签（含本次新增的 SOS 录音与敏感地点围栏事件）
  typeLabels: {
    'SOS': '🆘 一键紧急求助',
    'GEOFENCE_RECORDING': '📍 进入敏感地点（自动录音存证）',
    'GEOFENCE_EXIT': '📍 离开敏感地点（录音停止）',
    'LOCATION_RISK': '📍 敏感地点停留告警',
    'LOCATION_UPDATE': '📍 位置上报',
    'PAYMENT_RISK': '💳 大额支付预警',
    'CALL_RISK': '📞 异常通话预警',
    'DEVICE_ONLINE': '📶 设备上线'
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
            detailsStr: this.formatDetails(item.details),
            typeLabel: this.typeLabels[item.event_type] || item.event_type
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
