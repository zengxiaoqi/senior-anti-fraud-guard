const app = getApp();
const eventLabels = require('../../utils/eventLabels');
const geo = require('../../utils/geo');

Page({
  data: {
    isBound: false,
    boundUser: null,
    locations: [],
    // 停留日志（连续同地点合并 ×N，见 buildTrackLog）
    trackLog: [],
    // 时间范围（与 App 端 TrackFragment 同款分段）：true=近7天（默认），false=今天。
    // 默认选周：白天早上或刚装机的用户"今天"几乎必为空，会被当成功能坏了
    rangeWeek: true,
    // 敏感地点围栏（/api/geofence/list，含停用项），地图画橙色圈 + 行内设/取消
    fences: [],
    latitude: 39.9042,
    longitude: 116.4074,
    // 地图缩放：默认城市级 14，点列表行定位时拉近到 16
    scale: 14,
    // 地图全屏（与 App 端 toggleMapExpand 同意图）：全屏时列表让位隐藏
    mapExpanded: false,
    markers: [],
    polyline: [],
    circles: [],
    // 归纳摘要：压缩成三条短行，文案在 JS 拼好（updateSummary 统一重算）
    statusText: '',
    statusOnline: false,
    lastSeenLabel: '',
    latestAddress: '',
    todayPoints: 0,
    summaryLine1: '',
    summaryLine2: '',
    summaryLine3: '',
    emptyHint: '',
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
        this.fetchLocations();
        this.fetchStatus();
        this.fetchFences();
      }
    });
  },

  fetchLocations: function () {
    // 30s 节流，避免切 tab 高频请求
    const now = Date.now();
    if (now - this.data.lastFetch < 30000) return;

    // 多人绑定：读「当前选中」老人（getCurElder 收口，下同）
    const elderId = app.getCurElder().id;

    app.apiRequest({
      // limit=200：与 Android 轨迹页同一口径（服务端默认只回 10 条，
      // 10 个点连不出有意义的轨迹，老人活动规律基本看不见）
      url: `${app.globalData.serverHost}/api/events/location/${elderId}?limit=200`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          const locations = (res.data.data || []).map(l => ({
            ...l,
            timeLabel: eventLabels.shortLocalTime(l.created_at)
          }));
          this.setData({ locations, lastFetch: now });
          this.applyRenderings();
        }
      },
      fail: () => {
        wx.showToast({ title: '获取位置信息失败', icon: 'none' });
      }
    });
  },

  // 围栏与轨迹是两份独立数据（与 App 端 TrackFragment 同策略）
  fetchFences: function () {
    const elderId = app.getCurElder().id;
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/geofence/list/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ fences: res.data.data || [] });
          this.applyRenderings();
        }
      },
      fail: () => { /* 围栏拉取失败不打扰：地图仍有轨迹 */ }
    });
  },

  // ── 时间范围切换（今天 / 近7天）：数据已在内存，只做本地重渲染 ──
  onRangeTap: function (e) {
    const week = e.currentTarget.dataset.range === 'week';
    if (week === this.data.rangeWeek) return;
    this.setData({ rangeWeek: week });
    this.applyRenderings();
  },

  // 当前时间范围下的轨迹点（今天 0 点 / 近 7 天，与 App 端 filteredPoints 同口径）
  filteredLocations: function () {
    const todayStart = eventLabels.todayStartMillis();
    const start = this.data.rangeWeek ? todayStart - 6 * 86400000 : todayStart;
    return (this.data.locations || []).filter(l => {
      const t = eventLabels.parseTime(l.created_at);
      return t && t.getTime() >= start;
    });
  },

  // 统一渲染出口：列表 / 地图 / 空态 / 围栏圈一处决策，
  // 避免"列表空了地图还有点"的口径分裂（与 App 端 refreshRenderings 同意图）
  applyRenderings: function () {
    const list = this.filteredLocations();
    const all = this.data.locations || [];
    const latest = list[0];
    const mapData = this.buildMapData(list);

    const patch = {
      trackLog: this.buildTrackLog(list),
      // 今日上报次数按全量算，不随范围切换变化（它本来就是"今日"口径）
      todayPoints: this.countToday(all),
      latestAddress: latest ? String(latest.address || '').trim() : '',
      emptyHint: this.buildEmptyHint(list),
      markers: mapData.markers,
      polyline: mapData.polyline,
      circles: this.buildFenceCircles()
    };
    // 中心点只在实际有轨迹时跟随最新位置，空范围不打扰用户当前的视野
    if (mapData.center) {
      patch.latitude = mapData.center.latitude;
      patch.longitude = mapData.center.longitude;
    }
    // 状态接口失败时的兜底：最近出现时间退化为轨迹最新一条
    if (!this.data.lastSeenLabel && latest) {
      patch.lastSeenLabel = latest.timeLabel || '';
    }
    this.setData(patch);
    this.updateSummary();
  },

  // 摘要三行统一重算（状态/轨迹/围栏任一更新后都走这里）：
  // 行1 = 在线状态 + 最近出现 + 今日上报；行2 = 最新位置（截断）；
  // 行3 = 围栏数量与生效情况。文案全部 JS 拼好，WXML 不做拼接
  updateSummary: function () {
    const d = this.data;
    const parts = [];
    if (d.statusText) parts.push(d.statusOnline ? '🟢 在线' : '🔴 离线');
    if (d.lastSeenLabel) parts.push('最近出现 ' + d.lastSeenLabel);
    if (d.todayPoints > 0) parts.push('今日上报 ' + d.todayPoints + ' 次');

    const addr = d.latestAddress || '';
    const line2 = addr
      ? '最新位置：' + (addr.length > 18 ? addr.slice(0, 18) + '…' : addr)
      : '';

    const fences = d.fences || [];
    const on = fences.filter(f => Number(f.enabled) === 1).length;
    const line3 = fences.length === 0 ? ''
      : '🛡️ 敏感地点围栏 ' + fences.length + ' 个' +
        (on === fences.length ? '，全部生效中' : '，' + on + ' 个生效') +
        '，老人进入自动录音存证';

    this.setData({
      summaryLine1: parts.join(' · '),
      summaryLine2: line2,
      summaryLine3: line3
    });
  },

  countToday: function (locations) {
    const todayStart = eventLabels.todayStartMillis();
    let n = 0;
    (locations || []).forEach(l => {
      const t = eventLabels.parseTime(l.created_at);
      if (t && t.getTime() >= todayStart) n++;
    });
    return n;
  },

  buildEmptyHint: function (list) {
    if (list.length > 0) return '';
    if ((this.data.fences || []).length > 0) {
      return '当前范围暂无轨迹数据\n但已登记 ' + this.data.fences.length + ' 个敏感地点围栏，地图上有圈标出';
    }
    if (!this.data.rangeWeek) return '今天暂无轨迹数据\n可切换「近 7 天」查看历史';
    return '暂无轨迹数据：请确认老人端设备已开启定位上报';
  },

  // 围栏说明行已并入 updateSummary 的第三行（buildFenceSummary 移除）

  // 在线状态：服务端按多表 MAX 判活（state / last_seen），离线给出可读时间兜底
  fetchStatus: function () {
    const elderId = app.getCurElder().id;
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/status/${elderId}`,
      timeout: 10000,
      success: (res) => {
        if (!res.data.success) return;
        const d = res.data.data || {};
        const online = String(d.state || 'ONLINE') === 'ONLINE';
        const rawSeen = d.last_seen || d.lastSeen || '';
        this.setData({
          statusOnline: online,
          statusText: online ? '🟢 设备在线' : '🔴 设备离线',
          lastSeenLabel: eventLabels.shortLocalTime(rawSeen) || this.data.lastSeenLabel
        });
        this.updateSummary();
      },
      fail: () => {
        // 状态接口失败不打扰用户：摘要行退化为只用轨迹数据算出的最近出现时间
      }
    });
  },

  // 停留日志合并：连续相同地点只展示一次 + ×N 总次数（与 App 端同规则）。
  // 老人上报是节流心跳，同一位置会连发几十条，逐条罗列全是重复没有信息量；
  // 地址为空（定位失败补不出地名）的行不合并，逐条保留避免把不同点混成一坨。
  // 每行带上代表坐标与围栏覆盖状态：支撑"设为围栏 / 取消围栏"行内操作
  //（与 App 端 TrackFragment.renderList 的 fenceBtn 同款交互）
  buildTrackLog: function (locations) {
    const merged = [];
    (locations || []).forEach((l, i) => {
      const addr = String(l.address || '').trim();
      const last = merged[merged.length - 1];
      if (addr && last && last.address === addr) {
        last.count++;
      } else {
        const lat = Number(l.latitude) || 0;
        const lng = Number(l.longitude) || 0;
        const fence = lat && lng ? this.fenceCovering(lat, lng) : null;
        // 行内预存 GCJ-02 坐标：点击行定位地图时直接用，免得每次点击都转一遍
        const g = lat && lng ? geo.wgs2gcj(lat, lng) : [0, 0];
        merged.push({
          key: (addr || 'unknown') + '-' + (l.created_at || i),
          address: addr,
          timeLabel: l.timeLabel || '',
          count: 1,
          sensitive: !!l.is_sensitive,
          lat: lat,
          lng: lng,
          gcjLat: g[0],
          gcjLng: g[1],
          fenceId: fence ? fence.id : 0,
          fenceName: fence ? fence.name : ''
        });
      }
    });
    return merged;
  },

  // 同一地点判定半径：300m 内的两次到达算同一处（注册围栏半径默认 200m）；
  // 判定取 max(围栏半径, 300) 再放宽 100m，避免"地图上有圈、列表却说没设"
  // 的自相矛盾（与 App 端 fenceCovering 同一套判据）
  fenceCovering: function (lat, lng) {
    const MERGE_RADIUS_M = 300;
    return (this.data.fences || []).find(f => {
      const r = Math.max(Number(f.radius) || 200, MERGE_RADIUS_M) + 100;
      return this.distMeters(lat, lng, Number(f.latitude), Number(f.longitude)) <= r;
    }) || null;
  },

  distMeters: function (aLat, aLng, bLat, bLng) {
    const rad = Math.PI / 180;
    const dLat = (bLat - aLat) * rad;
    const dLng = (bLng - aLng) * rad;
    const s = Math.sin(dLat / 2) ** 2 +
      Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(dLng / 2) ** 2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
  },

  // 把服务端轨迹点转成地图 markers，并把中心点定位到最新位置。
  // ⚠️ 服务端是 WGS-84，微信地图组件要 GCJ-02，必须逐点纠偏，
  // 否则整条轨迹会系统性偏移 50~500 米（高德/微信地图同一世界两套坐标）
  buildMapData: function (locations) {
    const points = (locations || [])
      .filter(l => l.latitude && l.longitude)
      .map(l => {
        const g = geo.wgs2gcj(Number(l.latitude), Number(l.longitude));
        return {
          latitude: g[0],
          longitude: g[1],
          address: l.address || '',
          createdAt: l.created_at || ''
        };
      });

    if (points.length === 0) {
      return { markers: [], polyline: [], center: null };
    }

    const markers = points.map((p, i) => ({
      id: i,
      latitude: p.latitude,
      longitude: p.longitude,
      width: 20,
      height: 20,
      callout: {
        content: p.address || `轨迹点 ${i + 1}`,
        display: 'BYCLICK',
        borderRadius: 6,
        padding: 6,
        fontSize: 12
      }
    }));

    // 服务端按时间倒序返回；正序连线才是老人实际走过的方向
    const ordered = points.slice().reverse();

    return {
      markers,
      polyline: this.buildPolyline(ordered),
      // 中心点定位到最新一个轨迹点
      center: points[0]
    };
  },

  // 围栏圈：中心同样要 WGS→GCJ 纠偏；停用围栏灰色一眼可辨（与 App 端同色）
  buildFenceCircles: function () {
    return (this.data.fences || [])
      .filter(f => Number(f.latitude) !== 0 && Number(f.longitude) !== 0)
      .map(f => {
        const g = geo.wgs2gcj(Number(f.latitude), Number(f.longitude));
        const enabled = Number(f.enabled) === 1;
        return {
          latitude: g[0],
          longitude: g[1],
          radius: Number(f.radius) || 200,
          color: enabled ? '#F59E0BFF' : '#94A3B8FF',
          fillColor: enabled ? '#22F59E0B' : '#2294A3B8',
          strokeWidth: 3
        };
      });
  },

  // 轨迹连线（与 Android 轨迹页同规则）：两点间隔超过 20km 视为 GPS 漂移/上报断档，
  // 在此断开成多段——否则会画出"横穿城市"的假轨迹，误导子女判断。
  // 输入必须已是 GCJ-02 的地图点序列（正序 = 老人实际走过的方向）
  buildPolyline: function (orderedPoints) {
    const MAX_SEG_M = 20000;
    const segments = [];
    let current = [];

    for (let i = 0; i < orderedPoints.length; i++) {
      const p = orderedPoints[i];
      if (current.length === 0) {
        current.push(p);
        continue;
      }
      const prev = current[current.length - 1];
      if (this.distMeters(prev.latitude, prev.longitude, p.latitude, p.longitude) > MAX_SEG_M) {
        if (current.length >= 2) segments.push(current);
        current = [p];
      } else {
        current.push(p);
      }
    }
    if (current.length >= 2) segments.push(current);

    return segments.map(seg => ({
      points: seg.map(p => ({ latitude: p.latitude, longitude: p.longitude })),
      color: '#3B82F6',
      width: 3,
      arrowLine: true
    }));
  },

  // ── 行内围栏操作（与 App 端 TrackFragment 设为/取消围栏同款） ──

  // 点击地点行 → 地图定位到该地点并拉近（与 App 端 focusGroup 同意图）
  onRowFocus: function (e) {
    const idx = e.currentTarget.dataset.index;
    const row = this.data.trackLog[idx];
    if (!row) return;
    if (!row.gcjLat || !row.gcjLng) {
      wx.showToast({ title: '该记录没有有效坐标，无法定位', icon: 'none' });
      return;
    }
    // 全屏态下定位收益最大：用户切全屏就是为了看细节
    this.setData({
      latitude: row.gcjLat,
      longitude: row.gcjLng,
      scale: 16
    });
    if (row.address) wx.showToast({ title: row.address.slice(0, 20), icon: 'none' });
  },

  // 地图 半屏 ↔ 全屏（与 App 端 toggleMapExpand 同意图）：全屏时列表让位隐藏
  onToggleExpand: function () {
    this.setData({ mapExpanded: !this.data.mapExpanded });
  },

  // 从轨迹行直接设为敏感地点：弹窗确认名称（预填地名可改），坐标直接用
  // 服务端 WGS-84 原值入库（不走 chooseLocation，不需要 gcj2wgs 反纠偏）
  onRowFenceAdd: function (e) {
    const idx = e.currentTarget.dataset.index;
    const row = this.data.trackLog[idx];
    if (!row || !row.lat || !row.lng) {
      wx.showToast({ title: '该记录没有有效坐标，无法设围栏', icon: 'none' });
      return;
    }
    if (!app.globalData.isBound || !app.globalData.boundUser) {
      wx.showToast({ title: '请先在控制台绑定老人', icon: 'none' });
      return;
    }
    const defaultName = (row.address || '').slice(0, 20) || '未命名地点';
    wx.showModal({
      title: '设为敏感地点',
      editable: true,
      content: defaultName,
      placeholderText: '地点名称（可修改）',
      success: (res) => {
        if (!res.confirm) return;
        const name = String(res.content || '').trim() || defaultName;
        app.apiRequest({
          url: `${app.globalData.serverHost}/api/geofence/add`,
          method: 'POST',
          data: { name, latitude: row.lat, longitude: row.lng, radius: 200 },
          timeout: 10000,
          success: (r) => {
            if (r.data.success) {
              wx.showToast({ title: '已设为敏感地点，地图上有橙色圈', icon: 'none' });
              this.fetchFences();
            } else {
              wx.showToast({ title: r.data.error || '登记失败', icon: 'none' });
            }
          },
          fail: () => wx.showToast({ title: '网络异常，请重试', icon: 'none' })
        });
      }
    });
  },

  // 取消围栏必须二次确认：围栏是"老人一进某处就录音+告警"的守护配置，
  // 误删没有任何提示性后果（与 App 端 removeFence 同理由）
  onRowFenceRemove: function (e) {
    const idx = e.currentTarget.dataset.index;
    const row = this.data.trackLog[idx];
    if (!row || !row.fenceId) return;
    wx.showModal({
      title: '取消「' + (row.fenceName || row.address) + '」的敏感地点标记？',
      content: '取消后，老人再进入这一带不会自动录音存证，也不会推送敏感地点告警。',
      confirmText: '取消围栏',
      success: (res) => {
        if (!res.confirm) return;
        app.apiRequest({
          url: `${app.globalData.serverHost}/api/geofence/delete`,
          method: 'POST',
          data: { id: row.fenceId },
          timeout: 10000,
          success: (r) => {
            if (r.data.success) {
              wx.showToast({ title: '已取消敏感地点标记', icon: 'none' });
              this.fetchFences();
            } else {
              wx.showToast({ title: r.data.error || '取消失败', icon: 'none' });
            }
          },
          fail: () => wx.showToast({ title: '网络异常，请重试', icon: 'none' })
        });
      }
    });
  },

  goBind: function () {
    wx.switchTab({ url: '/pages/dashboard/dashboard' });
  }
});
