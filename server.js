// 加载项目根目录 .env（存在才生效；真实环境变量优先，不被覆盖）
require('./scripts/load-env');

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');
const cors = require('cors');

// 导入数据库与路由
const db = require('./database/db');
const authRoutes = require('./routes/auth');
const eventsRoutes = require('./routes/events');
const evidenceRoutes = require('./routes/evidence');
const aiRoutes = require('./routes/ai');
const geofenceRoutes = require('./routes/geofence');
const recordingsRoutes = require('./routes/recordings');
const appUpdateRoutes = require('./routes/appUpdate');
const recordingCleanup = require('./services/recordingCleanup');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// 中间件配置
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// 托管静态 Web 页面
app.use(express.static(path.join(__dirname, 'public')));

// 挂载 REST API
app.use('/api/auth', authRoutes);
app.use('/api/events', eventsRoutes);
app.use('/api/evidence', evidenceRoutes);
app.use('/api/ai', aiRoutes);
app.use('/api/geofence', geofenceRoutes);
app.use('/api/recordings', recordingsRoutes);
// App 内自升级：免登录（老人端未登记账号时也必须能升上来）
app.use('/api/app-update', appUpdateRoutes);

// 健康检查：App 端用于在登记/绑定前探测后端是否可达
app.get('/api/health', (req, res) => {
  res.json({ success: true, message: 'ok', time: Date.now() });
});

// WebSocket 客户端在线连接表 Map <userId, WebSocketClient>
const clients = new Map();

// 老人端离线时的紧急打断指令缓存 Map <elderId, Array<payload>>
// 老人端重连注册后立即补发，避免"老人恰好离线 → 指令凭空丢失"
const pendingInterrupts = new Map();

/**
 * 老人端录音运行态 Map <elderId, { reason, place, startedAt, segments }>
 * 老人端上线开始录音 / 停止录音时通过 RECORDING_STATE 指令实时同步，
 * 子女端据此显示"正在录音"并决定是否展示远程停止按钮。
 */
const elderRecordingState = new Map();

/** 推送给某老人的守护人（子女端），无绑定则忽略 */
function notifyBoundFamily(elderId, payload) {
  db.get('SELECT bound_user_id FROM users WHERE id = ?', [elderId], (err, row) => {
    if (err || !row || !row.bound_user_id) return;
    const ws = clients.get(row.bound_user_id);
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(payload));
      } catch (e) {
        console.error('推送录音事件给子女端失败:', e.message);
      }
    }
  });
}

function isElderRecording(elderId) {
  const s = elderRecordingState.get(Number(elderId));
  if (!s) return { active: false };
  return { active: true, ...s };
}

// 把录音路由需要的能力注入进去（避免路由反向依赖 server）
recordingsRoutes.setHub({
  notifyFamily: notifyBoundFamily,
  isElderRecording
});

/**
 * 推送守护规则变更给老人端。
 *
 * 老人端离线时**不缓存** —— 与 pendingInterrupts 的取舍相反：
 * 指令（远程打断、停止录音）有时效性，错过就该作废；
 * 配置没有时效性，缓存一份陈旧配置会在上线时覆盖掉更新的值。
 * 离线老人靠 30 分钟拉取兜底即可。
 */
function notifyElderSettingsChanged(elderId, settings) {
  const ws = clients.get(Number(elderId));
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    console.log(`⚙️ 老人端 [ID: ${elderId}] 不在线，配置变更等下次拉取兜底`);
    return;
  }
  try {
    ws.send(JSON.stringify({
      type: 'ELDER_SETTINGS_UPDATED',
      data: { settings, changedBy: 'family' }
    }));
    console.log(`⚙️ 守护规则变更已推送到老人端 [ID: ${elderId}]`);
  } catch (e) {
    console.error('推送守护规则给老人端失败:', e.message);
  }
}

authRoutes.setHub({ notifyElderSettingsChanged });

/**
 * 查询某老人已绑定守护人的真实手机号（用于老人端警报页一键拨给子女核实）。
 * 子女端 phone 字段存的是登录用户名，真正的手机号在 mobile；
 * mobile 为空则退而用 phone；都没有返回空串。
 */
function lookupFamilyPhone(elderId, callback) {
  db.get(
    "SELECT f.id, f.name, f.phone, f.mobile FROM users e " +
    "LEFT JOIN users f ON f.id = e.bound_user_id " +
    "WHERE e.id = ? AND e.role = 'elder'",
    [elderId],
    (err, row) => {
      if (err || !row || !row.id) return callback('');
      const phone = (row.mobile || row.phone || '').trim();
      // 排除微信占位号（wx_ 开头不是真实号码）
      if (!/^\+?\d{6,}$/.test(phone.replace(/-/g, ''))) return callback('');
      return callback(phone);
    }
  );
}

wss.on('connection', (ws, req) => {
  let connectedUserId = null;

  console.log('📡 新的 WebSocket 客户端已连接');

  // 关键：必须挂 error 处理器，否则单个客户端异常断开（如无效关闭帧）会打崩整个进程
  ws.on('error', (err) => {
    console.error(`⚠️ WebSocket 客户端异常 [${connectedUserId || '未注册'}]: ${err.message}`);
    try { ws.terminate(); } catch (_) {}
  });

  ws.on('message', (message) => {
    try {
      const payload = JSON.parse(message);
      
      // 1. 客户端身份注册逻辑 { type: 'REGISTER', userId: 1, role: 'elder' }
      if (payload.type === 'REGISTER') {
        connectedUserId = payload.userId;
        clients.set(connectedUserId, ws);
        console.log(`✅ 终端用户 [ID: ${connectedUserId}, 角色: ${payload.role}] 成功建立 WebSocket 实时通道`);

        ws.send(JSON.stringify({ type: 'REGISTER_ACK', message: '已成功接入守护全双工网络' }));

        // 老人端上线：补发其离线期间缓存的紧急打断指令（最多保留最近 5 条）
        if (payload.role === 'elder' && pendingInterrupts.has(connectedUserId)) {
          const queued = pendingInterrupts.get(connectedUserId);
          pendingInterrupts.delete(connectedUserId);
          queued.slice(-5).forEach((cmd) => {
            try {
              ws.send(JSON.stringify({
                type: 'EMERGENCY_INTERRUPT',
                ...cmd,
                playAudio: true,
                offlineQueued: true
              }));
              console.log(`⚡ 老人端 [ID: ${connectedUserId}] 重新上线，补发离线期间的紧急打断指令`);
            } catch (_) {}
          });
        }
      }

      // 2. 子女端发起远程强提醒打断指令 { type: 'INTERRUPT_CMD', targetElderId: 1, message: '...' }
      if (payload.type === 'INTERRUPT_CMD') {
        const targetElderId = parseInt(payload.targetElderId, 10) || 0;
        const fromUser = payload.fromUser || '子女端守护人';
        const alertTitle = '⚠️ 紧急亲情防骗强提醒！';
        const alertMessage = payload.message || '子女已监听到高危行为，请暂停当前通话与转账！';

        // 取出守护人真实手机号一并下发，老人端警报页可直接预填拨号，无需手工输入
        lookupFamilyPhone(targetElderId, (fromPhone) => {
          const targetWs = clients.get(targetElderId);
          if (targetWs && targetWs.readyState === WebSocket.OPEN) {
            targetWs.send(JSON.stringify({
              type: 'EMERGENCY_INTERRUPT',
              fromUser,
              fromPhone,
              alertTitle,
              alertMessage,
              playAudio: true
            }));

            console.log(`⚡ 紧急打断指令已推送到老人端 [ID: ${targetElderId}]${fromPhone ? '（含守护人手机号 ' + fromPhone + '）' : ''}`);
            ws.send(JSON.stringify({ type: 'INTERRUPT_ACK', success: true, message: '强打断指令已穿透推送到老人手机' }));
          } else {
            // 老人端离线：缓存指令，待其重连注册后立即补发
            if (!pendingInterrupts.has(targetElderId)) pendingInterrupts.set(targetElderId, []);
            pendingInterrupts.get(targetElderId).push({
              fromUser,
              fromPhone,
              alertTitle,
              alertMessage
            });
            console.log(`⏳ 老人端 [ID: ${targetElderId}] 当前离线，紧急打断指令已缓存待其上线补发`);
            ws.send(JSON.stringify({
              type: 'INTERRUPT_ACK',
              success: false,
              offline: true,
              message: '老人端手机当前不在线，指令已缓存，老人端一上线将立即弹出警报'
            }));
          }
        });
      }

      // 3. 老人端上报录音运行态（开始 / 分段 / 停止）
      if (payload.type === 'RECORDING_STATE') {
        handleRecordingState(payload);
      }

      // 4. 子女端远程停止老人端录音
      if (payload.type === 'RECORDING_STOP_CMD') {
        handleRecordingStopCmd(payload, ws);
      }

    } catch (e) {
      console.error('解析 WS 消息失败:', e.message);
    }
  });

  // 老人端上报录音运行态：开始 / 停止 / 分段
  // { type: 'RECORDING_STATE', state: 'STARTED'|'STOPPED'|'SEGMENT', reason, place, segmentIndex, sessionId }
  function handleRecordingState(payload) {
    if (connectedUserId == null) return;
    const elderId = connectedUserId;
    const state = payload.state;

    if (state === 'STARTED') {
      elderRecordingState.set(elderId, {
        reason: payload.reason || 'SOS',
        place: payload.place || '',
        startedAt: Date.now(),
        segments: 0,
        sessionId: payload.sessionId || ''
      });
    } else if (state === 'SEGMENT') {
      const s = elderRecordingState.get(elderId) || { reason: payload.reason || 'SOS', place: payload.place || '', startedAt: Date.now(), segments: 0 };
      s.segments = payload.segmentIndex || (s.segments + 1);
      s.sessionId = payload.sessionId || s.sessionId;
      elderRecordingState.set(elderId, s);
    } else if (state === 'STOPPED') {
      elderRecordingState.delete(elderId);
    }

    // 同步给子女端，让"正在录音"指示灯和远程停止按钮真实可用
    notifyBoundFamily(elderId, {
      type: 'RECORDING_STATE',
      data: { state, ...isElderRecording(elderId), reason: payload.reason, segmentIndex: payload.segmentIndex }
    });
  }

  // 子女端远程停止老人端录音 { type: 'RECORDING_STOP_CMD', targetElderId }
  function handleRecordingStopCmd(payload, ws) {
    const targetElderId = parseInt(payload.targetElderId, 10) || 0;
    const targetWs = clients.get(targetElderId);

    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      targetWs.send(JSON.stringify({
        type: 'RECORDING_STOP',
        fromUser: payload.fromUser || '子女端守护人',
        reason: 'REMOTE'
      }));
      console.log(`⏹️ 远程停止录音指令已推送到老人端 [ID: ${targetElderId}]`);
      ws.send(JSON.stringify({ type: 'RECORDING_STOP_ACK', success: true, message: '已通知老人端停止录音' }));
    } else {
      ws.send(JSON.stringify({
        type: 'RECORDING_STOP_ACK',
        success: false,
        offline: true,
        message: '老人端当前不在线，无法远程停止'
      }));
    }
  }

  ws.on('close', () => {
    if (connectedUserId) {
      // 关键修复：仅当连接表中登记的仍是"本条连接"时才删除。
      // 老人端网络抖动会先建新连接再断旧连接，旧连接的 close 若无条件删除，
      // 会误删新连接的注册记录，导致服务端误判"老人未在线"而丢弃打断指令。
      if (clients.get(connectedUserId) === ws) {
        clients.delete(connectedUserId);
        // 老人端掉线，其录音状态不再可信，清掉以免子女端看到过期的"正在录音"
        elderRecordingState.delete(connectedUserId);
        console.log(`❌ 用户 ID: ${connectedUserId} 断开 WebSocket 连接`);
      } else {
        console.log(`❌ 用户 ID: ${connectedUserId} 的旧连接已关闭（新连接已接管，忽略）`);
      }
    }
  });
});

// 设置广播给绑定账户的回调 handler
eventsRoutes.setBroadcastHandler((elderId, payload) => {
  // 查找老人绑定的子女 ID
  db.get("SELECT bound_user_id FROM users WHERE id = ?", [elderId], (err, row) => {
    if (row && row.bound_user_id) {
      const familyWs = clients.get(row.bound_user_id);
      if (familyWs && familyWs.readyState === WebSocket.OPEN) {
        try {
          familyWs.send(JSON.stringify(payload));
          console.log(`⚡ 风险事件已即时广播给子女终端 [ID: ${row.bound_user_id}]`);
        } catch (e) {
          console.error('广播给子女终端失败:', e.message);
        }
      }
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`====================================================`);
  console.log(`🛡️  长者防诈亲情守护系统 API & WebSocket Server 已启动`);
  console.log(`📡 HTTP & WS 服务运行在: http://localhost:${PORT}`);
  console.log(`📁 数据库: SQLite (${process.env.DB_PATH || 'data.sqlite'})`);
  console.log(`🎙️ 录音存证: uploads/recordings（AI 研判：${require('./services/asr').providerName()}）`);
  // 地理编码状态必须打出来：没配 key 时地址会退化成坐标串，
  // 子女端看到的却是"28.2738, 113.0616"，用户会以为功能坏了
  console.log(`📍 逆地理编码: ${require('./services/regeo').providerName()}`);
  console.log(`====================================================`);
});

// 启动录音证据生命周期清理（非诈骗录音到期自动清理，诈骗录音长期保留）
recordingCleanup.startScheduler();
