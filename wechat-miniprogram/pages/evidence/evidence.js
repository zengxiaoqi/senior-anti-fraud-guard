const app = getApp();

Page({
  data: {
    isBound: false,
    pkg: null,
    lastFetch: 0
  },

  onShow: function () {
    this.setData({ isBound: app.globalData.isBound });
    // ✅ 修复：未绑定时不再发起请求，避免 /api/evidence/export/undefined
    if (app.globalData.isBound && app.globalData.boundUser) {
      this.fetchEvidence();
    }
  },

  fetchEvidence: function () {
    // 30s 节流
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;

    // ✅ 修复：原来引用了不存在的 globalData.elderId，统一改用 boundUser.id
    const elderId = app.globalData.boundUser.id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/evidence/export/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const pkg = res.data.data;
          // 把可疑通话的 details 对象转成可读的「key: value」数组，供模板渲染
          (pkg.suspicious_calls || []).forEach(call => {
            const d = call.details || {};
            call.callDetails = Object.keys(d).map(k => `${k}: ${d[k]}`);
          });
          this.setData({ pkg, lastFetch: now });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取证据材料失败', icon: 'none' });
      }
    });
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  },

  onExportTap: function () {
    if (!this.data.pkg) {
      wx.showToast({ title: '材料未就绪，请稍后再试', icon: 'none' });
      return;
    }
    const text = JSON.stringify(this.data.pkg, null, 2);
    wx.setClipboardData({
      data: text,
      success: () => {
        wx.showToast({ title: '证据链文本已复制', icon: 'success' });
      }
    });
  }
});
