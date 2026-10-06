const app = getApp();

Page({
  data: {
    isBound: false,
    boundUser: null,
    locations: [],
    latitude: 39.9042,
    longitude: 116.4074,
    markers: [],
    polyline: [],
    lastFetch: 0
  },

  onShow: function () {
    this.setData({
      isBound: app.globalData.isBound,
      boundUser: app.globalData.boundUser
    });
    if (app.globalData.isBound && app.globalData.boundUser) {
      this.fetchLocations();
    }
  },

  fetchLocations: function () {
    // 30s 节流，避免切 tab 高频请求
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;

    // ✅ 修复：原来引用了不存在的 globalData.elderId，统一改用 boundUser.id
    const elderId = app.globalData.boundUser.id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/location/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const locations = res.data.data || [];
          this.setData({
            locations: locations,
            lastFetch: now,
            ...this.buildMapData(locations)
          });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取位置信息失败', icon: 'none' });
      }
    });
  },

  // 把服务端轨迹点转成地图 markers + 轨迹连线，并把中心点定位到最新位置
  buildMapData: function (locations) {
    const points = (locations || [])
      .filter(l => l.latitude && l.longitude)
      .map(l => ({
        latitude: Number(l.latitude),
        longitude: Number(l.longitude),
        address: l.address || ''
      }));

    if (points.length === 0) {
      return { markers: [], polyline: [] };
    }

    const markers = points.map((p, i) => ({
      id: i,
      latitude: p.latitude,
      longitude: p.longitude,
      width: 20,
      height: 20,
      callout: {
        content: p.address || `轨迹点 ${i + 1}`,
        display: 'BYCLICK',
        borderRadius: 6,
        padding: 6,
        fontSize: 12
      }
    }));

    const polyline = [{
      points: points.map(p => ({ latitude: p.latitude, longitude: p.longitude })),
      color: '#3B82F6',
      width: 3,
      arrowLine: true
    }];

    // 中心点定位到最新一个轨迹点（服务端按时间倒序返回，取第一个）
    const latest = points[0];
    return {
      markers,
      polyline,
      latitude: latest.latitude,
      longitude: latest.longitude
    };
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  }
});
