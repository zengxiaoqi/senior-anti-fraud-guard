/**
 * DO Hub 的 REST 侧客户端封装。
 *
 * 业务路由（recordings/events）通过这里触达实时推送能力；全部方法都是
 * fire-and-forget 语义的调用方，应配合 c.executionCtx.waitUntil 使用，
 * 不阻塞 HTTP 响应（对齐本地 hub.notifyFamily 的异步推送）。
 */
import type { Env } from '../env';

export function hubStub(env: Env): DurableObjectStub {
  return env.GUARD_HUB.get(env.GUARD_HUB.idFromName('global'));
}

/** 把 payload 推给 elderId 的绑定子女端（无绑定/离线时静默忽略，对齐本地） */
export async function notifyFamily(env: Env, elderId: number, payload: unknown): Promise<void> {
  try {
    await hubStub(env).fetch('https://hub/notify', {
      method: 'POST',
      body: JSON.stringify({ elderId, payload })
    });
  } catch {
    // 推送失败不影响业务主流程
  }
}

/**
 * 把 payload 推给老人端本人（对应本地 server.js 的 notifyElderSettingsChanged）。
 * 离线不缓存：配置没有时效性，缓存陈旧配置会在上线时覆盖掉更新的值，
 * 老人端有 30 分钟拉取兜底。
 */
export async function notifyElder(env: Env, elderId: number, payload: unknown): Promise<void> {
  try {
    await hubStub(env).fetch('https://hub/notify-elder', {
      method: 'POST',
      body: JSON.stringify({ elderId, payload })
    });
  } catch {
    // 推送失败不影响业务主流程
  }
}

/** 查询老人端录音运行态（/api/recordings/status 用） */
export async function getRecordingState(
  env: Env,
  elderId: number
): Promise<{ active: boolean } & Record<string, unknown>> {
  try {
    const res = await hubStub(env).fetch(`https://hub/recording-state?elderId=${elderId}`);
    return (await res.json()) as { active: boolean } & Record<string, unknown>;
  } catch {
    return { active: false };
  }
}

/** 紧急打断（离线自动入队待补发）。返回 offline 供调用方组织回执文案 */
export async function deliverInterrupt(
  env: Env,
  targetElderId: number,
  fromUser: string,
  message: string
): Promise<{ delivered: boolean }> {
  const res = await hubStub(env).fetch('https://hub/interrupt', {
    method: 'POST',
    body: JSON.stringify({ targetElderId, fromUser, message })
  });
  return (await res.json()) as { delivered: boolean };
}

/** 远程停止录音指令投递结果 */
export async function stopElderRecording(
  env: Env,
  targetElderId: number,
  fromUser: string
): Promise<boolean> {
  try {
    const res = await hubStub(env).fetch('https://hub/stop-recording', {
      method: 'POST',
      body: JSON.stringify({ targetElderId, fromUser })
    });
    const json = (await res.json()) as { delivered?: boolean };
    return !!json.delivered;
  } catch {
    return false;
  }
}
