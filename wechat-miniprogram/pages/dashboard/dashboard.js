const app = getApp();

Page({
  data: {
    elderInfo: {},
    todayStats: { total: 0, high: 0, medium: 0, low: 0 },
    recentEvents: [],
    filterType: 'all',
    filterOptions: [
      { value: 'all', label: '全部' },
      { value: 'CALL_RISK', label: '通话风险' },
      { value: 'PAYMENT_RISK', label: '大额扣款' },
      { value: 'DEVICE_ONLINE', label: '设备上线' }
    ],
    isConnected: false
  },

  onLoad: function () {
    this.loadElderInfo();
    this.loadTodayStats();
    this.loadRecentEvents();
  },

  onShow: function () {
    this.loadTodayStats();
    this.loadRecentEvents();
    this.checkConnection();
  },

  checkConnection: function () {
    const that = this;
    wx.getSocketState({
      success: (res) => {
        that.setData({ isConnected: res.socketOpened });
      }
    });
  },

  loadElderInfo: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/auth/user/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          that.setData({ elderInfo: res.data.data });
        }
      }
    });
  },

  loadTodayStats: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          const today = new Date().toDateString();
          const todayEvents = res.data.data.filter(e => new Date(e.created_at).toDateString() === today);
          const stats = { total: todayEvents.length, high: 0, medium: 0, low: 0 };
          todayEvents.forEach(e => {
            if (e.severity === 'HIGH') stats.high++;
            else if (e.severity === 'MEDIUM') stats.medium++;
            else stats.low++;
          });
          that.setData({ todayStats: stats });
        }
      }
    });
  },

  loadRecentEvents: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          let events = res.data.data.slice(0, 20);
          if (that.data.filterType !== 'all') {
            events = events.filter(e => e.event_type === that.data.filterType);
          }
          const formatted = events.map(item => ({
            ...item,
            typeLabel: that.getEventLabel(item.event_type),
            timeLabel: that.formatTime(item.created_at),
            severityColor: item.severity === 'HIGH' ? '#EF4444' : item.severity === 'MEDIUM' ? '#F59E0B' : '#10B981'
          }));
          that.setData({ recentEvents: formatted });
        }
      }
    });
  },

  onFilterChange: function (e) {
    const index = e.detail.value;
    this.setData({ filterType: this.data.filterOptions[index].value });
    this.loadRecentEvents();
  },

  onInterruptTap: function () {
    wx.showModal({
      title: '确认远程打断',
      content: '将向老人手机发送强提醒，打断当前通话/操作',
      confirmText: '确认打断',
      confirmColor: '#EF4444',
      success: (res) => {
        if (res.confirm) {
          app.sendRemoteInterrupt();
        }
      }
    });
  },

  onCallElderTap: function () {
    if (this.data.elderInfo.phone) {
      wx.makePhoneCall({ phoneNumber: this.data.elderInfo.phone });
    } else {
      wx.showToast({ title: '未绑定老人手机号', icon: 'none' });
    }
  },

  onNavigateToMap: function () {
    wx.switchTab({ url: '/pages/map/map' });
  },

  onNavigateToEvidence: function () {
    wx.switchTab({ url: '/pages/evidence/evidence' });
  },

  onNavigateToGuide: function () {
    wx.switchTab({ url: '/pages/guide/guide' });
  },

  getEventLabel: function (type) {
    const labels = {
      'CALL_RISK': '通话风险',
      'PAYMENT_RISK': '大额扣款',
      'DEVICE_ONLINE': '设备上线',
      'PING_TEST': '连接测试'
    };
    return labels[type] || type;
  },

  formatTime: function (dateStr) {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now - date;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`;
    return `${date.getMonth() + 1}-${date.getDate()} ${date.getHours()}:${date.getMinutes()}`;
  }
});
