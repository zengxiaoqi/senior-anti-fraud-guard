// 地图选点页（无地理位置接口版本）
//
// 为什么不用 wx.chooseLocation：它属「地理位置接口」，2022-07-14 后发布的小程序
// 必须在小程序后台「开发管理 → 接口设置」申请，且只对具备地理位置强相关场景的
// 类目开放——个人主体小程序没有可用类目，申请必驳回，上传/提审时还会因
// app.json 声明了 requiredPrivateInfos 被拦截（61040 / 「接口无权限」弹窗）。
//
// 替代方案：原生 <map> 是基础组件，不涉及任何定位接口权限；bindtap 直接给出
// 点击点的经纬度（GCJ-02，与 chooseLocation 同一坐标口径），因此下游
// geo.gcj2wgs 反纠偏逻辑可以原样保留。
const app = getApp();
const geo = require('../../utils/geo');

const NO_CENTER_HINT = '该老人暂无位置记录，请拖动/缩放地图到可疑地点所在区域，再点击地图选点';
const DEFAULT_HINT = '在地图上点击可疑地点所在位置，蓝色圆圈是围栏触发范围';

// 两点间距离（米）：只用于"附近地点"名称建议，不需要高精度
function distMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLng = (lng2 - lng1) * rad;
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLng / 2);
  const a = s1 * s1 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * s2 * s2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

// 轨迹行里的 address 偶尔是坐标串或包名，用作围栏名会很难看，直接丢弃
function usableName(addr) {
  const s = (addr || '').trim();
  if (!s || s.length > 40) return '';
  if (/^[-\d.\s,]+$/.test(s)) return '';
  return s;
}

Page({
  data: {
    // 无任何可用中心点时给全国视野，用户自行拖到目标城市
    latitude: 34.0,
    longitude: 108.0,
    scale: 4,
    markers: [],
    circles: [],
    picked: false,
    latText: '',
    lngText: '',
    suggestName: '',
    radius: 200,
    hint: DEFAULT_HINT
  },

  onLoad: function (options) {
    this.points = [];

    const opt = options || {};
    const radius = Math.abs(Number(opt.radius)) || 200;
    this.radius = radius;
    this.setData({ radius });

    const lat = Number(opt.lat);
    const lng = Number(opt.lng);
    if (isFinite(lat) && isFinite(lng) && lat && lng) {
      // 传入的是 WGS-84（围栏/轨迹口径），地图要 GCJ-02
      const g = geo.wgs2gcj(lat, lng);
      this.hasCenter = true;
      this.setData({ latitude: g[0], longitude: g[1], scale: 16 });
      this.loadElderLocations(); // 同时取附近地点做名称建议
      return;
    }
    this.hasCenter = false;
    this.loadElderLocations();
  },

  // 拉老人最近位置：既是默认视野中心，也是「附近地点」名称建议的来源。
  // 失败一律静默降级为手动拖图（选点功能本身不依赖它）。
  loadElderLocations: function () {
    const elder = app.getCurElder ? app.getCurElder() : null;
    if (!elder || !elder.id) {
      if (!this.hasCenter) this.setData({ hint: NO_CENTER_HINT });
      return;
    }
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/location/${elder.id}?limit=50`,
      timeout: 10000,
      success: (res) => {
        const rows = (res.data && res.data.data) || [];
        const pts = [];
        rows.forEach((r) => {
          const lat = Number(r.lat);
          const lng = Number(r.lng);
          if (!isFinite(lat) || !isFinite(lng) || !lat || !lng) return;
          const g = geo.wgs2gcj(lat, lng);
          pts.push({ latitude: g[0], longitude: g[1], address: r.address || '' });
        });
        this.points = pts;
        if (pts.length > 0) {
          // 调用方给了中心点（通常是已登记围栏）就不抢视野，只取名称建议
          if (!this.hasCenter) {
            this.setData({ latitude: pts[0].latitude, longitude: pts[0].longitude, scale: 16 });
          }
        } else if (!this.hasCenter) {
          this.setData({ hint: NO_CENTER_HINT });
        }
      },
      fail: () => {
        if (!this.hasCenter) this.setData({ hint: NO_CENTER_HINT });
      }
    });
  },

  onMapTap: function (e) {
    const d = (e && e.detail) || {};
    const lat = Number(d.latitude);
    const lng = Number(d.longitude);
    if (!isFinite(lat) || !isFinite(lng)) return;

    this.pickedLat = lat;
    this.pickedLng = lng;

    this.setData({
      picked: true,
      latText: lat.toFixed(5),
      lngText: lng.toFixed(5),
      markers: [{
        id: 1,
        latitude: lat,
        longitude: lng,
        width: 26,
        height: 34
      }],
      circles: [{
        latitude: lat,
        longitude: lng,
        radius: this.radius,
        color: '#3B82F6CC',
        fillColor: '#3B82F633',
        strokeWidth: 2
      }],
      suggestName: this.nearestName(lat, lng)
    });
  },

  // 附近 300 米内有轨迹地址就拿来当名称建议（老 chooseLocation 能自动带出地名，
  // 这里用老人自己的轨迹地址还原同等体验；没有就留空，让用户自己填）
  nearestName: function (lat, lng) {
    const pts = this.points || [];
    let best = null;
    let bestD = Infinity;
    pts.forEach((p) => {
      const d = distMeters(lat, lng, p.latitude, p.longitude);
      if (d < bestD) { bestD = d; best = p; }
    });
    return best && bestD <= 300 ? usableName(best.address) : '';
  },

  onConfirm: function () {
    if (!this.data.picked) return;
    // 结果回传用「落盘 + 上一页 onShow 兜底」：
    // wx.navigateTo 的 events 事件通道要求基础库 2.7.3+，旧版本会静默丢结果，
    // 这里统一走 globalData，调用页（敏感地点守护）onShow 取一次即清空。
    app.globalData.pendingFencePick = {
      latitude: this.pickedLat,
      longitude: this.pickedLng,
      suggestName: this.data.suggestName || ''
    };
    wx.navigateBack();
  }
});
