/**
 * /api/evidence 路由（对齐本地 routes/evidence.js）。
 *
 * 一键导出《反诈报案维权证据包》数据。checksum 是对证据包核心内容
 * （老人身份 + 全部录音编号与文件摘要）的真实 SHA-256，可复算验证完整性。
 * file_present 在 Workers 版查 R2 对象是否存在（本地版查磁盘文件）。
 */
import { Hono } from 'hono';
import { createHash } from 'node:crypto';
import { requireFamilyAuth, requireBoundElder, type AppEnv } from '../middleware/auth';
import { toBeijingRows, toBeijing } from '../lib/timeFormat';

export const evidenceRoutes = new Hono<AppEnv>();

evidenceRoutes.get('/export/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = Number(c.req.param('elderId'));

  const elder = await c.env.DB.prepare(
    `SELECT u.*, COALESCE(NULLIF(u.mobile, ''), u.phone) as display_phone,
            b.name as family_name, COALESCE(NULLIF(b.mobile, ''), b.phone) as family_phone
     FROM users u LEFT JOIN users b ON u.bound_user_id = b.id
     WHERE u.id = ?`
  )
    .bind(elderId)
    .first<Record<string, any>>();
  if (!elder) return c.json({ error: '找不到老人账户信息' }, 404);

  const [payments, locations, callRisks, recordings] = await Promise.all([
    c.env.DB.prepare('SELECT * FROM payments WHERE elder_id = ? ORDER BY id DESC').bind(elderId).all(),
    c.env.DB.prepare('SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT 20').bind(elderId).all(),
    c.env.DB.prepare("SELECT * FROM risk_events WHERE elder_id = ? AND event_type = 'CALL_RISK' ORDER BY id DESC")
      .bind(elderId)
      .all(),
    c.env.DB.prepare('SELECT * FROM recordings WHERE elder_id = ? ORDER BY id ASC').bind(elderId).all()
  ]);

  const recRows = (recordings.results ?? []) as Array<Record<string, any>>;

  const evidencePackage = {
    metadata: await buildMetadata(elder, recRows),
    elder_info: {
      name: elder.name,
      phone: elder.display_phone,
      guardian_name: elder.family_name || '已绑定防诈监护人',
      guardian_phone: elder.family_phone || '未知'
    },
    payment_records: toBeijingRows(payments.results as Array<Record<string, unknown>>),
    location_logs: toBeijingRows(locations.results as Array<Record<string, unknown>>),
    suspicious_calls: toBeijingRows(callRisks.results as Array<Record<string, unknown>>).map((cr) => ({
      ...cr,
      details: cr.details ? JSON.parse(String(cr.details)) : {}
    })),
    audio_recordings: await buildAudioSection(c.env, recRows)
  };

  return c.json({ success: true, data: evidencePackage });
});

/** 证据包元信息：真实 SHA-256（民警可用同样算法复算），不再用 HASH_时间戳装饰值 */
async function buildMetadata(elder: Record<string, any>, recordings: Array<Record<string, any>>) {
  const parts = [
    `elder:${elder.id}:${elder.name}:${elder.display_phone || ''}`,
    `recordings:${recordings.length}`,
    ...recordings.map((r) => `rec:${r.id}:${r.sha256 || ''}`)
  ].join('|');

  return {
    title: '电信诈骗/非法会销涉案电子证据集',
    generated_at: new Date().toISOString(),
    generated_at_beijing: toBeijing(new Date()),
    system_version: '1.1.0-SQLite',
    algorithm: 'SHA-256',
    checksum: createHash('sha256').update(parts).digest('hex'),
    checksum_note: '对守护对象身份与全部录音文件的 SHA-256 摘要计算所得，可用同样算法复算验证'
  };
}

/** 录音存证部分：文件摘要 + 转写 + 研判结论齐备，才算能用的报警材料 */
async function buildAudioSection(env: import('../env').Env, recordings: Array<Record<string, any>>) {
  // file_present：Workers 版 file_name 存 R2 object key，存在性查 R2 head（条目 ≤200，开销可接受）
  const items = await Promise.all(
    toBeijingRows(recordings).map(async (r) => {
      let filePresent = false;
      try {
        filePresent = !!(r.file_name && (await env.RECORDINGS.head(String(r.file_name))));
      } catch {
        filePresent = false;
      }
      return {
        id: r.id,
        session_id: r.session_id,
        segment_index: r.segment_index,
        reason: r.reason,
        reason_desc:
          r.reason === 'SOS'
            ? '老人按下紧急求助时触发'
            : `老人进入子女登记的敏感地点「${r.place_name || ''}」时触发`,
        place_name: r.place_name,
        recorded_at: r.recorded_at,
        duration_seconds: Math.round((r.duration_ms || 0) / 1000),
        file_name: r.file_name,
        file_present: filePresent,
        // 报警材料里最关键的一行：可现场复算的文件摘要
        sha256: r.sha256,
        address: r.address,
        latitude: r.latitude,
        longitude: r.longitude,
        transcript: r.transcript,
        transcript_status: r.transcript_status,
        fraud_status: r.fraud_status,
        fraud_score: r.fraud_score,
        fraud_verdict: r.fraud_verdict,
        fraud_labels: r.fraud_labels ? JSON.parse(String(r.fraud_labels)) : [],
        suspect_role: r.suspect_role,
        keep_as_evidence: !!r.keep_as_evidence,
        retention_until: r.retention_until,
        cleanup_reason: r.cleanup_reason,
        reviewed_by_family: !!r.reviewed_by_family
      };
    })
  );

  return {
    total: recordings.length,
    fraudCount: recordings.filter((r) => r.fraud_status === 'FRAUD').length,
    suspectCount: recordings.filter((r) => r.fraud_status === 'SUSPECT').length,
    keptAsEvidence: recordings.filter((r) => r.keep_as_evidence).length,
    note: '音频文件本体请使用「一键打包下载」获取 zip（含逐条证据清单）；此处为文字索引与摘要。',
    items
  };
}
