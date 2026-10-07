// 录音转文字（ASR）
//
// 设计成"可插拔 Provider"：密钥和厂商都可能换，业务代码不该跟着改。
// 通过环境变量 ASR_PROVIDER 切换，未配置时不会静默假装成功，而是把状态标成
// SKIPPED/FAILED 并记下原因 —— 子女端会如实显示"未转写"，不会把空文本当结论。
//
// 支持的 Provider：
//   whisper  —— 任何 OpenAI 兼容的 /v1/audio/transcriptions 接口
//               （OpenAI / 硅基流动 / Groq / 本地 whisper.cpp server 均可）
//   tencent  —— 腾讯云一句话识别 WebSocket 接口（国内网络更稳）
//   none     —— 不配置，不转写
//
// 环境变量：
//   ASR_PROVIDER=whisper
//   ASR_BASE_URL=https://api.siliconflow.cn/v1
//   ASR_API_KEY=xxx
//   ASR_MODEL=FunAudioLLM/SenseVoiceSmall
//   ASR_TIMEOUT_MS=60000
const fs = require('fs');
const https = require('https');
const http = require('http');
const crypto = require('crypto');

const PROVIDER = String(process.env.ASR_PROVIDER || 'none').toLowerCase();
const TIMEOUT_MS = parseInt(process.env.ASR_TIMEOUT_MS || '60000', 10);

function isConfigured() {
  if (PROVIDER === 'whisper') {
    return !!(process.env.ASR_BASE_URL && process.env.ASR_API_KEY);
  }
  if (PROVIDER === 'tencent') {
    return !!(
      process.env.TENCENT_SECRET_ID &&
      process.env.TENCENT_SECRET_KEY &&
      process.env.TENCENT_ASR_ENGINE_TYPE
    );
  }
  return false;
}

function providerName() {
  return PROVIDER;
}

/**
 * 转写单个录音文件。
 * @returns {Promise<{ok:boolean, text?:string, status:'DONE'|'FAILED'|'SKIPPED', error?:string, engine?:string, durationMs?:number}>}
 */
async function transcribe(filePath, mimeType = 'audio/mp4') {
  const startedAt = Date.now();

  if (!isConfigured()) {
    return {
      ok: false,
      status: 'SKIPPED',
      error: PROVIDER === 'none'
        ? '服务端未配置语音转写服务（ASR_PROVIDER=none）'
        : '语音转写服务配置不完整，缺少必要的 API 密钥'
    };
  }

  // 文件已经不在磁盘上（例如刚被清理），不必再试
  if (!fs.existsSync(filePath)) {
    return { ok: false, status: 'FAILED', error: '录音文件不存在，可能已被清理' };
  }

  try {
    if (PROVIDER === 'whisper') return await transcribeByWhisper(filePath, mimeType, startedAt);
    if (PROVIDER === 'tencent') return await transcribeByTencent(filePath, startedAt);
    return { ok: false, status: 'FAILED', error: `未知的 ASR_PROVIDER：${PROVIDER}` };
  } catch (e) {
    return { ok: false, status: 'FAILED', error: e.message || '转写过程异常' };
  }
}

// ──────────────────────────────────────────
//  OpenAI 兼容 /audio/transcriptions
// ──────────────────────────────────────────

function transcribeByWhisper(filePath, mimeType, startedAt) {
  return new Promise((resolve, reject) => {
    const baseUrl = String(process.env.ASR_BASE_URL).replace(/\/+$/, '');
    const urlObj = new URL(`${baseUrl}/audio/transcriptions`);
    const isHttps = urlObj.protocol === 'https:';
    const lib = isHttps ? https : http;

    // 手写 multipart，避免为一个请求引入额外依赖
    const boundary = `----GuardAsr${crypto.randomBytes(12).toString('hex')}`;
    const fileName = filePath.replace(/\\/g, '/').split('/').pop();
    const fileStat = fs.statSync(filePath);

    const head = Buffer.from(
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="${fileName}"\r\n` +
      `Content-Type: ${mimeType || 'audio/mp4'}\r\n\r\n`,
      'utf8'
    );
    const tail = Buffer.from(
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="model"\r\n\r\n` +
      `${process.env.ASR_MODEL || 'whisper-1'}\r\n` +
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="response_format"\r\n\r\n` +
      `json\r\n` +
      `--${boundary}--\r\n`,
      'utf8'
    );

    const fileStream = fs.createReadStream(filePath);
    const chunks = [head];
    fileStream.on('data', (c) => chunks.push(c));
    fileStream.on('error', reject);
    fileStream.on('end', () => {
      chunks.push(tail);
      const body = Buffer.concat(chunks);
      const totalLength = body.length;

      const req = lib.request(
        {
          protocol: urlObj.protocol,
          hostname: urlObj.hostname,
          port: urlObj.port || (isHttps ? 443 : 80),
          path: `${urlObj.pathname}${urlObj.search}`,
          method: 'POST',
          headers: {
            'Content-Type': `multipart/form-data; boundary=${boundary}`,
            'Content-Length': totalLength,
            'Authorization': `Bearer ${process.env.ASR_API_KEY}`,
            'Accept': 'application/json'
          }
        },
        (res) => {
          let raw = '';
          res.on('data', (d) => (raw += d));
          res.on('end', () => {
            if (res.statusCode < 200 || res.statusCode >= 300) {
              // 供应商报错原样带回，便于运维定位（但会截断，避免把 HTML 错误页塞进数据库）
              return resolve({
                ok: false,
                status: 'FAILED',
                error: `ASR 服务返回 HTTP ${res.statusCode}：${raw.slice(0, 200)}`,
                engine: 'whisper'
              });
            }
            try {
              const json = JSON.parse(raw);
              const text = (json.text || '').trim();
              if (!text) {
                return resolve({
                  ok: false,
                  status: 'FAILED',
                  error: 'ASR 返回了空文本（可能是纯静音或环境噪声）',
                  engine: 'whisper'
                });
              }
              resolve({
                ok: true,
                status: 'DONE',
                text,
                engine: 'whisper',
                durationMs: Date.now() - startedAt
              });
            } catch (e) {
              resolve({ ok: false, status: 'FAILED', error: 'ASR 返回内容无法解析', engine: 'whisper' });
            }
          });
        }
      );

      req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error('ASR 请求超时')));
      req.on('error', reject);
      req.end(body);
    });
  });
}

// ──────────────────────────────────────────
//  腾讯云一句话识别（WebSocket + 签名）
//  音频需先转成 base64 分片，识别完整段；适合 10 分钟以内的短录音
// ──────────────────────────────────────────

function transcribeByTencent(filePath, startedAt) {
  return new Promise((resolve, reject) => {
    const secretId = process.env.TENCENT_SECRET_ID;
    const secretKey = process.env.TENCENT_SECRET_KEY;
    const engineType = process.env.TENCENT_ASR_ENGINE_TYPE || '16k_zh';
    const voiceId = crypto.randomBytes(16).toString('hex');
    const expire = Math.floor(Date.now() / 1000) + 600;

    // 腾讯云签名：原文为 "asr:appid:secretId:expire"，用 HMAC-SHA256 派生 key
    const originalStr = `asr:appid:${secretId}:${expire}`;
    const signature = crypto
      .createHmac('sha256', secretKey)
      .update(originalStr)
      .digest('hex');

    const header = {
      app_id: Number(process.env.TENCENT_APP_ID || 0),
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

    const ws = new (require('ws').WebSocket)(`wss://tts.cloud.tencent.com/asr/v2?engine_type=${engineType}&secretid=${secretId}&timestamp=${expire}&expired=${expire}&nonce=${voiceId}&signature=${signature}&voice_id=${voiceId}&needvad=0`);

    let result = '';
    let finished = false;

    const finish = (payload) => {
      if (finished) return;
      finished = true;
      try { ws.close(); } catch (e) {}
      resolve(payload);
    };

    const timer = setTimeout(() => {
      finish({ ok: false, status: 'FAILED', error: '腾讯云 ASR 请求超时', engine: 'tencent' });
    }, TIMEOUT_MS);

    ws.on('open', () => {
      const audio = fs.readFileSync(filePath);
      // 腾讯云要求 40ms 一包，16k/16bit 单声道 = 1280 字节
      const CHUNK = 1280;
      let seq = 1;
      for (let i = 0; i < audio.length; i += CHUNK) {
        ws.send(
          JSON.stringify({
            ...header,
            seq: seq++,
            end: i + CHUNK >= audio.length ? 1 : 0
          }),
          { binary: true }
        );
      }
      if (audio.length === 0) {
        finish({ ok: false, status: 'FAILED', error: '录音文件为空', engine: 'tencent' });
      }
    });

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString());
        if (msg.code !== 0) {
          clearTimeout(timer);
          return finish({
            ok: false,
            status: 'FAILED',
            error: `腾讯云 ASR 错误 ${msg.code}：${msg.message || ''}`,
            engine: 'tencent'
          });
        }
        if (msg.result && typeof msg.result === 'string') {
          // 腾讯云是增量返回，最终以 final=1 的那条为准
          if (msg.final === 0) {
            result = msg.result;
          } else if (msg.final === 1) {
            result = msg.result;
            clearTimeout(timer);
            finish({
              ok: true,
              status: 'DONE',
              text: result.trim(),
              engine: 'tencent',
              durationMs: Date.now() - startedAt
            });
          }
        }
      } catch (e) {
        // 忽略非 JSON 心跳帧
      }
    });

    ws.on('error', (e) => {
      clearTimeout(timer);
      finish({ ok: false, status: 'FAILED', error: `腾讯云 ASR 连接失败：${e.message}`, engine: 'tencent' });
    });

    ws.on('close', () => {
      clearTimeout(timer);
      if (finished) return;
      const text = result.trim();
      finish(
        text
          ? { ok: true, status: 'DONE', text, engine: 'tencent', durationMs: Date.now() - startedAt }
          : { ok: false, status: 'FAILED', error: '腾讯云 ASR 未返回识别结果', engine: 'tencent' }
      );
    });
  });
}

module.exports = { transcribe, isConfigured, providerName, PROVIDER };
