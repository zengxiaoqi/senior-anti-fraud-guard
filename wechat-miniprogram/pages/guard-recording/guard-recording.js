const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 录音存证」。
// 只提交 recordingAutoUpload / recordingMaxSegments / recordingSegmentMinutes。
// 实时换算总时长那行必须留着：段数与单段时长分开摆会让人以为设的是"总时长"，
// 实际是两数相乘 —— 不当场算出来，保存完才发现"我以为是 30 分钟结果是 50"。
Page({
  data: {
    autoUpload: true,
    segments: '',
    segmentMinutes: '',
    hint: '请填写：段数 1~6，每段 1~10 分钟',
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
      const s = settings || {};
      this.filling = true;
      this.setData({
        autoUpload: s.recordingAutoUpload === undefined ? true : !!s.recordingAutoUpload,
        segments: guardApi.posStr(s, 'recordingMaxSegments'),
        segmentMinutes: guardApi.posStr(s, 'recordingSegmentMinutes')
      });
      this.filling = false;
      this.clearDirty();
      this.refreshHint();
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

  onAutoUploadChange: function (e) {
    if (this.filling) return;
    this.markDirty();
    this.setData({ autoUpload: e.detail.value });
  },

  onSegmentsInput: function (e) { this.onFieldInput('segments', e); },
  onSegmentMinutesInput: function (e) { this.onFieldInput('segmentMinutes', e); },

  onFieldInput: function (key, e) {
    if (this.filling) return;
    this.markDirty();
    const patch = {};
    patch[key] = e.detail.value;
    this.setData(patch);
    this.refreshHint();
  },

  refreshHint: function () {
    const seg = parseInt(this.data.segments, 10);
    const per = parseInt(this.data.segmentMinutes, 10);
    let hint;
    let hintWarn;
    if (!isFinite(seg) || !isFinite(per)) {
      hint = '请填写：段数 1~6，每段 1~10 分钟';
      hintWarn = false;
      this.setData({ hint, hintWarn });
      return;
    }
    const cs = guardApi.clampInt('recordingMaxSegments', seg);
    const cp = guardApi.clampInt('recordingSegmentMinutes', per);
    if (cs !== seg || cp !== per) {
      hint = `超出范围：将按 ${cs} 段 × ${cp} 分钟 = ${cs * cp} 分钟保存`;
      hintWarn = true;
    } else {
      hint = `当前：${seg} 段 × ${per} 分钟，约 ${seg * per} 分钟。弱网下单段越短越不容易上传失败。`;
      hintWarn = per >= 8; // 单段过长提示弱网风险
    }
    this.setData({ hint, hintWarn });
  },

  onSave: function () {
    const seg = parseInt(this.data.segments, 10);
    const perMin = parseInt(this.data.segmentMinutes, 10);
    if (!isFinite(seg) || !isFinite(perMin)) {
      wx.showToast({ title: '有输入框为空或格式不正确，请检查', icon: 'none' });
      return;
    }
    const cs = guardApi.clampInt('recordingMaxSegments', seg);
    const cp = guardApi.clampInt('recordingSegmentMinutes', perMin);
    const note = guardApi.clampNote([
      ['录音段数', seg, cs],
      ['单段时长', perMin, cp]
    ]);
    this.doSave({
      recordingAutoUpload: this.data.autoUpload,
      recordingMaxSegments: cs,
      recordingSegmentMinutes: cp
    }, note);
  },

  doSave: function (partial, note) {
    if (this.data.saving) {
      wx.showToast({ title: '正在保存，请稍候…', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    guardApi.saveSettings(this.elderId, partial, {}, (merged) => {
      const s = merged || {};
      this.setData({ saving: false });
      this.clearDirty();
      this.filling = true;
      this.setData({
        autoUpload: s.recordingAutoUpload === undefined ? true : !!s.recordingAutoUpload,
        segments: guardApi.posStr(s, 'recordingMaxSegments'),
        segmentMinutes: guardApi.posStr(s, 'recordingSegmentMinutes')
      });
      this.filling = false;
      this.refreshHint();
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
