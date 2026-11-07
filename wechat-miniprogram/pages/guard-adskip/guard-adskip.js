const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 拦截开屏广告」。
// 只有一个开关（adSkipEnabled），但有三件事必须说出口（与 Android AdSkipRuleActivity 一致）：
//  1. 开关通过 guard_settings 实时下发到老人手机（WS 推送，最长一个拉取周期兜底）；
//  2. 光有开关不够：老人手机还要为本应用开启系统「无障碍」权限，且只能在老人手机上操作
//     —— 子女端看不到那台机器的授权状态，所以这里要提醒子女让老人去开；
//  3. 拦截计数存在老人手机本地，不同步到子女端，这里看不到"拦了多少次"。
Page({
  data: {
    enabled: false,
    saving: false
  },

  onLoad: function () {
    this.filling = true;
    this.elderId = 0;
  },

  onShow: function () {
    app.onSessionReady(() => this.load());
  },

  load: function () {
    const elder = app.getCurElder();
    if (!elder || !elder.id) return;
    this.elderId = elder.id;
    guardApi.fetchSettings(elder.id, (settings) => {
      const cur = app.getCurElder();
      if (!cur || cur.id !== elder.id) return;
      this.filling = true;
      this.setData({ enabled: !!((settings || {}).adSkipEnabled) });
      this.filling = false;
    }, (err) => {
      wx.showToast({ title: '读取守护参数失败：' + err, icon: 'none' });
    });
  },

  onEnabledChange: function (e) {
    if (this.filling) return;
    const enabled = e.detail.value;
    this.setData({ enabled });
    this.save(enabled);
  },

  save: function (enabled) {
    if (this.data.saving) return;
    this.setData({ saving: true });
    guardApi.saveSettings(this.elderId, { adSkipEnabled: enabled }, {}, (merged) => {
      // 以服务端回执为准重渲染
      this.filling = true;
      this.setData({ enabled: !!((merged || {}).adSkipEnabled), saving: false });
      this.filling = false;
      wx.showToast({ title: '✅ 已保存并同步到老人手机', icon: 'none', duration: 2500 });
    }, (err) => {
      this.setData({ saving: false });
      // 保存失败把开关弹回真实值：云端没变，界面不能假装已开
      guardApi.fetchSettings(this.elderId, (s) => {
        this.filling = true;
        this.setData({ enabled: !!((s || {}).adSkipEnabled) });
        this.filling = false;
      }, () => {});
      wx.showModal({
        title: '保存失败',
        content: `原因：${err}\n\n这次改动没有保存，老人手机仍在按原来的参数守护。`,
        confirmText: '重试',
        cancelText: '关闭',
        success: (r) => { if (r.confirm) this.save(enabled); }
      });
    });
  }
});
