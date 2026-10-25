// 后端地址统一由 config.js 提供，换域名只改那一个文件
const config = require('./config');
const eventLabels = require('./utils/eventLabels');

/** 订阅消息授权状态的本机存储 key（微信查不到剩余额度，只能自己记） */
const NOTIFY_STATE_KEY = 'alertNotifyState';

App({
  globalData: {
    serverHost: config.serverHost,
    wsHost: config.wsHost,
    userId: null,
    bindCode: null,
    boundUser: null,
    isBound: false,
    token: null,
    // 本人真实手机号：登录时由服务端下发，改号后回写，供"修改手机号"入口渲染
    mobile: '',
    mobileMissing: true,
    // 是否 App 端账密账号：false = 微信自动建的空账号，控制台据此显示"关联 App 账号"入口
    hasPassword: false,
    // 老人手机号缓存：登录/绑定时随当前选中老人更新，解绑时清除。
    // 「联系老人」一律拨这个号（与 Android GuardConfig.boundElderPhone 同一约定）——
    // 高危告警弹窗里"拨打电话"动作靠它，紧急场景多一次网络请求都嫌慢
    elderPhone: '',
    // 多人绑定（2026-10-20）：全部 ACTIVE 老人列表 + 当前选中的老人 id。
    // boundUser 保留为兼容字段且恒 = 当前选中老人（老页面/老代码零改动）。
    // 读老人一律走 getCurElder()，别再直接摸 boundUser.id。
    boundUsers: [],
    currentElderId: 0,
    // 地图选点页 → 敏感地点守护页的回执暂存（coordinates 为 GCJ-02）。
    // 选点页写入、geofence onShow 取一次即清空——小程序跨页回传不靠
    // navigateTo events（要基础库 2.7.3+），统一落 globalData 更稳。
    pendingFencePick: null
  },

  /**
   * 当前守护的老人（多人绑定收口）。
   * 列表非空 → 按 currentElderId 取，取不到回落列表首位；
   * 列表为空（旧服务端无列表字段）→ 回退 boundUser 兼容字段。
   * 返回 null 仅在完全未绑定时发生，与旧代码判 boundUser 的守卫语义一致。
   */
  getCurElder: function () {
    const g = this.globalData;
    const list = g.boundUsers || [];
    if (list.length > 0) {
      return list.find((e) => e.id === (g.currentElderId || 0)) || list[0];
    }
    return g.boundUser || null;
  },

  /**
   * 切换当前守护对象（dashboard 切换条调用）。
   * @returns {boolean} 是否真的切了（id 无效或已是当前选中时为 false）
   */
  switchElder: function (elderId) {
    const list = this.globalData.boundUsers || [];
    const hit = list.find((e) => e.id === (Number(elderId) || 0));
    if (!hit || hit.id === this.globalData.currentElderId) return false;
    this.globalData.currentElderId = hit.id;
    this.globalData.boundUser = hit;
    this.globalData.elderPhone = hit.phone || '';
    return true;
  },

  /**
   * 用服务端回执刷新绑定列表（与 Android ApiClient.applyBoundElderList 同语义）。
   * 三种字段名兼容：登录/关联回执 boundUsers、bind/unbind 回执 boundList、用户详情 bound_elders。
   * 字段缺失 = 旧服务端响应，不动现有登录态（防降级误清）。
   * 当前选中仍有效就保留（401 静默重登录不打断用户的选择）；
   * 失效了（被解绑）再回落 boundUser 兼容字段 → 列表首位。
   */
  applyBoundElderList: function (data) {
    const d = data || {};
    const list = d.boundUsers !== undefined ? d.boundUsers
      : (d.boundList !== undefined ? d.boundList : d.bound_elders);
    if (!Array.isArray(list)) return;

    const safe = list.filter((e) => e && e.id);
    this.globalData.boundUsers = safe;

    const buId = (d.boundUser || this.globalData.boundUser || {}).id || 0;
    let cur = Number(this.globalData.currentElderId) || 0;
    if (!cur || !safe.some((e) => e.id === cur)) {
      cur = (buId && safe.some((e) => e.id === buId)) ? buId : (safe[0] ? safe[0].id : 0);
    }
    this.globalData.currentElderId = cur;

    // boundUser 兼容字段恒 = 当前选中老人
    this.globalData.boundUser = safe.find((e) => e.id === cur) || null;
    this.globalData.isBound = !!this.globalData.boundUser;
    this.globalData.elderPhone = (this.globalData.boundUser && this.globalData.boundUser.phone) || '';
  },

  // WebSocket 重连状态（指数退避，防止服务端宕机时无限重试）
  _wsReconnectAttempts: 0,
  _wsReconnectTimer: null,

  // ── WS 心跳 / 半开连接检测（2026-10-20）──
  // 线上实证：子女点「远程强响铃/语音打断」报"连接已断开，正在重连"。
  // 根因：小程序切后台约 5s 即挂起、WS 断开；回前台后 onSocketClose 早已
  // 错过，也没有任何重连触发点——连接半开，直到 sendSocketMessage 才失败。
  // App 端没这个问题（OkHttp pingInterval(15s) 协议层心跳自动检出半开）。
  // 解法：应用层心跳（PING/PONG，服务端双后端已对齐）+ 回前台失联自愈。
  _socketTask: null,
  _wsLastPongAt: 0,
  _wsPingTimer: null,

  /** 小程序切回前台：心跳失联超过 60s 就重建通道（打断按钮才不会报"连接已断开"） */
  onShow: function () {
    if (!this.globalData.userId) return;   // 冷启动时登录未完成，交给 wxLogin 流程
    if (Date.now() - this._wsLastPongAt > 60000) {
      console.log('回前台检测到 WS 失联，重建连接');
      this.initWebSocket();
    }
  },

  // ── 会话就绪通知 ──
  // 冷启动时 wx-login 是异步的，而首页 onShow 跑在它完成之前——页面读到的是
  // 还没填充的 globalData（isBound=false），表现为"进首页看不到绑定信息，
  // 切别的 tab 再切回来才有"。页面统一用 onSessionReady 包住 onShow 逻辑。
  _sessionReady: false,
  _sessionReadyCallbacks: [],

  /** 登录流程结束（成功或失败）后回调；已就绪时立即执行 */
  onSessionReady: function (cb) {
    if (typeof cb !== 'function') return;
    if (this._sessionReady) { cb(); return; }
    this._sessionReadyCallbacks.push(cb);
  },

  _notifySessionReady: function () {
    if (this._sessionReady) return;
    this._sessionReady = true;
    const cbs = this._sessionReadyCallbacks;
    this._sessionReadyCallbacks = [];
    cbs.forEach((cb) => { try { cb(); } catch (e) { /* 页面回调异常不互相拖垮 */ } });
  },

  onLaunch: function () {
    console.log("🛡️ 长者防诈守护 (子女端微信小程序) 已启动");
    this.wxLogin();
  },

  wxLogin: function () {
    wx.login({
      success: (res) => {
        if (res.code) {
          wx.request({
            url: `${this.globalData.serverHost}/api/auth/wx-login`,
            method: 'POST',
            data: { code: res.code },
            timeout: 10000,
            success: (response) => {
              if (response.data.success) {
                const data = response.data.data;
                this.globalData.userId = data.userId;
                this.globalData.bindCode = data.bindCode;
                this.globalData.boundUser = data.boundUser;
                this.globalData.isBound = !!data.boundUser;
                // 服务端签发的登录态，后续所有请求通过 X-Auth-Token 携带
                this.globalData.token = data.token || null;
                // 手机号与是否缺失随登录态一起下发，避免为了渲染入口再多发一次请求
                this.globalData.mobile = data.mobile || '';
                this.globalData.mobileMissing = !!data.mobileMissing || !data.mobile;
                this.globalData.hasPassword = !!data.hasPassword;
                // 老人号随 boundUser 下发（phone 列是 elder 真实号码），有就缓存
                this.globalData.elderPhone =
                  (data.boundUser && data.boundUser.phone) || '';
                // 多人绑定：解析 boundUsers 列表（字段缺失时此调用按旧服务端处理，不动登录态）
                this.applyBoundElderList(data);

                console.log('✅ 微信登录成功, userId:', data.userId);

                if (!this.globalData.isBound) {
                  this.showBindingGuide();
                }

                this.initWebSocket();
                // 登录完成：等在 onSessionReady 上的页面现在可以渲染了
                this._notifySessionReady();
              } else {
                this._notifySessionReady(); // 失败也要放行页面，展示未绑定态
                wx.showToast({ title: response.data.message || '登录失败', icon: 'none' });
              }
            },
            fail: () => {
              this._notifySessionReady();
              wx.showToast({ title: '网络异常，请重试', icon: 'none' });
            }
          });
        } else {
          // 拿不到 code（极少见）：也要放行页面，否则 onSessionReady 永远不触发
          this._notifySessionReady();
        }
      },
      fail: () => {
        this._notifySessionReady();
        wx.showToast({ title: '微信登录失败', icon: 'none' });
      }
    });
  },

  // 统一请求封装：自动携带 X-Auth-Token；401 时静默重登录一次
  apiRequest: function (options) {
    const app = this;
    wx.request(Object.assign({}, options, {
      header: Object.assign(
        { 'X-Auth-Token': this.globalData.token || '' },
        options.header || {}
      ),
      success: (res) => {
        if (res.statusCode === 401) {
          console.warn('登录态失效，正在静默重新登录...');
          app.wxLogin();
          if (options.fail) {
            options.fail({ errMsg: 'request:ok 登录态失效，已自动重新登录' });
          }
          return;
        }
        if (options.success) options.success(res);
      },
      fail: options.fail
    }));
  },

  showBindingGuide: function () {
    wx.showModal({
      title: '亲情绑定',
      content: '请输入老人端的6位绑定码完成绑定',
      confirmText: '去绑定',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) {
          wx.switchTab({ url: '/pages/dashboard/dashboard' });
        }
      }
    });
  },

  /**
   * 请求高危告警订阅消息授权（一次性订阅：每次授权 = 服务端可下发一条）。
   *
   * 必须在用户点按手势的回调里调用（微信硬性限制），所以挂在绑定成功流程
   * 和控制台的"开启告警通知"按钮上。未配置模板 ID 时静默跳过。
   *
   * 攒额度策略（2026-10-19）：授权弹窗勾选「总是保持以上选择」并允许后，
   * 之后每次调用都**不再弹窗、静默 +1 条额度**（官方确认的机制）——所以
   * 高频点按处也顺带调用本方法攒额度。没勾时每次调用都会弹窗，会变成骚扰，
   * 因此非强制调用（opts.force 不为 true）30 分钟内最多发起一次；
   * 绑定/订阅按钮等用户显式入口传 force 直接发起。
   *
   * 微信不提供"剩余授权额度"查询接口——开启状态只能本机记账（accept 时
   * 记一笔，供控制台展示"已开启/未开启"），额度是否已被消耗无法实时感知。
   *
   * @param {Function} [onDone] 授权流程结束回调，参数 = 本次是否 accept（被节流跳过时为 null）
   * @param {{force?: boolean}} [opts] force=true 跳过 30 分钟节流（显式按钮入口用）
   */
  requestAlertSubscription: function (onDone, opts) {
    const tmplId = config.subscribeTemplateId;
    if (!tmplId) return; // 模板未配置 = 功能关闭，不打扰用户

    const options = opts || {};
    const now = Date.now();
    try {
      const st = wx.getStorageSync(NOTIFY_STATE_KEY) || {};
      if (!options.force && st.lastRequestAt && now - st.lastRequestAt < 30 * 60 * 1000) {
        if (typeof onDone === 'function') onDone(null); // null = 被节流跳过
        return;
      }
      st.lastRequestAt = now;
      wx.setStorageSync(NOTIFY_STATE_KEY, st);
    } catch (e) {
      // 存储异常不影响授权本身
    }

    wx.requestSubscribeMessage({
      tmplIds: [tmplId],
      complete: (res) => {
        const accepted = !!(res && res[tmplId] === 'accept');
        if (accepted) {
          try {
            const st = wx.getStorageSync(NOTIFY_STATE_KEY) || {};
            st.lastAcceptAt = Date.now();
            st.accepts = (st.accepts || 0) + 1;
            wx.setStorageSync(NOTIFY_STATE_KEY, st);
          } catch (e) {
            // 存储异常不影响授权本身
          }
        }
        if (typeof onDone === 'function') onDone(accepted);
      }
    });
  },

  /** 读取本机记录的通知开启状态（从未授权过返回 null） */
  getAlertNotifyStatus: function () {
    try {
      return wx.getStorageSync(NOTIFY_STATE_KEY) || null;
    } catch (e) {
      return null;
    }
  },

  /**
   * 填写/修改本人手机号。
   *
   * 与 App 端同一套服务端接口（/api/auth/profile-mobile），行为保持一致：
   * 换号后老人端紧急警报才能拨到新号码，否则会一直打给已经用不了的旧号。
   *
   * @param {string} phone 新手机号
   * @param {function} onDone 成功回调（供页面刷新界面）
   */
  updateMyMobile: function (phone, onDone) {
    // 中文输入法默认打全角，服务端 /^1[3-9]\d{9}$/ 必判失败 —— 先归一化
    const normalized = String(phone || '').replace(/[０-９]/g, (c) =>
      String.fromCharCode(c.charCodeAt(0) - 0xFEE0)
    ).replace(/[\s-－]/g, '');

    if (!/^1[3-9]\d{9}$/.test(normalized)) {
      wx.showToast({ title: '手机号格式不正确（需11位）', icon: 'none' });
      return;
    }
    if (normalized === this.globalData.mobile) {
      wx.showToast({ title: '号码没有变化', icon: 'none' });
      return;
    }

    this.apiRequest({
      url: `${this.globalData.serverHost}/api/auth/profile-mobile`,
      method: 'POST',
      data: { phone: normalized },
      success: (res) => {
        if (res.data && res.data.success) {
          this.globalData.mobile = normalized;
          this.globalData.mobileMissing = false;
          wx.showToast({ title: '✅ 手机号已更新', icon: 'success' });
          if (onDone) onDone(normalized);
        } else {
          wx.showToast({ title: (res.data && res.data.error) || '保存失败', icon: 'none' });
        }
      },
      fail: () => wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
    });
  },

  /**
   * 关联已有 App 端账密账号。
   *
   * 微信登录自动建的号是独立空账号，和 App 端注册的账号互不相干 —— 手机号、
   * 绑定关系全割裂。关联后微信端直接复用 App 账号身份：服务端把 wx_openid
   * 挂到 App 账号上、废弃占位号、把微信端已有的绑定关系搬过去，然后下发
   * 一份与登录同形状的完整会话，客户端整体替换 globalData 并重连 WS。
   *
   * @param {string} username App 端用户名（或手机号）
   * @param {string} password App 端密码
   * @param {function} onDone 成功回调
   */
  linkAppAccount: function (username, password, onDone) {
    this.apiRequest({
      url: `${this.globalData.serverHost}/api/auth/wx-link`,
      method: 'POST',
      data: { username, password },
      success: (res) => {
        if (res.data && res.data.success) {
          const data = res.data.data;
          // 整体替换登录态：身份从占位号切到 App 账号
          this.globalData.userId = data.userId;
          this.globalData.token = data.token;
          this.globalData.bindCode = data.bindCode;
          this.globalData.boundUser = data.boundUser;
          this.globalData.isBound = !!data.boundUser;
          this.globalData.mobile = data.mobile || '';
          this.globalData.mobileMissing = !!data.mobileMissing || !data.mobile;
          this.globalData.hasPassword = true;
          // 老人号随 boundUser 重算；WS 也要用新 userId 重新注册，否则收不到告警推送
          this.globalData.elderPhone = (data.boundUser && data.boundUser.phone) || '';
          // 多人绑定：关联后绑定集合可能整体变化（App 账号的绑定搬过来），全量刷新
          this.applyBoundElderList(data);
          this.initWebSocket();
          wx.showToast({ title: '✅ 关联成功', icon: 'success' });
          if (onDone) onDone();
        } else {
          wx.showToast({ title: (res.data && res.data.error) || '关联失败', icon: 'none' });
        }
      },
      fail: () => wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
    });
  },

  /**
   * 「这条告警值不值得打断子女」的判定。
   *
   * 服务端对每一条风险事件都广播 RISK_ALERT，之前这里收到就弹
   * 「长者正处于高危状态 (LOCATION_UPDATE)」。而老人端定位上报是心跳级的
   * （每次 onLocationChanged 一条、severity 恒 LOW），于是子女被噪声弹窗糊满，
   * 真高危反而被淹掉 —— 告警疲劳，且是漏报方向。
   *
   * 与 services/riskAlertPolicy.js / Android family/RiskAlertPolicy.kt 同一把尺子，
   * 三处规则必须同步。
   */
  shouldInterruptAlert: function (data) {
    const d = data || {};
    // 优先用服务端算好的判定，保证各端同一把尺子；字段缺失时本地兜底重算
    if (d.interruptible !== undefined) return !!d.interruptible;
    const INTERRUPTIBLE = ['SOS', 'PAYMENT_RISK', 'COERCION_RISK', 'CALL_RISK', 'FALL_RISK', 'ELDER_OFFLINE'];
    const type = String(d.event_type || '').trim().toUpperCase();
    if (INTERRUPTIBLE.indexOf(type) === -1) return false;
    if (type === 'SOS') return true;   // 老人主动求助，任何级别都必须送达
    return String(d.severity || '').trim().toUpperCase() === 'HIGH';
  },

  initWebSocket: function () {
    if (!this.globalData.userId) return;

    // 清退避定时器与心跳
    if (this._wsReconnectTimer) {
      clearTimeout(this._wsReconnectTimer);
      this._wsReconnectTimer = null;
    }
    this.stopWsHeartbeat();

    // ⚠️ 必须用 SocketTask 而不是全局 wx.onSocketClose：
    // 全局监听不分连接，主动关闭旧连接的 close 事件会触发新挂的回调，
    // 造成"重建一次、又排一个重连"的双连接泄漏。挂 task 上各连接隔离。
    if (this._socketTask) {
      const old = this._socketTask;
      this._socketTask = null;   // 先置空：旧连接的 onClose 走"忽略"分支
      try { old.close({ code: 4000, reason: 'reconnect' }); } catch (e) { /* 已断则忽略 */ }
    }

    const task = wx.connectSocket({ url: this.globalData.wsHost });
    if (!task) return;
    this._socketTask = task;

    task.onOpen(() => {
      console.log('WebSocket 已连接');
      // 连接成功后重置退避计数，并启动心跳
      this._wsReconnectAttempts = 0;
      this._wsLastPongAt = Date.now();
      this.startWsHeartbeat();
      task.send({
        data: JSON.stringify({ type: 'REGISTER', userId: this.globalData.userId, role: 'family' }),
        fail: () => console.warn('REGISTER 发送失败')
      });
    });

    task.onMessage((res) => {
      // 任何服务端消息都证明链路活着（含 PONG 应答与告警推送）
      this._wsLastPongAt = Date.now();
      try {
        const payload = JSON.parse(res.data);
        if (payload.type === 'PONG') return;
        if (payload.type === 'RISK_ALERT' && this.shouldInterruptAlert(payload.data)) {
          // 只对值得打断的高危事件弹窗；定位心跳等 LOW 事件静默入列表。
          // 挡在这里而不是继续往下走：AlertDialog/showModal 会把上一个盖住，
          // 连续弹的结果是真高危事件反而看不到。
          const d = payload.data || {};
          const typeLabel = eventLabels.eventName(String(d.event_type || '').trim());
          wx.showModal({
            title: '⚠️ 收到微信紧急防诈预警',
            content: `检测到「${typeLabel}」，长者可能正处于高危状态。可远程打断异常通话，也可立即电话联系老人核实。`,
            confirmText: '远程打断',
            cancelText: '拨打电话',
            confirmColor: '#EF4444',
            success: (res) => {
              if (res.confirm) {
                this.sendRemoteInterrupt();
              } else if (res.cancel) {
                // 紧急场景最自然的动作：直接拨老人电话（打断有时不如一句"妈，别转钱"）
                this.callElder();
              }
            }
          });
        }

        // 紧急打断的服务端回执：如实反馈送达结果，避免"假成功"误导子女
        if (payload.type === 'INTERRUPT_ACK') {
          if (payload.success) {
            wx.showToast({ title: '✅ 已送达老人手机', icon: 'success' });
          } else if (payload.offline) {
            wx.showModal({
              title: '⚠️ 老人手机当前离线',
              content: '打断指令已暂存，老人端守护应用一恢复联网就会立即弹出全屏警报。若情况紧急，请直接电话联系老人。',
              showCancel: false,
              confirmText: '知道了'
            });
          } else {
            wx.showToast({ title: payload.message || '发送失败', icon: 'none' });
          }
        }
      } catch (e) {
        console.warn('WebSocket 消息解析失败:', e);
      }
    });

    task.onError(() => {
      // 只记录；close 事件随后到达，统一走 onClose 的重连逻辑
      console.error('WebSocket 错误');
    });

    task.onClose(() => {
      this.stopWsHeartbeat();
      // 旧连接的关闭事件（被 initWebSocket 主动置换）不是真断线，忽略
      if (this._socketTask !== task) return;
      this._socketTask = null;
      // 指数退避重连：3s → 6s → 12s ... 上限 60s，服务端恢复后自动追平
      const delay = Math.min(3000 * Math.pow(2, this._wsReconnectAttempts), 60000);
      this._wsReconnectAttempts += 1;
      console.log(`WebSocket 已断开，${delay / 1000}s 后重连...`);
      this._wsReconnectTimer = setTimeout(() => this.initWebSocket(), delay);
    });
  },

  /* ── 心跳：25s 一发 PING；45s 没有任何服务端消息判定半开，主动断开触发重连 ── */

  startWsHeartbeat: function () {
    this.stopWsHeartbeat();
    this._wsPingTimer = setInterval(() => {
      if (Date.now() - this._wsLastPongAt > 45000) {
        console.warn('WebSocket 心跳超时（45s 无服务端消息），主动断开重连');
        this.stopWsHeartbeat();
        try {
          // _socketTask 仍是本连接 → onClose 会走退避重连
          if (this._socketTask) this._socketTask.close({ code: 4000, reason: 'heartbeat timeout' });
        } catch (e) { /* ignore */ }
        return;
      }
      if (this._socketTask) {
        this._socketTask.send({
          data: JSON.stringify({ type: 'PING' }),
          fail: () => { /* 发送失败由超时机制兜底 */ }
        });
      }
    }, 25000);
  },

  stopWsHeartbeat: function () {
    if (this._wsPingTimer) {
      clearInterval(this._wsPingTimer);
      this._wsPingTimer = null;
    }
  },

  sendRemoteInterrupt: function () {
    // 多人绑定：打断/回拨都发给「当前选中」的老人
    const elder = this.getCurElder();
    if (!elder) {
      wx.showToast({ title: '未绑定老人', icon: 'none' });
      return;
    }

    // 发送结果一律以服务端 INTERRUPT_ACK 回执为准（见 onMessage）
    const task = this._socketTask;
    if (!task) {
      // 无连接：提示的同时立即重建，几秒后重试就能走通
      wx.showToast({ title: '连接已断开，正在重连，请稍后重试', icon: 'none' });
      this.initWebSocket();
      return;
    }
    task.send({
      data: JSON.stringify({
        type: 'INTERRUPT_CMD',
        targetElderId: elder.id,
        message: '微信强打断：子女提醒您立即终止当前异常通话！'
      }),
      fail: () => {
        wx.showToast({ title: '连接已断开，正在重连，请稍后重试', icon: 'none' });
        // 半开连接：立即重建，不要等 25s 心跳才发现
        this.initWebSocket();
      }
    });
  },

  /**
   * 拨打老人电话。
   *
   * 优先用登录/绑定时缓存的 elderPhone（boundUser.phone 就是 elder 真实号码）；
   * 缓存缺失（如老版本登录态、换绑后）再拉 /api/auth/user/:id 兜底。
   * 注意不能拨 familyMobile——那是反方向（老人端告警回拨子女），拨它=号码与提示相反。
   */
  callElder: function () {
    // 多人绑定：拨「当前选中」老人的号（elderPhone 缓存随切换同步更新）
    const elder = this.getCurElder();
    if (!elder) {
      wx.showToast({ title: '未绑定老人', icon: 'none' });
      return;
    }
    if (this.globalData.elderPhone) {
      wx.makePhoneCall({ phoneNumber: this.globalData.elderPhone });
      return;
    }
    const elderId = elder.id;
    this.apiRequest({
      url: `${this.globalData.serverHost}/api/auth/user/${elderId}`,
      timeout: config.requestTimeout,
      success: (res) => {
        const phone = (res.data && res.data.success && res.data.data && res.data.data.phone) || '';
        if (!phone) {
          wx.showToast({ title: '未获取到老人手机号', icon: 'none' });
          return;
        }
        this.globalData.elderPhone = phone;
        wx.makePhoneCall({ phoneNumber: phone });
      },
      fail: () => wx.showToast({ title: '网络异常，无法拨号', icon: 'none' })
    });
  }
});
