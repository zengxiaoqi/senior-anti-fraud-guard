// 广告「源头关闭」指引页：蒸馏自 android-ad-cleaner（MIT）的机型清单。
// 数据由后端 /api/adguide 提供（公开只读，无鉴权），改数据不用发版。
const app = getApp();

Page({
  data: {
    loading: true,
    loadError: false,
    version: '',
    source: null,
    brands: [],
    universal: [],
    // 当前选中品牌
    brandId: '',
    brand: null,
    // 老人端自动匹配到的设备信息（匹配不中为空串）
    deviceBrand: '',
    deviceModel: '',
    // 勾选进度：key = `节下标-条目下标`，value = true
    doneMap: {},
    doneCount: 0,
    totalCount: 0
  },

  onLoad() {
    this.loadData();
  },

  loadData() {
    this.setData({ loading: true, loadError: false });
    wx.request({
      url: `${app.globalData.serverHost}/api/adguide/brands`,
      timeout: 10000,
      success: (res) => {
        if (res.data && res.data.success) {
          const d = res.data.data;
          this.setData({
            loading: false,
            version: d.version,
            source: d.source,
            brands: d.brands,
            universal: d.universal
          });
          // 数据就绪后尝试自动匹配老人手机品牌
          this.autoMatchBrand();
        } else {
          this.setData({ loading: false, loadError: true });
        }
      },
      fail: () => {
        this.setData({ loading: false, loadError: true });
      }
    });
  },

  /**
   * 自动匹配老人手机品牌：读老人端随定位心跳上报的 device_brand/device_model，
   * 按数据里各品牌的 match 关键词做小写包含匹配。匹配不中（未升级 APK /
   * 还没心跳过 / 小众品牌）就静默保持手动选择，不打扰。
   */
  autoMatchBrand() {
    const elder = app.getCurElder();
    if (!elder || !elder.id) return;
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/auth/user/${elder.id}`,
      timeout: 10000,
      success: (res) => {
        const body = res.data || {};
        if (!body.success) return;
        const u = body.data || {};
        const brand = String(u.device_brand || '').trim();
        const model = String(u.device_model || '').trim();
        if (!brand && !model) return;
        const haystack = `${brand} ${model}`.toLowerCase();
        const hit = this.data.brands.find((b) =>
          (b.match || []).some((kw) => haystack.includes(String(kw).toLowerCase()))
        );
        if (hit && hit.id !== this.data.brandId) {
          this.selectBrand(hit.id);
          this.setData({ deviceBrand: brand, deviceModel: model });
        }
      },
      fail: () => { /* 匹配失败静默：页面保持手动选择态 */ }
    });
  },

  onRetryTap() {
    this.loadData();
  },

  onBrandTap(e) {
    const id = e.currentTarget.dataset.id;
    if (id === this.data.brandId) {
      // 再点一次收起
      this.setData({ brandId: '', brand: null, doneMap: {}, doneCount: 0, totalCount: 0 });
      return;
    }
    this.selectBrand(id);
  },

  selectBrand(id) {
    wx.request({
      url: `${app.globalData.serverHost}/api/adguide/brands/${id}`,
      timeout: 10000,
      success: (res) => {
        if (res.data && res.data.success) {
          const brand = res.data.data;
          const total = brand.sections.reduce((n, s) => n + (s.off || []).length, 0);
          this.setData({ brandId: id, brand, doneMap: {}, doneCount: 0, totalCount: total });
        }
      },
      fail: () => {
        wx.showToast({ title: '加载失败，请重试', icon: 'none' });
      }
    });
  },

  onItemToggle(e) {
    const { s, i } = e.currentTarget.dataset;
    const key = `${s}-${i}`;
    const doneMap = this.data.doneMap;
    if (doneMap[key]) {
      delete doneMap[key];
    } else {
      doneMap[key] = true;
    }
    this.setData({ doneMap, doneCount: Object.keys(doneMap).length });
  },

  onCopyPath(e) {
    const text = e.currentTarget.dataset.path;
    if (!text) return;
    wx.setClipboardData({ data: text });
  }
});
