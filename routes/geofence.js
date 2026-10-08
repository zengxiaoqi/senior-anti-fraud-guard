// 敏感地点围栏配置接口
// 信任模型：
//  - 老人端免登录拉取（与 /api/events/report 一致，以 elderId 标识设备）
//  - 子女端增删改需 X-Auth-Token 登录态（写操作固定作用于自己绑定的老人，不接受任意 elderId）
const express = require('express');
const router = express.Router();
const db = require('../database/db');
const { requireFamilyAuth } = require('../services/tokenAuth');
const { toBeijingRows } = require('../services/timeFormat');
const geo = require('../services/regeo');

function getBoundElderId(req, res, cb) {
  db.get('SELECT bound_user_id FROM users WHERE id = ?', [req.authUserId], (err, row) => {
    if (err || !row || !row.bound_user_id) {
      res.status(403).json({ success: false, error: '尚未绑定守护对象，无法配置敏感地点' });
      return;
    }
    cb(row.bound_user_id);
  });
}

// 老人端拉取本账号启用的围栏列表（免登录，仅返回启用项与必要字段）
router.get('/elder/:elderId', (req, res) => {
  const elderId = req.params.elderId;
  db.all(`SELECT id, name, latitude, longitude, radius, dwell_minutes FROM geofences
          WHERE elder_id = ? AND enabled = 1`, [elderId], (err, rows) => {
    if (err) return res.status(500).json({ success: false, error: err.message });
    res.json({ success: true, data: rows || [] });
  });
});

// 子女端查看围栏配置（含停用项）
router.get('/list/:elderId', requireFamilyAuth, (req, res) => {
  const elderId = req.params.elderId;
  db.get('SELECT bound_user_id FROM users WHERE id = ?', [req.authUserId], (err, row) => {
    if (err) return res.status(500).json({ success: false, error: err.message });
    if (!row || String(row.bound_user_id) !== String(elderId)) {
      return res.status(403).json({ success: false, error: '无权访问该用户的围栏配置' });
    }
    db.all(`SELECT * FROM geofences WHERE elder_id = ? ORDER BY id DESC`, [elderId], (err2, rows) => {
      if (err2) return res.status(500).json({ success: false, error: err2.message });
      const out = toBeijingRows(rows || []);
      // 历史围栏名可能是坐标串（"GPS 位置 (28.2" 这类），实时兜底成真实地名，
      // 否则用户在子女端看到的是一串数字，认不出自己配的是哪个地点。
      Promise.all(out.map(r => {
        if (geo.looksLikeRealAddress(r.name)) return Promise.resolve({ ...r, place_source: 'db' });
        return geo.describePlace(r.latitude, r.longitude, db, elderId)
          .then(({ name, source }) => {
            // 只有真实地图地名才写回库；"常去地点①"这类描述性推断不覆盖原始值
            if (source === 'geo') {
              db.run(
                `UPDATE geofences SET name = ? WHERE id = ? AND (name LIKE 'GPS%' OR name LIKE '坐标%' OR name LIKE '%附近 (%')`,
                [name, r.id]
              );
            }
            return { ...r, name, place_source: source };
          })
          .catch(() => r);
      })).then((merged) => {
        res.json({ success: true, data: merged });
      }).catch(() => {
        res.json({ success: true, data: out });
      });
    });
  });
});

// 子女端新增敏感地点 { name, latitude, longitude, radius?, dwellMinutes? }
router.post('/add', requireFamilyAuth, (req, res) => {
  const { name, latitude, longitude, radius, dwellMinutes } = req.body || {};
  const lat = parseFloat(latitude);
  const lng = parseFloat(longitude);
  const r = parseInt(radius, 10);
  // 1-8 停留告警阈值（分钟）：0/缺省 = 只录音不额外告警，上限 720 分钟
  const dwell = isNaN(parseInt(dwellMinutes, 10))
    ? 0 : Math.min(Math.max(parseInt(dwellMinutes, 10), 0), 720);

  if (!name || String(name).trim().length === 0) {
    return res.status(400).json({ success: false, error: '请填写地点名称' });
  }
  if (isNaN(lat) || lat < -90 || lat > 90 || isNaN(lng) || lng < -180 || lng > 180) {
    return res.status(400).json({ success: false, error: '经纬度无效' });
  }

  getBoundElderId(req, res, (elderId) => {
    // normalizeFenceName 可能是异步（要查地名），所以这里用 Promise 串起来
    Promise.resolve(normalizeFenceName(name, lat, lng)).then((finalName) => {
      db.run(`INSERT INTO geofences (elder_id, name, latitude, longitude, radius, dwell_minutes)
              VALUES (?, ?, ?, ?, ?, ?)`,
        [elderId, finalName, lat, lng,
         isNaN(r) ? 200 : Math.min(Math.max(r, 50), 2000),
         dwell],
        function (err) {
          if (err) return res.status(500).json({ success: false, error: err.message });
          res.json({ success: true, id: this.lastID, name: finalName, message: '敏感地点已登记，老人进入后自动开启环境录音存证' });
        });
    }).catch(() => {
      res.status(500).json({ success: false, error: '地点名称解析失败，请重试' });
    });
  });
});

/**
 * 围栏名称兜底。
 *
 * 背景：客户端（尤其是老人端定位服务）会用 "GPS 位置 (28.2732, 113.0623)" 这种
 * 坐标串当默认名上报。它既不是人话、又常被截断（如 maxLength=30 或 take(12)），
 * 界面上就显示成 "GPS 位置 (28.2" 这种半截字符串 —— 用户根本认不出自己配的
 * 地点，于是以为"配置丢了"。这是静默的可理解性故障，比报错更难排查。
 *
 * 因此凡是命中坐标串特征（或超长被截断成半个坐标）的，都改成可读名称：
 * 优先真实地名（地图 key），没有 key 就用附近锚点 / 常去地点推断（纯本地）。
 */
function normalizeFenceName(rawName, lat, lng) {
  let s = String(rawName == null ? '' : rawName).trim();
  const coordLike =
    !s ||
    /^(gps\s*位置)?\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]?\s*-?\d+\.?\d*\s*[\)）]?\s*$/i.test(s) ||
    /^gps\s*位置/i.test(s) ||
    /^坐标\s*[\(（]?\s*-?\d+\.?\d*\s*[,，]/i.test(s) ||
    /[\(（]\s*\d+\.\d*\s*$/.test(s);   // 被截断的半个坐标串
  if (coordLike) return geo.guessPlace(lat, lng, db, null);

  // 超长无意义串也截一下，避免撑爆列表项
  return s.length > 30 ? s.substring(0, 30) : s;
}

function fallbackFenceName(lat, lng) {
  return `我的位置附近(${Number(lat).toFixed(3)}, ${Number(lng).toFixed(3)})`;
}

// 子女端更新围栏 { id, name?, radius?, enabled?, dwellMinutes? }
router.post('/update', requireFamilyAuth, (req, res) => {
  const { id, name, radius, enabled, dwellMinutes } = req.body || {};
  if (!id) return res.status(400).json({ success: false, error: '缺失围栏 id' });

  getBoundElderId(req, res, (elderId) => {
    db.get(`SELECT * FROM geofences WHERE id = ? AND elder_id = ?`, [id, elderId], (err, row) => {
      if (err) return res.status(500).json({ success: false, error: err.message });
      if (!row) return res.status(404).json({ success: false, error: '围栏不存在' });

      const newName = (name !== undefined && String(name).trim()) ? String(name).trim() : row.name;
      const newRadius = (radius !== undefined && !isNaN(parseInt(radius, 10)))
        ? Math.min(Math.max(parseInt(radius, 10), 50), 2000) : row.radius;
      const newEnabled = (enabled !== undefined) ? (enabled ? 1 : 0) : row.enabled;
      const newDwell = (dwellMinutes !== undefined && !isNaN(parseInt(dwellMinutes, 10)))
        ? Math.min(Math.max(parseInt(dwellMinutes, 10), 0), 720) : (row.dwell_minutes || 0);

      db.run(`UPDATE geofences SET name = ?, radius = ?, enabled = ?, dwell_minutes = ? WHERE id = ?`,
        [newName, newRadius, newEnabled, newDwell, id], (err2) => {
          if (err2) return res.status(500).json({ success: false, error: err2.message });
          res.json({ success: true, message: '围栏配置已更新' });
        });
    });
  });
});

// 子女端删除围栏 { id }
router.post('/delete', requireFamilyAuth, (req, res) => {
  const id = (req.body || {}).id;
  if (!id) return res.status(400).json({ success: false, error: '缺失围栏 id' });

  getBoundElderId(req, res, (elderId) => {
    db.run(`DELETE FROM geofences WHERE id = ? AND elder_id = ?`, [id, elderId], function (err) {
      if (err) return res.status(500).json({ success: false, error: err.message });
      if (this.changes === 0) return res.status(404).json({ success: false, error: '围栏不存在' });
      res.json({ success: true, message: '敏感地点已删除' });
    });
  });
});

module.exports = router;
