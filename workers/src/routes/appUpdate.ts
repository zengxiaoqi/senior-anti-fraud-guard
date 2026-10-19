/**
 * /api/app-update 路由（对齐本地 routes/appUpdate.js）。
 *
 * 与本地后端的唯一差别是"发布清单从哪读"：本地读 dist/releases.json，
 * 这里读 R2 的 releases/latest.json（由 scripts/publish-release.js --upload 写入）。
 * 契约字段逐字一致，tests/contract/app-update.contract.test.js 两个后端跑同一套用例。
 *
 * 三个刻意的设计：
 *  1. 清单在 KV 里缓存 5 分钟。App 每次冷启动都会查一次版本，直接打 R2 的话
 *     读操作次数随装机量线性增长，而版本号一天也未必变一次；
 *  2. APK 不读进内存再吐 —— 37MB 的 Buffer 既踩 Workers 内存限制，也会让
 *     首字节时间白白等一整个文件读完。用 tee 分流，一路给客户端一路喂缓存；
 *  3. 更重要的是别让 Worker 长期承担大文件分发：new Response(r2.body) 逐字节
 *     流式转发实测只有 ~8KB/s（同一网络出口到 CF 边缘有 1MB/s），因为这条路径
 *     不进 CF 标准对象分发。配了 APK_PUBLIC_BASE_URL 就 302 跳到 R2 公开域，
 *     没配则退化为边缘缓存（首个用户回源慢一次，之后从边缘吐）。
 *     2026-10-12 用户报"升级下载都失败"才定位到：此前走 cloudflared 隧道 +
 *     本地 Express sendFile 有 100KB/s，切 Workers 后降到 8KB/s，同期包又从
 *     24MB 涨到 37MB，两个变化叠加把升级从 4 分钟拖到 1 小时以上。
 */
import { Hono } from 'hono';
import type { Env } from '../env';

const RELEASES_JSON_KEY = 'releases/latest.json';
const KV_CACHE_KEY = 'release:latest';
const KV_CACHE_TTL = 300; // 秒

interface ReleaseEntry {
  versionCode: number;
  versionName: string;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  changelog: string;
  minSupportedCode?: number;
  publishedAt?: string;
}

interface ReleasesFile {
  latest: ReleaseEntry;
  history?: ReleaseEntry[];
}

/** 读发布清单：KV 命中直接用，未命中回源 R2 并回填 */
async function loadReleases(env: Env): Promise<ReleasesFile | null> {
  try {
    const cached = await env.CACHE.get(KV_CACHE_KEY, 'json');
    if (cached && (cached as ReleasesFile).latest?.versionCode) {
      return cached as ReleasesFile;
    }
  } catch {
    // KV 读失败不致命：直接回源 R2。缓存是优化，不是依赖。
  }

  try {
    const obj = await env.RECORDINGS.get(RELEASES_JSON_KEY);
    if (!obj) return null;
    const parsed = JSON.parse(await obj.text()) as ReleasesFile;
    if (!parsed?.latest?.versionCode) return null;

    // 回填失败不影响本次响应 —— 只是下次多读一次 R2
    env.ctx?.waitUntil(
      env.CACHE.put(KV_CACHE_KEY, JSON.stringify(parsed), { expirationTtl: KV_CACHE_TTL })
    );
    return parsed;
  } catch (e) {
    console.error('[app-update] 发布清单读取失败:', e instanceof Error ? e.message : e);
    return null;
  }
}

function findRelease(releases: ReleasesFile | null, versionCode: number): ReleaseEntry | null {
  if (!releases) return null;
  if (Number(releases.latest.versionCode) === versionCode) return releases.latest;
  const history = Array.isArray(releases.history) ? releases.history : [];
  return history.find((r) => Number(r.versionCode) === versionCode) ?? null;
}

export const appUpdateRoutes = new Hono<{ Bindings: Env }>();

appUpdateRoutes.get('/latest', async (c) => {
  const currentCode = Number(c.req.query('versionCode')) || 0;
  const releases = await loadReleases(c.env);

  if (!releases) {
    return c.json({
      success: true,
      hasUpdate: false,
      reason: 'no_release_published',
      message: '服务端尚未发布升级包'
    });
  }

  const latest = releases.latest;
  const latestCode = Number(latest.versionCode);

  if (!latestCode || latestCode <= currentCode) {
    return c.json({
      success: true,
      hasUpdate: false,
      reason: 'already_latest',
      current: { versionCode: currentCode, latestCode }
    });
  }

  const minSupported = Number(latest.minSupportedCode ?? 0);
  const force = minSupported > 0 && currentCode < minSupported;

  return c.json({
    success: true,
    hasUpdate: true,
    force,
    latest: {
      versionCode: latestCode,
      versionName: String(latest.versionName ?? ''),
      sizeBytes: Number(latest.sizeBytes ?? 0),
      sha256: String(latest.sha256 ?? ''),
      changelog: String(latest.changelog ?? ''),
      force,
      publishedAt: String(latest.publishedAt ?? ''),
      downloadUrl: `/api/app-update/download?versionCode=${latestCode}`
    },
    current: { versionCode: currentCode }
  });
});

appUpdateRoutes.get('/download', async (c) => {
  const releases = await loadReleases(c.env);
  if (!releases) {
    return c.json({ success: false, error: '服务端尚未发布升级包' }, 404);
  }

  const raw = c.req.query('versionCode');
  const requested = raw === undefined ? NaN : Number(raw);
  const release = Number.isNaN(requested) ? releases.latest : findRelease(releases, requested);

  if (!release?.fileName) {
    return c.json({ success: false, error: '找不到该版本的安装包' }, 404);
  }

  // 文件名只来自发布清单，请求参数只用于挑选记录 —— 挡住 ?fileName=../ 之类的穿越
  const r2Key = `releases/${release.fileName}`;
  const fullSize = Number(release.sizeBytes ?? 0);

  // ── 路线一（首选）：R2 桶开了公开访问 / 绑了自定义域就 302 跳过去 ──
  //
  // 为什么不用 Worker 直接吐：APK 已涨到 37MB，而 Worker 用 new Response(r2.body)
  // 逐字节流式转发实测只有 ~8KB/s（同一网络出口到 CF 边缘有 1MB/s，差两个数量级），
  // 因为这条路径不进 CF 的标准对象分发链路。跳到 R2 公开域后走的是同一条高速路。
  // 没配 APK_PUBLIC_BASE_URL 就走下面的边缘缓存兜底。
  const publicBase = (c.env.APK_PUBLIC_BASE_URL ?? '').trim().replace(/\/+$/, '');
  if (publicBase) {
    return new Response(null, {
      status: 302,
      headers: {
        Location: `${publicBase}/${r2Key}`,
        // 跳转发本身也可缓存：版本不变时 Location 不变
        'Cache-Control': 'public, max-age=3600'
      }
    });
  }

  // ── 路线二：没配公开域，用边缘缓存兜底 ──
  // 首个用户回源慢一次，之后 37MB 全从边缘缓存吐。
  // ⭐ 缓存 key 必须带版本身份：整包 GET 的 URL（如 /download）发版前后不变，
  // key 只用 URL 的话老包会被边缘缓存继续吐满 max-age=86400，新版本发不出去。
  // key 拼上清单里的 versionCode 后，每次发版天然换 key，旧条目自己过期。
  const base = c.req.url;
  const cacheUrl =
    base + (base.includes('?') ? '&' : '?') +
    `v=${encodeURIComponent(String(release.versionCode ?? release.fileName))}`;
  const cacheKey = new Request(cacheUrl, { method: 'GET' });
  const cache = caches.default;
  const rangeHeader = c.req.header('Range');

  try {
    const hit = await cache.match(cacheKey);
    if (hit && !rangeHeader) {
      const h = new Headers(hit.headers);
      h.set('X-Cache', 'HIT');
      return new Response(hit.body, { status: 200, headers: h });
    }
  } catch {
    // 缓存不可用不致命：直接回源，别把读缓存的异常抛给用户
  }

  const range = rangeHeader && fullSize > 0 ? parseRange(rangeHeader, fullSize) : null;
  const obj = await env_R2_get(c.env, r2Key, range ?? undefined);
  if (!obj) {
    return c.json({ success: false, error: '安装包文件已不存在，请重新发布' }, 410);
  }

  const headers = new Headers({
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Disposition': `attachment; filename="${release.fileName}"`,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'public, max-age=86400',
    'X-Cache': 'MISS'
  });

  if (range && 'offset' in range && typeof range.offset === 'number' && typeof range.length === 'number') {
    // 断点续传：206 + Content-Range。完整大小取发布清单，range 模式下 obj.size 不可用
    const start = range.offset;
    const end = start + range.length - 1;
    headers.set('Content-Range', `bytes ${start}-${end}/${fullSize}`);
    headers.set('Content-Length', String(range.length));
    return new Response(obj.body, { status: 206, headers });
  }

  headers.set('Content-Length', String(obj.size ?? fullSize));

  // tee 分流：一路流式给客户端，一路喂边缘缓存。
  // 绝不能 arrayBuffer() 全量入内存 —— 37MB Buffer 既踩 Workers 内存上限，
  // 也让首字节时间白白等一整个文件读完（文件头注释里就是这么写的）。
  const [toClient, toCache] = obj.body.tee();
  try {
    const cacheable = new Headers(headers);
    cacheable.delete('X-Cache');
    c.executionCtx?.waitUntil(
      cache.put(cacheKey, new Response(toCache, { headers: cacheable })).catch(() => undefined)
    );
  } catch {
    // 写缓存失败不影响本次下载
  }

  return new Response(toClient, { headers });
});

/** 解析 `bytes=0-1023` / `bytes=1024-` / `bytes=-512`，解析不了就返回 null（降级成完整下载） */
function parseRange(header: string, total: number): R2Range | null {
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m) return null;
  const [, startStr, endStr] = m;
  if (startStr === '' && endStr === '') return null;

  if (startStr === '') {
    // `bytes=-512` 要的是末尾 512 字节。统一换算成 offset/length，
    // 让下游只有一条 206 分支，避免 suffix 形式被误当成完整下载。
    const suffix = Math.min(Number(endStr), total);
    if (!Number.isFinite(suffix) || suffix <= 0) return null;
    return { offset: total - suffix, length: suffix };
  }

  const offset = Number(startStr);
  if (!Number.isFinite(offset) || offset >= total) return null;
  const end = endStr === '' ? total - 1 : Math.min(Number(endStr), total - 1);
  if (!Number.isFinite(end) || end < offset) return null;
  return { offset, length: end - offset + 1 };
}

/** 单独包一层：R2 get 抛错时统一降级成 410，避免把内部异常直接抛给客户端 */
async function env_R2_get(
  env: Env,
  key: string,
  range?: R2Range
): Promise<R2ObjectBody | null> {
  try {
    return range ? await env.RECORDINGS.get(key, { range }) : await env.RECORDINGS.get(key);
  } catch (e) {
    console.error('[app-update] R2 读取失败:', e instanceof Error ? e.message : e);
    return null;
  }
}
