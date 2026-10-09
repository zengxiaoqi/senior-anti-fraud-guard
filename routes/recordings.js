// 录音存证链路 HTTP 接口
//
// 完整链路：
//   老人端分段录音 → POST /upload（multipart，断网重传靠 sha256 幂等）
//     → 落盘 uploads/recordings/ + 计算 SHA-256
//     → 异步转文字（ASR） → AI 诈骗研判 → 决定 keep_as_evidence 与清理期限
//     → 子女端 GET /list 拉列表 → GET /stream/:id 收听 → GET /pack 打包带走
//
// 鉴权模型沿用现有约定：
//   - /upload 是老人设备上报，与 /api/events/report 同信任模型，保持开放；
//     但要求携带 elderId + sessionId，且 sha256 用于去重，重复上传不会产生冗余文件。
//   - 其余接口（list / stream / pack / delete / review）必须带 X-Auth-Token 且校验绑定关系。
const express = require('express');
const router = express.Router();
const multer = require('multer');
const fs = require('fs');
const path = require('path');

const db = require('../database/db');
const store = require('../services/recordingStore');
const asr = require('../services/asr');
const { analyzeTranscript, SAFE_RETENTION_DAYS } = require('../services/fraudDetector');
const { buildZip } = require('../services/zipWriter');
const { sign, verify } = require('../services/signToken');
const { requireFamilyAuth, requireBoundElder } = require('../services/tokenAuth');
const { toBeijing, toBeijingRows } = require('../services/timeFormat');
const geo = require('../services/regeo');
const {
  buildListWhere, normalizeListPaging,
  buildSessionQuery, buildSessionDetailQuery,
  buildSessionCountQuery, buildRecordingCountQuery, buildFraudCountQuery,
  resolveRange, resolveLevel,
} = require('../services/recordingQuery');

// 老人端 WS 连接（由 server.js 注入），用于把"录音已上传/已研判/该清理了"推给子女
let hub = {
  notifyFamily: () => {},
  isElderOnline: () => false
};
router.setHub = (h) => { hub = h; };

// ──────────────────────────────────────────
//  1. 老人端上传（multipart）
// ──────────────────────────────────────────

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => {
      try {
        cb(null, store.ensureRecordingDir());
      } catch (e) {
        cb(e);
      }
    },
    // 关键：落盘名完全由服务端生成，杜绝老人端传入 ../ 造成的目录穿越。
    // 注意不能在这里拼 elderId —— multer 的 filename 回调早于 body 解析完成，
    // 此时 req.body 还是空对象，拼出来会是 "undefined_..."。
    // 老人维度由 recordings 表的 elder_id 关联，文件名只保证唯一可读。
    filename: (req, file, cb) => {
      try {
        cb(null, store.buildStoredName(file.originalname));
      } catch (e) {
        cb(e);
      }
    }
  }),
  limits: {
    fileSize: 60 * 1024 * 1024,  // 单段上限 60MB（录音端单段 50MB 兜底）
    files: 1
  },
  fileFilter: (req, file, cb) => {
    const okType = /^audio\//.test(file.mimetype) || /\.(m4a|mp4|aac|mp3|amr|3gp|wav|ogg)$/i.test(file.originalname || '');
    if (!okType) return cb(new Error('只接受音频文件'));
    cb(null, true);
  }
});

router.post('/upload', (req, res) => {
  upload.single('file')(req, res, (uploadErr) => {
    if (uploadErr) {
      const msg = uploadErr.code === 'LIMIT_FILE_SIZE'
        ? '录音文件超过 60MB 上限'
        : (uploadErr.message || '文件上传失败');
      return res.status(400).json({ success: false, error: msg });
    }
    if (!req.file) return res.status(400).json({ success: false, error: '缺少录音文件' });

    const elderId = parseInt(req.body.elderId, 10);
    if (!elderId) {
      store.deleteStoredFile(req.file.filename);
      return res.status(400).json({ success: false, error: '缺少 elderId' });
    }

    // 校验老人账号真实存在。
    // 不校验会产生"孤儿录音"：记录挂在一个不存在的 elder_id 上，
    // 子女端按自己的 boundElderId 查询永远查不到，表现为"录了但子女看不到"，
    // 极难排查。宁可当场 400 让老人端看到明确原因。
    db.get("SELECT id FROM users WHERE id = ? AND role = 'elder'", [elderId], (userErr, userRow) => {
      if (userErr || !userRow) {
        store.deleteStoredFile(req.file.filename);
        return res.status(400).json({
          success: false,
          error: `老人账号 ${elderId} 不存在，请先在「我的」完成账号激活`
        });
      }
      continueUpload(req, res, req.file, elderId);
    });
  });
});

/** elderId 校验通过后继续处理上传 */
function continueUpload(req, res, file, elderId) {
    const sessionId = String(req.body.sessionId || '').slice(0, 64) || `S_${Date.now()}`;
    const segmentIndex = parseInt(req.body.segmentIndex, 10) || 1;
    const reason = ['SOS', 'GEOFENCE'].includes(req.body.reason) ? req.body.reason : 'SOS';
    const clientSha = String(req.body.sha256 || '').toLowerCase();
    const durationMs = parseInt(req.body.durationMs, 10) || 0;
    const recordedAt = parseDateTime(req.body.recordedAt);

    // 幂等：同一段录音重传（断网队列重试）时，直接返回既有记录，不再重复落盘
    if (clientSha) {
      const existing = db.get(
        'SELECT id, file_name, sha256 FROM recordings WHERE elder_id = ? AND sha256 = ? LIMIT 1',
        [elderId, clientSha],
        (e, row) => {
          if (!e && row) {
            // 文件已被清理过（如 SAFE 到期），那就当新文件重新入库
            if (store.fileExists(row.file_name)) {
              return res.json({
                success: true,
                data: { id: row.id, fileName: row.file_name, duplicated: true }
              });
            }
            return doInsert(res, file, {
              elderId, sessionId, segmentIndex, reason, durationMs, recordedAt,
              clientSha, placeName: req.body.placeName || '',
              latitude: parseFloat(req.body.latitude) || null,
              longitude: parseFloat(req.body.longitude) || null,
              address: req.body.address || ''
            });
          }
          return doInsert(res, file, {
            elderId, sessionId, segmentIndex, reason, durationMs, recordedAt,
            clientSha, placeName: req.body.placeName || '',
            latitude: parseFloat(req.body.latitude) || null,
            longitude: parseFloat(req.body.longitude) || null,
            address: req.body.address || ''
          });
        }
      );
      return existing;
    }

    return doInsert(res, file, {
      elderId, sessionId, segmentIndex, reason, durationMs, recordedAt,
      clientSha, placeName: req.body.placeName || '',
      latitude: parseFloat(req.body.latitude) || null,
      longitude: parseFloat(req.body.longitude) || null,
      address: req.body.address || ''
    });
}

/** 落盘 → 算 SHA-256 → 入库 → 异步转写研判 */
function doInsert(res, file, meta) {
  const mimeType = file.mimetype || 'audio/mp4';

  store.sha256File(file.path)
    .then((sha256) => {
      const fileName = file.filename;
      const sizeBytes = store.fileSize(fileName);

      const stmt = db.prepare(`
        INSERT INTO recordings
          (elder_id, session_id, segment_index, reason, place_name, file_name, original_name,
           duration_ms, size_bytes, sha256, mime_type, recorded_at, latitude, longitude, address)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      `);
      stmt.run([
        meta.elderId, meta.sessionId, meta.segmentIndex, meta.reason,
        meta.placeName || null, fileName, file.originalname || null,
        meta.durationMs, sizeBytes, sha256, mimeType,
        meta.recordedAt || new Date().toISOString(),
        meta.latitude, meta.longitude, meta.address || null
      ], async function (err) {
        if (err) {
          store.deleteStoredFile(fileName);
          return res.status(500).json({ success: false, error: err.message });
        }
        const id = this.lastID;
        console.log(`🎙️ 收到录音片段 #${id}（老人 ${meta.elderId} / ${meta.reason} / 第${meta.segmentIndex}段 / ${(sizeBytes / 1024).toFixed(0)}KB）`);

        // 通知子女端"新录音到了"
        hub.notifyFamily(meta.elderId, {
          type: 'RECORDING_UPLOADED',
          data: { id, reason: meta.reason, segmentIndex: meta.segmentIndex, sessionId: meta.sessionId }
        });

        // 转写 + 研判是耗时操作，绝不能阻塞老人端的上传响应
        processRecordingAsync(id, fileName, {
          reason: meta.reason,
          place_name: meta.placeName || '',
          session_id: meta.sessionId
        });

        res.json({
          success: true,
          data: { id, fileName, sha256, sizeBytes, duplicated: false }
        });
      });
    })
    .catch((e) => {
      store.deleteStoredFile(file.filename);
      res.status(500).json({ success: false, error: `计算文件摘要失败：${e.message}` });
    });
}

/** 后台异步：转文字 → 研判 → 决定去留 */
async function processRecordingAsync(id, fileName, context) {
  const abs = store.resolveStoredPath(fileName);
  if (!abs) return;

  db.get('SELECT mime_type, elder_id FROM recordings WHERE id = ?', [id], async (err, row) => {
    if (err || !row) return;
    const elderId = row.elder_id;
    const mimeType = row.mime_type;

    // 1. 转写
    const asrResult = await asr.transcribe(abs, mimeType);

    if (!asrResult.ok) {
      // 转写失败不能直接判 SAFE 删除 —— 老人可能正在被骗中，证据丢了就追不回来。
      // 统一按 SUSPECT 临时保留，并记录失败原因供子女端查看。
      const retention = new Date(Date.now() + SAFE_RETENTION_DAYS * 86400 * 1000);
      db.run(
        `UPDATE recordings SET transcript_status = ?, transcript_error = ?, fraud_status = 'SUSPECT',
         fraud_verdict = ?, keep_as_evidence = 0, retention_until = ? WHERE id = ?`,
        [
          asrResult.status === 'SKIPPED' ? 'SKIPPED' : 'FAILED',
          asrResult.error || '',
          `转文字未完成（${asrResult.error || '原因未知'}），无法判定内容。` +
          `为避免误删证据，临时保留 ${SAFE_RETENTION_DAYS} 天，子女端可直接收听人工判断。`,
          retention.toISOString(), id
        ],
        () => {
          hub.notifyFamily(elderId, { type: 'RECORDING_ANALYZED', data: { id, transcriptStatus: asrResult.status } });
        }
      );
      return;
    }

    // 2. 研判
    let analysis;
    try {
      analysis = await analyzeTranscript(asrResult.text, context);
    } catch (e) {
      analysis = {
        status: 'SUSPECT',
        score: 0,
        labels: [],
        role: null,
        verdict: `AI 研判异常：${e.message}。临时保留证据，待子女端人工复核。`
      };
    }

    // 3. 落地：keep_as_evidence / retention_until 决定文件生命周期
    const keep = analysis.status === 'FRAUD' ? 1 : 0;
    const retentionUntil = analysis.status === 'SAFE'
      ? new Date(Date.now() + SAFE_RETENTION_DAYS * 86400 * 1000).toISOString()
      : (analysis.status === 'SUSPECT'
        ? new Date(Date.now() + SAFE_RETENTION_DAYS * 86400 * 1000).toISOString()
        : null); // FRAUD 长期保留，不设清理时间

    const cleanupReason = analysis.status === 'SAFE'
      ? `AI 研判未发现诈骗特征（引擎：${analysis.engine || 'rules'}），按策略仅短期保留 ${SAFE_RETENTION_DAYS} 天`
      : null;

    db.run(
      `UPDATE recordings SET transcript = ?, transcript_status = 'DONE',
         fraud_status = ?, fraud_score = ?, fraud_verdict = ?, fraud_labels = ?, suspect_role = ?,
         keep_as_evidence = ?, retention_until = ?, cleanup_reason = ? WHERE id = ?`,
      [
        asrResult.text,
        analysis.status,
        analysis.score,
        analysis.verdict,
        JSON.stringify(analysis.labels || []),
        analysis.role,
        keep,
        retentionUntil,
        cleanupReason,
        id
      ],
      () => {
        console.log(
          `🔍 录音 #${id} 研判完成：${analysis.status}（${analysis.score} 分，引擎 ${analysis.engine || 'rules'}）` +
          `${keep ? '，长期保留为证据' : `，${SAFE_RETENTION_DAYS} 天后自动清理`}`
        );
        hub.notifyFamily(elderId, {
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
    );
  });
}

// ──────────────────────────────────────────
//  2. 录音列表
// ──────────────────────────────────────────

router.get('/list/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);
  const sessionGroup = req.query.group === '1';

  const where = buildListWhere({
    q: req.query.q,
    range: req.query.range,
    level: req.query.level,
    evidence: req.query.evidence === '1',
  });
  const { limit, offset } = normalizeListPaging(req.query);

  // 第一步：按会话切页。分页必须在 SQL 里做 —— 老实现是
  // 「LIMIT 200 拉全量 → JS 里 groupBy」，JS 分组发生在拿到全部行之后，
  // 切页时流量全白费（隧道实测吞吐只有 86~102KB/s）。
  const sessionQuery = buildSessionQuery(elderId, where, limit, offset);

  db.all(sessionQuery.sql, sessionQuery.params, (sessErr, sessionRows) => {
    if (sessErr) return res.status(500).json({ success: false, error: sessErr.message });

    const sessionIds = (sessionRows || []).map((r) => r.session_id);

    const respond = (rows, stats) => {
      const formatted = toBeijingRows(rows || []).map((r) => {
        const { token, expiresAt } = sign(r.id, req.authUserId);
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
          streamTokenExpiresAt: expiresAt,
        };
      });

      // 真正的响应体在这里发。抽成闭包是为了让「先用原值响应、
      // 补全完地名再响应」两条路径复用同一段渲染逻辑。
      const sendResponse = () => {
        if (!sessionGroup) {
          return res.json({
            success: true,
            data: { recordings: formatted, total: stats.totalRecordings, ...stats },
          });
        }

      // 按会话聚合。一次连续录音的多段归到一起，子女端看得更清楚。
      // 顺序沿用第一步查出的会话顺序（已按 MAX(id) DESC 排好），
      // 不靠 Map 的插入顺序碰运气。
      const metaBySession = new Map(sessionRows.map((r) => [r.session_id, r]));
      const sessions = [];
      for (const r of formatted) {
        let g = sessions.find((s) => s.sessionId === r.sessionId);
        if (!g) {
          const meta = metaBySession.get(r.sessionId) || {};
          g = {
            sessionId: r.sessionId,
            reason: r.reason,
            reasonLabel: r.reasonLabel,
            placeName: r.placeName,
            startedAt: meta.started_at || r.recordedAt,
            segmentCount: 0,
            totalDurationMs: 0,
            isFraud: false,
            isSuspect: false,
            hasTranscript: false,
            recordings: [],
          };
          sessions.push(g);
        }
        g.segmentCount += 1;
        g.totalDurationMs += r.durationMs || 0;
        g.recordings.push(r);
        if (r.fraudStatus === 'FRAUD') g.isFraud = true;
        if (r.fraudStatus === 'SUSPECT' || r.fraudStatus === 'FAILED') g.isSuspect = true;
        if (r.transcript) g.hasTranscript = true;
      }

      res.json({
        success: true,
        data: {
          sessions,
          ...stats,
          hasMore: offset + sessionIds.length < (stats.totalSessions || 0),
          appliedFilters: {
            q: String(req.query.q || '').trim(),
            range: resolveRange(req.query.range),
            level: resolveLevel(req.query.level),
            evidence: req.query.evidence === '1',
          },
        },
      });
      };

      // 坐标串名称补全。
      //
      // 为什么要有：recordings.place_name 是老人端上传时带上来、未经服务端地名
      // 推断的，坐标串直接进标题就成了「进入敏感地点『曾爷爷常去地点(28.273,
      // 113.062)』」。子女看到一串数字认不出自己配的是哪个地点，会以为配置
      // 丢了 —— 这是「静默的可理解性故障」，比报错更难排查。
      //
      // 做法对齐 geofence.js 的 /list/:elderId：先用原值响应（describePlace
      // 要查地图 key，阻塞列表不可接受），Promise.all 补全后再发一次响应。
      const needGuess = formatted.filter(
        (r) => r.reason !== 'SOS'
          && !geo.looksLikeRealAddress(r.placeName || '')
          && Number.isFinite(r.latitude) && r.latitude !== 0
          && Number.isFinite(r.longitude) && r.longitude !== 0
      );

      if (needGuess.length === 0) return sendResponse();

      Promise.all(needGuess.map((r) =>
        geo.describePlace(r.latitude, r.longitude, db, elderId)
          .then(({ name, source }) => ({ rec: r, name, source }))
          .catch(() => null)
      )).then((hits) => {
        for (const hit of (hits || []).filter(Boolean)) {
          if (!hit.name) continue;
          hit.rec.placeName = hit.name;
          hit.rec.reasonLabel = `进入敏感地点「${hit.name}」`;
          // 只把真实地图地名写回库。「常去地点①」这类推断描述覆盖原值会丢信息，
          // 老人下次再录时仍要重新推断，而原值可能本来就是有用的地点名。
          if (hit.source === 'geo') {
            db.run('UPDATE recordings SET place_name = ? WHERE id = ?', [hit.name, hit.rec.id]);
          }
        }
        sendResponse();
      });
    };

    // 统计必须独立 COUNT 且不带 LIMIT：否则翻到第二页时标题上的
    // 「共 17 段，其中 2 段诈骗」会随翻页跳变。
    const statsQueries = {
      sessions: buildSessionCountQuery(elderId, where),
      recordings: buildRecordingCountQuery(elderId, where),
      fraud: buildFraudCountQuery(elderId, where),
    };
    db.get(statsQueries.sessions.sql, statsQueries.sessions.params, (e1, s1) => {
      db.get(statsQueries.recordings.sql, statsQueries.recordings.params, (e2, s2) => {
        db.get(statsQueries.fraud.sql, statsQueries.fraud.params, (e3, s3) => {
          if (e1 || e2 || e3) {
            return res.status(500).json({ success: false, error: (e1 || e2 || e3).message });
          }
          const stats = {
            totalSessions: (s1 && s1.n) || 0,
            totalRecordings: (s2 && s2.n) || 0,
            fraudCount: (s3 && s3.n) || 0,
          };

          if (sessionIds.length === 0) return respond([], stats);

          const detail = buildSessionDetailQuery(elderId, sessionIds);
          db.all(detail.sql, detail.params, (dErr, rows) => {
            if (dErr) return res.status(500).json({ success: false, error: dErr.message });
            respond(rows, stats);
          });
        });
      });
    });
  });
});

function safeParseLabels(raw) {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}

// ──────────────────────────────────────────
//  3. 流式播放（支持 Range，播放器拖动进度条必需）
// ──────────────────────────────────────────

router.get('/stream/:id', (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (!id) return res.status(400).json({ success: false, error: '录音编号无效' });

  // 令牌由已鉴权的 list 接口签发，HMAC 覆盖 rid/uid/exp 整个 payload。
  // 播放器虽不带自定义头拿不到 uid，但签名真实有效：令牌不可伪造，也不能挪用到别的录音。
  const check = verify(req.query.token, id);
  if (!check.ok) {
    return res.status(401).json({ success: false, error: check.error });
  }

  streamFile(req, res, id);
});

function streamFile(req, res, id) {
  db.get('SELECT file_name, mime_type, original_name FROM recordings WHERE id = ?',
    [id], (err, row) => {
      if (err || !row) return res.status(404).json({ success: false, error: '录音不存在' });
      const abs = store.resolveStoredPath(row.file_name);
      if (!abs || !fs.existsSync(abs)) {
        return res.status(410).json({ success: false, error: '录音文件已被清理，如需留存请及时导出' });
      }
      const stat = fs.statSync(abs);
      const mime = row.mime_type || 'audio/mp4';
      const range = req.headers.range;

      if (range) {
        const m = /bytes=(\d*)-(\d*)/.exec(range);
        const start = m && m[1] ? parseInt(m[1], 10) : 0;
        const end = m && m[2] ? parseInt(m[2], 10) : stat.size - 1;
        if (isNaN(start) || isNaN(end) || start > end || end >= stat.size) {
          res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` });
          return res.end();
        }
        res.writeHead(206, {
          'Content-Type': mime,
          'Content-Range': `bytes ${start}-${end}/${stat.size}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': end - start + 1,
          'Cache-Control': 'private, max-age=300'
        });
        return fs.createReadStream(abs, { start, end }).pipe(res);
      }

      res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': stat.size,
        'Accept-Ranges': 'bytes',
        'Content-Disposition': `inline; filename="${encodeURIComponent(row.original_name || row.file_name)}"`,
        'Cache-Control': 'private, max-age=300'
      });
      fs.createReadStream(abs).pipe(res);
    });
}

// ──────────────────────────────────────────
//  4. 一键打包下载（报警材料）
// ──────────────────────────────────────────

router.get('/pack/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);
  const onlyEvidence = req.query.scope === 'evidence';

  const where = onlyEvidence
    ? 'WHERE elder_id = ? AND keep_as_evidence = 1'
    : 'WHERE elder_id = ?';

  db.all(`SELECT * FROM recordings ${where} ORDER BY id ASC`, [elderId], (err, rows) => {
    if (err) return res.status(500).json({ success: false, error: err.message });
    if (!rows || rows.length === 0) {
      return res.status(404).json({ success: false, error: '暂无可打包的录音材料' });
    }

    const entries = [];
    const manifest = [];
    const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
    let index = 0;

    for (const r of rows) {
      const abs = store.resolveStoredPath(r.file_name);
      if (!abs || !fs.existsSync(abs)) continue; // 已被清理，跳过
      index += 1;
      const zoneName = sanitizeName(`${r.recorded_at || ''}`.slice(0, 10) || '未知日期');
      const base = `录音/${zoneName}/REC_${String(r.id).padStart(4, '0')}_${
        r.reason === 'SOS' ? '一键求助' : '围栏'
      }_第${r.segment_index}段.m4a`;
      entries.push({ name: base, data: fs.readFileSync(abs), date: toDate(r.recorded_at) });

      manifest.push({
        录音编号: r.id,
        会话: r.session_id,
        段序: r.segment_index,
        触发来源: r.reason === 'SOS' ? '老人一键紧急求助' : `进入敏感地点「${r.place_name || ''}」`,
        录音时间: toBeijing(new Date(r.recorded_at || Date.now())),
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
      return res.status(410).json({ success: false, error: '录音文件均已被清理' });
    }

    // 证据清单：民警看得懂的第一份文件
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
        `【录音 ${String(m.录音编号).padStart(4, '0')}】`,
        ...Object.entries(m).map(([k, v]) => `  ${k}：${v === null || v === '' ? '（空）' : v}`),
        ''
      ])
    ].join('\n');

    entries.unshift({ name: '00_证据清单.txt', data: Buffer.from(readme, 'utf8') });
    entries.unshift({
      name: '01_证据清单.json',
      data: Buffer.from(
        JSON.stringify({
          生成时间: toBeijing(new Date()),
          系统: '长者防诈亲情守护系统',
          录音数量: manifest.length,
          明细: manifest
        }, null, 2),
        'utf8'
      )
    });

    const zip = buildZip(entries);
    const fileName = `反诈录音证据包_${elderId}_${stamp}.zip`;
    console.log(`📦 打包 ${entries.length - 2} 段录音 + 证据清单，共 ${(zip.length / 1024 / 1024).toFixed(1)}MB`);

    res.writeHead(200, {
      'Content-Type': 'application/zip',
      'Content-Length': zip.length,
      'Content-Disposition': `attachment; filename="evidence_${elderId}_${stamp}.zip"; filename*=UTF-8''${encodeURIComponent(fileName)}`
    });
    res.end(zip);
  });
});

// ──────────────────────────────────────────
//  5. 人工复核 / 删除
// ──────────────────────────────────────────

/** 子女端人工复核：推翻 AI 判定（把 SAFE 改成证据保留） */
router.post('/:id/review', requireFamilyAuth, (req, res) => {
  const id = parseInt(req.params.id, 10);
  const keep = !!req.body.keep;
  const note = String(req.body.note || '').slice(0, 500);

  db.get('SELECT elder_id FROM recordings WHERE id = ?', [id], (err, row) => {
    if (err || !row) return res.status(404).json({ success: false, error: '录音不存在' });
    db.get('SELECT bound_user_id FROM users WHERE id = ?', [req.authUserId], (e2, me) => {
      if (e2 || !me || (String(me.bound_user_id) !== String(row.elder_id) && String(req.authUserId) !== String(row.elder_id))) {
        return res.status(403).json({ success: false, error: '无权复核该录音' });
      }
      db.run(
        `UPDATE recordings SET reviewed_by_family = 1, keep_as_evidence = ?,
           retention_until = NULL, cleanup_reason = ?,
           fraud_verdict = COALESCE(NULLIF(?, ''), fraud_verdict) WHERE id = ?`,
        [keep ? 1 : 0, note || (keep ? '子女端人工复核：判定为有效证据' : '子女端人工复核：判定无需保留'), note, id],
        function (e) {
          if (e) return res.status(500).json({ success: false, error: e.message });
          hub.notifyFamily(row.elder_id, { type: 'RECORDING_REVIEWED', data: { id, keepAsEvidence: keep } });
          res.json({ success: true, data: { id, keepAsEvidence: keep } });
        }
      );
    });
  });
});

/** 删除录音记录与文件 */
router.delete('/:id', requireFamilyAuth, (req, res) => {
  const id = parseInt(req.params.id, 10);
  db.get('SELECT elder_id, file_name FROM recordings WHERE id = ?', [id], (err, row) => {
    if (err || !row) return res.status(404).json({ success: false, error: '录音不存在' });
    db.get('SELECT bound_user_id FROM users WHERE id = ?', [req.authUserId], (e2, me) => {
      if (e2 || !me || (String(me.bound_user_id) !== String(row.elder_id) && String(req.authUserId) !== String(row.elder_id))) {
        return res.status(403).json({ success: false, error: '无权删除该录音' });
      }
      store.deleteStoredFile(row.file_name);
      db.run('DELETE FROM recordings WHERE id = ?', [id], function (e) {
        if (e) return res.status(500).json({ success: false, error: e.message });
        hub.notifyFamily(row.elder_id, { type: 'RECORDING_DELETED', data: { id } });
        res.json({ success: true, data: { id, deleted: true } });
      });
    });
  });
});

/** 查询录音会话当前是否正在录音（子女端据此显示"停止录音"按钮） */
router.get('/status/:elderId', requireFamilyAuth, requireBoundElder, (req, res) => {
  const elderId = parseInt(req.params.elderId, 10);
  res.json({
    success: true,
    data: {
      elderId,
      recording: hub.isElderRecording ? hub.isElderRecording(elderId) : { active: false },
      serverTime: toBeijing(new Date())
    }
  });
});

// ──────────────────────────────────────────
//  工具
// ──────────────────────────────────────────

function sanitizeName(s) {
  return String(s || '').replace(/[\\/:*?"<>|]/g, '_').trim() || '未分类';
}

function parseDateTime(raw) {
  if (!raw) return null;
  const d = new Date(raw);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

function toDate(raw) {
  const d = raw ? new Date(raw) : new Date();
  return isNaN(d.getTime()) ? new Date() : d;
}

module.exports = router;
