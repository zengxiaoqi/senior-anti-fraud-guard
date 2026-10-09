/**
 * 录音存证链路（对齐本地 routes/recordings.js，P4）。
 *
 * 完整链路：老人端分段录音 → POST /upload（multipart，sha256 幂等）→ R2 落地
 *   → waitUntil 异步：ASR 转写 → AI 研判 → 决定 keep_as_evidence 与清理期限
 *   → 子女端 list 拉取 → stream 收听（Range）→ pack 打包带走。
 *
 * 鉴权模型与本地一致：
 *  - /upload 与 /api/events/report 同信任模型保持开放（但校验 elderId 真实存在，
 *    否则产生"孤儿录音"：子女端永远查不到，极难排查）；
 *  - list / stream / pack / delete / review 走 X-Auth-Token + 绑定校验；
 *    stream 的播放令牌由 list 签发（MediaPlayer 不带自定义头）。
 *
 * 存储差异：file_name 列存 R2 object key（列名不动，客户端不感知）；
 * 上传响应不等待分析（本地为 fire-and-forget，Workers 用 waitUntil 承接）。
 */
import { Hono } from 'hono';
import { createHash, randomBytes } from 'node:crypto';
import { requireFamilyAuth, requireBoundElder, type AppEnv } from '../middleware/auth';
import * as dao from '../db/dao/recordings';
import type { RecordingRow } from '../db/types';
import * as rq from '../lib/recordingQuery';
import * as geo from '../lib/geo';
import { canAccessElder } from '../services/tokenAuth';
import { sign as signPlayToken, verify as verifyPlayToken } from '../lib/signToken';
import { buildZip } from '../lib/zip';
import { transcribe as asrTranscribe } from '../lib/asr';
import { analyzeTranscript, safeRetentionDays } from '../lib/fraudDetector';
import { toBeijing, toBeijingRows } from '../lib/timeFormat';
import { notifyFamily, getRecordingState } from '../lib/hub';
import type { Env } from '../env';

export const recordingsRoutes = new Hono<AppEnv>();

const MAX_FILE_SIZE = 60 * 1024 * 1024; // 单段上限 60MB（录音端单段 50MB 兜底）
const EXT_WHITELIST = ['.m4a', '.mp4', '.aac', '.mp3', '.amr', '.3gp', '.wav', '.ogg'];

// ──────────────────────────────────────────
//  工具
// ──────────────────────────────────────────

/** 只保留白名单扩展名，兜底给 .m4a（杜绝老人端传入 "../../x" 类路径） */
function pickExtension(originalName: string): string {
  const m = /\.([a-z0-9]+)$/i.exec(String(originalName || ''));
  const ext = m ? '.' + m[1].toLowerCase() : '';
  return EXT_WHITELIST.includes(ext) ? ext : '.m4a';
}

/** 生成安全的 R2 object key：recordings/{elderId}/{UTC时间戳}_{随机8位}{ext} */
function buildStoredKey(elderId: number, originalName: string): string {
  const ext = pickExtension(originalName);
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '_');
  const rand = randomBytes(4).toString('hex');
  return `recordings/${elderId}/${stamp}_${rand}${ext}`;
}

function sha256Hex(buf: ArrayBuffer): string {
  return createHash('sha256').update(new Uint8Array(buf)).digest('hex');
}

function parseDateTime(raw: unknown): string | null {
  if (!raw) return null;
  const d = new Date(String(raw));
  return isNaN(d.getTime()) ? null : d.toISOString();
}

function toDate(raw: unknown): Date {
  const d = raw ? new Date(String(raw)) : new Date();
  return isNaN(d.getTime()) ? new Date() : d;
}

function sanitizeName(s: unknown): string {
  return String(s || '').replace(/[\\/:*?"<>|]/g, '_').trim() || '未分类';
}

function safeParseLabels(raw: unknown): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(String(raw));
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

interface UploadMeta {
  elderId: number;
  sessionId: string;
  segmentIndex: number;
  reason: string;
  durationMs: number;
  recordedAt: string | null;
  clientSha: string;
  placeName: string;
  latitude: number | null;
  longitude: number | null;
  address: string;
}

/** 后台异步：转文字 → 研判 → 决定去留（对齐本地 processRecordingAsync） */
async function processRecordingAsync(
  env: Env,
  id: number,
  r2Key: string,
  mimeType: string,
  context: { reason: string; place_name: string; session_id: string }
): Promise<void> {
  const obj = await env.RECORDINGS.get(r2Key);
  if (!obj) return;
  const audio = await obj.arrayBuffer();

  const retentionDays = safeRetentionDays(env);

  // 1. 转写
  const asrResult = await asrTranscribe(env, audio, mimeType);

  if (!asrResult.ok) {
    // 转写失败不能判 SAFE 删证据——老人可能正在被骗中，统一 SUSPECT 临时保留
    const retention = new Date(Date.now() + retentionDays * 86400 * 1000).toISOString();
    const elderIdRow = await env.DB.prepare('SELECT elder_id FROM recordings WHERE id = ?').bind(id)
      .first<{ elder_id: number }>();
    await dao.markTranscriptFailed(env, id, {
      status: asrResult.status === 'SKIPPED' ? 'SKIPPED' : 'FAILED',
      error: asrResult.error || '',
      verdict:
        `转文字未完成（${asrResult.error || '原因未知'}），无法判定内容。` +
        `为避免误删证据，临时保留 ${retentionDays} 天，子女端可直接收听人工判断。`,
      retentionUntil: retention
    });
    if (elderIdRow) {
      await notifyFamily(env, elderIdRow.elder_id, {
        type: 'RECORDING_ANALYZED',
        data: { id, transcriptStatus: asrResult.status }
      });
    }
    return;
  }

  // 2. 研判
  let analysis;
  try {
    analysis = await analyzeTranscript(env, asrResult.text || '', context);
  } catch (e) {
    analysis = {
      status: 'SUSPECT' as const,
      score: 0,
      labels: [],
      role: null,
      verdict: `AI 研判异常：${(e as Error).message}。临时保留证据，待子女端人工复核。`
    };
  }

  // 3. 落地：keep_as_evidence / retention_until 决定文件生命周期（FRAUD 长期保留）
  const keep = analysis.status === 'FRAUD' ? 1 : 0;
  const retentionUntil =
    analysis.status === 'SAFE' || analysis.status === 'SUSPECT'
      ? new Date(Date.now() + retentionDays * 86400 * 1000).toISOString()
      : null;
  const cleanupReason =
    analysis.status === 'SAFE'
      ? `AI 研判未发现诈骗特征（引擎：${analysis.engine || 'rules'}），按策略仅短期保留 ${retentionDays} 天`
      : null;

  await dao.saveAnalysisResult(env, id, {
    transcript: asrResult.text || '',
    fraudStatus: analysis.status,
    fraudScore: analysis.score,
    fraudVerdict: analysis.verdict,
    fraudLabels: JSON.stringify(analysis.labels || []),
    suspectRole: analysis.role,
    keepAsEvidence: keep,
    retentionUntil,
    cleanupReason
  });

  const elderIdRow = await env.DB.prepare('SELECT elder_id FROM recordings WHERE id = ?').bind(id)
    .first<{ elder_id: number }>();
  if (elderIdRow) {
    await notifyFamily(env, elderIdRow.elder_id, {
      type: 'RECORDING_ANALYZED',
      data: {
        id,
        fraudStatus: analysis.status,
        score: analysis.score,
        labels: analysis.labels || [],
        verdict: analysis.verdict,
        keepAsEvidence: keep,
        transcript: asrResult.text
      }
    });
  }
}

// ──────────────────────────────────────────
//  1. 老人端上传（multipart）
// ──────────────────────────────────────────

recordingsRoutes.post('/upload', async (c) => {
  let body: Record<string, string | File>;
  try {
    body = await c.req.parseBody();
  } catch {
    return c.json({ success: false, error: '文件上传失败' }, 400);
  }

  const file = body['file'];
  if (!(file instanceof File)) {
    return c.json({ success: false, error: '缺少录音文件' }, 400);
  }
  const okType = /^audio\//.test(file.type) || /\.(m4a|mp4|aac|mp3|amr|3gp|wav|ogg)$/i.test(file.name || '');
  if (!okType) return c.json({ success: false, error: '只接受音频文件' }, 400);
  if (file.size > MAX_FILE_SIZE) {
    return c.json({ success: false, error: '录音文件超过 60MB 上限' }, 400);
  }

  const elderId = parseInt(String(body['elderId'] ?? ''), 10);
  if (!elderId) return c.json({ success: false, error: '缺少 elderId' }, 400);

  // 校验老人账号真实存在，防孤儿录音（子女端按 boundElderId 永远查不到）
  const user = await c.env.DB.prepare("SELECT id FROM users WHERE id = ? AND role = 'elder'")
    .bind(elderId)
    .first<{ id: number }>();
  if (!user) {
    return c.json(
      { success: false, error: `老人账号 ${elderId} 不存在，请先在「我的」完成账号激活` },
      400
    );
  }

  const meta: UploadMeta = {
    elderId,
    sessionId: String(body['sessionId'] ?? '').slice(0, 64) || `S_${Date.now()}`,
    segmentIndex: parseInt(String(body['segmentIndex'] ?? ''), 10) || 1,
    reason: ['SOS', 'GEOFENCE'].includes(String(body['reason'])) ? String(body['reason']) : 'SOS',
    durationMs: parseInt(String(body['durationMs'] ?? ''), 10) || 0,
    recordedAt: parseDateTime(body['recordedAt']),
    clientSha: String(body['sha256'] ?? '').toLowerCase(),
    placeName: String(body['placeName'] ?? ''),
    latitude: parseFloat(String(body['latitude'] ?? '')) || null,
    longitude: parseFloat(String(body['longitude'] ?? '')) || null,
    address: String(body['address'] ?? '')
  };

  // 幂等：同一段录音重传（断网队列重试）直接返回既有记录
  if (meta.clientSha) {
    const existing = await dao.findBySha(c.env, elderId, meta.clientSha);
    if (existing) {
      // 文件已被清理过（如 SAFE 到期）→ 当新文件重新入库
      const head = await c.env.RECORDINGS.head(existing.file_name);
      if (head) {
        return c.json({
          success: true,
          data: { id: existing.id, fileName: existing.file_name, duplicated: true }
        });
      }
    }
  }

  const audioBuf = await file.arrayBuffer();
  const sha256 = sha256Hex(audioBuf);
  const r2Key = buildStoredKey(elderId, file.name);
  const mimeType = file.type || 'audio/mp4';

  try {
    await c.env.RECORDINGS.put(r2Key, audioBuf, {
      httpMetadata: { contentType: mimeType }
    });
  } catch (e) {
    return c.json({ success: false, error: `录音存储失败：${(e as Error).message}` }, 500);
  }

  let id: number;
  try {
    id = await dao.insertRecording(c.env, {
      elderId: meta.elderId,
      sessionId: meta.sessionId,
      segmentIndex: meta.segmentIndex,
      reason: meta.reason,
      placeName: meta.placeName || null,
      fileName: r2Key,
      originalName: file.name || null,
      durationMs: meta.durationMs,
      sizeBytes: audioBuf.byteLength,
      sha256,
      mimeType,
      recordedAt: meta.recordedAt || new Date().toISOString(),
      latitude: meta.latitude,
      longitude: meta.longitude,
      address: meta.address || null
    });
  } catch (e) {
    // 入库失败回滚 R2 对象（对齐本地"insert 失败删文件"）
    try { await c.env.RECORDINGS.delete(r2Key); } catch { /* ignore */ }
    return c.json({ success: false, error: (e as Error).message }, 500);
  }

  // 通知子女端"新录音到了"；转写研判绝不阻塞上传响应
  c.executionCtx.waitUntil(
    notifyFamily(c.env, meta.elderId, {
      type: 'RECORDING_UPLOADED',
      data: { id, reason: meta.reason, segmentIndex: meta.segmentIndex, sessionId: meta.sessionId }
    })
  );
  c.executionCtx.waitUntil(
    processRecordingAsync(c.env, id, r2Key, mimeType, {
      reason: meta.reason,
      place_name: meta.placeName,
      session_id: meta.sessionId
    })
  );

  return c.json({
    success: true,
    data: { id, fileName: r2Key, sha256, sizeBytes: audioBuf.byteLength, duplicated: false }
  });
});

// ──────────────────────────────────────────
//  2. 录音列表
// ──────────────────────────────────────────

recordingsRoutes.get('/list/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = parseInt(c.req.param('elderId'), 10);
  const sessionGroup = c.req.query('group') === '1';

  // 与本地 routes/recordings.js 同一套口径（services/recordingQuery.js 的 TS 移植）：
  // SQL 两步分页 —— 先按会话聚合切页，再用 session_id 取明细，切页不拉全量。
  const where = rq.buildListWhere({
    q: c.req.query('q'),
    range: c.req.query('range'),
    level: c.req.query('level'),
    evidence: c.req.query('evidence') === '1'
  });
  const { limit, offset } = rq.normalizeListPaging(c.req.query() as unknown as Record<string, unknown>);

  const sessionQuery = rq.buildSessionQuery(elderId, where, limit, offset);
  const sessionRows = (
    await c.env.DB.prepare(sessionQuery.sql).bind(...(sessionQuery.params as unknown[])).all<{
      session_id: string;
      segment_count: number;
      total_duration_ms: number;
      started_at: string;
      last_id: number;
    }>()
  ).results ?? [];
  const sessionIds = sessionRows.map((r) => String(r.session_id));

  // 统计必须独立 COUNT 且不带 LIMIT：否则翻到第二页时标题上的
  // 「共 17 段，其中 2 段诈骗」会随翻页跳变。
  const countOne = async (q: { sql: string; params: unknown[] }): Promise<number> => {
    const row = await c.env.DB.prepare(q.sql).bind(...(q.params as unknown[])).first<{ n: number }>();
    return Number(row?.n ?? 0);
  };
  const stats = {
    totalSessions: await countOne(rq.buildSessionCountQuery(elderId, where)),
    totalRecordings: await countOne(rq.buildRecordingCountQuery(elderId, where)),
    fraudCount: await countOne(rq.buildFraudCountQuery(elderId, where))
  };

  const detailQuery = rq.buildSessionDetailQuery(elderId, sessionIds);
  const rows = (
    await c.env.DB.prepare(detailQuery.sql).bind(...(detailQuery.params as unknown[])).all<RecordingRow>()
  ).results ?? [];
  const authUserId = c.get('authUserId');

  const formatted = toBeijingRows(rows as unknown as Array<Record<string, unknown>>).map((r) => {
    const { token, expiresAt } = signPlayToken(c.env, Number(r.id), authUserId);
    return {
      id: r.id,
      sessionId: r.session_id,
      segmentIndex: r.segment_index,
      reason: r.reason,
      reasonLabel: r.reason === 'SOS' ? '一键紧急求助' : `进入敏感地点「${r.place_name || '未知'}」`,
      placeName: r.place_name,
      fileName: r.file_name,
      durationMs: r.duration_ms,
      sizeBytes: r.size_bytes,
      sha256: r.sha256,
      recordedAt: r.recorded_at,
      address: r.address,
      latitude: r.latitude,
      longitude: r.longitude,
      transcript: r.transcript,
      transcriptStatus: r.transcript_status,
      transcriptError: r.transcript_error,
      fraudStatus: r.fraud_status,
      fraudScore: r.fraud_score,
      fraudVerdict: r.fraud_verdict,
      fraudLabels: safeParseLabels(r.fraud_labels),
      suspectRole: r.suspect_role,
      keepAsEvidence: !!r.keep_as_evidence,
      retentionUntil: r.retention_until,
      cleanupReason: r.cleanup_reason,
      reviewedByFamily: !!r.reviewed_by_family,
      // 播放器直接用这个 URL 发 GET，不带自定义头，所以必须带签名
      streamUrl: `/api/recordings/stream/${r.id}?token=${encodeURIComponent(token)}`,
      streamTokenExpiresAt: expiresAt
    };
  });

  // 坐标串名称补全（对齐本地 Task 3 与 workers geofence.ts 的既有做法）：
  // place_name 是老人端上传时带上来的原值，坐标串直接进标题子女认不出是哪个地点。
  // 只把真实地图地名（source=geo）写回库，推断描述只用于本次响应、不覆盖原值。
  const needGuess = formatted.filter(
    (r) => r.reason !== 'SOS'
      && !geo.guess.looksLikeRealAddress(String(r.placeName || ''))
      && Number.isFinite(Number(r.latitude)) && Number(r.latitude) !== 0
      && Number.isFinite(Number(r.longitude)) && Number(r.longitude) !== 0
  );
  await Promise.all(
    needGuess.map(async (r) => {
      try {
        const { name, source } = await geo.describePlace(
          c.env,
          Number(r.latitude),
          Number(r.longitude),
          elderId
        );
        if (!name) return;
        r.placeName = name;
        r.reasonLabel = `进入敏感地点「${name}」`;
        if (source === 'geo') {
          c.executionCtx.waitUntil(
            c.env.DB.prepare('UPDATE recordings SET place_name = ? WHERE id = ?')
              .bind(name, r.id)
              .run()
          );
        }
      } catch {
        // 补全失败保持原值：列表不能因为地图服务挂了而 500
      }
    })
  );

  if (!sessionGroup) {
    return c.json({
      success: true,
      data: { recordings: formatted, total: stats.totalRecordings, ...stats }
    });
  }

  // 按会话聚合。一次连续录音的多段归到一起，子女端看得更清楚。
  // 顺序沿用第一步查出的会话顺序（已按 MAX(id) DESC 排好），
  // 不靠 Map 的插入顺序碰运气。
  const metaBySession = new Map(sessionRows.map((r) => [String(r.session_id), r]));
  const sessions: Record<string, unknown>[] = [];
  for (const r of formatted) {
    const sessionKey = String(r.sessionId);
    let g = sessions.find((s) => s.sessionId === sessionKey);
    if (!g) {
      const meta = metaBySession.get(sessionKey);
      g = {
        sessionId: r.sessionId,
        reason: r.reason,
        reasonLabel: r.reasonLabel,
        placeName: r.placeName,
        startedAt: (meta?.started_at as string) || r.recordedAt,
        segmentCount: 0,
        totalDurationMs: 0,
        isFraud: false,
        isSuspect: false,
        hasTranscript: false,
        recordings: []
      };
      sessions.push(g);
    }
    g.segmentCount = Number(g.segmentCount) + 1;
    g.totalDurationMs = Number(g.totalDurationMs) + (Number(r.durationMs) || 0);
    (g.recordings as unknown[]).push(r);
    if (r.fraudStatus === 'FRAUD') g.isFraud = true;
    if (r.fraudStatus === 'SUSPECT' || r.fraudStatus === 'FAILED') g.isSuspect = true;
    if (r.transcript) g.hasTranscript = true;
  }

  return c.json({
    success: true,
    data: {
      sessions,
      ...stats,
      hasMore: offset + sessionIds.length < stats.totalSessions,
      appliedFilters: {
        q: String(c.req.query('q') || '').trim(),
        range: rq.resolveRange(c.req.query('range')),
        level: rq.resolveLevel(c.req.query('level')),
        evidence: c.req.query('evidence') === '1'
      }
    }
  });
});

// ──────────────────────────────────────────
//  3. 流式播放（支持 Range，播放器拖动进度条必需）
// ──────────────────────────────────────────

recordingsRoutes.get('/stream/:id', async (c) => {
  const id = parseInt(c.req.param('id'), 10);
  if (!id) return c.json({ success: false, error: '录音编号无效' }, 400);

  // 令牌由已鉴权的 list 接口签发，HMAC 覆盖 rid/uid/exp 整个 payload
  const check = verifyPlayToken(c.env, c.req.query('token'), id);
  if (!check.ok) {
    return c.json({ success: false, error: check.error }, 401);
  }

  const row = await dao.findById(c.env, id);
  if (!row) return c.json({ success: false, error: '录音不存在' }, 404);

  const obj = await c.env.RECORDINGS.get(row.file_name);
  if (!obj) {
    return c.json({ success: false, error: '录音文件已被清理，如需留存请及时导出' }, 410);
  }
  const mime = row.mime_type || 'audio/mp4';
  const rangeHeader = c.req.header('Range');

  if (rangeHeader) {
    // 解析语义与本地逐字对齐（含"end 空段补到最后"的行为）
    const m = /bytes=(\d*)-(\d*)/.exec(rangeHeader);
    const start = m && m[1] ? parseInt(m[1], 10) : 0;
    const end = m && m[2] ? parseInt(m[2], 10) : obj.size - 1;
    if (isNaN(start) || isNaN(end) || start > end || end >= obj.size) {
      return new Response(null, {
        status: 416,
        headers: { 'Content-Range': `bytes */${obj.size}` }
      });
    }
    const ranged = await c.env.RECORDINGS.get(row.file_name, {
      range: { offset: start, length: end - start + 1 }
    });
    if (!ranged) return c.json({ success: false, error: '录音文件已被清理，如需留存请及时导出' }, 410);
    return new Response(ranged.body, {
      status: 206,
      headers: {
        'Content-Type': mime,
        'Content-Range': `bytes ${start}-${end}/${obj.size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': String(end - start + 1),
        'Cache-Control': 'private, max-age=300'
      }
    });
  }

  return new Response(obj.body, {
    status: 200,
    headers: {
      'Content-Type': mime,
      'Content-Length': String(obj.size),
      'Accept-Ranges': 'bytes',
      'Content-Disposition': `inline; filename="${encodeURIComponent(row.original_name || row.file_name)}"`,
      'Cache-Control': 'private, max-age=300'
    }
  });
});

// ──────────────────────────────────────────
//  4. 一键打包下载（报警材料）
// ──────────────────────────────────────────

recordingsRoutes.get('/pack/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = parseInt(c.req.param('elderId'), 10);
  const onlyEvidence = c.req.query('scope') === 'evidence';

  const rows = await dao.listForPack(c.env, elderId, onlyEvidence);
  if (!rows || rows.length === 0) {
    return c.json({ success: false, error: '暂无可打包的录音材料' }, 404);
  }

  const entries: Array<{ name: string; data: Uint8Array | string; date?: Date }> = [];
  const manifest: Array<Record<string, unknown>> = [];

  for (const r of rows) {
    const obj = await c.env.RECORDINGS.get(r.file_name);
    if (!obj) continue; // 已被清理，跳过
    const data = new Uint8Array(await obj.arrayBuffer());
    const zoneName = sanitizeName(`${r.recorded_at || ''}`.slice(0, 10) || '未知日期');
    const base = `录音/${zoneName}/REC_${String(r.id).padStart(4, '0')}_${
      r.reason === 'SOS' ? '一键求助' : '围栏'
    }_第${r.segment_index}段.m4a`;
    entries.push({ name: base, data, date: toDate(r.recorded_at) });

    manifest.push({
      录音编号: r.id,
      会话: r.session_id,
      段序: r.segment_index,
      触发来源: r.reason === 'SOS' ? '老人一键紧急求助' : `进入敏感地点「${r.place_name || ''}」`,
      录音时间: toBeijing(toDate(r.recorded_at)),
      时长秒: Math.round((r.duration_ms || 0) / 1000),
      位置: r.address || '',
      文件SHA256: r.sha256 || '',
      转写状态: r.transcript_status,
      转写文本: r.transcript || '',
      诈骗研判: r.fraud_status,
      置信分: r.fraud_score,
      研判结论: r.fraud_verdict || '',
      命中特征: safeParseLabels(r.fraud_labels).join('、'),
      是否长期保留: r.keep_as_evidence ? '是' : '否',
      子女已复核: r.reviewed_by_family ? '是' : '否'
    });
  }

  if (entries.length === 0) {
    return c.json({ success: false, error: '录音文件均已被清理' }, 410);
  }

  const readme = [
    '═══════════════════════════════════════════════',
    '　长者防诈守护系统 · 环境录音存证清单',
    '═══════════════════════════════════════════════',
    `导出时间：${toBeijing(new Date())}`,
    `录音数量：${manifest.length} 段`,
    '',
    '【本材料说明】',
    '1. 每个 .m4a 文件对应清单中的一行，文件名的「录音编号」与清单编号一致；',
    '2. 清单中的 SHA-256 为该音频文件的完整摘要，公安机关可现场复算比对，',
    '   用于证明录音自导出后未被修改；',
    '3. 「诈骗研判」为系统自动分析结果，「是否长期保留」代表系统对该录音的',
    '   证据价值判断；非诈骗录音按策略短期留存，到期自动清理；',
    '4. 录音的合法性前提：老人在按下紧急求助，或进入家属事先登记的敏感地点时触发，',
    '   属其正当利益需要，并非常态化监听。',
    '',
    '───────────────  逐条明细  ───────────────',
    '',
    ...manifest.flatMap((m) => [
      `【录音 ${String(m['录音编号']).padStart(4, '0')}】`,
      ...Object.entries(m).map(([k, v]) => `  ${k}：${v === null || v === '' ? '（空）' : v}`),
      ''
    ])
  ].join('\n');

  entries.unshift({ name: '00_证据清单.txt', data: readme });
  entries.unshift({
    name: '01_证据清单.json',
    data: JSON.stringify(
      {
        生成时间: toBeijing(new Date()),
        系统: '长者防诈亲情守护系统',
        录音数量: manifest.length,
        明细: manifest
      },
      null,
      2
    )
  });

  const zip = buildZip(entries);
  const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
  const fileName = `反诈录音证据包_${elderId}_${stamp}.zip`;

  return new Response(zip as unknown as BodyInit, {
    status: 200,
    headers: {
      'Content-Type': 'application/zip',
      'Content-Length': String(zip.length),
      'Content-Disposition': `attachment; filename="evidence_${elderId}_${stamp}.zip"; filename*=UTF-8''${encodeURIComponent(fileName)}`
    }
  });
});

// ──────────────────────────────────────────
//  5. 人工复核 / 删除 / 状态
// ──────────────────────────────────────────

/** 子女端人工复核：推翻 AI 判定（把 SAFE 改成证据保留） */
recordingsRoutes.post('/:id/review', requireFamilyAuth, async (c) => {
  const id = parseInt(c.req.param('id'), 10);
  const body = await c.req.json().catch(() => ({}) as Record<string, unknown>);
  const keep = !!body.keep;
  const note = String(body.note || '').slice(0, 500);

  const row = await dao.findById(c.env, id);
  if (!row) return c.json({ success: false, error: '录音不存在' }, 404);

  const ok = await canAccessElder(c.env, c.get('authUserId'), row.elder_id);
  if (!ok) return c.json({ success: false, error: '无权复核该录音' }, 403);

  await dao.reviewRecording(c.env, id, {
    keep: keep ? 1 : 0,
    cleanupReason:
      note || (keep ? '子女端人工复核：判定为有效证据' : '子女端人工复核：判定无需保留'),
    note
  });
  c.executionCtx.waitUntil(
    notifyFamily(c.env, row.elder_id, { type: 'RECORDING_REVIEWED', data: { id, keepAsEvidence: keep } })
  );
  return c.json({ success: true, data: { id, keepAsEvidence: keep } });
});

/** 删除录音记录与文件 */
recordingsRoutes.delete('/:id', requireFamilyAuth, async (c) => {
  const id = parseInt(c.req.param('id'), 10);
  const row = await dao.findById(c.env, id);
  if (!row) return c.json({ success: false, error: '录音不存在' }, 404);

  const ok = await canAccessElder(c.env, c.get('authUserId'), row.elder_id);
  if (!ok) return c.json({ success: false, error: '无权删除该录音' }, 403);

  try { await c.env.RECORDINGS.delete(row.file_name); } catch { /* ignore */ }
  await dao.deleteRecording(c.env, id);
  c.executionCtx.waitUntil(
    notifyFamily(c.env, row.elder_id, { type: 'RECORDING_DELETED', data: { id } })
  );
  return c.json({ success: true, data: { id, deleted: true } });
});

/** 查询录音会话当前是否正在录音（子女端据此显示"停止录音"按钮） */
recordingsRoutes.get('/status/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = parseInt(c.req.param('elderId'), 10);
  const recording = await getRecordingState(c.env, elderId);
  return c.json({
    success: true,
    data: {
      elderId,
      recording,
      serverTime: toBeijing(new Date())
    }
  });
});
