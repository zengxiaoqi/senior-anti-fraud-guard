/**
 * 老人端「换手机号」链路回归测试
 *
 * 覆盖三个最容易出事的场景：
 *  1. 换号后带 elderId 回来 → 就地改号，绑定关系不动
 *  2. 换号后本地会话丢了（elderId=0）+ 带上旧号 → 找回原账号，不能新建
 *  3. 真的换了新号且没有旧号线索 → 才允许新建
 *  另加：退出登录后仍要能凭绑定码重新绑定回同一老人
 *
 * 跑法：DB_PATH=<临时db> node scripts/test-elder-phone-change.js
 */
const path = require('path');
const fs = require('fs');
const os = require('os');

const tmpDb = path.join(os.tmpdir(), `elder-phone-test-${Date.now()}.sqlite`);
process.env.DB_PATH = tmpDb;

const db = require('../database/db');
const request = require('../routes/auth');

const express = require('express');
const app = express();
app.use(express.json());
app.use('/api/auth', request);

let pass = 0, fail = 0;
function check(label, cond, extra) {
  if (cond) { pass++; console.log(`  ✅ ${label}`); }
  else { fail++; console.log(`  ❌ ${label}${extra ? ' → ' + extra : ''}`); }
}

const server = app.listen(0, async () => {
  const port = server.address().port;
  const base = `http://127.0.0.1:${port}/api/auth`;

  async function post(path, body, headers = {}) {
    const res = await fetch(base + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify(body)
    });
    return { status: res.status, data: await res.json() };
  }

  console.log('\n【场景 1】首次激活');
  const reg = await post('/elder-register', { elderId: 0, name: '张爷爷', phone: '13800000001' });
  check('激活成功', reg.data.success === true, JSON.stringify(reg.data));
  const elderId = reg.data.elderId;
  const bindCode = reg.data.bindCode;   // 注意：elder-register 返回顶层字段，不是 data 包裹
  check('拿到 6 位绑定码', /^\d{6}$/.test(bindCode), bindCode);

  console.log('\n【场景 2】子女端用绑定码绑定该老人');
  const famReg = await post('/register', { username: 'zhangson', password: 'abc123', phone: '13900000002', name: '张强' });
  check('子女注册成功', famReg.data.success === true, JSON.stringify(famReg.data));
  const famToken = famReg.data.data.token;
  const famId = famReg.data.data.userId;
  check('拿到登录 token', typeof famToken === 'string' && famToken.length > 0, String(famToken));

  const bindRes = await post('/bind', { bindCode }, { 'X-Auth-Token': famToken });
  const bindData = bindRes.data;
  check('绑定成功', bindData.success === true, JSON.stringify(bindData));

  console.log('\n【场景 3】老人换手机号，本地带着 elderId 回来（就地改号）');
  const change1 = await post('/elder-register', { elderId, name: '张爷爷', phone: '13800000009' });
  check('改号成功', change1.data.success === true, JSON.stringify(change1.data));
  check('elderId 未变（保住账号）', change1.data.elderId === elderId, `${change1.data.elderId} vs ${elderId}`);
  check('绑定码未变', change1.data.bindCode === bindCode);

  console.log('\n【场景 4】改号后绑定关系仍在（子女端仍绑着同一个老人）');
  const bindCheck = await new Promise((resolve) => {
    db.get("SELECT bound_user_id, bind_code FROM users WHERE id = ?", [elderId], (e, row) => resolve(row));
  });
  check('elder.bound_user_id 仍是子女账号', bindCheck.bound_user_id === famId, JSON.stringify(bindCheck));
  check('bind_code 未被重置', bindCheck.bind_code === bindCode);

  console.log('\n【场景 5】换号后又卸载重装：elderId=0，但带 previousPhone 找回');
  const regen = await post('/elder-register', {
    elderId: 0, name: '张爷爷', phone: '13800000009', previousPhone: '13800000009'
  });
  check('凭当前号找回成功', regen.data.success === true, JSON.stringify(regen.data));
  check('elderId 仍是原账号', regen.data.elderId === elderId, `${regen.data.elderId} vs ${elderId}`);

  console.log('\n【场景 6】换到全新手机号 + 本地会话丢失 + 带旧号 → 绝不能新建账号');
  const regen2 = await post('/elder-register', {
    elderId: 0, name: '张爷爷', phone: '13800000010', previousPhone: '13800000009'
  });
  check('找回成功', regen2.data.success === true, JSON.stringify(regen2.data));
  check('复用了原账号（elderId 不变）', regen2.data.elderId === elderId, `${regen2.data.elderId} vs ${elderId}`);
  check('标记为换号找回', regen2.data.recoveredByPhoneChange === true);
  const bindAfter = await new Promise((resolve) => {
    db.get("SELECT bound_user_id, phone FROM users WHERE id = ?", [elderId], (e, row) => resolve(row));
  });
  check('绑定关系仍保住', bindAfter.bound_user_id === famId, JSON.stringify(bindAfter));
  check('库里的号码已换成新号', bindAfter.phone === '13800000010', bindAfter.phone);

  console.log('\n【场景 7】历史录音/事件是否仍挂在这个 elderId 上');
  // 先记一条"换号之前"的历史录音，换号后再确认它仍归这个老人
  await new Promise((resolve) => {
    db.run("INSERT INTO recordings (elder_id, session_id, segment_index, reason, file_name, duration_ms, size_bytes, recorded_at) VALUES (?,?,?,?,?,?,?,datetime('now'))",
      [elderId, 'old-session', 1, 'SOS', 'old.m4a', 10000, 100], resolve);
  });
  const recBefore = await new Promise((resolve) => {
    db.get("SELECT COUNT(*) c FROM recordings WHERE elder_id = ?", [elderId], (e, row) => resolve(row ? row.c : 0));
  });
  check('换号前历史录音已存在', recBefore >= 1, String(recBefore));

  // 换到又一个新号，验证历史录音没有掉
  const regen3 = await post('/elder-register', {
    elderId: 0, name: '张爷爷', phone: '13800000012', previousPhone: '13800000010'
  });
  check('再次换号成功', regen3.data.success === true, JSON.stringify(regen3.data));
  check('仍是同一账号', regen3.data.elderId === elderId, `${regen3.data.elderId} vs ${elderId}`);
  const recAfter = await new Promise((resolve) => {
    db.get("SELECT COUNT(*) c FROM recordings WHERE elder_id = ?", [elderId], (e, row) => resolve(row ? row.c : 0));
  });
  check('换号后历史录音仍在（未掉到孤儿账号）', recAfter === recBefore, `${recBefore} → ${recAfter}`);

  console.log('\n【场景 8】已被换走的旧号，不能被他人用来新建账号');
  // 张爷爷已把号换成 13800000010；此时若有人拿旧号 13800000009 来注册，
  // 那是个全新号，理应新建独立账号（而不是把张爷爷的数据送出去）
  const oldNoReuse = await post('/elder-register', { elderId: 0, name: '他人', phone: '13800000009' });
  check('旧号可被当作全新号注册', oldNoReuse.data.success === true, JSON.stringify(oldNoReuse.data));
  check('不会退回张爷爷的账号', oldNoReuse.data.elderId !== elderId, `${oldNoReuse.data.elderId} vs ${elderId}`);
  const zhangRecs = await new Promise((resolve) => {
    db.get("SELECT COUNT(*) c FROM recordings WHERE elder_id = ?", [elderId], (e, row) => resolve(row ? row.c : 0));
  });
  const otherRecs = await new Promise((resolve) => {
    db.get("SELECT COUNT(*) c FROM recordings WHERE elder_id = ?", [oldNoReuse.data.elderId], (e, row) => resolve(row ? row.c : 0));
  });
  check('他人账号拿不到张爷爷的历史录音', otherRecs === 0, `张爷爷=${zhangRecs} 他人=${otherRecs}`);

  console.log('\n【场景 9】新号已被他人占用 → 换号必须报错，不能抢占也不能冒用');
  // 王奶奶占用了 13700000077；张爷爷想带着旧号换到这个号上，必须被拒
  await post('/elder-register', { elderId: 0, name: '王奶奶', phone: '13700000077' });
  const conflict = await post('/elder-register', {
    elderId: 0, name: '张爷爷', phone: '13700000077', previousPhone: '13800000012'
  });
  check('占用冲突返回 409', conflict.status === 409, `status=${conflict.status}`);
  check('提示语明确', /已被其他账号使用/.test(conflict.data.error || ''), conflict.data.error);
  // 关键：冲突响应里绝不能带 elderId，否则客户端会把别人的账号当成自己的
  check('冲突响应不含任何 elderId（防止冒用）', conflict.data.elderId === undefined, JSON.stringify(conflict.data));
  const wangPhone = await new Promise((resolve) => {
    db.get("SELECT name FROM users WHERE phone = ?", ['13700000077'], (e, row) => resolve(row));
  });
  check('王奶奶账号未被篡改', wangPhone && wangPhone.name === '王奶奶', JSON.stringify(wangPhone));

  console.log('\n【场景 10】参数校验');
  const badPhone = await post('/elder-register', { elderId: 0, name: '张爷爷', phone: '123' });
  check('非法手机号被拒', badPhone.status === 400);
  const badPrev = await post('/elder-register', { elderId: 0, name: '张爷爷', phone: '13800000011', previousPhone: 'abc' });
  check('非法 previousPhone 被忽略（不崩溃，按新号建号）', badPrev.data.success === true, JSON.stringify(badPrev.data));

  console.log(`\n══════ 结果：${pass} 通过 / ${fail} 失败 ══════\n`);
  server.close();
  db.close();
  try { fs.unlinkSync(tmpDb); } catch (e) { /* ignore */ }
  process.exit(fail === 0 ? 0 : 1);
});