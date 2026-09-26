const app = getApp();

Page({
  data: {
    pkg: null
  },

  onShow: function () {
    const that = this;
    wx.request({
      url: `${app.globalData.serverHost}/api/evidence/export/${app.globalData.elderId}`,
      success: (res) => {
        if (res.data.success) {
          that.setData({ pkg: res.data.data });
        }
      }
    });
  },

  onExportTap: function() {
    if (this.data.pkg) {
      const text = JSON.stringify(this.data.pkg, null, 2);
      wx.setClipboardData({
        data: text,
        success: () => {
          wx.showToast({ title: '证据链文本已复制', icon: 'success' });
        }
      });
    }
  }
});
