const app = getApp();

Page({
  data: {
    locations: []
  },

  onShow: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/events/location/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          that.setData({ locations: res.data.data });
        }
      }
    });
  }
});
