/**
 * /api/app-update —— App 内自升级（免登录）。
 *
 * 为什么要有这套接口：本 App 不进应用商店，是自建分发（dist/ 里的 APK 手动发给
 * 老人/子女）。以前"升级"只能靠人工把 APK 发过去再教对方装一次，老人基本做不到，
 * 结果就是装的是半年前的旧版本，修好的问题在他们手机上依然存在。
 * 现在 App 里点一下就能自己装上新版本。
 *
 * 信任模型（与 /api/events/report 同级）：
 *  - /latest 免登录：版本号与更新说明不是敏感数据，且老人端在"尚未登记账号"时
 *    也必须能升上来（否则旧版本连注册流程都跑不通就永远卡死）；
 *  - /download 同样免登录，但**只服务发布清单里登记过的文件**，绝不按请求参数
 *    拼路径 —— 否则就是一个任意文件下载漏洞（?f=../../data.sqlite）。
 *
 * 事实源：`dist/releases.json`，由 `scripts/publish-release.js` 生成。
 * 服务端不自己扫目录猜版本：文件名只有 versionName，没有 versionCode，
 * 而决定"要不要升"的只能是 versionCode（1.10.0 的 code 一定大于 1.9.0 的，
 * 字符串比较则会得出 1.10.0 < 1.9.0 的错误结论）。
 *
 * 契约（与 workers/src/routes/appUpdate.ts 完全一致，tests/contract 双端校验）：
 *  GET /api/app-update/latest?versionCode=14
 *    → { success, hasUpdate, latest?: { versionCode, versionName, sizeBytes,
 *        sha256, changelog, force, downloadUrl, publishedAt }, current? }
 *  GET /api/app-update/download?versionCode=15  → APK 二进制
 */
const express = require('express');
const fs = require('fs');
const path = require('path');

const router = express.Router();

/** 发布清单路径：脚本生成，服务端只读 */
const DIST_DIR = path.join(__dirname, '..', 'dist');
const RELEASES_JSON = path.join(DIST_DIR, 'releases.json');

/**
 * 读发布清单。
 *
 * 读不到 / 解析失败一律当作"还没发布过升级包"，返回 null 而不是抛 500：
 * 升级是锦上添花的能力，它坏了绝不能连累守护主流程 —— 老人端的检查只是
 * 拿不到结果而已，守护服务照常在跑。
 */
function readReleases() {
  try {
    if (!fs.existsSync(RELEASES_JSON)) return null;
    const raw = fs.readFileSync(RELEASES_JSON, 'utf8');
    const json = JSON.parse(raw);
    if (!json || !json.latest || !json.latest.versionCode) return null;
    return json;
  } catch (e) {
    console.error('[app-update] 发布清单读取失败，按"未发布"处理:', e.message);
    return null;
  }
}

/** 按 versionCode 找一条发布记录（latest 优先，其次历史） */
function findRelease(releases, versionCode) {
  if (!releases) return null;
  if (Number(releases.latest.versionCode) === Number(versionCode)) return releases.latest;
  const history = Array.isArray(releases.history) ? releases.history : [];
  return history.find((r) => Number(r.versionCode) === Number(versionCode)) || null;
}

/** 下载直链。刻意给绝对路径：客户端拼 baseUrl 即可用，不必知道接口布局 */
function downloadUrlOf(versionCode) {
  return `/api/app-update/download?versionCode=${versionCode}`;
}

// ── 查询最新版本 ──────────────────────────────────────────────
// versionCode 是唯一比较依据。客户端传当前 versionCode，服务端只回答
// "有没有比你新的"，不做字符串版本号比较（1.10.0 vs 1.9.0 会翻车）。
router.get('/latest', (req, res) => {
  const currentCode = parseInt(req.query.versionCode, 10) || 0;
  const releases = readReleases();

  if (!releases) {
    return res.json({
      success: true,
      hasUpdate: false,
      reason: 'no_release_published',
      message: '服务端尚未发布升级包'
    });
  }

  const latest = releases.latest;
  const latestCode = Number(latest.versionCode);

  if (!latestCode || latestCode <= currentCode) {
    return res.json({
      success: true,
      hasUpdate: false,
      reason: 'already_latest',
      current: { versionCode: currentCode, latestCode }
    });
  }

  // 强制升级：客户端版本低于"最低可用版本"时，不给"稍后再说"的选项。
  // 用于老版本存在安全/数据问题时把存量设备拉上来。默认不强制。
  const minSupported = Number(latest.minSupportedCode || 0);
  const force = minSupported > 0 && currentCode < minSupported;

  return res.json({
    success: true,
    hasUpdate: true,
    force,
    latest: {
      versionCode: latestCode,
      versionName: String(latest.versionName || ''),
      sizeBytes: Number(latest.sizeBytes || 0),
      sha256: String(latest.sha256 || ''),
      changelog: String(latest.changelog || ''),
      force,
      publishedAt: String(latest.publishedAt || ''),
      downloadUrl: downloadUrlOf(latestCode)
    },
    current: { versionCode: currentCode }
  });
});

// ── 下载 APK ──────────────────────────────────────────────────
// 文件名永远来自发布清单，不来自请求参数 —— 请求只用来挑"哪一条记录"。
// 这是防目录穿越的关键：参数里写 ../ 也只会匹配不到记录而 404。
router.get('/download', (req, res) => {
  const releases = readReleases();
  if (!releases) {
    return res.status(404).json({ success: false, error: '服务端尚未发布升级包' });
  }

  const requested = parseInt(req.query.versionCode, 10);
  const release = Number.isNaN(requested)
    ? releases.latest
    : findRelease(releases, requested);

  if (!release || !release.fileName) {
    return res.status(404).json({ success: false, error: '找不到该版本的安装包' });
  }

  // 二次约束：解析后的真实路径必须仍在 dist/ 内。
  // 万一有人手改了 releases.json 塞进 ../，这里也能挡住。
  const abs = path.resolve(DIST_DIR, release.fileName);
  if (!abs.startsWith(path.resolve(DIST_DIR) + path.sep)) {
    return res.status(400).json({ success: false, error: '非法的安装包路径' });
  }
  if (!fs.existsSync(abs)) {
    // 清单里有、文件没了（手工清过 dist）：这不是客户端的错，
    // 但要说清楚，否则客户端只会看到"下载失败"反复重试。
    return res.status(410).json({ success: false, error: '安装包文件已不存在，请重新发布' });
  }

  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Length', fs.statSync(abs).size);
  res.setHeader('Content-Disposition', `attachment; filename="${release.fileName}"`);
  // APK 是不可变产物，同名即同内容，允许客户端/CDN 缓存
  res.setHeader('Cache-Control', 'public, max-age=86400');
  res.sendFile(abs);
});

module.exports = router;
