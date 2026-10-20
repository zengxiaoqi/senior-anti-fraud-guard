const app = getApp();
const eventLabels = require('../../utils/eventLabels');

Page({
  data: {
    isBound: false,
    pkg: null,
    // 录音存证（会话分组）
    sessions: [],
    // 默认只展示最近 N 组摘要，避免录音列表把维权材料挤到看不见
    displaySessions: [],
    sessionPreviewCount: 3,
    showAllRecs: false,
    // 会话展开状态（sessionId -> bool），刷新列表时保留
    expanded: {},
    recStats: null,
    playingId: null,
    // 正在播放段的进度（cur/dur 秒、percent 百分比、标签串），渲染进度条用
    playing: { cur: 0, dur: 0, percent: 0, curLabel: '0:00', durLabel: '0:00' },
    lastFetch: 0
  },

  onShow: function () {
    // 等登录会话就绪再读 globalData（冷启动 onShow 先于 wx-login 完成执行）
    app.onSessionReady(() => {
      this.setData({ isBound: app.globalData.isBound });
      if (app.globalData.isBound && app.globalData.boundUser) {
        this.fetchEvidence();
        this.fetchRecordings();
      }
    });
  },

  // 离开页面必须停掉播放，否则录音会在后台继续响
  onHide: function () { this._stopAudio(); },
  onUnload: function () {
    this._stopAudio();
    if (this._audio) { this._audio.destroy(); this._audio = null; }
  },

  fetchEvidence: function () {
    // 30s 节流
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;
    this.lastFetch = now;

    // 多人绑定：读「当前选中」老人（getCurElder 收口，下同）
    const elderId = app.getCurElder().id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/evidence/export/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const pkg = res.data.data;
          // 把可疑通话的 details 对象转成可读的「key: value」数组，供模板渲染
          (pkg.suspicious_calls || []).forEach(call => {
            const d = call.details || {};
            call.callDetails = Object.keys(d).map(k => `${k}: ${d[k]}`);
          });
          // 元数据里的生成时间是服务端原始串，转成"今天 12:59"式可读时间
          if (pkg.metadata && pkg.metadata.generated_at) {
            pkg.generatedAtLabel = eventLabels.shortLocalTime(pkg.metadata.generated_at);
          }
          this.setData({ pkg });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取证据材料失败', icon: 'none' });
      }
    });
  },

  // ── 录音存证 ──
  //
  // 服务端 /api/recordings/list?group=1 按会话分组（一次连续录音的多段归到一起），
  // 每段带签名 streamUrl（HMAC 覆盖 rid/uid/exp，播放器直接 GET，无需自定义头）。
  // 鉴权与绑定校验在服务端做，小程序只管拉列表和播放。
  fetchRecordings: function () {
    const elderId = app.getCurElder().id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/recordings/list/${elderId}?group=1&limit=30`,
      timeout: 10000,
      success: (res) => {
        if (!res.data.success) return;
        const data = res.data.data || {};
        const sessions = (data.sessions || []).map(s => ({
          ...s,
          startedAtLabel: eventLabels.shortLocalTime(s.startedAt),
          totalDurationLabel: this.formatDuration(s.totalDurationMs),
          // 会话级研判结论（FRAUD 会话给出 verdict；SUSPECT/转写失败也要让子女看到原因）
          verdictText: this.sessionVerdict(s),
          // 折叠态摘要行上的小徽标：让子女不展开也能扫出"哪组有问题"
          badgeText: s.isFraud ? '疑似诈骗' : (s.isSuspect ? '内容可疑' : ''),
          recordings: (s.recordings || []).map(r => ({
            ...r,
            durationLabel: this.formatDuration(r.durationMs),
            recordedAtLabel: eventLabels.shortLocalTime(r.recordedAt)
          }))
        }));
        this.setData({
          sessions,
          recStats: {
            totalRecordings: data.totalRecordings || 0,
            fraudCount: data.fraudCount || 0
          },
          // WXML 表达式不支持模板字符串，标题文案在 JS 里拼好
          recTitle: data.totalRecordings
            ? `（共 ${data.totalRecordings} 段${data.fraudCount ? '，其中 ' + data.fraudCount + ' 段判为疑似诈骗' : ''}）`
            : ''
        });
        this.applySessionView();
      },
      fail: () => {
        // 录音列表拉取失败不弹 toast 抢戏（证据文本请求已各自提示），静默置空即可
        this.setData({ sessions: [], recStats: null });
      }
    });
  },

  // 会话级研判文案：优先 FRAUD 结论，其次 SUSPECT/转写失败的第一段原因
  sessionVerdict: function (session) {
    const recs = session.recordings || [];
    if (session.isFraud) {
      const fraud = recs.find(r => r.fraudStatus === 'FRAUD' && r.fraudVerdict);
      return fraud ? fraud.fraudVerdict : '⚠️ AI 研判判定为疑似诈骗，录音已长期保留为证据';
    }
    if (session.isSuspect) {
      const suspect = recs.find(r => (r.fraudStatus === 'SUSPECT' || r.fraudStatus === 'FAILED') && r.fraudVerdict);
      return suspect ? suspect.fraudVerdict : '❓ 内容可疑，请人工收听确认';
    }
    return '';
  },

  // ── 折叠视图 ──
  // 按当前"全部/预览"状态重算 displaySessions；收起全部时把超出预览数的会话从页面摘掉
  applySessionView: function () {
    const n = this.data.sessionPreviewCount;
    const shown = this.data.showAllRecs ? this.data.sessions : this.data.sessions.slice(0, n);
    this.setData({ displaySessions: shown });
  },

  onToggleAllRecs: function () {
    this.setData({ showAllRecs: !this.data.showAllRecs }, () => this.applySessionView());
    if (this.data.showAllRecs === false) this._stopAudio();
  },

  // 点分组行：展开/收起该组分段列表。收起时若正在播这组的录音，一并停掉
  onToggleSession: function (e) {
    const sid = e.currentTarget.dataset.sid;
    const opening = !this.data.expanded[sid];
    if (!opening) this._stopAudio();
    this.setData({ ['expanded.' + sid]: opening });
  },

  // 分段行空白处点按不冒泡到分组折叠逻辑
  noop: function () {},

  formatDuration: function (ms) {
    const total = Math.round((ms || 0) / 1000);
    if (total <= 0) return '—';
    const m = Math.floor(total / 60);
    const s = total % 60;
    return m > 0 ? `${m}分${s < 10 ? '0' + s : s}秒` : `${s}秒`;
  },

  // 秒 → "m:ss"（进度条两端的时间标签）
  formatSec: function (sec) {
    const s = Math.max(0, Math.floor(sec || 0));
    return Math.floor(s / 60) + ':' + (s % 60 < 10 ? '0' : '') + (s % 60);
  },

  _resetPlaying: function () {
    this.setData({ playing: { cur: 0, dur: 0, percent: 0, curLabel: '0:00', durLabel: '0:00' } });
  },

  _ensureAudio: function () {
    if (this._audio) return this._audio;
    const ctx = wx.createInnerAudioContext();
    // 注意：不挂 onStop 清状态 —— onPlayTap 里"先 stop 再 play"也会触发 onStop，
    // 异步回调会把刚设置的 playingId 清掉，按钮状态就乱了。
    // 播放态的清除只走 onEnded（自然播完）与 onError（播放失败）两条路。
    ctx.onEnded(() => {
      this.setData({ playingId: null });
      this._resetPlaying();
    });
    ctx.onError(() => {
      this.setData({ playingId: null });
      this._resetPlaying();
      wx.showToast({ title: '播放失败，请稍后重试', icon: 'none' });
    });
    // 进度跟踪：onTimeUpdate 触发很密，500ms 节流后再 setData（setData 跨线程，贵）
    this._lastTick = 0;
    ctx.onTimeUpdate(() => {
      const now = Date.now();
      if (now - this._lastTick < 500) return;
      this._lastTick = now;
      if (!this.data.playingId) return;
      const cur = ctx.currentTime || 0;
      // 部分机型流式播放 ctx.duration 恒 0 —— 用服务端 durationMs 兜底，
      // 否则进度条永远出不来（线上实证）
      const ctxDur = ctx.duration;
      const dur = (ctxDur && isFinite(ctxDur) && ctxDur > 0) ? ctxDur : (this._durFallback || 0);
      if (!(dur > 0)) return;
      this.setData({
        playing: {
          cur,
          dur,
          percent: Math.min(100, Math.round((cur / dur) * 100)),
          curLabel: this.formatSec(cur),
          durLabel: this.formatSec(dur)
        }
      });
    });
    this._audio = ctx;
    return ctx;
  },

  _stopAudio: function () {
    if (this._audio && this.data.playingId) {
      this._audio.stop();
      this.setData({ playingId: null });
      this._resetPlaying();
    }
  },

  // 拖动中只更新显示，不真正 seek（松手才跳，避免频繁 seek 卡顿）
  onSeekPreview: function (e) {
    const percent = e.detail.value;
    const dur = this.data.playing.dur;
    if (!(dur > 0)) return;
    const target = (dur * percent) / 100;
    this.setData({
      'playing.percent': percent,
      'playing.cur': target,
      'playing.curLabel': this.formatSec(target)
    });
  },

  // 松手跳转：InnerAudioContext.seek 单位是秒
  onSeek: function (e) {
    const percent = e.detail.value;
    const audio = this._audio;
    const dur = this.data.playing.dur;
    if (!audio || !this.data.playingId || !(dur > 0)) return;
    const target = Math.floor((dur * percent) / 100);
    audio.seek(target);
    this.setData({
      'playing.percent': percent,
      'playing.cur': target,
      'playing.curLabel': this.formatSec(target)
    });
  },

  // 点击播放/停止。streamUrl 是相对路径（/api/recordings/stream/:id?token=...），
  // 播放器不带自定义头，签名 token 就在 URL 里；令牌过期时重新进页刷新列表即可
  onPlayTap: function (e) {
    const id = Number(e.currentTarget.dataset.id);
    const url = e.currentTarget.dataset.url;
    // 服务端下发的本段时长（毫秒）：流式播放时 ctx.duration 可能恒 0，
    // 用它兜底驱动进度条（seek 单位秒，不受影响）
    const durMs = Number(e.currentTarget.dataset.dur) || 0;
    if (!id || !url) return;
    const audio = this._ensureAudio();

    // 再点一次正在播放的段 = 停止
    if (this.data.playingId === id) {
      this._stopAudio();
      return;
    }

    audio.stop();
    audio.src = app.globalData.serverHost + url;
    this._durFallback = durMs / 1000;
    audio.play();
    const dur = this._durFallback;
    this.setData({
      playingId: id,
      playing: { cur: 0, dur, percent: 0, curLabel: '0:00', durLabel: this.formatSec(dur) }
    });
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  },

  onExportTap: function () {
    if (!this.data.pkg) {
      wx.showToast({ title: '材料未就绪，请稍后再试', icon: 'none' });
      return;
    }
    const text = JSON.stringify(this.data.pkg, null, 2);
    wx.setClipboardData({
      data: text,
      success: () => {
        wx.showToast({ title: '证据链文本已复制', icon: 'success' });
      }
    });
  }
});
