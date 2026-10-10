/**
 * 契约 /ws：实时推送通道（对本地 server.js 与 Workers GuardHubDO 同一套用例）。
 *
 * 覆盖：REGISTER 握手、RECORDING_STATE 同步、INTERRUPT_CMD 在线直推、
 * 老人离线时指令缓存 + 重连补发（offlineQueued）、远程停止离线回执。
 */
const { describe, it, after } = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { setupBoundPair } = require('./helpers');

const BASE = (process.env.CONTRACT_BASE_URL || 'http://127.0.0.1:3999').replace(/^http/, 'ws');

/** 所有建过的连接，套件结束后统一关闭（否则 node:test 因句柄挂起不退出） */
const ALL_CLIENTS = [];

/** 建一条 WS 连接并等 REGISTER_ACK；返回带 waitFor 的客户端句柄 */
function connectAndRegister(userId, role) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(BASE);
    const messages = [];
    const waiters = [];

    ws.on('message', (raw) => {
      let msg = null;
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      messages.push(msg);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        // 只送达在 waitFor 调用之后到达的消息
        if (messages.indexOf(msg) >= w.startIdx && msg.type === w.msg.type) {
          waiters.splice(i, 1)[0].resolve(msg);
        }
      }
    });
    ws.on('error', (e) => reject(e));

    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'REGISTER', userId, role }));
      waitFor('REGISTER_ACK').then(() => resolve(client)).catch(reject);
    });

    function waitFor(type, timeoutMs = 5000) {
      // 只匹配"调用之后"到达的消息：否则上一用例的同类型残留会被误配
      const startIdx = messages.length;
      const existing = messages.slice(startIdx).find((m) => m.type === type);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve, reject) => {
        const w = { msg: { type }, resolve, reject, startIdx };
        waiters.push(w);
        setTimeout(() => {
          const idx = waiters.indexOf(w);
          if (idx >= 0) {
            waiters.splice(idx, 1);
            reject(new Error(`等待 ${type} 超时（已收到: ${messages.map((m) => m.type).join(',')}）`));
          }
        }, timeoutMs);
      });
    }

    const client = {
      ws,
      messages,
      waitFor,
      send: (obj) => ws.send(JSON.stringify(obj)),
      close: () => new Promise((r) => { ws.once('close', r); ws.close(); })
    };
    ALL_CLIENTS.push(client);
  });
}

after(async () => {
  for (const c of ALL_CLIENTS) {
    try { await Promise.race([c.close(), new Promise((r) => setTimeout(r, 500))]); } catch { /* ignore */ }
  }
});

describe('契约 /ws：实时推送通道', () => {
  let ctx = {};

  it('REGISTER 握手：老人端/子女端均收到 REGISTER_ACK', async () => {
    ctx = await setupBoundPair();
    ctx.familyWs = await connectAndRegister(ctx.family.userId, 'family');
    ctx.elderWs = await connectAndRegister(ctx.elder.elderId, 'elder');
    assert.ok(ctx.familyWs.messages.some((m) => m.type === 'REGISTER_ACK'));
    assert.ok(ctx.elderWs.messages.some((m) => m.type === 'REGISTER_ACK'));
  });

  it('RECORDING_STATE 上报 → 子女端实时同步"正在录音"', async () => {
    const familyP = ctx.familyWs.waitFor('RECORDING_STATE');
    ctx.elderWs.send({
      type: 'RECORDING_STATE',
      state: 'STARTED',
      reason: 'SOS',
      sessionId: 'S_WS_' + Date.now()
    });
    const msg = await familyP;
    assert.equal(msg.data.state, 'STARTED');
    assert.equal(msg.data.active, true);
    assert.equal(msg.data.reason, 'SOS');
  });

  it('INTERRUPT_CMD 在线：老人端收到 EMERGENCY_INTERRUPT（含拨号手机号位）', async () => {
    const elderP = ctx.elderWs.waitFor('EMERGENCY_INTERRUPT');
    const familyP = ctx.familyWs.waitFor('INTERRUPT_ACK');
    ctx.familyWs.send({
      type: 'INTERRUPT_CMD',
      targetElderId: ctx.elder.elderId,
      message: '契约测试：请立即停止转账'
    });
    const pushed = await elderP;
    assert.equal(pushed.type, 'EMERGENCY_INTERRUPT');
    assert.equal(pushed.alertMessage, '契约测试：请立即停止转账');
    assert.equal(pushed.playAudio, true);
    assert.ok('fromPhone' in pushed, 'EMERGENCY_INTERRUPT 应携带 fromPhone 字段（可为空串）');

    const ack = await familyP;
    assert.equal(ack.success, true);
  });

  it('老人离线 → INTERRUPT_CMD 缓存；重连注册后补发（offlineQueued）', async () => {
    await ctx.elderWs.close();
    // 给服务端留出感知断开的时间
    await new Promise((r) => setTimeout(r, 500));

    const ackP = ctx.familyWs.waitFor('INTERRUPT_ACK');
    ctx.familyWs.send({
      type: 'INTERRUPT_CMD',
      targetElderId: ctx.elder.elderId,
      message: '离线缓存测试'
    });
    const ack = await ackP;
    assert.equal(ack.success, false);
    assert.equal(ack.offline, true);

    // 老人端重连 + 注册 → 立即补发
    const again = await connectAndRegister(ctx.elder.elderId, 'elder');
    ctx.elderWs = again;
    const queued = await again.waitFor('EMERGENCY_INTERRUPT');
    assert.equal(queued.alertMessage, '离线缓存测试');
    assert.equal(queued.offlineQueued, true, '补发指令必须带 offlineQueued 标记');
    assert.equal(queued.playAudio, true);
  });

  it('RECORDING_STOP_CMD 目标离线 → RECORDING_STOP_ACK offline', async () => {
    await ctx.elderWs.close();
    await new Promise((r) => setTimeout(r, 500));

    const ackP = ctx.familyWs.waitFor('RECORDING_STOP_ACK');
    ctx.familyWs.send({ type: 'RECORDING_STOP_CMD', targetElderId: ctx.elder.elderId });
    const ack = await ackP;
    assert.equal(ack.success, false);
    assert.equal(ack.offline, true);
  });
});
