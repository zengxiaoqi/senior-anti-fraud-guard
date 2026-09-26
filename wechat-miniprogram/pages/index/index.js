const app = getApp();

Page({
  data: {
    events: []
  },

  onShow: function () {
    this.fetchRiskEvents();
  },

  fetchRiskEvents: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/events/list/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          const formatted = res.data.data.map(item => ({
            ...item,
            detailsStr: JSON.stringify(item.details)
          }));
          that.setData({ events: formatted });
        }
      }
    });
  },

  onInterruptTap: function () {
    app.sendRemoteInterrupt();
  }
});
