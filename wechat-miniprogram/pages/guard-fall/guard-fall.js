const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 跌倒检测」。
// 只提交 fallFreefallMinMs 一个字段（判定灵敏度）。
// 三档预设而不是数字输入框：这个参数的本质是「掉落多高才算摔」（h=½gt²），
// 子女理解不了 150ms 是什么，但理解得了"我妈习惯把手机揣裤兜"。
// 档位值是三处唯一事实源之一：服务端 bounds(100~400)、老人端检测器默认、本文件。
// 改档位值时三处要一起改（见 utils/guardSettingsApi.js 顶部说明）。
Page({
  data: {
    levels: guardApi.FALL_LEVELS,
    selectedMs: guardApi.FALL_DEFAULT_MS,
    hintText: '',
    hintWarn: false,
    saving: false
  },

  onLoad: function () {
    this.filling = true;
    this.dirty = false;
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
      const ms = Number((settings || {}).fallFreefallMinMs);
      // 服务端没配过 → 落默认档；显示出来让人知道"现在实际按什么在判定"
      this.filling = true;
      this.setData({
        selectedMs: isFinite(ms) && ms >= 100 && ms <= 400 ? ms : guardApi.FALL_DEFAULT_MS
      });
      this.filling = false;
      this.clearDirty();
      this.renderSelection();
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

  onLevelTap: function (e) {
    const idx = Number(e.currentTarget.dataset.index);
    const level = guardApi.FALL_LEVELS[idx];
    if (!level || this.filling) return;
    this.markDirty();
    this.setData({ selectedMs: level.ms });
    this.renderSelection();
  },

  // 选中态 + 该档位的"适合谁"说明，一次渲染完
  renderSelection: function () {
    const levels = guardApi.FALL_LEVELS;
    let idx = levels.findIndex((l) => l.ms === this.data.selectedMs);
    if (idx < 0) idx = 1;
    const level = levels[idx];
    this.setData({
      hintText: `${level.label}：${level.desc}`,
      hintWarn: idx !== 1
    });
  },

  onSave: function () {
    this.doSave({ fallFreefallMinMs: this.data.selectedMs }, '');
  },

  doSave: function (partial, note) {
    if (this.data.saving) {
      wx.showToast({ title: '正在保存，请稍候…', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    guardApi.saveSettings(this.elderId, partial, {}, (merged) => {
      const ms = Number((merged || {}).fallFreefallMinMs);
      this.setData({ saving: false });
      this.clearDirty();
      this.filling = true;
      this.setData({
        selectedMs: isFinite(ms) && ms >= 100 && ms <= 400 ? ms : guardApi.FALL_DEFAULT_MS
      });
      this.filling = false;
      this.renderSelection();
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
