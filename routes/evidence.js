const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const fs = require('fs');
const db = require('../database/db');
const store = require('../services/recordingStore');
const { requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const { toBeijing, toBeijingRows } = require('../services/timeFormat');

// 一键导出老人《反诈报案维权证据包》数据（需登录态 + 绑定关系校验）
router.get('/export/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = req.params.elderId;

  // 聚合查询老人信息、扣款流水、风险通话、轨迹、录音存证
  db.get("SELECT u.*, COALESCE(NULLIF(u.mobile, ''), u.phone) as display_phone, b.name as family_name, COALESCE(NULLIF(b.mobile, ''), b.phone) as family_phone FROM users u LEFT JOIN users b ON u.bound_user_id = b.id WHERE u.id = ?", [elderId], (err, elder) => {
    if (err || !elder) return res.status(404).json({ error: '找不到老人账户信息' });

    db.all("SELECT * FROM payments WHERE elder_id = ? ORDER BY id DESC", [elderId], (err, payments) => {
      db.all("SELECT * FROM locations WHERE elder_id = ? ORDER BY id DESC LIMIT 20", [elderId], (err, locations) => {
        db.all("SELECT * FROM risk_events WHERE elder_id = ? AND event_type = 'CALL_RISK' ORDER BY id DESC", [elderId], (err, callRisks) => {
          db.all("SELECT * FROM recordings WHERE elder_id = ? ORDER BY id ASC", [elderId], (err, recordings) => {

          const evidencePackage = {
            metadata: buildMetadata(elder, recordings || []),
            elder_info: {
              name: elder.name,
              phone: elder.display_phone,
              guardian_name: elder.family_name || '已绑定防诈监护人',
              guardian_phone: elder.family_phone || '未知'
            },
            payment_records: toBeijingRows(payments),
            location_logs: toBeijingRows(locations),
            suspicious_calls: toBeijingRows(callRisks).map(c => ({
              ...c,
              details: c.details ? JSON.parse(c.details) : {}
            })),
            audio_recordings: buildAudioSection(recordings || [])
          };

          res.json({ success: true, data: evidencePackage });
          });
        });
      });
    });
  });
});

/**
 * 证据包元信息。
 *
 * checksum 之前是 `HASH_${Date.now().toString(16)}` —— 纯装饰：
 * 每次导出都不一样，既不能复算也不能证明内容未被改动，对办案毫无价值。
 * 现在改成对证据包核心内容做真实 SHA-256，
 * 民警拿到材料后用同样算法复算即可验证完整性。
 */
function buildMetadata(elder, recordings) {
  const parts = [
    `elder:${elder.id}:${elder.name}:${elder.display_phone || ''}`,
    `recordings:${recordings.length}`,
    ...recordings.map(r => `rec:${r.id}:${r.sha256 || ''}`)
  ].join('|');

  return {
    title: "电信诈骗/非法会销涉案电子证据集",
    generated_at: new Date().toISOString(),
    generated_at_beijing: toBeijing(new Date()),
    system_version: "1.1.0-SQLite",
    algorithm: "SHA-256",
    // 覆盖老人身份 + 全部录音编号与文件摘要
    checksum: crypto.createHash('sha256').update(parts).digest('hex'),
    checksum_note: "对守护对象身份与全部录音文件的 SHA-256 摘要计算所得，可用同样算法复算验证"
  };
}

/** 录音存证部分：文件摘要 + 转写 + 研判结论齐备，才算能用的报警材料 */
function buildAudioSection(recordings) {
  return {
    total: recordings.length,
    fraudCount: recordings.filter(r => r.fraud_status === 'FRAUD').length,
    suspectCount: recordings.filter(r => r.fraud_status === 'SUSPECT').length,
    keptAsEvidence: recordings.filter(r => r.keep_as_evidence).length,
    note: "音频文件本体请使用「一键打包下载」获取 zip（含逐条证据清单）；此处为文字索引与摘要。",
    items: toBeijingRows(recordings).map(r => {
      const filePath = store.resolveStoredPath(r.file_name);
      return {
        id: r.id,
        session_id: r.session_id,
        segment_index: r.segment_index,
        reason: r.reason,
        reason_desc: r.reason === 'SOS'
          ? '老人按下紧急求助时触发'
          : `老人进入子女登记的敏感地点「${r.place_name || ''}」时触发`,
        place_name: r.place_name,
        recorded_at: r.recorded_at,
        duration_seconds: Math.round((r.duration_ms || 0) / 1000),
        file_name: r.file_name,
        file_present: !!(filePath && fs.existsSync(filePath)),
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
        fraud_labels: r.fraud_labels ? JSON.parse(r.fraud_labels) : [],
        suspect_role: r.suspect_role,
        keep_as_evidence: !!r.keep_as_evidence,
        retention_until: r.retention_until,
        cleanup_reason: r.cleanup_reason,
        reviewed_by_family: !!r.reviewed_by_family
      };
    })
  };
}

module.exports = router;
