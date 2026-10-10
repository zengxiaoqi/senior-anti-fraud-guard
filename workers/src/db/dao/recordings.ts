/**
 * recordings 表 DAO。语义对齐本地 routes/recordings.js + services/recordingCleanup.js。
 *
 * 关键约定（都是踩过坑的）：
 *  - file_name 在 Workers 版存 R2 object key（列名不动，客户端不感知存储差异）；
 *  - sha256 幂等重传：命中且文件仍存在 → 返回 duplicated:true；文件已被清理 → 重新入库；
 *  - 转写失败绝不判 SAFE 删证据，统一 SUSPECT + 临时保留（保留策略在 cleanupSweep）；
 *  - retention_until / recorded_at 是 ISO UTC 字符串，客户端已做双格式兼容；
 *  - reviewed_by_family 复核后清空 retention_until（复核过的不清）。
 */
import type { Env } from '../../env';
import type { RecordingRow } from '../types';

export async function findById(env: Env, id: number): Promise<RecordingRow | null> {
  return env.DB.prepare('SELECT * FROM recordings WHERE id = ?').bind(id).first<RecordingRow>();
}

/** 幂等检查：同 elder 同 sha256 的既有记录 */
export async function findBySha(
  env: Env,
  elderId: number,
  sha256: string
): Promise<Pick<RecordingRow, 'id' | 'file_name' | 'sha256'> | null> {
  return env.DB.prepare(
    'SELECT id, file_name, sha256 FROM recordings WHERE elder_id = ? AND sha256 = ? LIMIT 1'
  )
    .bind(elderId, sha256)
    .first<Pick<RecordingRow, 'id' | 'file_name' | 'sha256'>>();
}

export interface NewRecording {
  elderId: number;
  sessionId: string;
  segmentIndex: number;
  reason: string;
  placeName: string | null;
  fileName: string; // R2 object key
  originalName: string | null;
  durationMs: number;
  sizeBytes: number;
  sha256: string;
  mimeType: string;
  recordedAt: string;
  latitude: number | null;
  longitude: number | null;
  address: string | null;
}

export async function insertRecording(env: Env, meta: NewRecording): Promise<number> {
  const res = await env.DB.prepare(
    `INSERT INTO recordings
       (elder_id, session_id, segment_index, reason, place_name, file_name, original_name,
        duration_ms, size_bytes, sha256, mime_type, recorded_at, latitude, longitude, address)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  )
    .bind(
      meta.elderId,
      meta.sessionId,
      meta.segmentIndex,
      meta.reason,
      meta.placeName,
      meta.fileName,
      meta.originalName,
      meta.durationMs,
      meta.sizeBytes,
      meta.sha256,
      meta.mimeType,
      meta.recordedAt,
      meta.latitude,
      meta.longitude,
      meta.address
    )
    .run();
  return res.meta.last_row_id;
}

export async function listByElder(env: Env, elderId: number, onlyEvidence: boolean): Promise<RecordingRow[]> {
  const sql = onlyEvidence
    ? 'SELECT * FROM recordings WHERE elder_id = ? AND keep_as_evidence = 1 ORDER BY id DESC LIMIT 200'
    : 'SELECT * FROM recordings WHERE elder_id = ? ORDER BY id DESC LIMIT 200';
  const { results } = await env.DB.prepare(sql).bind(elderId).all<RecordingRow>();
  return results ?? [];
}

/** 证据包打包：按会话时间正序（对齐 pack 接口的 ORDER BY id ASC） */
export async function listForPack(env: Env, elderId: number, onlyEvidence: boolean): Promise<RecordingRow[]> {
  const sql = onlyEvidence
    ? 'SELECT * FROM recordings WHERE elder_id = ? AND keep_as_evidence = 1 ORDER BY id ASC'
    : 'SELECT * FROM recordings WHERE elder_id = ? ORDER BY id ASC';
  const { results } = await env.DB.prepare(sql).bind(elderId).all<RecordingRow>();
  return results ?? [];
}

export async function getR2Key(env: Env, id: number): Promise<string | null> {
  const row = await env.DB.prepare('SELECT file_name FROM recordings WHERE id = ?')
    .bind(id)
    .first<{ file_name: string }>();
  return row?.file_name ?? null;
}

/** cron 分析扫描：捞待转写记录（方案 B 主路径） */
export async function listPendingTranscripts(env: Env, limit = 20): Promise<RecordingRow[]> {
  const { results } = await env.DB.prepare(
    `SELECT * FROM recordings WHERE transcript_status = 'PENDING' ORDER BY id ASC LIMIT ?`
  )
    .bind(limit)
    .all<RecordingRow>();
  return results ?? [];
}

/** 转写失败/SKIPPED：标 SUSPECT 临时保留，绝不静默删证据 */
export async function markTranscriptFailed(
  env: Env,
  id: number,
  args: { status: 'FAILED' | 'SKIPPED'; error: string; verdict: string; retentionUntil: string }
): Promise<void> {
  await env.DB.prepare(
    `UPDATE recordings SET transcript_status = ?, transcript_error = ?, fraud_status = 'SUSPECT',
       fraud_verdict = ?, keep_as_evidence = 0, retention_until = ? WHERE id = ?`
  )
    .bind(args.status, args.error, args.verdict, args.retentionUntil, id)
    .run();
}

/** 转写 + 研判完成，一次性落地全部结果（对齐本地 processRecordingAsync 的 UPDATE） */
export async function saveAnalysisResult(
  env: Env,
  id: number,
  args: {
    transcript: string;
    fraudStatus: RecordingRow['fraud_status'];
    fraudScore: number;
    fraudVerdict: string;
    fraudLabels: string;
    suspectRole: string | null;
    keepAsEvidence: number;
    retentionUntil: string | null;
    cleanupReason: string | null;
  }
): Promise<void> {
  await env.DB.prepare(
    `UPDATE recordings SET transcript = ?, transcript_status = 'DONE',
       fraud_status = ?, fraud_score = ?, fraud_verdict = ?, fraud_labels = ?, suspect_role = ?,
       keep_as_evidence = ?, retention_until = ?, cleanup_reason = ? WHERE id = ?`
  )
    .bind(
      args.transcript,
      args.fraudStatus,
      args.fraudScore,
      args.fraudVerdict,
      args.fraudLabels,
      args.suspectRole,
      args.keepAsEvidence,
      args.retentionUntil,
      args.cleanupReason,
      id
    )
    .run();
}

/** 子女端人工复核：推翻 AI 判定，清空保留期 */
export async function reviewRecording(
  env: Env,
  id: number,
  args: { keep: number; cleanupReason: string; note: string }
): Promise<void> {
  await env.DB.prepare(
    `UPDATE recordings SET reviewed_by_family = 1, keep_as_evidence = ?,
       retention_until = NULL, cleanup_reason = ?,
       fraud_verdict = COALESCE(NULLIF(?, ''), fraud_verdict) WHERE id = ?`
  )
    .bind(args.keep, args.cleanupReason, args.note, id)
    .run();
}

/** 返回删除的行数；R2 对象由路由层按 file_name 删除 */
export async function deleteRecording(env: Env, id: number): Promise<number> {
  const res = await env.DB.prepare('DELETE FROM recordings WHERE id = ?').bind(id).run();
  return res.meta.changes ?? 0;
}

/** 保留策略清理（cleanupSweep 用）：捞已到期的非证据录音 */
export async function listExpiredForCleanup(env: Env, nowIso: string, limit = 100): Promise<RecordingRow[]> {
  const { results } = await env.DB.prepare(
    `SELECT id, elder_id, file_name, keep_as_evidence, reviewed_by_family FROM recordings
     WHERE keep_as_evidence = 0 AND reviewed_by_family = 0
       AND retention_until IS NOT NULL AND retention_until <= ?
     LIMIT ?`
  )
    .bind(nowIso, limit)
    .all<RecordingRow>();
  return results ?? [];
}
