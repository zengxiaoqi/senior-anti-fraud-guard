-- 多人绑定关系表（对齐本地 database/db.js 的建表与回填，docs/multi-binding-design.md）
-- 旧模型 users.bound_user_id 是 1:1 双向两列；本表成为绑定关系一等公民事实源，
-- bound_user_id 降级为「主绑定」派生缓存继续双写（elder→主守护人，family→最早绑定的老人）。
CREATE TABLE IF NOT EXISTS bindings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  elder_id INTEGER NOT NULL,
  family_id INTEGER NOT NULL,
  is_primary INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(elder_id, family_id)
);

-- 判活/推送/鉴权查询全部走索引前缀，防全表扫描计费（D1 铁律）
CREATE INDEX IF NOT EXISTS idx_bindings_elder ON bindings(elder_id, status);
CREATE INDEX IF NOT EXISTS idx_bindings_family ON bindings(family_id, status);

-- 存量回填（幂等）：把旧双向两列的 1:1 关系灌进 bindings，只认 elder↔family 有效对。
-- 已解绑账号 bound_user_id 已清空，天然不会被捞进来；UNIQUE 保证重复执行不产生重复行。
INSERT OR IGNORE INTO bindings (elder_id, family_id, is_primary, status)
SELECT e.id, e.bound_user_id, 1, 'ACTIVE'
  FROM users e JOIN users f ON f.id = e.bound_user_id
 WHERE e.role = 'elder' AND f.role = 'family' AND e.bound_user_id IS NOT NULL;
