const app = getApp();
const eventLabels = require('../../utils/eventLabels');

Page({
  data: {
    isBound: false,
    boundUser: null,
    events: [],
    // 计数总览（今日高危 / 今日需留意 / 本周累计，与 Android AlertsFragment 同口径）
    counts: { highToday: 0, midToday: 0, weekTotal: 0 },
    // 列表筛选：all=全部；high=今日高危；mid=今日需留意；week=近7天。
    // 点击统计卡切换，再点同一张取消。计数永远按全量算，只过滤列表。
    filter: 'all',
    // 空态文案（筛选态与全量态不同，在 JS 拼好进 WXML）
    emptyText: '今天还没有风险事件，老人一切正常',
    // 按天分组的渲染行：[{ header, items: [{typeLabel, timeLabel, detailsStr, count, high}] }]
    groups: [],
    lastFetch: 0
  },

  onShow: function () {
    // 等登录会话就绪再读 globalData（冷启动 onShow 先于 wx-login 完成执行）
    app.onSessionReady(() => {
      this.setData({
        isBound: app.globalData.isBound,
        boundUser: app.globalData.boundUser
      });
      if (app.globalData.isBound && app.globalData.boundUser) {
        this.fetchRiskEvents();
      }
    });
  },

  fetchRiskEvents: function () {
    // 30s 节流，避免每次切 tab 高频请求
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;

    const elderId = app.globalData.boundUser.id;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/list/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const events = res.data.data || [];
          const built = this.buildSummary(events, this.data.filter);
          this.setData({
            events,
            counts: built.counts,
            groups: built.groups,
            emptyText: this.buildEmptyText(events, built.groups, this.data.filter),
            lastFetch: now
          });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取风险事件失败', icon: 'none' });
      }
    });
  },

  /**
   * 归纳汇总（与 Android AlertsFragment 同一套规则）：
   *  1. 计数总览按"今日"口径，不按全量——守护人问的是"今天有没有事"，
   *     全量累计只会单调增长，很快失去"要不要现在看"的信号意义；
   *  2. 列表按天分组（今天/昨天/M月d日 周X），跨天不合并；
   *  3. 同一天内连续同类同级别的事件合并成一条 ×N（最新时间代表该组）；
   *  4. filter 参数只过滤"分组列表"，计数始终按全量算——否则点了筛选
   *     卡之后卡片上的数字会跟着变，"高危 2"的注意力锚点就没了。
   */
  buildSummary: function (events, filter) {
    const todayStart = eventLabels.todayStartMillis();
    const weekStart = todayStart - 6 * 86400000;
    const counts = { highToday: 0, midToday: 0, weekTotal: 0 };

    // 筛选判据（与 Android AlertsFragment.applyFilter 同一套口径）
    const hit = (ms, e) => {
      if (filter === 'high') return ms >= todayStart && e.severity === 'HIGH';
      if (filter === 'mid') return ms >= todayStart && e.severity === 'MEDIUM';
      if (filter === 'week') return ms >= weekStart;
      return true;
    };

    const groups = [];
    let curGroup = null;
    let lastItem = null;

    (events || []).forEach(e => {
      const t = eventLabels.parseTime(e.created_at);
      if (!t) return;
      const ms = t.getTime();

      if (ms >= weekStart) counts.weekTotal++;
      if (ms >= todayStart) {
        if (e.severity === 'HIGH') counts.highToday++;
        else if (e.severity === 'MEDIUM') counts.midToday++;
      }

      if (!hit(ms, e)) return; // 只影响列表，不影响上方计数

      const header = eventLabels.dayHeader(e.created_at);
      if (!curGroup || curGroup.header !== header) {
        curGroup = { header, items: [] };
        groups.push(curGroup);
        lastItem = null; // 跨天不合并：昨天和今天的同类事件是两件事
      }

      if (lastItem && eventLabels.sameKind(lastItem.raw, e)) {
        lastItem.count++;
      } else {
        lastItem = {
          raw: e,
          // 列表渲染 key：组内用"天+类型+时间"构造，保证同组内唯一
          key: header + '-' + e.event_type + '-' + String(e.created_at || ''),
          typeLabel: eventLabels.eventName(e.event_type),
          timeLabel: eventLabels.shortLocalTime(e.created_at),
          detailsStr: eventLabels.formatDetails(e.details),
          count: 1,
          high: e.severity === 'HIGH'
        };
        curGroup.items.push(lastItem);
      }
    });

    return { counts, groups };
  },

  // 未绑定时引导去控制台绑定
  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  },

  // 空态文案：筛选态要提示"怎么退出去"，否则用户以为数据丢了
  buildEmptyText: function (events, groups, filter) {
    if (groups.length > 0) return '';
    if (filter === 'all') return '今天还没有风险事件，老人一切正常';
    const label = filter === 'high' ? '今日高危' : (filter === 'mid' ? '今日需留意' : '本周累计');
    return '「' + label + '」筛选下暂无事件，再点一次上方计数卡取消筛选';
  },

  // 点击统计卡过滤列表：再点同一张取消。用缓存的 events 本地重算，不发请求
  onFilterTap: function (e) {
    const key = e.currentTarget.dataset.filter;
    const next = this.data.filter === key ? 'all' : key;
    const built = this.buildSummary(this.data.events, next);
    this.setData({
      filter: next,
      groups: built.groups,
      emptyText: this.buildEmptyText(this.data.events, built.groups, next)
    });
  },

  onInterruptTap: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) {
      wx.showToast({ title: '请先绑定老人', icon: 'none' });
      return;
    }
    // 同 onCallElderTap：借高频点按攒订阅额度（内部 30 分钟节流）
    app.requestAlertSubscription();
    app.sendRemoteInterrupt();
  },

  // 高危告警页快捷拨号：紧急场景减少一层跳转（拨老人号，见 app.callElder）
  onCallElderTap: function () {
    if (!app.globalData.isBound || !app.globalData.boundUser) {
      wx.showToast({ title: '请先绑定老人', icon: 'none' });
      return;
    }
    // 顺带攒订阅额度（app 内部 30 分钟节流）：勾过「总是保持以上选择」时
    // 本次调用静默 +1 条额度不弹窗；没勾时最多每 30 分钟打扰一次弹窗。
    // 拨号是子女最频繁的点按手势，正好是合法的订阅请求发起时机。
    app.requestAlertSubscription();
    app.callElder();
  }
});
