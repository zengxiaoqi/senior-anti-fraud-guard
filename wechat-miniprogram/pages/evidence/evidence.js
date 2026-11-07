const app = getApp();
const eventLabels = require('../../utils/eventLabels');

// 可疑通话 details 的键名 → 中文标签。服务端存的是 Android 端上报的原始
// snake_case 字段（CALL_RISK / COERCION_RISK / CALL_STAT），直接「k: v」展示
// 用户看到的是 caller_number: 138... 之类的英文，完全读不懂。
// 键值对里只做"标签翻译"，值尽量保留原文——这是维权材料，号码/包名/说明
// 一个字都不能改错；未知键原样透出（字段扩展时不炸、不丢）。
const CALL_DETAIL_LABELS = {
  caller_number: '来电号码',
  call_number: '通话号码',
  duration_minutes: '通话时长（分）',
  duration_so_far_min: '已通话时长（分）',
  duration_sec: '通话时长',
  threshold_minutes: '触发阈值（分）',
  trigger: '触发场景',
  foreground_package: '前台应用包名',
  foreground_app: '前台应用',
  app_category: '应用分类',
  app_level: '应用风险等级',
  is_stranger: '陌生号码',
  peak_risk: '峰值风险',
  coercion_reported: '已报诱导风险',
  coercion_app: '疑似诱导应用',
  event_desc: '事件说明',
  from: '上报来源',
  timestamp: '记录时间'
};

// trigger 是枚举值，单独翻译（foreground_app_during_call = 通话中高危应用切前台）
const CALL_TRIGGER_LABELS = {
  foreground_app_during_call: '通话中高危应用切到前台'
};

// true/false/'unknown' 三态 → 是/否/未知（is_stranger、coercion_reported）
function humanizeBool(v) {
  if (v === true) return '是';
  if (v === false) return '否';
  if (v === 'unknown') return '未知';
  return null;
}

// 与 utils/eventLabels 的 DETAIL_SKIP 同口径：纯内部诊断字段，对子女/民警没有
// 阅读价值（行头已有服务端时间，端上 timestamp 只是冗余副本；from 是埋点来源）
const CALL_DETAIL_SKIP = new Set(['timestamp', 'from']);

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
    playing: { cur: 0, dur: 0, percent: 0, curLabel: '0:00', durLabel: '0:00' }
  },

  // 节流 / 源身份标记挂实例：this.lastFetch = 上次请求时间，this.lastElderId = 这批数据属于谁。
  // ⚠️ 原先节流读 data.lastFetch 却写 this.lastFetch（永远读到 0），且切人不清空，
  //    "维权证据"页既会重复请求又会显示上一位老人的材料（2026-11-07 统一修正）。

  onShow: function () {
    // 等登录会话就绪再读 globalData（冷启动 onShow 先于 wx-login 完成执行）
    app.onSessionReady(() => {
      const elder = app.getCurElder();
      if (!elder) {
        this.lastElderId = 0;
        this.lastFetch = 0;
        this._stopAudio();
        this.resetEvidence();
        this.setData({ isBound: false });
        return;
      }

      const elderChanged = elder.id !== this.lastElderId;
      this.setData({ isBound: true });
      if (elderChanged) {
        // 切人 = 换数据源：旧证据包/录音列表先清，节流清零后必定重拉
        this.lastElderId = elder.id;
        this.lastFetch = 0;
        this._stopAudio();
        this.resetEvidence();
      }
      this.fetchEvidence();
      this.fetchRecordings();
    });
  },

  // 清空上一位老人的证据包与录音列表（切人、解绑时用）
  resetEvidence: function () {
    this.setData({
      pkg: null,
      sessions: [],
      displaySessions: [],
      recStats: null,
      recTitle: '',
      playingId: null
    });
    this._resetPlaying();
  },

  // 当前选中老人 id（0 = 未绑定），给在途请求做源校验用
  curElderId: function () {
    const e = app.getCurElder();
    return e ? e.id : 0;
  },

  // 离开页面必须停掉播放，否则录音会在后台继续响
  onHide: function () { this._stopAudio(); },
  onUnload: function () {
    this._stopAudio();
    if (this._audio) { this._audio.destroy(); this._audio = null; }
  },

  fetchEvidence: function () {
    const cur = app.getCurElder();
    if (!cur) return;
    // 多人绑定：读「当前选中」老人（getCurElder 收口，下同）
    const elderId = cur.id;

    // 30s 节流
    const now = Date.now();
    if (now - (this.lastFetch || 0) < 30000) return;
    this.lastFetch = now;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/evidence/export/${elderId}`,
      timeout: 10000,
      success: (res) => {
        // 回执晚于切人到达：源已换就丢弃，别把 A 的证据包挂到 B 的页面上
        if (elderId !== this.curElderId()) return;
        if (res.data.success) {
          const pkg = res.data.data;
          // 把可疑通话的 details 对象转成可读的「中文标签：值」行，供模板渲染。
          // 键名查 CALL_DETAIL_LABELS；值只做三件事：时长秒数转"X分Y秒"、
          // 布尔/unknown 转"是/否/未知"、trigger 枚举翻译，其余原样保留。
          (pkg.suspicious_calls || []).forEach(call => {
            const d = call.details || {};
            call.callDetails = Object.keys(d)
              .filter(k => !CALL_DETAIL_SKIP.has(k))
              .map(k => {
                let v = d[k];
                if (k === 'duration_sec') {
                  v = this.formatDuration((Number(v) || 0) * 1000);
                } else if (k === 'duration_minutes' || k === 'duration_so_far_min' || k === 'threshold_minutes') {
                  // 分钟数自带单位进值列（标签只留"通话时长"这种短词，两列排版不折行）
                  v = `${v} 分钟`;
                } else if (k === 'trigger') {
                  v = CALL_TRIGGER_LABELS[v] || v;
                } else if (k === 'app_level' || k === 'peak_risk') {
                  // 风险等级枚举（与 severityName 同源取值，这里是"等级"语义用短词）
                  v = { HIGH: '高', MEDIUM: '中', LOW: '低' }[v] || v;
                } else {
                  const b = humanizeBool(v);
                  if (b !== null) v = b;
                }
                // 标签里的「（分）」「（秒）」后缀挪进值列，5em 定宽列放得下
                return {
                  label: (CALL_DETAIL_LABELS[k] || k).replace(/（[分秒]）$/, ''),
                  value: v === undefined || v === null ? '' : v
                };
              });
            // 事件行：类型与级别走统一文案（CALL_RISK → 异常通话预警），时间转"今天 12:59"
            call.typeLabel = eventLabels.eventName(call.event_type);
            call.sevLabel = eventLabels.severityName(call.severity);
            call.timeLabel = eventLabels.shortLocalTime(call.created_at);
          });
          // 扣款流水预处理：时间截"MM-DD HH:mm"，收款账户为占位值（"未知卡号"）时不展示。
          // order_no 是端上自造的 ORD_ANDROID_时间戳 占位单号（通知监听拿不到微信真实
          // 商户单号），只当唯一标识用——所以排版降级到次行小字，不跟收款方抢主行。
          (pkg.payment_records || []).forEach(p => {
            p.timeLabel = String(p.created_at || '').replace('T', ' ').slice(5, 16);
            const acc = String(p.payee_account || '').trim();
            p.accountLabel = acc && acc.indexOf('未知') !== 0 ? acc : '';
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
    const cur = app.getCurElder();
    if (!cur) return;
    const elderId = cur.id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/recordings/list/${elderId}?group=1&limit=30`,
      timeout: 10000,
      success: (res) => {
        if (elderId !== this.curElderId()) return;
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
