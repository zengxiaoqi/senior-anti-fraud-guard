/** 绑定环境：与 wrangler.jsonc 一一对应（wrangler types 会生成同名全局类型，这里显式导出避免各处重复声明） */
export interface Env {
  DB: D1Database;
  RECORDINGS: R2Bucket;
  CACHE: KVNamespace;
  GUARD_HUB: DurableObjectNamespace;
  ASSETS: Fetcher;
  SERVICE_STAGE?: string;

  // ── 逆地理编码（可选，缺省时地点推断走本地锚点/热点兜底）──
  GEO_PROVIDER?: string; // AMAP | TENCENT | NONE
  AMAP_WEB_KEY?: string;
  TENCENT_MAP_KEY?: string;

  // ── 微信登录与推送（secrets，未配置时 wx-login 返回 500，与本地行为一致）──
  WECHAT_APPID?: string;
  WECHAT_APPSECRET?: string;

  // ── 录音转写 ASR（可选，缺省标 SKIPPED → SUSPECT 临时保留）──
  ASR_PROVIDER?: string; // whisper | tencent | none
  ASR_BASE_URL?: string;
  ASR_API_KEY?: string;
  ASR_MODEL?: string;
  ASR_TIMEOUT_MS?: string;
  TENCENT_SECRET_ID?: string;
  TENCENT_SECRET_KEY?: string;
  TENCENT_APP_ID?: string;
  TENCENT_ASR_ENGINE_TYPE?: string;

  // ── 录音研判 LLM 复核（可选，规则引擎是主依据）──
  LLM_BASE_URL?: string;
  LLM_API_KEY?: string;
  LLM_MODEL?: string;
  LLM_TIMEOUT_MS?: string;
  RECORDING_SAFE_RETENTION_DAYS?: string;

  /** 播放令牌密钥（secrets）。Workers 多隔离实例必须显式配置，未配置用开发兜底密钥 */
  EVIDENCE_TOKEN_SECRET?: string;
  PLAY_TOKEN_TTL_MS?: string;

  /** fetch handler 里传入，供库内代码把后台任务挂到 waitUntil（结构化类型，避免与 Hono/全局 ExecutionContext 产生类型身份冲突） */
  ctx?: { waitUntil(promise: Promise<unknown>): void; passThroughOnException(): void };
}
