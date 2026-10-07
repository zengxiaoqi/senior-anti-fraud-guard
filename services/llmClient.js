// 极简 LLM 客户端（OpenAI 兼容 /chat/completions）
//
// 只服务于"录音转写文本的诈骗研判复核"这一个场景，所以刻意做得很薄：
// 没有重试、没有流式、没有多模态，失败就抛给调用方降级。
const http = require('http');
const https = require('https');

function isConfigured() {
  return !!(process.env.LLM_BASE_URL && process.env.LLM_API_KEY);
}

function callLLM(prompt, options = {}) {
  return new Promise((resolve, reject) => {
    if (!isConfigured()) {
      return reject(new Error('LLM 未配置'));
    }
    const baseUrl = String(process.env.LLM_BASE_URL).replace(/\/+$/, '');
    const urlObj = new URL(`${baseUrl}/chat/completions`);
    const isHttps = urlObj.protocol === 'https:';
    const lib = isHttps ? https : http;
    const timeoutMs = options.timeoutMs || parseInt(process.env.LLM_TIMEOUT_MS || '45000', 10);

    const body = Buffer.from(
      JSON.stringify({
        model: process.env.LLM_MODEL || 'gpt-4o-mini',
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.1,
        max_tokens: options.maxTokens || 800
      }),
      'utf8'
    );

    const req = lib.request(
      {
        protocol: urlObj.protocol,
        hostname: urlObj.hostname,
        port: urlObj.port || (isHttps ? 443 : 80),
        path: `${urlObj.pathname}${urlObj.search}`,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': body.length,
          'Authorization': `Bearer ${process.env.LLM_API_KEY}`
        }
      },
      (res) => {
        let raw = '';
        res.on('data', (d) => (raw += d));
        res.on('end', () => {
          if (res.statusCode < 200 || res.statusCode >= 300) {
            return reject(new Error(`LLM 返回 HTTP ${res.statusCode}：${raw.slice(0, 200)}`));
          }
          try {
            const json = JSON.parse(raw);
            const content = json.choices?.[0]?.message?.content;
            if (!content) return reject(new Error('LLM 返回内容为空'));
            resolve(String(content));
          } catch (e) {
            reject(new Error('LLM 返回内容无法解析'));
          }
        });
      }
    );

    req.setTimeout(timeoutMs, () => req.destroy(new Error('LLM 请求超时')));
    req.on('error', reject);
    req.end(body);
  });
}

module.exports = { callLLM, isConfigured };
