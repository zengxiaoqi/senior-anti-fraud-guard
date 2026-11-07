const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 通话与支付」。
// 只提交 callThresholdMinutes / paymentThreshold 两个字段。
// 这两项回答同一个问题："老人正在被诱导转账的迹象，多快通知我"——
// 诈骗话术里"长时间通话"和"大额支出"是连着发生的两个动作，拆开调容易顾此失彼。
Page({
  data: {
    call: '',
    pay: '',
    presets: guardApi.CALLPAY_PRESETS,
    activePreset: -1,
    saving: false
  },

  onLoad: function () {
    this.filling = true;
    this.dirty = false;
    this.settings = null;
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
      this.settings = settings || {};
      this.filling = true;
      this.setData({
        call: guardApi.posStr(this.settings, 'callThresholdMinutes'),
        pay: guardApi.posStr(this.settings, 'paymentThreshold')
      });
      this.filling = false;
      this.clearDirty();
      this.refreshPreset();
    }, (err) => {
      wx.showToast({ title: '读取守护参数失败：' + err, icon: 'none' });
    });
  },

  // ── 未保存改动拦截 ──
  markDirty: function () {
    if (this.dirty) return;
    this.dirty = true;
    try {
      if (wx.enableAlertBeforeUnload) {
        wx.enableAlertBeforeUnload({ message: '本页有还没保存的改动，直接返回不会生效。' });
      }
    } catch (e) { /* 基础库过旧，静默跳过 */ }
  },

  clearDirty: function () {
    this.dirty = false;
    try {
      if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload();
    } catch (e) { /* ignore */ }
  },

  onCallInput: function (e) { this.onFieldInput('call', e); },
  onPayInput: function (e) { this.onFieldInput('pay', e); },

  onFieldInput: function (key, e) {
    if (this.filling) return;
    this.markDirty();
    const patch = {};
    patch[key] = e.detail.value;
    this.setData(patch);
    this.refreshPreset();
  },

  onPresetTap: function (e) {
    const idx = Number(e.currentTarget.dataset.index);
    const p = guardApi.CALLPAY_PRESETS[idx];
    if (!p || this.filling) return;
    this.markDirty();
    this.setData({ call: String(p.call), pay: guardApi.trimMoney(p.pay) });
    this.refreshPreset();
  },

  // 档位高亮跟随输入框实际值
  refreshPreset: function () {
    const call = parseInt(this.data.call, 10);
    const pay = Number(this.data.pay);
    let activePreset = -1;
    guardApi.CALLPAY_PRESETS.forEach((p, i) => {
      if (call === p.call && isFinite(pay) && pay === p.pay) activePreset = i;
    });
    this.setData({ activePreset });
  },

  onSave: function () {
    const callMin = parseInt(this.data.call, 10);
    const payAmt = Number(this.data.pay);
    if (!isFinite(callMin) || !isFinite(payAmt) || this.data.call === '' || this.data.pay === '') {
      wx.showToast({ title: '有输入框为空或格式不正确，请检查', icon: 'none' });
      return;
    }
    if (payAmt < 1) {
      wx.showToast({ title: '支付预警金额须大于 0', icon: 'none' });
      return;
    }
    // 通话时长有上下界会收敛；金额服务端 clamp 到 1~1000000。
    // 收敛必须说出口：静默 clamp 会让人以为设了 300、实际按 240 守护。
    const cCall = guardApi.clampInt('callThresholdMinutes', callMin);
    const cPay = guardApi.clampMoney(payAmt);
    const note = guardApi.clampNote([['通话时长', callMin, cCall]]);
    this.doSave({
      callThresholdMinutes: cCall,
      paymentThreshold: cPay
    }, note);
  },

  doSave: function (partial, note) {
    if (this.data.saving) {
      wx.showToast({ title: '正在保存，请稍候…', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    guardApi.saveSettings(this.elderId, partial, {}, (merged) => {
      this.settings = merged || {};
      this.setData({ saving: false });
      this.clearDirty();
      this.filling = true;
      this.setData({
        call: guardApi.posStr(this.settings, 'callThresholdMinutes'),
        pay: guardApi.posStr(this.settings, 'paymentThreshold')
      });
      this.filling = false;
      this.refreshPreset();
      wx.showToast({
        title: note ? `✅ 已保存（${note}）` : '✅ 已保存并同步到老人手机',
        icon: 'none',
        duration: 2500
      });
    }, (err) => {
      this.setData({ saving: false });
      wx.showModal({
        title: '保存失败',
        content: `原因：${err}\n\n这次改动没有保存，老人手机仍在按原来的参数守护。`,
        confirmText: '重试',
        cancelText: '关闭',
        success: (r) => { if (r.confirm) this.doSave(partial, note); }
      });
    });
  }
});
