/**
 * /api/app-update 路由（对齐本地 routes/appUpdate.js）。
 *
 * 与本地后端的唯一差别是"发布清单从哪读"：本地读 dist/releases.json，
 * 这里读 R2 的 releases/latest.json（由 scripts/publish-release.js --upload 写入）。
 * 契约字段逐字一致，tests/contract/app-update.contract.test.js 两个后端跑同一套用例。
 *
 * 两个刻意的设计：
 *  1. 清单在 KV 里缓存 5 分钟。App 每次冷启动都会查一次版本，直接打 R2 的话
 *     读操作次数随装机量线性增长，而版本号一天也未必变一次；
 *  2. APK 走 R2 对象流式返回，不读进内存再吐 —— 24MB 的 Buffer 既踩 Workers
 *     内存限制，也会让首字节时间白白等一整个文件读完。
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
  const obj = await env_R2_get(c.env, `releases/${release.fileName}`);
  if (!obj) {
    return c.json({ success: false, error: '安装包文件已不存在，请重新发布' }, 410);
  }

  return new Response(obj.body, {
    headers: {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(obj.size ?? release.sizeBytes ?? 0),
      'Content-Disposition': `attachment; filename="${release.fileName}"`,
      'Cache-Control': 'public, max-age=86400'
    }
  });
});

/** 单独包一层：R2 get 抛错时统一降级成 410，避免把内部异常直接抛给客户端 */
async function env_R2_get(env: Env, key: string): Promise<R2ObjectBody | null> {
  try {
    return await env.RECORDINGS.get(key);
  } catch (e) {
    console.error('[app-update] R2 读取失败:', e instanceof Error ? e.message : e);
    return null;
  }
}
