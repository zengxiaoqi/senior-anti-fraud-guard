const app = getApp();
const config = require('../../config');
const eventLabels = require('../../utils/eventLabels');

Page({
  data: {
    events: [],
    locations: [],
    isBound: false,
    boundUser: null,
    // 多人绑定：切换条数据源（≥2 人时在守护卡里显示）
    boundUsers: [],
    bindCode: '',
    bindPhone: '',
    myMobile: '',
    // 是否 App 端账密账号：false = 微信自动建的空账号，显示"关联 App 账号"入口
    hasPassword: false,
    linkUsername: '',
    linkPassword: '',
    // 订阅消息模板未配置时隐藏入口（整条链路未上线前不打扰用户）
    showSubscribeEntry: !!config.subscribeTemplateId,
    // 构建版本标记：显示在 AI 扫描卡片，真机排查时确认跑的哪个包
    buildVersion: config.buildVersion,
    // 通知开启状态（本机记录，见 app.getAlertNotifyStatus）
    subscribeStatus: { enabled: false, text: '未开启' },
    // 通知卡说明文案（含状态摘要），在 JS 拼好，WXML 不做字符串拼接
    notifyDesc: '小程序退到后台收不到实时告警。开启后，高危事件（求助/跌倒/失联/大额扣款）会以微信服务通知推送到你的手机。',
    scanText: '',
    scanning: false,
    scanResult: null,
    // OCR 诊断行（拍照/相册按钮下方小字），真机排障用
    ocrDiag: ''
  },

  onShow: function () {
    // 冷启动时登录是异步的：不等会话就绪就读 globalData，绑定信息会显示不出来
    // （表现为"进首页是空的，切 tab 回来才有"）。已就绪时回调立即执行，不影响热切换。
    app.onSessionReady(() => {
      this.checkBindingStatus();
      this.fetchData();
    });
  },

  // 30s 节流：每次切 tab 不再重复发 3 个请求
  fetchData: function () {
    if (!app.getCurElder()) return;
    const now = Date.now();
    if (now - (this.lastFetch || 0) < 30000) return;
    this.lastFetch = now;
    this.fetchRiskEvents();
    this.fetchLocations();
  },

  checkBindingStatus: function () {
    this.setData({
      isBound: app.globalData.isBound,
      // boundUser 兼容字段恒 = 当前选中老人（app 侧同步），直接用即可
      boundUser: app.getCurElder(),
      boundUsers: app.globalData.boundUsers || [],
      myMobile: app.globalData.mobile || '',
      hasPassword: !!app.globalData.hasPassword
    });
    this.refreshNotifyStatus();
  },

  // ── 多人绑定切换条（≥2 位老人才显示，与 Android DashboardFragment 同构）──
  onElderChipTap: function (e) {
    const id = Number(e.currentTarget.dataset.id) || 0;
    if (!app.switchElder(id)) return;
    this.setData({ boundUser: app.getCurElder() });
    // 切人后概览数据（事件/轨迹）全量换新，节流计时器清零
    this.lastFetch = 0;
    this.fetchData();
  },

  // 刷新"告警微信通知"状态行。微信没有剩余额度查询接口，这里展示的是
  // 本机记账（用户点过几次允许、最近一次是什么时候），供子女自查有没有开过
  refreshNotifyStatus: function () {
    const st = app.getAlertNotifyStatus();
    if (!st || !st.lastAcceptAt) {
      this.setData({
        subscribeStatus: { enabled: false, text: '未开启' },
        notifyDesc: '小程序退到后台收不到实时告警。开启后，高危事件（求助/跌倒/失联/大额扣款）会以微信服务通知推送到你的手机。'
      });
      return;
    }
    const lastLabel = eventLabels.shortLocalTime(new Date(st.lastAcceptAt).toISOString()) || '刚刚';
    const text = '已开启 · 最近 ' + lastLabel + (st.accepts > 1 ? ' · 累计授权 ' + st.accepts + ' 次' : '');
    this.setData({
      subscribeStatus: { enabled: true, text: text },
      notifyDesc: text + '。授权额度用一条少一条且微信不提供余额查询，建议隔段时间回来续订一次。'
    });
  },

  // 填写/修改我的手机号：弹窗输入后交给 app.updateMyMobile 统一处理
  // （全角归一化与格式校验都在 app.js 里，与 App 端同一套逻辑）
  onEditMobileTap: function () {
    const isUpdate = !!this.data.myMobile;
    wx.showModal({
      title: isUpdate ? '修改手机号' : '填写手机号',
      editable: true,
      placeholderText: '请输入11位手机号',
      content: isUpdate ? this.data.myMobile : '',
      success: (res) => {
        if (!res.confirm) return;
        app.updateMyMobile(res.content, () => {
          this.setData({ myMobile: app.globalData.mobile || '' });
        });
      }
    });
  },

  onBindCodeInput: function (e) {
    this.setData({ bindCode: e.detail.value });
  },

  onBindPhoneInput: function (e) {
    this.setData({ bindPhone: e.detail.value });
  },

  // ── 关联 App 端账号 ──
  onLinkUserInput: function (e) {
    this.setData({ linkUsername: e.detail.value });
  },

  onLinkPwdInput: function (e) {
    this.setData({ linkPassword: e.detail.value });
  },

  // 关联是把两个账号合并成一个身份的不可逆操作，弹窗确认后再执行；
  // 成功后整体刷新本页（手机号/绑定状态都来自新账号）
  onLinkAppTap: function () {
    const username = String(this.data.linkUsername || '').trim();
    const password = String(this.data.linkPassword || '');
    if (!username || !password) {
      wx.showToast({ title: '请填写 App 端用户名和密码', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '关联 App 端账号',
      content: '将把当前微信端账号与 App 端账号合并为一个身份，App 端的手机号和绑定关系会同步过来。确定继续吗？',
      success: (res) => {
        if (!res.confirm) return;
        app.linkAppAccount(username, password, () => {
          this.setData({ linkUsername: '', linkPassword: '' });
          this.checkBindingStatus();
          this.lastFetch = 0;
          this.fetchData();
        });
      }
    });
  },

  // 按老人手机号绑定（便捷入口）：归一化规则与 app.updateMyMobile 同一套
  // （全角数字→半角 + 去空白/连字符，中文输入法全角是高频坑）
  onBindPhoneTap: function () {
    const phone = String(this.data.bindPhone || '')
      .replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xFEE0))
      .replace(/[\s-－]/g, '');

    if (!/^1[3-9]\d{9}$/.test(phone)) {
      wx.showToast({ title: '请输入老人登记的11位手机号', icon: 'none' });
      return;
    }

    // 同 onBindTap：订阅授权必须借点按手势发起（force：绑定是显式流程，不节流）
    app.requestAlertSubscription(null, { force: true });

    wx.showLoading({ title: '绑定中...' });

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/auth/bind`,
      method: 'POST',
      timeout: 10000,
      data: { userId: app.globalData.userId, phone },
      success: (res) => {
        wx.hideLoading();
        if (res.data.success) {
          // 多人绑定：服务端 boundUser=本次绑定对象，绑完自动切到新老人
          app.globalData.isBound = true;
          app.applyBoundElderList(res.data);
          app.switchElder(res.data.boundUser && res.data.boundUser.id);
          this.setData({
            isBound: true,
            boundUser: app.getCurElder(),
            boundUsers: app.globalData.boundUsers || [],
            bindPhone: ''
          });
          wx.showToast({ title: res.data.message || '绑定成功', icon: 'success' });
        } else {
          wx.showToast({ title: res.data.error || '绑定失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      }
    });
  },

  onBindTap: function () {
    const bindCode = this.data.bindCode.trim();

    if (!bindCode) {
      wx.showToast({ title: '请输入绑定码', icon: 'none' });
      return;
    }

    if (!/^\d{6}$/.test(bindCode)) {
      wx.showToast({ title: '绑定码为6位数字', icon: 'none' });
      return;
    }

    // 订阅授权必须借用户点按手势发起（微信硬性限制），网络请求是异步的，
    // 等绑定回调里再调会被判"非用户手势"直接 fail——所以在这里先请求。
    // force：绑定是用户显式流程，必须真实弹窗，不能被 30 分钟节流吞掉
    app.requestAlertSubscription(null, { force: true });

    this.doBindCode(bindCode);
  },

  // 绑定码提交（未绑定态表单与「＋ 添加」弹窗共用）
  doBindCode: function (bindCode) {
    wx.showLoading({ title: '绑定中...' });

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/auth/bind`,
      method: 'POST',
      timeout: 10000,
      data: { userId: app.globalData.userId, bindCode },
      success: (res) => {
        wx.hideLoading();
        if (res.data.success) {
          // 多人绑定：服务端 boundUser=本次绑定对象，绑完自动切到新老人
          app.globalData.isBound = true;
          app.applyBoundElderList(res.data);
          app.switchElder(res.data.boundUser && res.data.boundUser.id);
          this.setData({
            isBound: true,
            boundUser: app.getCurElder(),
            boundUsers: app.globalData.boundUsers || [],
            bindCode: ''
          });
          wx.showToast({ title: res.data.message || '绑定成功', icon: 'success' });
        } else {
          wx.showToast({ title: res.data.error || '绑定失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      }
    });
  },

  // 追加绑定：绑定表单只在未绑定态出现，绑定后想再守护一位老人，
  // 入口在切换条末尾「＋ 添加」。showModal 确认回调已脱离点按手势，
  // requestAlertSubscription 不能在这里调（会判非手势静默失败）——
  // 订阅提示交给其他手势时机兜底，不为此牺牲绑定主流程。
  onAddElderTap: function () {
    wx.showModal({
      title: '添加守护对象',
      content: '输入新老人的 6 位绑定码（在老人端引导页查看）',
      editable: true,
      placeholderText: '6 位数字绑定码',
      confirmText: '绑定',
      success: (res) => {
        if (!res.confirm) return;
        const bindCode = String(res.content || '').trim();
        if (!/^\d{6}$/.test(bindCode)) {
          wx.showToast({ title: '绑定码为6位数字', icon: 'none' });
          return;
        }
        this.doBindCode(bindCode);
      }
    });
  },

  goGeofence: function () {
    wx.navigateTo({ url: '/pages/geofence/geofence' });
  },

  // 概览 chips 点击跳转：风险事件→防诈告警 tab，轨迹点→亲情轨迹 tab（均为 tabBar 页，必须 switchTab）
  goAlerts: function () {
    wx.switchTab({ url: '/pages/index/index' });
  },

  goTrack: function () {
    wx.switchTab({ url: '/pages/map/map' });
  },

  // 常驻订阅入口：一次性订阅的授权额度是消耗型的（一条授权 = 一条推送），
  // 弹窗拒绝/额度用完后告警就送不到了，所以让子女随时能补授权。
  // 勾选「总是保持以上选择」后，之后每次静默调用都会 +1 条额度。
  onSubscribeTap: function () {
    app.requestAlertSubscription((accepted) => {
      this.refreshNotifyStatus();
      if (accepted === null) return; // 被节流跳过（force 入口不会走到这）
      wx.showToast({
        title: accepted
          ? '已开启，高危告警将推送到微信'
          : '未开启：弹窗点「允许」并勾选「总是保持以上选择」',
        icon: 'none'
      });
    }, { force: true });
  },

  onUnbindTap: function () {
    // 多人绑定：先选要解绑哪位老人（服务端多绑定且不指定 elderId 会回 400）
    const elders = app.globalData.boundUsers || [];
    const pick = (elderId) => {
      const target = elders.find((e) => e.id === elderId);
      wx.showModal({
        title: '解除绑定',
        content: target ? `确定要解除与「${target.name || '该老人'}」的绑定吗？` : '确定要解除与老人的绑定吗？',
        success: (res) => {
          if (res.confirm) {
            app.apiRequest({
              url: `${app.globalData.serverHost}/api/auth/unbind`,
              method: 'POST',
              timeout: 10000,
              data: { userId: app.globalData.userId, elderId },
              success: (res) => {
                if (res.data.success) {
                  // 服务端回执 boundList=解绑后剩余列表（可能还剩其他老人）
                  app.applyBoundElderList(res.data);
                  this.setData({
                    isBound: app.globalData.isBound,
                    boundUser: app.getCurElder(),
                    boundUsers: app.globalData.boundUsers || []
                  });
                  wx.showToast({ title: res.data.message || '已解除绑定', icon: 'success' });
                  this.lastFetch = 0;
                  this.fetchData();
                } else {
                  wx.showToast({ title: res.data.error || '解除绑定失败', icon: 'none' });
                }
              },
              fail: () => {
                wx.showToast({ title: '网络异常，请重试', icon: 'none' });
              }
            });
          }
        }
      });
    };

    if (elders.length > 1) {
      wx.showActionSheet({
        itemList: elders.slice(0, 6).map((e) => e.name || `老人#${e.id}`),
        success: (res) => {
          if (res.tapIndex >= 0 && res.tapIndex < elders.length) {
            pick(elders[res.tapIndex].id);
          }
        }
      });
      return;
    }
    pick(0); // 单绑定：elderId=0 由服务端自行定位
  },

  fetchRiskEvents: function () {
    const elder = app.getCurElder();
    if (!elder) return;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/list/${elder.id}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ events: res.data.data });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取风险事件失败', icon: 'none' });
      }
    });
  },

  // ===== AI 风险扫描 =====
  onScanInput: function (e) {
    this.setData({ scanText: e.detail.value });
  },

  // OCR 排查用的实时日志：mp 后台「运维中心 → 实时日志」可按用户/时间查到，
  // 真机上没有调试面板，报障时靠它定位卡在哪一环
  _ocrLog: function (msg) {
    try {
      if (wx.getRealtimeLogManager) wx.getRealtimeLogManager().info('[OCR]', msg);
    } catch (e) { /* 日志失败不影响主流程 */ }
    console.log('[OCR]', msg);
  },

  // 诊断信息同步显示到页面上（扫描按钮下方的小字）：远程排障时让用户直接
  // 截图这行字，不用翻控制台/实时日志就能知道卡在哪个环节
  _ocrDiag: function (msg) {
    const elapsed = this._ocrT0 ? Math.round((Date.now() - this._ocrT0) / 1000) + 's' : '0s';
    const line = '[' + config.buildVersion + ' · ' + elapsed + '] ' + msg;
    this._ocrLog(line);
    this.setData({ ocrDiag: line });
  },

  // ── 拍照/相册 OCR（对齐 App 端拍照识诈） ──
  // 链路：chooseMedia 选图 → OffscreenCanvas 缩放取 RGBA → VKSession OCR(mode 2)
  // 静态图识别 → 文字填进输入框，供核对后再点「开始扫描」走服务端鉴别。
  onPhotoTap: function () {
    this._chooseAndOCR(['camera']);
  },

  onAlbumTap: function () {
    this._chooseAndOCR(['album']);
  },

  _chooseAndOCR: function (sourceType) {
    this._ocrT0 = Date.now();
    this._ocrDiag(sourceType === 'camera' ? '拍照选图中' : '相册选图中');
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType,
      sizeType: ['compressed'],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (file && file.tempFilePath) {
          this._ocrDiag('已选图 ' + Math.round((file.size || 0) / 1024) + 'KB，开始读图');
          this._recognizeImage(file.tempFilePath);
        } else {
          this._ocrDiag('选图返回空路径');
          wx.showToast({ title: '未获取到图片，请重试', icon: 'none' });
        }
      },
      fail: (err) => {
        // 用户主动取消（errMsg 含 cancel）静默处理，其余如实展示
        const msg = (err && err.errMsg) || '';
        if (msg.indexOf('cancel') === -1) {
          this._ocrDiag('选图失败: ' + msg);
          wx.showToast({ title: '选图失败，请重试', icon: 'none' });
        }
      }
    });
  },

  _recognizeImage: function (filePath) {
    // VKSession 需设备支持：v1 优先（社区验证 OCR 可用），v2 兜底，都没有才拒绝
    const vkVer = (wx.isVKSupport && wx.isVKSupport('v1')) ? 'v1'
      : ((wx.isVKSupport && wx.isVKSupport('v2')) ? 'v2' : null);
    if (!wx.createVKSession || !wx.createOffscreenCanvas || !vkVer) {
      this._ocrDiag('设备不支持 VKSession (createVKSession=' + !!wx.createVKSession + ')');
      wx.showToast({ title: '当前微信不支持图片识字，请手动输入文字', icon: 'none', duration: 2500 });
      return;
    }
    wx.showLoading({ title: '识别文字中...', mask: true });
    // OCR 流程活跃标记：chooseMedia 拉起原生相机/相册会触发页面 onHide，
    // 只在识别流程真正运行时才允许 onHide 取消，避免误杀后续回调链
    this._ocrActive = true;
    this._vkVer = vkVer;
    this._ocrDiag('读图中 vk=' + vkVer);

    // 全程看门狗：读图/引擎启动/识别回调任何一环卡死都保证退出 loading。
    // （旧实现只在 start 成功后才挂 15s 超时——start 回调本身不触发就会一直转圈）
    this._ocrDone = false;
    this._ocrStage = '读图';
    this._ocrWatchdog = setTimeout(() => {
      this._finishOCR(null, '识别超时(' + this._ocrStage + '无响应)，请直接输入文字');
    }, 20000);

    try {
      const canvas = wx.createOffscreenCanvas({ type: '2d' });
      if (!canvas.createImage) throw new Error('canvas.createImage 不存在');
      const img = canvas.createImage();
      img.onload = () => {
        if (this._ocrDone) return;
        try {
          // 缩到最大边 1280：像素量小一半以上，OCR 更快也更省内存
          const MAX = 1280;
          const scale = Math.min(1, MAX / Math.max(img.width, img.height));
          const w = Math.max(1, Math.round(img.width * scale));
          const h = Math.max(1, Math.round(img.height * scale));
          canvas.width = w;
          canvas.height = h;
          const ctx = canvas.getContext('2d');
          ctx.drawImage(img, 0, 0, w, h);
          const imageData = ctx.getImageData(0, 0, w, h);
          this._ocrStage = '引擎启动';
          this._ocrDiag('图就绪 ' + w + 'x' + h + '，启动引擎 ' + vkVer);
          this._runOCR(imageData.data.buffer, w, h, vkVer);
        } catch (e) {
          this._ocrDiag('图处理异常: ' + (e && e.message));
          this._finishOCR(null, '图片处理失败，请重试或直接输入');
        }
      };
      img.onerror = () => {
        this._ocrDiag('img.onerror 触发');
        this._finishOCR(null, '图片读取失败，请重试或直接输入');
      };
      img.src = filePath;
    } catch (e) {
      this._ocrDiag('读图异常: ' + (e && e.message));
      this._finishOCR(null, '图片处理失败，请重试或直接输入');
    }
  },

  _runOCR: function (frameBuffer, width, height, vkVer) {
    this._destroyOCRSession();
    // version 显式锁定探测过的版本：不传时引擎版本由系统决定，
    // 部分真机上 start 回调静默无响应（社区验证 v1 + OCR mode2 可用）
    const session = wx.createVKSession({ track: { OCR: { mode: 2 } }, version: vkVer });
    this._vkSession = session;

    session.on('updateAnchors', (anchors) => {
      this._ocrDiag('识别回调: ' + (anchors || []).length + ' 行');
      this._finishOCR((anchors || []).map(a => a.text));
    });
    session.start((errno) => {
      if (this._ocrDone) return; // 看门狗/取消已先到
      if (errno) {
        this._ocrDiag('start 失败 errno=' + errno);
        this._finishOCR(null, '图片识字启动失败，请直接输入文字');
        return;
      }
      this._ocrDiag('引擎已启动，识别中');
      this._ocrStage = '识别回调';
      session.runOCR({ frameBuffer, width, height });
      // 引擎启动成功后识别无回调的第二道兜底
      // （官方确认：图片里没有文字时 updateAnchors 不触发，靠这里退）
      this._ocrTimer = setTimeout(() => this._finishOCR([]), 15000);
    });
  },

  // OCR 收尾统一出口（结果 or 错误文案），保证 loading 必退、定时器必清
  _finishOCR: function (lines, errMsg) {
    if (this._ocrDone) return;
    this._ocrDone = true;
    this._ocrActive = false;
    clearTimeout(this._ocrWatchdog);
    clearTimeout(this._ocrTimer);
    this._destroyOCRSession();
    wx.hideLoading();
    if (errMsg) {
      this._ocrDiag('失败: ' + errMsg);
      wx.showToast({ title: errMsg, icon: 'none', duration: 2500 });
      return;
    }
    const joined = (lines || []).join('\n').trim();
    if (!joined) {
      this._ocrDiag('完成: 图里没有文字');
      wx.showToast({ title: '图片里没有识别到文字，请拍清楚一些或直接输入', icon: 'none', duration: 2500 });
      return;
    }
    this._ocrDiag('成功: ' + joined.length + ' 字');
    this.setData({ scanText: joined });
    wx.showToast({ title: '已识别 ' + joined.length + ' 字，核对后点「开始扫描」', icon: 'none', duration: 2500 });
  },

  // 页面离开/卸载时静默取消：只在识别流程真正运行时取消（chooseMedia 拉起
  // 原生相机/相册也会触发 onHide，那时流程还没开始，不能误杀后续回调链）
  _cancelOCR: function () {
    if (!this._ocrActive) return;
    this._ocrDone = true;
    this._ocrActive = false;
    this._ocrDiag('页面离开，取消识别');
    clearTimeout(this._ocrWatchdog);
    clearTimeout(this._ocrTimer);
    this._destroyOCRSession();
    wx.hideLoading();
  },

  _destroyOCRSession: function () {
    if (this._vkSession) {
      try { this._vkSession.destroy(); } catch (e) { /* 已销毁无妨 */ }
      this._vkSession = null;
    }
  },

  onHide: function () { this._cancelOCR(); },
  onUnload: function () { this._cancelOCR(); },

  onScanTap: function () {
    const text = (this.data.scanText || '').trim();
    if (!text) {
      wx.showToast({ title: '请先粘贴可疑文案', icon: 'none' });
      return;
    }
    this.setData({ scanning: true, scanResult: null });
    app.apiRequest({
      url: `${app.globalData.serverHost}/api/ai/scan`,
      method: 'POST',
      timeout: 10000,
      data: { textContent: text },
      success: (res) => {
        if (res.data && res.data.success) {
          this.setData({ scanResult: this.formatScanResult(res.data.data || {}) });
        } else {
          wx.showToast({ title: (res.data && res.data.error) || '扫描失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请重试', icon: 'none' });
      },
      complete: () => {
        this.setData({ scanning: false });
      }
    });
  },

  fetchLocations: function () {
    const elder = app.getCurElder();
    if (!elder) return;

    app.apiRequest({
      url: `${app.globalData.serverHost}/api/events/location/${elder.id}`,
      timeout: 10000,
      success: (res) => {
        if (res.data.success) {
          this.setData({ locations: res.data.data });
        }
      },
      fail: () => {
        wx.showToast({ title: '获取位置信息失败', icon: 'none' });
      }
    });
  },

  /**
   * AI 扫描结果结构化（与 Android AiScanActivity.renderResult 同一套文案与分级）：
   * HIGH 红 / SAFE 绿 / 其余灰，横幅第一眼定调；置信度转百分比；
   * 高危附"三句话"口诀。所有文案在 JS 拼好，WXML 不做复杂三元嵌套。
   */
  formatScanResult: function (result) {
    const level = result.risk_level === 'HIGH' ? 'HIGH'
      : (result.risk_level === 'SAFE' ? 'SAFE' : 'UNKNOWN');

    const verdictTitle = level === 'HIGH' ? '🚨 很可能是诈骗，请勿转账'
      : (level === 'SAFE' ? '✅ 未检出已知诈骗话术' : '⚠️ 暂时无法判断，请谨慎对待');

    let verdictDetail = '';
    if (typeof result.confidence === 'number') {
      verdictDetail = 'AI 置信度 ' + Math.round(result.confidence * 100) + '%';
    }
    const action = String(result.suggested_action || '').trim();
    if (action) verdictDetail += (verdictDetail ? ' · ' : '') + action;

    return {
      ...result,
      level,
      verdictTitle,
      verdictDetail,
      keywords: Array.isArray(result.matched_keywords) ? result.matched_keywords : [],
      reminder: level === 'HIGH'
        ? '💡 记住三句话：不转账、不透露验证码、先打给子女核实。'
        : '💡 若对方提到转账、汇款或索要验证码，请立即停止并告知子女。'
    };
  }
});
