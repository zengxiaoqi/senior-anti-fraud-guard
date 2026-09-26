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

// WebSocket 客户端在线连接表 Map <userId, WebSocketClient>
const clients = new Map();

wss.on('connection', (ws, req) => {
  let connectedUserId = null;

  console.log('📡 新的 WebSocket 客户端已连接');

  ws.on('message', (message) => {
    try {
      const payload = JSON.parse(message);
      
      // 1. 客户端身份注册逻辑 { type: 'REGISTER', userId: 1, role: 'elder' }
      if (payload.type === 'REGISTER') {
        connectedUserId = payload.userId;
        clients.set(connectedUserId, ws);
        console.log(`✅ 终端用户 [ID: ${connectedUserId}, 角色: ${payload.role}] 成功建立 WebSocket 实时通道`);
        
        ws.send(JSON.stringify({ type: 'REGISTER_ACK', message: '已成功接入守护全双工网络' }));
      }

      // 2. 子女端发起远程强提醒打断指令 { type: 'INTERRUPT_CMD', targetElderId: 1, message: '...' }
      if (payload.type === 'INTERRUPT_CMD') {
        const targetWs = clients.get(payload.targetElderId);
        if (targetWs && targetWs.readyState === WebSocket.OPEN) {
          targetWs.send(JSON.stringify({
            type: 'EMERGENCY_INTERRUPT',
            fromUser: payload.fromUser || '子女端守护人',
            alertTitle: '⚠️ 紧急亲情防骗强提醒！',
            alertMessage: payload.message || '子女已监听到高危行为，请暂停当前通话与转账！',
            playAudio: true
          }));
          
          ws.send(JSON.stringify({ type: 'INTERRUPT_ACK', success: true, message: '强打断指令已穿透推送到老人手机' }));
        } else {
          ws.send(JSON.stringify({ type: 'INTERRUPT_ACK', success: false, message: '老人端手机当前未在线' }));
        }
      }

    } catch (e) {
      console.error('解析 WS 消息失败:', e.message);
    }
  });

  ws.on('close', () => {
    if (connectedUserId) {
      clients.delete(connectedUserId);
      console.log(`❌ 用户 ID: ${connectedUserId} 断开 WebSocket 连接`);
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
        familyWs.send(JSON.stringify(payload));
        console.log(`⚡ 风险事件已即时广播给子女终端 [ID: ${row.bound_user_id}]`);
      }
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`====================================================`);
  console.log(`🛡️  长者防诈亲情守护系统 API & WebSocket Server 已启动`);
  console.log(`📡 HTTP & WS 服务运行在: http://localhost:${PORT}`);
  console.log(`📁 数据库: SQLite (data.sqlite)`);
  console.log(`====================================================`);
});
