const assert = require('assert');
const db = require('../database/db');

function generateBindCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function testGenerateBindCode() {
  const code = generateBindCode();
  assert.strictEqual(code.length, 6, '绑定码应为 6 位');
  assert.match(code, /^\d{6}$/, '绑定码应为纯数字');
  console.log('✅ generateBindCode 测试通过');
}

function testUserCreation() {
  const testOpenid = 'test_openid_' + Date.now();
  const bindCode = generateBindCode();

  db.run(
    'INSERT INTO users (role, name, phone, bind_code, wx_openid) VALUES (?, ?, ?, ?, ?)',
    ['family', '测试用户', '139' + Date.now().toString().slice(-8), bindCode, testOpenid],
    function(err) {
      assert.ifError(err);

      db.get('SELECT * FROM users WHERE wx_openid = ?', [testOpenid], (err, row) => {
        assert.ifError(err);
        assert.strictEqual(row.wx_openid, testOpenid);
        assert.strictEqual(row.bind_code, bindCode);
        console.log('✅ 用户创建测试通过');

        db.run('DELETE FROM users WHERE wx_openid = ?', [testOpenid], (err) => {
          assert.ifError(err);
          console.log('✅ 测试数据已清理');
        });
      });
    }
  );
}

testGenerateBindCode();
testUserCreation();
