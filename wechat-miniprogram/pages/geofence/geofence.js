const app = getApp();

Page({
  data: {
    isBound: false,
    fences: [],
    // 新增表单
    form: {
      name: '',
      latitude: null,
      longitude: null,
      radius: 200,
      address: ''
    },
    radiusOptions: [100, 200, 500, 1000],
    submitting: false
  },

  onShow: function () {
    this.setData({ isBound: app.globalData.isBound });
    if (app.globalData.isBound && app.globalData.boundUser) {
      this.fetchFences();
    }
  },

  fetchFences: function () {
    const elderId = app.globalData.boundUser.id;
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/geofence/list/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ fences: res.data.data || [] });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取围栏配置失败', icon: 'none' });
      }
    });
  },

  // ── 表单输入 ──
  onNameInput: function (e) {
    this.setData({ 'form.name': e.detail.value });
  },

  onRadiusChange: function (e) {
    this.setData({ 'form.radius': this.data.radiusOptions[Number(e.detail.value)] });
  },

  // 地图选点：从微信位置选择器取坐标
  onPickLocation: function () {
    wx.chooseLocation({
      success: (res) => {
        this.setData({
          form: Object.assign({}, this.data.form, {
            latitude: res.latitude,
            longitude: res.longitude,
            address: res.address || res.name || '',
            // 未填名称时自动带出地点名
            name: this.data.form.name || res.name || ''
          })
        });
      },
      fail: (err) => {
        if (err && err.errMsg && err.errMsg.indexOf('auth') !== -1) {
          wx.showToast({ title: '需要位置权限才能选点', icon: 'none' });
        }
      }
    });
  },

  onSubmit: function () {
    const { name, latitude, longitude, radius } = this.data.form;
    if (!app.globalData.isBound || !app.globalData.boundUser) {
      wx.showToast({ title: '请先在控制台绑定老人', icon: 'none' });
      return;
    }
    if (!name || !name.trim()) {
      wx.showToast({ title: '请填写地点名称', icon: 'none' });
      return;
    }
    if (latitude === null || longitude === null) {
      wx.showToast({ title: '请在地图上选取地点', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/geofence/add`,
      method: 'POST',
      data: { name: name.trim(), latitude, longitude, radius },
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          wx.showToast({ title: '敏感地点已登记', icon: 'success' });
          this.setData({
            form: { name: '', latitude: null, longitude: null, radius: 200, address: '' },
            submitting: false
          });
          this.fetchFences();
        } else {
          wx.showToast({ title: res.data.error || '登记失败', icon: 'none' });
          this.setData({ submitting: false });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
        this.setData({ submitting: false });
      }
    });
  },

  // ── 列表操作 ──
  onToggleFence: function (e) {
    const { id, enabled } = e.currentTarget.dataset;
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/geofence/update`,
      method: 'POST',
      data: { id, enabled: !enabled },
      timeout: 10000,
      success: (res) => {
        if (res.data.success) this.fetchFences();
        else wx.showToast({ title: res.data.error || '更新失败', icon: 'none' });
      },
      fail: () => wx.showToast({ title: '网络异常', icon: 'none' })
    });
  },

  onDeleteFence: function (e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除敏感地点',
      content: '删除后老人进入该地点将不再自动录音上报，确认删除？',
      confirmColor: '#EF4444',
      success: (res) => {
        if (!res.confirm) return;
        app.apiRequest({
          url: `${app.globalData.serverHost}/api/geofence/delete`,
          method: 'POST',
          data: { id },
          timeout: 10000,
          success: (res2) => {
            if (res2.data.success) this.fetchFences();
            else wx.showToast({ title: res2.data.error || '删除失败', icon: 'none' });
          },
          fail: () => wx.showToast({ title: '网络异常', icon: 'none' })
        });
      }
    });
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  }
});
