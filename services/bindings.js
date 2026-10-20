/**
 * 多人绑定服务层（本地后端）：bindings 多对多表的一等公民操作。
 *
 * 背景：原模型是 users.bound_user_id 双向两列的 1:1 结构，装不下
 * 「一个子女多个老人 / 一个老人多个子女」。本模块把绑定关系迁到
 * bindings 表（见 database/db.js 建表），bound_user_id 降级为
 * 「主绑定」派生缓存继续双写——旧读路径与旧客户端兼容边界是
 * API 响应形状（boundUser / boundFamily = 主守护人），不是 DB 列。
 *
 * 语义约定（与 docs/multi-binding-design.md 对齐）：
 *  - 绑定是追加式：重复绑同一对幂等成功，不会冲掉其他绑定；
 *  - is_primary：每个 elder 至多一位主守护人（第一个绑定者自动成为主），
 *    主守护人解绑后由最早 ACTIVE 的其余守护人顶上；
 *  - family.bound_user_id = 该子女最早绑定的老人（登录回执 boundUser 的来源）；
 *    elder.bound_user_id = 主守护人（推送回拨、旧字段兼容的来源）；
 *  - 解绑是软删（status='REMOVED'），保留审计行；重绑把行恢复为 ACTIVE。
 *  - 容量上限：每老人 ≤ 5 守护人、每子女 ≤ 5 老人（防滥用，改这里一处生效）。
 *
 * 与 Workers 侧的对齐点：workers/src/db/dao/users.ts 的同名语义 + 契约测试。
 * 任何一侧改了判定口径，另一侧必须同步，否则双后端行为漂移。
 */

const MAX_FAMILIES_PER_ELDER = 5;
const MAX_ELDERS_PER_FAMILY = 5;

/** 某老人当前 ACTIVE 守护人数 */
function countFamiliesOfElder(db, elderId, cb) {
  db.get(
    "SELECT COUNT(*) AS n FROM bindings WHERE elder_id = ? AND status = 'ACTIVE'",
    [elderId],
    (err, row) => cb(err, row ? row.n : 0)
  );
}

/** 某子女当前 ACTIVE 绑定的老人数 */
function countEldersOfFamily(db, familyId, cb) {
  db.get(
    "SELECT COUNT(*) AS n FROM bindings WHERE family_id = ? AND status = 'ACTIVE'",
    [familyId],
    (err, row) => cb(err, row ? row.n : 0)
  );
}

/** 子女与老人之间是否存在 ACTIVE 绑定（鉴权用，一行判定） */
function isBound(db, familyId, elderId, cb) {
  db.get(
    "SELECT 1 AS ok FROM bindings WHERE family_id = ? AND elder_id = ? AND status = 'ACTIVE' LIMIT 1",
    [familyId, elderId],
    (err, row) => cb(err, !!(row && row.ok))
  );
}

/** 某老人的全部 ACTIVE 守护人（主守护人在前），供推送 fanout / 老人端展示 */
function listFamiliesOfElder(db, elderId, cb) {
  db.all(
    `SELECT f.id, f.name, f.phone, f.mobile, f.wx_openid, b.is_primary
       FROM bindings b JOIN users f ON f.id = b.family_id
      WHERE b.elder_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.is_primary DESC, b.id ASC`,
    [elderId],
    cb
  );
}

/** 某子女的全部 ACTIVE 老人（最早的在前），供登录回执 / my-elders */
function listEldersOfFamily(db, familyId, cb) {
  db.all(
    `SELECT e.id, e.name, e.phone, e.bind_code, b.is_primary, b.created_at
       FROM bindings b JOIN users e ON e.id = b.elder_id
      WHERE b.family_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.id ASC`,
    [familyId],
    cb
  );
}

/**
 * 追加一条绑定（幂等）。完成 bindings 行写入/恢复 + is_primary 分配 +
 * 双方 bound_user_id 回填。整体在一个事务里，任何一步失败整体回滚。
 *
 * @returns cb(err, { already: Boolean, isPrimary: 0|1 })
 */
function addBinding(db, elderId, familyId, cb) {
  db.serialize(() => {
    db.run('BEGIN TRANSACTION');
    const fail = (e) => { db.run('ROLLBACK'); cb(e); };
    const done = (result) => db.run('COMMIT', (err) => (err ? fail(err) : cb(null, result)));

    // 1. 该老人当前的绑定行（含 REMOVED，决定恢复还是新插）
    db.get(
      'SELECT id, status, is_primary FROM bindings WHERE elder_id = ? AND family_id = ?',
      [elderId, familyId],
      (err, row) => {
        if (err) return fail(err);
        if (row && row.status === 'ACTIVE') {
          // 幂等：已绑定，什么都不改（绝不冲掉其他绑定——旧覆盖式行为已废除）
          return done({ already: true, isPrimary: row.is_primary });
        }

        // 2. 该老人还没有主守护人时，本次绑定者自动成为主
        db.get(
          "SELECT id FROM bindings WHERE elder_id = ? AND status = 'ACTIVE' AND is_primary = 1",
          [elderId],
          (err2, primaryRow) => {
            if (err2) return fail(err2);
            const isPrimary = primaryRow ? 0 : 1;

            const write = (sql, args) => db.run(sql, args, (err3) => (err3 ? fail(err3) : afterWrite()));
            const afterWrite = () => {
              // 3. 回填 bound_user_id 缓存：只在为空时写（不覆盖已有的主绑定）
              db.run(
                'UPDATE users SET bound_user_id = ? WHERE id = ? AND bound_user_id IS NULL',
                [familyId, elderId],
                () => {
                  db.run(
                    'UPDATE users SET bound_user_id = ? WHERE id = ? AND bound_user_id IS NULL',
                    [elderId, familyId],
                    () => done({ already: false, isPrimary })
                  );
                }
              );
            };

            if (row) {
              // 软删行恢复：ACTIVE 复位，is_primary 按当前事实重算
              write(
                "UPDATE bindings SET status = 'ACTIVE', is_primary = ? WHERE id = ?",
                [isPrimary, row.id]
              );
            } else {
              write(
                "INSERT INTO bindings (elder_id, family_id, is_primary, status) VALUES (?, ?, ?, 'ACTIVE')",
                [elderId, familyId, isPrimary]
              );
            }
          }
        );
      }
    );
  });
}

/**
 * 解除一条绑定（软删）+ 主守护人轮换 + 双方 bound_user_id 回填。
 * 只解除 (familyId, elderId) 这一对，不再清对方的整条绑定。
 *
 * @returns cb(err, { removed: Boolean })
 */
function removeBinding(db, elderId, familyId, cb) {
  db.serialize(() => {
    db.run('BEGIN TRANSACTION');
    const fail = (e) => { db.run('ROLLBACK'); cb(e); };
    const done = (result) => db.run('COMMIT', (err) => (err ? fail(err) : cb(null, result)));

    db.get(
      "SELECT id, is_primary FROM bindings WHERE elder_id = ? AND family_id = ? AND status = 'ACTIVE'",
      [elderId, familyId],
      (err, row) => {
        if (err) return fail(err);
        // 没有这条绑定：幂等成功（旧解绑对未绑账号也返回成功，保持口径）
        if (!row) return done({ removed: false });

        db.run(
          "UPDATE bindings SET status = 'REMOVED' WHERE id = ?",
          [row.id],
          (err2) => {
            if (err2) return fail(err2);

            const promoteAndSync = () => {
              // 主守护人走了：最早 ACTIVE 的其余守护人顶上（没有则该老人回到未绑定）
              db.get(
                "SELECT family_id FROM bindings WHERE elder_id = ? AND status = 'ACTIVE' ORDER BY id ASC LIMIT 1",
                [elderId],
                (err3, next) => {
                  if (err3) return fail(err3);
                  const nextFamily = next ? next.family_id : null;
                  db.run(
                    "UPDATE bindings SET is_primary = 1 WHERE elder_id = ? AND family_id = ? AND status = 'ACTIVE'",
                    [elderId, nextFamily || -1],
                    () => {
                      // elder.bound_user_id 永远指主守护人（或 NULL）
                      db.run(
                        'UPDATE users SET bound_user_id = ? WHERE id = ?',
                        [nextFamily, elderId],
                        () => {
                          // family.bound_user_id 若还指着这个老人，回填为其最早 ACTIVE 老人（或 NULL）
                          db.get(
                            "SELECT elder_id FROM bindings WHERE family_id = ? AND status = 'ACTIVE' ORDER BY id ASC LIMIT 1",
                            [familyId],
                            (err4, nextElder) => {
                              if (err4) return fail(err4);
                              const nextElderId = nextElder ? nextElder.elder_id : null;
                              db.run(
                                'UPDATE users SET bound_user_id = ? WHERE id = ? AND bound_user_id = ?',
                                [nextElderId, familyId, elderId],
                                () => done({ removed: true })
                              );
                            }
                          );
                        }
                      );
                    }
                  );
                }
              );
            };

            if (row.is_primary) {
              promoteAndSync();
            } else {
              // 非主守护人解绑：elder.bound_user_id 不动，只需回填 family 侧
              db.get(
                "SELECT elder_id FROM bindings WHERE family_id = ? AND status = 'ACTIVE' ORDER BY id ASC LIMIT 1",
                [familyId],
                (err5, nextElder) => {
                  if (err5) return fail(err5);
                  const nextElderId = nextElder ? nextElder.elder_id : null;
                  db.run(
                    'UPDATE users SET bound_user_id = ? WHERE id = ? AND bound_user_id = ?',
                    [nextElderId, familyId, elderId],
                    () => done({ removed: true })
                  );
                }
              );
            }
          }
        );
      }
    );
  });
}

/**
 * 把某账号的所有绑定整体转移给另一账号（wx-link 微信占位号 → App 账号）。
 * 同 elder 去重靠 UNIQUE 冲突跳过；占位号的原行整体删除；
 * 转移过去的行一律非主（目标账号已有的主绑定不动），随后对「目标账号绑了
 * 但没有任何主守护人」的老人补选主。
 *
 * @returns cb(err, { moved: number })
 */
function migrateBindings(db, fromUserId, toUserId, cb) {
  db.serialize(() => {
    db.run('BEGIN TRANSACTION');
    const fail = (e) => { db.run('ROLLBACK'); cb(e); };
    const done = (result) => db.run('COMMIT', (err) => (err ? fail(err) : cb(null, result)));

    // 1. 复制占位号的 ACTIVE 绑定到目标账号（同 elder 已绑则 OR IGNORE 跳过），一律 is_primary=0
    db.run(
      `INSERT OR IGNORE INTO bindings (elder_id, family_id, is_primary, status, created_at)
       SELECT elder_id, ?, 0, 'ACTIVE', created_at FROM bindings
        WHERE family_id = ? AND status = 'ACTIVE'`,
      [toUserId, fromUserId],
      function (err) {
        if (err) return fail(err);
        const copied = this.changes || 0;

        // 2. 占位号的绑定行整体删除（含复制时撞 UNIQUE 的重复对）
        db.run(
          'DELETE FROM bindings WHERE family_id = ?',
          [fromUserId],
          (err2) => {
            if (err2) return fail(err2);

            // 3. 目标账号绑定的老人里，还没有主守护人的 → 该老人上目标账号最早的一条绑定成为主
            db.all(
              `SELECT b.elder_id FROM bindings b
                WHERE b.family_id = ? AND b.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM bindings p
                                   WHERE p.elder_id = b.elder_id AND p.status = 'ACTIVE' AND p.is_primary = 1)
                ORDER BY b.id ASC`,
              [toUserId],
              (err3, orphans) => {
                if (err3) return fail(err3);
                if (!orphans || orphans.length === 0) return done({ moved: copied });

                let pending = orphans.length;
                let failed = false;
                for (const o of orphans) {
                  db.run(
                    `UPDATE bindings SET is_primary = 1
                      WHERE id = (SELECT MIN(id) FROM bindings
                                   WHERE elder_id = ? AND family_id = ? AND status = 'ACTIVE')`,
                    [o.elder_id, toUserId],
                    (err4) => {
                      if (failed) return;
                      if (err4) { failed = true; return fail(err4); }
                      if (--pending === 0) done({ moved: copied });
                    }
                  );
                }
              }
            );
          }
        );
      }
    );
  });
}

module.exports = {
  MAX_FAMILIES_PER_ELDER,
  MAX_ELDERS_PER_FAMILY,
  countFamiliesOfElder,
  countEldersOfFamily,
  isBound,
  listFamiliesOfElder,
  listEldersOfFamily,
  addBinding,
  removeBinding,
  migrateBindings
};
