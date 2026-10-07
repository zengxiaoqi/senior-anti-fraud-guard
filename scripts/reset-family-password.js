// 重置子女端账号密码：node scripts/reset-family-password.js <用户名> <新密码>
// 用途：忘记密码时由服务器管理员在服务器上直接重置（scrypt 加盐哈希，与注册接口一致）
const path = require('path');
const db = require('../database/db');
const crypto = require('crypto');

const [username, newPassword] = process.argv.slice(2);

if (!username || !newPassword) {
  console.error('用法: node scripts/reset-family-password.js <用户名> <新密码(至少6位)>');
  process.exit(1);
}
if (String(newPassword).length < 6) {
  console.error('❌ 密码至少 6 位');
  process.exit(1);
}

db.get('SELECT id, role FROM users WHERE (phone = ? OR mobile = ?) AND password_hash IS NOT NULL', [username, username], (err, user) => {
  if (err) { console.error('❌ 数据库查询失败:', err.message); process.exit(1); }
  if (!user) { console.error(`❌ 未找到账号 "${username}"（或该账号未设置密码）`); process.exit(1); }

  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(newPassword), salt, 64).toString('hex');
  db.run('UPDATE users SET password_hash = ? WHERE id = ?', [`${salt}:${hash}`, user.id], (err) => {
    if (err) { console.error('❌ 更新失败:', err.message); process.exit(1); }
    console.log(`✅ 账号 "${username}" (ID: ${user.id}) 密码已重置，请用新密码重新登录`);
    process.exit(0);
  });
});
