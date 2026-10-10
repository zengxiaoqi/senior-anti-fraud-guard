-- Workers 本地契约测试专用：清空全部业务表（本地 D1 状态是持久的，
-- 不清会把上一轮的绑定码/订单号带进来造成假失败；对齐本地 runner 每次全新临时库的语义）
-- 用法：npx wrangler d1 execute safg-db --local --file scripts/reset-contract-d1.sql
DELETE FROM auth_tokens;
DELETE FROM risk_events;
DELETE FROM locations;
DELETE FROM payments;
DELETE FROM geofences;
DELETE FROM recordings;
DELETE FROM users;
