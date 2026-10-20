/**
 * GuardHubDO —— 实时推送中枢（替代本地 server.js 的内存 clients Map + 各类推送函数）。
 *
 * 本地实现：进程里 Map<userId, WebSocket>；Workers 没有跨请求共享内存，
 * 这张表必须活在 Durable Object 里。业务代码（REST 路由）通过 DO 的内部 HTTP
 * 端点（/notify、/interrupt、/recording-state、/stop-recording）触达推送能力。
 *
 * 关键设计：WebSocket Hibernation —— acceptWebSocket + serializeAttachment，
 * 空闲连接不占活跃时长，免费额度内可长期挂机（子女端随时收到告警的前提）。
 * DO 因休眠被逐出后，下一个事件会重新构造实例，sessions 表从 getWebSockets()
 * 的附件恢复，无需外部存储。
 *
 * 与本地逐条对齐的行为（都是踩过坑的）：
 *  - 关闭时仅当 sessions 里登记的仍是"本条连接"才删除（网络抖动先建新连再断旧连，
 *    旧连接 close 若无条件删除会误判"老人未在线"丢打断指令）；
 *  - 老人离线时的紧急打断指令缓存（保留最近 5 条），重新注册后立即补发并标 offlineQueued；
 *  - INTERRUPT_CMD 附带守护人真实手机号（mobile || phone，排除 wx_ 占位号），
 *    老人端警报页可直接预填拨号；
 *  - 老人掉线时清 recordingState，子女端不再看到过期的"正在录音"。
 */
import type { Env } from '../env';
import { sendAlertSubscribe } from '../lib/wxSubscribe';

interface RecordingState {
  reason: string;
  place: string;
  startedAt: number;
  segments: number;
  sessionId: string;
}

interface QueuedInterrupt {
  fromUser: string;
  fromPhone: string;
  alertTitle: string;
  alertMessage: string;
}

interface Attachment {
  userId: string;
  role: string;
}

/** 查询某老人主守护人的真实手机号（警报页一键拨号用；多人绑定取主守护人） */
async function lookupFamilyPhone(env: Env, elderId: number): Promise<string> {
  const row = await env.DB.prepare(
    `SELECT f.id, f.name, f.phone, f.mobile FROM bindings b
      JOIN users f ON f.id = b.family_id
     WHERE b.elder_id = ? AND b.status = 'ACTIVE'
     ORDER BY b.is_primary DESC, b.id ASC LIMIT 1`
  )
    .bind(elderId)
    .first<{ id: number; phone: string | null; mobile: string | null }>();
  if (!row?.id) return '';
  const phone = (row.mobile || row.phone || '').trim();
  // 排除微信占位号（wx_ 开头不是真实号码）
  if (!/^\+?\d{6,}$/.test(phone.replace(/-/g, ''))) return '';
  return phone;
}

/** 未注册的连接没有附件，deserializeAttachment 会抛错 → 统一兜底返回 null */
function safeAttachment(ws: WebSocket): Attachment | null {
  try {
    return (ws.deserializeAttachment() as Attachment | null) ?? null;
  } catch {
    return null;
  }
}

export class GuardHubDO implements DurableObject {
  private sessions = new Map<string, WebSocket>();
  private recordingState = new Map<number, RecordingState>();

  constructor(private state: DurableObjectState, private env: Env) {
    // 休眠唤醒：把上次 accept 的连接按附件登记回内存表
    for (const ws of state.getWebSockets()) {
      try {
        const att = ws.deserializeAttachment() as Attachment | null;
        if (att?.userId) this.sessions.set(att.userId, ws);
      } catch {
        // 无附件的连接（尚未 REGISTER），忽略
      }
    }
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/notify' && request.method === 'POST') {
      // 定向推送：按 bindings 多人绑定 fanout 到全部守护人的连接（WS 在线直推）
      const body = (await request.json()) as { elderId: number; payload: unknown };
      let delivered = false;
      if (body.elderId) {
        const { results } = await this.env.DB.prepare(
          "SELECT family_id FROM bindings WHERE elder_id = ? AND status = 'ACTIVE'"
        )
          .bind(body.elderId)
          .all<{ family_id: number }>();
        for (const { family_id } of results ?? []) {
          const target = this.sessions.get(String(family_id));
          if (target) {
            try {
              target.send(JSON.stringify(body.payload));
              delivered = true;
            } catch {
              // 单个家庭发送失败不影响其他守护人
            }
          }
        }
      }
      return Response.json({ success: true, delivered });
    }

    if (url.pathname === '/notify-elder' && request.method === 'POST') {
      // 定向推送老人端（对应本地 notifyElderSettingsChanged）：
      // 子女端改了守护规则，通知在线老人端立刻重算。离线不缓存 ——
      // 配置没有时效性，缓存陈旧配置会在上线时覆盖更新的值，靠 30 分钟拉取兜底。
      const body = (await request.json()) as { elderId: number; payload: unknown };
      const target = this.sessions.get(String(parseInt(String(body.elderId), 10) || 0));
      let delivered = false;
      if (target) {
        try {
          target.send(JSON.stringify(body.payload));
          delivered = true;
        } catch {
          delivered = false;
        }
      }
      return Response.json({ success: true, delivered });
    }

    if (url.pathname === '/interrupt' && request.method === 'POST') {
      // 紧急打断指令（对应本地 INTERRUPT_CMD 的服务端处理分支）
      const body = (await request.json()) as {
        targetElderId: number;
        fromUser?: string;
        message?: string;
      };
      const r = await this.deliverInterrupt(
        parseInt(String(body.targetElderId), 10) || 0,
        body.fromUser || '子女端守护人',
        body.message || ''
      );
      return Response.json({ success: true, delivered: r.delivered, offline: !r.delivered });
    }

    if (url.pathname === '/recording-state' && request.method === 'GET') {
      const elderId = parseInt(url.searchParams.get('elderId') || '0', 10);
      const s = this.recordingState.get(elderId);
      return Response.json(s ? { active: true, ...s } : { active: false });
    }

    if (url.pathname === '/stop-recording' && request.method === 'POST') {
      const body = (await request.json()) as { targetElderId: number; fromUser?: string };
      const target = this.sessions.get(String(parseInt(String(body.targetElderId), 10) || 0));
      if (target) {
        try {
          target.send(
            JSON.stringify({
              type: 'RECORDING_STOP',
              fromUser: body.fromUser || '子女端守护人',
              reason: 'REMOTE'
            })
          );
          return Response.json({ delivered: true });
        } catch {
          return Response.json({ delivered: false });
        }
      }
      return Response.json({ delivered: false });
    }

    if (request.headers.get('Upgrade') === 'websocket') {
      const pair = new WebSocketPair();
      // Hibernation API：交给 runtime 托管，进消息走 webSocketMessage 回调
      this.state.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] } as ResponseInit);
    }

    return Response.json({ success: false, error: 'GuardHubDO: 未支持的请求' }, { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(
        typeof message === 'string' ? message : new TextDecoder().decode(message)
      ) as Record<string, unknown>;
    } catch {
      try { ws.send(JSON.stringify({ type: 'ERROR', message: '无法解析的消息' })); } catch { /* ignore */ }
      return;
    }

    const type = payload?.type;

    // 0. 心跳应答（小程序端 25s 一发，用于半开连接检测——App 端走 OkHttp
    //    协议层 ping 不经过这里）。放在 REGISTER 守卫之前：PING 不要求已注册。
    if (type === 'PING') {
      try { ws.send(JSON.stringify({ type: 'PONG', ts: Date.now() })); } catch { /* ignore */ }
      return;
    }

    // 1. 身份注册 { type:'REGISTER', userId, role }
    if (type === 'REGISTER' && payload.userId != null) {
      const userId = String(payload.userId);
      const att: Attachment = { userId, role: String(payload.role || '') };
      (ws as WebSocket & { serializeAttachment(a: unknown): void }).serializeAttachment(att);
      this.sessions.set(userId, ws);
      try {
        ws.send(JSON.stringify({ type: 'REGISTER_ACK', message: '已成功接入守护全双工网络' }));
      } catch { /* ignore */ }

      // 老人端上线：补发离线期间缓存的紧急打断指令（最多最近 5 条）
      if (att.role === 'elder') {
        const key = `pi:${userId}`;
        const queued = (await this.state.storage.get<QueuedInterrupt[]>(key)) ?? [];
        if (queued.length) {
          await this.state.storage.delete(key);
          for (const cmd of queued.slice(-5)) {
            try {
              ws.send(JSON.stringify({ type: 'EMERGENCY_INTERRUPT', ...cmd, playAudio: true, offlineQueued: true }));
            } catch { /* ignore */ }
          }
        }
      }
      return;
    }

    // 未注册的连接不处理业务指令（对齐本地 connectedUserId == null 的守卫）
    const att = safeAttachment(ws);
    if (!att?.userId) return;

    // 2. 子女端发起紧急打断 { type:'INTERRUPT_CMD', targetElderId, message }
    if (type === 'INTERRUPT_CMD') {
      const r = await this.deliverInterrupt(
        parseInt(String(payload.targetElderId), 10) || 0,
        String(payload.fromUser || '子女端守护人'),
        String(payload.message || '')
      );
      try {
        ws.send(
          JSON.stringify(
            r.delivered
              ? { type: 'INTERRUPT_ACK', success: true, message: '强打断指令已穿透推送到老人手机' }
              : {
                  type: 'INTERRUPT_ACK',
                  success: false,
                  offline: true,
                  message: '老人端手机当前不在线，指令已缓存，老人端一上线将立即弹出警报'
                }
          )
        );
      } catch { /* ignore */ }
      return;
    }

    // 3. 老人端上报录音运行态 { type:'RECORDING_STATE', state:'STARTED'|'SEGMENT'|'STOPPED', ... }
    if (type === 'RECORDING_STATE') {
      const elderId = parseInt(att.userId, 10) || 0;
      if (!elderId) return;
      const state = String(payload.state || '');
      if (state === 'STARTED') {
        this.recordingState.set(elderId, {
          reason: String(payload.reason || 'SOS'),
          place: String(payload.place || ''),
          startedAt: Date.now(),
          segments: 0,
          sessionId: String(payload.sessionId || '')
        });
      } else if (state === 'SEGMENT') {
        const s = this.recordingState.get(elderId) || {
          reason: String(payload.reason || 'SOS'),
          place: String(payload.place || ''),
          startedAt: Date.now(),
          segments: 0,
          sessionId: ''
        };
        s.segments = Number(payload.segmentIndex) || s.segments + 1;
        s.sessionId = String(payload.sessionId || s.sessionId);
        this.recordingState.set(elderId, s);
      } else if (state === 'STOPPED') {
        this.recordingState.delete(elderId);
      }

      // 同步给子女端："正在录音"指示灯和远程停止按钮依赖这条
      const cur = this.recordingState.get(elderId);
      await this.notifyBoundFamily(elderId, {
        type: 'RECORDING_STATE',
        data: {
          state,
          ...(cur ? { active: true, ...cur } : { active: false }),
          reason: payload.reason,
          segmentIndex: payload.segmentIndex
        }
      });
      return;
    }

    // 4. 子女端远程停止老人端录音 { type:'RECORDING_STOP_CMD', targetElderId }
    if (type === 'RECORDING_STOP_CMD') {
      const targetElderId = parseInt(String(payload.targetElderId), 10) || 0;
      const target = this.sessions.get(String(targetElderId));
      if (target) {
        try {
          target.send(
            JSON.stringify({
              type: 'RECORDING_STOP',
              fromUser: String(payload.fromUser || '子女端守护人'),
              reason: 'REMOTE'
            })
          );
          ws.send(JSON.stringify({ type: 'RECORDING_STOP_ACK', success: true, message: '已通知老人端停止录音' }));
        } catch {
          ws.send(JSON.stringify({ type: 'RECORDING_STOP_ACK', success: false, offline: true, message: '老人端当前不在线，无法远程停止' }));
        }
      } else {
        ws.send(JSON.stringify({
          type: 'RECORDING_STOP_ACK',
          success: false,
          offline: true,
          message: '老人端当前不在线，无法远程停止'
        }));
      }
      return;
    }
  }

  /** 紧急打断：在线直推，离线入 storage 队列。供 /interrupt 端点与 WS 消息共用 */
  private async deliverInterrupt(
    targetElderId: number,
    fromUser: string,
    message: string
  ): Promise<{ delivered: boolean }> {
    if (!targetElderId) return { delivered: false };
    const alertTitle = '⚠️ 紧急亲情防骗强提醒！';
    const alertMessage = message || '子女已监听到高危行为，请暂停当前通话与转账！';
    const fromPhone = await lookupFamilyPhone(this.env, targetElderId);

    const target = this.sessions.get(String(targetElderId));
    if (target) {
      try {
        target.send(
          JSON.stringify({ type: 'EMERGENCY_INTERRUPT', fromUser, fromPhone, alertTitle, alertMessage, playAudio: true })
        );
        return { delivered: true };
      } catch {
        // 发送失败按离线处理
      }
    }
    const key = `pi:${targetElderId}`;
    const queued = ((await this.state.storage.get<QueuedInterrupt[]>(key)) ?? []).concat({
      fromUser,
      fromPhone,
      alertTitle,
      alertMessage
    });
    await this.state.storage.put(key, queued.slice(-5));
    return { delivered: false };
  }

  /** 按绑定关系定向推送（业务推送统一入口）。多人绑定：全量守护人逐个 fanout，
   *  WS 在线直推，离线的走订阅消息兑底（每家庭独立节流） */
  private async notifyBoundFamily(elderId: number, payload: unknown): Promise<void> {
    try {
      const { results } = await this.env.DB.prepare(
        "SELECT family_id FROM bindings WHERE elder_id = ? AND status = 'ACTIVE'"
      )
        .bind(elderId)
        .all<{ family_id: number }>();
      for (const { family_id } of results ?? []) {
        const ws = this.sessions.get(String(family_id));
        if (ws) {
          ws.send(JSON.stringify(payload));
          continue;
        }
        // WS 不在线 → 订阅消息兑底。小程序切后台几秒即挂起、WS 随之断开，
        // 此前这里是静默丢弃：老人真出事时子女多半收不到任何通知。
        // 只对可打断的高危事件发（与客户端 shouldInterruptAlert 同一把尺子），
        // 定位心跳类 LOW 事件绝不打扰。
        await this.maybeSubscribeAlert(family_id, elderId, payload);
      }
    } catch {
      // 推送失败不影响业务主流程
    }
  }

  /**
   * 高危事件 + 子女 WS 离线 → 微信订阅消息触达。
   * 节流放在 DO 内存里：DO 被逐出会重置，最坏情况是重复推送一条，
   * 比为此引入 storage 异步读写拖慢主推送路径划算。
   */
  private subAlertLastSent = new Map<string, number>();

  private async maybeSubscribeAlert(familyUserId: number, elderId: number, payload: unknown): Promise<void> {
    const p = payload as { type?: string; data?: { event_type?: string; interruptible?: boolean } };
    if (p?.type !== 'RISK_ALERT') return;
    const d = p.data || {};
    if (!d.interruptible) return;

    const eventType = String(d.event_type || '');
    // 节流键含 elderId：同一子女绑多个老人后，不同老人的同类告警互不吞掉
    const key = `${familyUserId}:${elderId}:${eventType}`;
    const last = this.subAlertLastSent.get(key) || 0;
    if (Date.now() - last < 5 * 60 * 1000) return; // 同类事件 5 分钟内不重复打扰
    this.subAlertLastSent.set(key, Date.now());

    await sendAlertSubscribe(this.env, familyUserId, {
      eventType,
      createdAt: String((p.data as { created_at?: string })?.created_at || new Date().toISOString())
    });
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const att = safeAttachment(ws);
    if (!att?.userId) return;
    // 仅当登记的仍是"本条连接"才删（网络抖动先建新连再断旧连的场景）
    if (this.sessions.get(att.userId) === ws) {
      this.sessions.delete(att.userId);
      // 老人掉线，录音状态不再可信，清掉以免子女端看到过期的"正在录音"
      this.recordingState.delete(parseInt(att.userId, 10));
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    const att = safeAttachment(ws);
    if (att?.userId && this.sessions.get(att.userId) === ws) {
      this.sessions.delete(att.userId);
      this.recordingState.delete(parseInt(att.userId, 10));
    }
    try { ws.close(); } catch { /* ignore */ }
  }
}
