const app = getApp();
const guardApi = require('../../utils/guardSettingsApi');

// 子女端「守护设置 › 位置守护阈值」。
// 只提交 homeAwayRadiusMeters / stayMoveMeters / homeStayMinutes 三个字段：
// 服务端是增量合并（mergeFamilySettings），不会冲掉通话/支付/录音那些项。
// 档位在前、数字框在后：先给三档让人对号入座，数字框只做微调兜底。
// 档位高亮跟随输入框实际值：手动改到和任何一档都不一致时不高亮任何档。
Page({
  data: {
    away: '',
    stayMove: '',
    minutes: '',
    presets: guardApi.LOCATION_PRESETS,
    activePreset: -1,
    hint: '三项都要填：离家半径 100~5000 米、停留判定半径 20~1000 米、停留时长 5~240 分钟',
    hintWarn: false,
    saving: false
  },

  onLoad: function () {
    // 服务端值填表过程中不置 dirty，用户一进页面就被拦"有未保存改动"是纯噪音
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
        away: guardApi.posStr(this.settings, 'homeAwayRadiusMeters'),
        stayMove: guardApi.posStr(this.settings, 'stayMoveMeters'),
        minutes: guardApi.posStr(this.settings, 'homeStayMinutes')
      });
      this.filling = false;
      this.clearDirty();
      this.refresh();
    }, (err) => {
      wx.showToast({ title: '读取守护参数失败：' + err, icon: 'none' });
    });
  },

  // ── 未保存改动拦截 ──
  // 有改动时返回前弹确认（基础库不支持时静默跳过，保存按钮仍是主路径）。
  // 填表过程中（filling）置值不置 dirty，用户一进页面就被拦是纯噪音。
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

  // ── 输入 ──

  onAwayInput: function (e) { this.onFieldInput('away', e); },
  onStayMoveInput: function (e) { this.onFieldInput('stayMove', e); },
  onMinutesInput: function (e) { this.onFieldInput('minutes', e); },

  onFieldInput: function (key, e) {
    if (this.filling) return;
    this.markDirty();
    const patch = {};
    patch[key] = e.detail.value;
    this.setData(patch);
    this.refresh();
  },

  onPresetTap: function (e) {
    const idx = Number(e.currentTarget.dataset.index);
    const p = guardApi.LOCATION_PRESETS[idx];
    if (!p) return;
    if (this.filling) return;
    this.markDirty();
    this.setData({ away: String(p.away), stayMove: String(p.stayMove), minutes: String(p.minutes) });
    this.refresh();
  },

  // ── 实时换算：把三个裸数字翻译成一句人话，填错当场就能看出 ──

  refresh: function () {
    const away = parseInt(this.data.away, 10);
    const stayMove = parseInt(this.data.stayMove, 10);
    const minutes = parseInt(this.data.minutes, 10);
    let hint;
    let hintWarn;
    if (!isFinite(away) || !isFinite(stayMove) || !isFinite(minutes)) {
      hint = '三项都要填：离家半径 100~5000 米、停留判定半径 20~1000 米、停留时长 5~240 分钟';
      hintWarn = false;
      this.setData({ hint, hintWarn, activePreset: -1 });
      return;
    }
    const cAway = guardApi.clampInt('homeAwayRadiusMeters', away);
    const cMove = guardApi.clampInt('stayMoveMeters', stayMove);
    const cMin = guardApi.clampInt('homeStayMinutes', minutes);
    const clamped = cAway !== away || cMove !== stayMove || cMin !== minutes;
    if (clamped) {
      hint = `超出范围，将按：离家超过 ${cAway} 米 · 停留判定半径 ${cMove} 米 · 超过 ${cMin} 分钟`;
      hintWarn = true;
    } else {
      hint = `当前：离家超过 ${cAway} 米告警；在同一地点（半径 ${cMove} 米内）待超过 ${cMin} 分钟告警`;
      hintWarn = false;
    }
    // 档位高亮跟随实际值：三项全对上才亮，不假装"现在还是标准档"
    let activePreset = -1;
    guardApi.LOCATION_PRESETS.forEach((p, i) => {
      if (cAway === p.away && cMove === p.stayMove && cMin === p.minutes) activePreset = i;
    });
    this.setData({ hint, hintWarn, activePreset });
  },

  // ── 保存 ──

  onSave: function () {
    const away = parseInt(this.data.away, 10);
    const stayMove = parseInt(this.data.stayMove, 10);
    const minutes = parseInt(this.data.minutes, 10);
    if (!isFinite(away) || !isFinite(stayMove) || !isFinite(minutes)) {
      wx.showToast({ title: '有输入框为空或格式不正确，请检查', icon: 'none' });
      return;
    }
    const cAway = guardApi.clampInt('homeAwayRadiusMeters', away);
    const cMove = guardApi.clampInt('stayMoveMeters', stayMove);
    const cMin = guardApi.clampInt('homeStayMinutes', minutes);
    const note = guardApi.clampNote([
      ['离家半径', away, cAway],
      ['停留判定半径', stayMove, cMove],
      ['停留时长', minutes, cMin]
    ]);
    this.doSave({
      homeAwayRadiusMeters: cAway,
      stayMoveMeters: cMove,
      homeStayMinutes: cMin
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
      // 成功后用服务端 merged 回填：客户端与服务端 clamp 口径万一漂移，
      // 界面显示的必须是真实生效值，而不是"我以为我存进去的那个值"
      this.filling = true;
      this.setData({
        away: guardApi.posStr(this.settings, 'homeAwayRadiusMeters'),
        stayMove: guardApi.posStr(this.settings, 'stayMoveMeters'),
        minutes: guardApi.posStr(this.settings, 'homeStayMinutes')
      });
      this.filling = false;
      this.refresh();
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
