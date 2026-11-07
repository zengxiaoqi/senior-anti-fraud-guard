const app = getApp();
const geo = require('../../utils/geo');
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 家基准位置」。
//
// 与 Android HomeBaseActivity 同一交互：本页不需要保存按钮 ——
// 只有"设置家基准"和"清除家基准"两件事，都即时生效，没有中间态，
// 而"点完还要记得去别处保存"最容易让人以为改过了、其实没改。
//
// 坐标口径：选点页给 GCJ-02，入库前转 WGS-84（与敏感地点围栏同一约定）；
// 服务端存的 homeLat/homeLng 是 WGS-84。
// 小程序不能调任何定位接口（个人主体），所以没有"用当前位置"入口，只有地图选点。
Page({
  data: {
    homeSet: false,
    homeText: '',
    homeName: '',
    saving: false
  },

  onLoad: function () {
    this.homeLat = null;
    this.homeLng = null;
    // 同一坐标只查一次逆地理：onShow 反复触发时不烧配额
    this.lastQueriedLat = null;
    this.lastQueriedLng = null;
  },

  onShow: function () {
    // 选点回执（落盘 + onShow 兜底，取一次即清空）
    if (app.globalData.pendingGuardHomePick) {
      const pending = app.globalData.pendingGuardHomePick;
      app.globalData.pendingGuardHomePick = null;
      this.consumePick(pending);
    }
    app.onSessionReady(() => this.load());
  },

  load: function () {
    const elder = app.getCurElder();
    if (!elder || !elder.id) return;
    this.elderId = elder.id;
    guardApi.fetchSettings(elder.id, (settings) => {
      const cur = app.getCurElder();
      if (!cur || cur.id !== elder.id) return;
      this.settings = settings || {};
      this.homeLat = guardApi.hasHomeBase(this.settings) ? Number(this.settings.homeLat) : null;
      this.homeLng = guardApi.hasHomeBase(this.settings) ? Number(this.settings.homeLng) : null;
      this.renderHome();
    }, (err) => {
      wx.showToast({ title: '读取家基准失败：' + err, icon: 'none' });
    });
  },

  renderHome: function () {
    const set = this.homeLat !== null && this.homeLng !== null;
    this.setData({
      homeSet: set,
      homeText: set
        ? `已设置（${this.homeLat.toFixed(6)}, ${this.homeLng.toFixed(6)}）`
        : '⚠️ 尚未设置。老人端会按「首次定位到的位置」当家，离家判定可能失真。'
    });
    if (set) this.resolveName();
  },

  // 逆地理解析家基准地名（/api/geofence/reverse，与敏感地点登记同源）。
  // 解析失败就只显示坐标，不打断 —— 坐标已在，地名拿不到不影响判断是否生效。
  resolveName: function () {
    if (this.homeLat === null || this.homeLng === null) return;
    if (this.homeLat === this.lastQueriedLat && this.homeLng === this.lastQueriedLng) return;
    this.lastQueriedLat = this.homeLat;
    this.lastQueriedLng = this.homeLng;
    app.apiRequest({
      url: `${app2.globalData.serverHost}/api/geofence/reverse?lat=${this.homeLat.toFixed(6)}&lng=${this.homeLng.toFixed(6)}`,
      timeout: 10000,
      success: (res) => {
        const name = (res.data && res.data.name || '').trim();
        const valid = name && name.indexOf('坐标') !== 0 && name.indexOf('GPS') !== 0;
        this.setData({ homeName: valid ? name : '' });
        if (valid) {
          this.setData({
            homeText: `已设置：${name}（${this.homeLat.toFixed(6)}, ${this.homeLng.toFixed(6)}）`
          });
        }
      },
      fail: () => { /* 静默：只显示坐标 */ }
    });
  },

  // ── 地图选点 ──

  onPickLocation: function () {
    // 拿当前离家半径当蓝色圆圈范围，选点时对"家"的范围有个直观感受
    const away = guardApi.clampInt('homeAwayRadiusMeters', (this.settings || {}).homeAwayRadiusMeters) || 500;
    let url = `/pages/picklocation/picklocation?slot=guardHome&radius=${away}`;
    if (this.homeLat !== null && this.homeLng !== null) {
      url += `&lat=${this.homeLat}&lng=${this.homeLng}`;
    }
    wx.navigateTo({
      url,
      fail: () => wx.showToast({ title: '打开地图失败', icon: 'none' })
    });
  },

  // 选点回执：坐标为 GCJ-02，转 WGS-84 后立即提交
  consumePick: function (p) {
    if (!p || typeof p.latitude !== 'number' || typeof p.longitude !== 'number') return;
    const wgs = geo.gcj2wgs(p.latitude, p.longitude);
    this.commitHome(wgs[0], wgs[1]);
  },

  // ── 设置 / 清除 ──

  commitHome: function (lat, lng) {
    // 坐标是唯一不能 clamp 的字段：把纬度 999 收敛成 90 会静默把家设到北极。
    // 非法坐标直接拒绝并说明原因。
    const vLat = Number(lat);
    const vLng = Number(lng);
    if (!isFinite(vLat) || !isFinite(vLng) || vLat === 0 || vLng === 0 ||
        vLat < -90 || vLat > 90 || vLng < -180 || vLng > 180) {
      wx.showToast({ title: '坐标无效（纬度 -90~90、经度 -180~180 且不为 0）', icon: 'none' });
      return;
    }
    this.homeLat = vLat;
    this.homeLng = vLng;
    this.save({ homeLat: vLat, homeLng: vLng });
  },

  onClearHome: function () {
    if (this.homeLat === null && this.homeLng === null) {
      wx.showToast({ title: '当前本就未设置家基准', icon: 'none' });
      return;
    }
    // 高影响操作二次确认：改家基准直接改变「离家 / 停留」告警的判定，
    // 误触的代价是整套位置告警静默失真，而界面上不会有任何提示。
    wx.showModal({
      title: '清除家基准',
      content: '清除后老人端会退回「首次定位到的位置」作为家。若老人是在外面时首次打开 App，之后的离家判定会全部失真。\n\n这会立即生效。',
      confirmText: '确认清除',
      confirmColor: '#EF4444',
      success: (res) => {
        if (!res.confirm) return;
        this.homeLat = null;
        this.homeLng = null;
        this.setData({ homeName: '' });
        this.save({}, { homeCleared: true });
      }
    });
  },

  save: function (partial, opts) {
    if (this.data.saving) {
      wx.showToast({ title: '正在保存，请稍候…', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    guardApi.saveSettings(this.elderId, partial, opts || {}, (merged) => {
      this.settings = merged || {};
      // 以服务端 merged 为准重渲染（含清除后的空值）
      this.homeLat = guardApi.hasHomeBase(this.settings) ? Number(this.settings.homeLat) : null;
      this.homeLng = guardApi.hasHomeBase(this.settings) ? Number(this.settings.homeLng) : null;
      this.setData({ saving: false });
      this.renderHome();
      wx.showToast({ title: '✅ 已保存并同步到老人手机', icon: 'none', duration: 2500 });
    }, (err) => {
      this.setData({ saving: false });
      wx.showModal({
        title: '保存失败',
        content: `原因：${err}\n\n这次改动没有保存，老人手机仍在按原来的参数守护。`,
        confirmText: '重试',
        cancelText: '关闭',
        success: (r) => {
          if (r.confirm) this.save(partial, opts);
        }
      });
    });
  }
});
