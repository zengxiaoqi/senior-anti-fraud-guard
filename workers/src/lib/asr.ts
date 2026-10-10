/**
 * 录音转文字 ASR（对齐本地 services/asr.js，可插拔 Provider）。
 *
 *  - whisper —— 任何 OpenAI 兼容 /v1/audio/transcriptions（fetch + 手写 multipart）
 *  - tencent —— 腾讯云一句话识别（出站 WebSocket + HMAC-SHA256 签名）
 *  - none    —— 未配置：不假装成功，标 SKIPPED 并记原因（子女端如实显示"未转写"）
 *
 * Workers 差异：文件内容从内存 Buffer 读（上传时已在内存/R2），不落盘；
 * 出站 WebSocket 用 workerd 原生 WebSocket 客户端（免费层每请求并发连接上限 6，够用）。
 */
import { createHmac, randomBytes } from 'node:crypto';
import type { Env } from '../env';

export interface AsrResult {
  ok: boolean;
  text?: string;
  status: 'DONE' | 'FAILED' | 'SKIPPED';
  error?: string;
  engine?: string;
  durationMs?: number;
}

function timeoutMs(env: Env): number {
  const n = parseInt(String(env.ASR_TIMEOUT_MS || ''), 10);
  return Number.isFinite(n) && n > 0 ? n : 60000;
}

export function isConfigured(env: Env): boolean {
  const provider = String(env.ASR_PROVIDER || 'none').toLowerCase();
  if (provider === 'whisper') return !!(env.ASR_BASE_URL && env.ASR_API_KEY);
  if (provider === 'tencent') {
    return !!(env.TENCENT_SECRET_ID && env.TENCENT_SECRET_KEY && env.TENCENT_ASR_ENGINE_TYPE);
  }
  return false;
}

export function providerName(env: Env): string {
  return String(env.ASR_PROVIDER || 'none').toLowerCase();
}

/**
 * 转写录音。audio 传文件字节；mimeType 用于 multipart 的 Content-Type。
 */
export async function transcribe(env: Env, audio: ArrayBuffer, mimeType: string): Promise<AsrResult> {
  const startedAt = Date.now();

  if (!isConfigured(env)) {
    return {
      ok: false,
      status: 'SKIPPED',
      error: providerName(env) === 'none'
        ? '服务端未配置语音转写服务（ASR_PROVIDER=none）'
        : '语音转写服务配置不完整，缺少必要的 API 密钥'
    };
  }

  try {
    if (providerName(env) === 'whisper') {
      return await transcribeByWhisper(env, audio, mimeType, startedAt);
    }
    if (providerName(env) === 'tencent') {
      return await transcribeByTencent(env, audio, startedAt);
    }
    return { ok: false, status: 'FAILED', error: `未知的 ASR_PROVIDER：${providerName(env)}` };
  } catch (e) {
    return { ok: false, status: 'FAILED', error: (e as Error).message || '转写过程异常' };
  }
}

// ──────────────────────────────────────────
//  OpenAI 兼容 /audio/transcriptions
// ──────────────────────────────────────────

async function transcribeByWhisper(
  env: Env,
  audio: ArrayBuffer,
  mimeType: string,
  startedAt: number
): Promise<AsrResult> {
  const baseUrl = String(env.ASR_BASE_URL).replace(/\/+$/, '');
  const boundary = `----GuardAsr${randomBytes(12).toString('hex')}`;
  const fileName = `audio${Date.now()}.m4a`;

  const head = `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="file"; filename="${fileName}"\r\n` +
    `Content-Type: ${mimeType || 'audio/mp4'}\r\n\r\n`;
  const tail = `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="model"\r\n\r\n` +
    `${env.ASR_MODEL || 'whisper-1'}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="response_format"\r\n\r\n` +
    `json\r\n` +
    `--${boundary}--\r\n`;

  const body = new Blob([head, audio, tail], { type: `multipart/form-data; boundary=${boundary}` });

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error('ASR 请求超时')), timeoutMs(env));
  try {
    const res = await fetch(`${baseUrl}/audio/transcriptions`, {
      method: 'POST',
      signal: ctrl.signal,
      headers: {
        Authorization: `Bearer ${env.ASR_API_KEY}`,
        Accept: 'application/json'
      },
      body
    });
    const raw = await res.text();
    if (!res.ok) {
      // 供应商报错原样带回（截断，避免 HTML 错误页塞进数据库）
      return { ok: false, status: 'FAILED', error: `ASR 服务返回 HTTP ${res.status}：${raw.slice(0, 200)}`, engine: 'whisper' };
    }
    const json = JSON.parse(raw) as { text?: string };
    const text = (json.text || '').trim();
    if (!text) {
      return { ok: false, status: 'FAILED', error: 'ASR 返回了空文本（可能是纯静音或环境噪声）', engine: 'whisper' };
    }
    return { ok: true, status: 'DONE', text, engine: 'whisper', durationMs: Date.now() - startedAt };
  } catch (e) {
    if ((e as Error).name === 'AbortError') {
      return { ok: false, status: 'FAILED', error: 'ASR 请求超时', engine: 'whisper' };
    }
    return { ok: false, status: 'FAILED', error: `ASR 请求失败：${(e as Error).message}`, engine: 'whisper' };
  } finally {
    clearTimeout(timer);
  }
}

// ──────────────────────────────────────────
//  腾讯云一句话识别（出站 WebSocket + 签名）
//  与本地实现同款 URL/参数（行为保持一致，含增量 result / final 帧）
// ──────────────────────────────────────────

async function transcribeByTencent(env: Env, audio: ArrayBuffer, startedAt: number): Promise<AsrResult> {
  const secretId = env.TENCENT_SECRET_ID as string;
  const secretKey = env.TENCENT_SECRET_KEY as string;
  const engineType = String(env.TENCENT_ASR_ENGINE_TYPE || '16k_zh');
  const voiceId = randomBytes(16).toString('hex');
  const expire = Math.floor(Date.now() / 1000) + 600;

  // 腾讯云签名：原文 "asr:appid:secretId:expire"，HMAC-SHA256 派生
  const originalStr = `asr:appid:${secretId}:${expire}`;
  const signature = createHmac('sha256', secretKey).update(originalStr).digest('hex');

  const header = {
    app_id: Number(env.TENCENT_APP_ID || 0),
    secret_id: secretId,
    timestamp: expire,
    expired: expire,
    nonce: voiceId,
    signature,
    engine_type: engineType,
    voice_id: voiceId,
    seq: 1,
    end: 1,
    format: 1,
    channel_id: 0,
    res_type: 0,
    source: 1
  };

  const wsUrl =
    `wss://tts.cloud.tencent.com/asr/v2?engine_type=${engineType}` +
    `&secretid=${encodeURIComponent(secretId)}&timestamp=${expire}&expired=${expire}` +
    `&nonce=${voiceId}&signature=${signature}&voice_id=${voiceId}&needvad=0`;

  return new Promise<AsrResult>((resolve) => {
    let result = '';
    let finished = false;
    const finish = (payload: AsrResult) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { ws.close(); } catch { /* 已关 */ }
      resolve(payload);
    };
    const timer = setTimeout(() => {
      finish({ ok: false, status: 'FAILED', error: '腾讯云 ASR 请求超时', engine: 'tencent' });
    }, timeoutMs(env));

    const ws = new WebSocket(wsUrl);

    ws.addEventListener('open', () => {
      const bytes = new Uint8Array(audio);
      // 腾讯云要求 40ms 一包，16k/16bit 单声道 = 1280 字节
      const CHUNK = 1280;
      let seq = 1;
      for (let i = 0; i < bytes.length; i += CHUNK) {
        const end = i + CHUNK >= bytes.length ? 1 : 0;
        const frame = JSON.stringify({ ...header, seq: seq++, end });
        // 本地实现把 JSON 头帧按 binary 发；行为保持一致
        ws.send(frame);
      }
      if (bytes.length === 0) {
        finish({ ok: false, status: 'FAILED', error: '录音文件为空', engine: 'tencent' });
      }
    });

    ws.addEventListener('message', (ev: MessageEvent) => {
      try {
        const raw = typeof ev.data === 'string' ? ev.data : new TextDecoder().decode(ev.data as ArrayBuffer);
        const msg = JSON.parse(raw) as { code?: number; message?: string; result?: string; final?: number };
        if (msg.code !== 0) {
          finish({
            ok: false,
            status: 'FAILED',
            error: `腾讯云 ASR 错误 ${msg.code}：${msg.message || ''}`,
            engine: 'tencent'
          });
          return;
        }
        if (msg.result && typeof msg.result === 'string') {
          // 腾讯云增量返回，最终以 final=1 那条为准
          if (msg.final === 0) {
            result = msg.result;
          } else if (msg.final === 1) {
            result = msg.result;
            finish({ ok: true, status: 'DONE', text: result.trim(), engine: 'tencent', durationMs: Date.now() - startedAt });
          }
        }
      } catch {
        // 忽略非 JSON 心跳帧
      }
    });

    ws.addEventListener('error', () => {
      finish({ ok: false, status: 'FAILED', error: '腾讯云 ASR 连接失败', engine: 'tencent' });
    });

    ws.addEventListener('close', () => {
      const text = result.trim();
      if (!finished) {
        finish(
          text
            ? { ok: true, status: 'DONE', text, engine: 'tencent', durationMs: Date.now() - startedAt }
            : { ok: false, status: 'FAILED', error: '腾讯云 ASR 未返回识别结果', engine: 'tencent' }
        );
      }
    });
  });
}
