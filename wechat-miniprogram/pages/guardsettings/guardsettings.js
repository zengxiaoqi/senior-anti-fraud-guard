const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 入口页只回答"每一项现在是什么状态"。
// 摘要必须是服务端值而不是本机缓存：云端是事实源，子页面保存成功后返回，
// 若显示旧值用户会以为保存没生效而再改一遍 —— 所以 onShow 无条件重拉。
// （与 Android ElderGuardSettingsActivity 同一职责划分。）
const ITEMS = [
  { key: 'home', icon: '🏠', title: '家基准位置', url: '/pages/guard-home/guard-home' },
  { key: 'location', icon: '📍', title: '位置守护阈值', url: '/pages/guard-location/guard-location' },
  { key: 'callpay', icon: '📞', title: '通话与支付', url: '/pages/guard-callpay/guard-callpay' },
  { key: 'recording', icon: '🎙️', title: '录音存证', url: '/pages/guard-recording/guard-recording' },
  { key: 'fall', icon: '🚑', title: '跌倒检测', url: '/pages/guard-fall/guard-fall' },
  { key: 'adskip', icon: '🚫', title: '拦截开屏广告', url: '/pages/guard-adskip/guard-adskip' }
];

Page({
  data: {
    isBound: false,
    items: [],
    error: ''
  },

  onShow: function () {
    app.onSessionReady(() => this.load());
  },

  load: function () {
    const bound = app.globalData.isBound;
    this.setData({ isBound: bound });
    if (!bound) return;

    // 多人绑定：设置跟随「当前选中」的老人；控制台切换后返回本页会重拉
    const elder = app.getCurElder();
    if (!elder || !elder.id) {
      this.setData({ error: '' });
      return;
    }
    guardApi.fetchSettings(elder.id, (settings) => {
      // 从子页返回后 settings 可能已是别的老人的（切换条），以请求时刻的 elder 为准
      const cur = app.getCurElder();
      if (!cur || cur.id !== elder.id) return;
      const sums = guardApi.buildSummaries(settings || {});
      const items = ITEMS.map((it) => Object.assign({}, it, sums[it.key]));
      this.setData({ items, error: '' });
    }, (err) => {
      // 不吞错误：读不出来时显示空白会让人以为"这些项都没配过"
      this.setData({ error: err });
    });
  },

  onEntryTap: function (e) {
    const key = e.currentTarget.dataset.key;
    const item = ITEMS.find((it) => it.key === key);
    if (!item) return;
    wx.navigateTo({
      url: item.url,
      fail: () => wx.showToast({ title: '打开页面失败', icon: 'none' })
    });
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  }
});
