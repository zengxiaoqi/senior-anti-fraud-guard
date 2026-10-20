const app = getApp();
const geo = require('../../utils/geo');

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
    // 等登录会话就绪再读 globalData（冷启动 onShow 先于 wx-login 完成执行）
    app.onSessionReady(() => {
      this.setData({ isBound: app.globalData.isBound });
      if (app.globalData.isBound && app.globalData.boundUser) {
        this.fetchFences();
      }
    });
  },

  fetchFences: function () {
    // 多人绑定：读「当前选中」老人（getCurElder 收口，下同）
    const elder = app.getCurElder();
    const elderId = elder.id;
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
    // wx.chooseLocation 返回 GCJ-02（火星坐标），而库里的围栏/老人端 GPS
    // 统一是 WGS-84（与 App 端 MapPicker gcj2wgs 后入库同一约定）。
    // 不转就提交会偏 50~500 米：老人真进了圈不触发、没进却误触发。
    const wgs = geo.gcj2wgs(latitude, longitude);
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/geofence/add`,
      method: 'POST',
      data: { name: name.trim(), latitude: wgs[0], longitude: wgs[1], radius },
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
