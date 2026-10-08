// ZIP 打包器回归测试
//
// 为什么重要：报警材料 ZIP 是"一键报案"链路最后一环，
// 打坏了民警打开就是一堆废文件，等于整个证据链前功尽弃。
// 本文件自带一个最小 ZIP 读取器，按格式规范反解产物，
// 而不是只断言"函数没抛异常"。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createZipStream, buildZip } = require('../services/zipWriter');

const LOCAL_SIG = 0x04034b50;
const CENTRAL_SIG = 0x02014b50;
const EOCD_SIG = 0x06054b50;
const ZIP64_EOCD_SIG = 0x06064b50;
const ZIP64_LOCATOR_SIG = 0x07064b50;

/** 从尾部倒着找到 EOCD 记录 */
function findEocd(buf) {
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) return i;
  }
  throw new Error('未找到 EOCD，产物不是合法 ZIP');
}

/** 解析中央目录，返回条目元信息 */
function readCentralDirectory(buf) {
  const eocd = findEocd(buf);
  const count = buf.readUInt16LE(eocd + 10);
  let ptr = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    assert.equal(buf.readUInt32LE(ptr), CENTRAL_SIG, `第 ${i} 条中央目录签名错误`);
    const nameLen = buf.readUInt16LE(ptr + 28);
    const extraLen = buf.readUInt16LE(ptr + 30);
    const commentLen = buf.readUInt16LE(ptr + 32);
    entries.push({
      name: buf.subarray(ptr + 46, ptr + 46 + nameLen).toString('utf8'),
      method: buf.readUInt16LE(ptr + 10),
      flags: buf.readUInt16LE(ptr + 8),
      crc: buf.readUInt32LE(ptr + 16),
      size: buf.readUInt32LE(ptr + 24),
      localOffset: buf.readUInt32LE(ptr + 42),
      externalAttrs: buf.readUInt32LE(ptr + 38),
    });
    ptr += 46 + nameLen + extraLen + commentLen;
  }
  return { entries, eocd };
}

/** 按本地头定位并取出文件内容，校验 CRC */
function readEntryData(buf, entry) {
  const o = entry.localOffset;
  assert.equal(buf.readUInt32LE(o), LOCAL_SIG, `本地头签名错误：${entry.name}`);
  const localCrc = buf.readUInt32LE(o + 14);
  const localSize = buf.readUInt32LE(o + 18);
  const nameLen = buf.readUInt16LE(o + 26);
  const extraLen = buf.readUInt16LE(o + 28);
  const name = buf.subarray(o + 30, o + 30 + nameLen).toString('utf8');
  const dataStart = o + 30 + nameLen + extraLen;
  const data = buf.subarray(dataStart, dataStart + entry.size);
  // 本地头与中央目录必须记录同一个 CRC 和同一个长度，否则部分解压工具会解出坏文件
  assert.equal(localCrc, entry.crc, `本地头与中央目录 CRC 不一致：${entry.name}`);
  assert.equal(localSize, entry.size, `本地头与中央目录长度不一致：${entry.name}`);
  assert.equal(name, entry.name, `本地头与中央目录文件名不一致：${entry.name}`);
  assert.equal(crc32(data), entry.crc, `CRC 不匹配：${entry.name}`);
  return { name, data };
}

// 独立的 CRC32 实现（刻意不复用被测代码，避免自证）
let TBL = null;
function crc32(buf) {
  if (!TBL) {
    TBL = new Int32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      TBL[i] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ TBL[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

// ──────────────────────────────────────────────
//  1. 基本结构
// ──────────────────────────────────────────────

test('单条目 ZIP 结构完整且内容可原样取回', () => {
  const payload = Buffer.from('反诈录音证据包测试');
  const buf = buildZip([{ name: '清单.txt', data: payload }]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].name, '清单.txt');
  assert.equal(entries[0].method, 0, 'm4a 已压缩，必须走 STORE 不再压');
  assert.deepEqual(readEntryData(buf, entries[0]).data, payload);
});

test('CRC32 实现符合标准校验值', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
});

test('多条目 ZIP 条目数与顺序正确', () => {
  const entries = [
    { name: '00_证据清单.txt', data: 'a'.repeat(10) },
    { name: '01_证据清单.json', data: '{"x":1}' },
    { name: '录音/2026-10-07/REC_0001_一键求助_第1段.m4a', data: 'ID3fakebinary' },
    { name: '录音/2026-10-08/REC_0002_围栏_第2段.m4a', data: Buffer.alloc(0) },
  ];
  const buf = buildZip(entries);
  const parsed = readCentralDirectory(buf).entries;
  assert.equal(parsed.length, 4);
  assert.deepEqual(parsed.map((e) => e.name), entries.map((e) => e.name));
  for (let i = 0; i < entries.length; i++) {
    const got = readEntryData(buf, parsed[i]).data;
    const want = Buffer.isBuffer(entries[i].data) ? entries[i].data : Buffer.from(entries[i].data);
    assert.deepEqual(got, want, `第 ${i} 条内容不一致`);
  }
});

test('空 ZIP（0 条目）也是合法结构，不崩', () => {
  const buf = buildZip([]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries.length, 0);
  assert.equal(buf.readUInt32LE(findEocd(buf)), EOCD_SIG);
});

test('空文件（0 字节）条目 CRC 正确', () => {
  const buf = buildZip([{ name: 'empty.bin', data: Buffer.alloc(0) }]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries[0].size, 0);
  assert.equal(entries[0].crc, 0);
});

// ──────────────────────────────────────────────
//  2. 中文与二进制
// ──────────────────────────────────────────────

test('中文/嵌套路径文件名正确置 UTF-8 标志位并可还原', () => {
  const name = '录音/2026-10-07/REC_0001_一键求助_第1段.m4a';
  const buf = buildZip([{ name, data: 'x' }]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries[0].flags & 0x0800, 0x0800, 'bit 11 必须置位声明 UTF-8 文件名');
  assert.equal(entries[0].name, name, '中文名不得乱码');
});

test('含 0x00-0xFF 全字节的二进制内容不被截断', () => {
  const bin = Buffer.from(Array.from({ length: 256 }, (_, i) => i));
  const buf = buildZip([{ name: 'a.m4a', data: bin }]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries[0].size, 256);
  assert.deepEqual(readEntryData(buf, entries[0]).data, bin);
});

test('字符串与 Buffer 两种入参等价', () => {
  const a = buildZip([{ name: 'a.txt', data: 'hello' }]);
  const b = buildZip([{ name: 'a.txt', data: Buffer.from('hello') }]);
  assert.deepEqual(a, b);
});

// ──────────────────────────────────────────────
//  3. 外部属性：回归 zipWriter.js:78-81 记录过的整型溢出崩溃
// ──────────────────────────────────────────────

test('外部属性写入不溢出（历史 bug：0o100644 << 16 变负数抛 ERR_OUT_OF_RANGE）', () => {
  const buf = buildZip([{ name: 'a.txt', data: 'x' }]);
  const { entries } = readCentralDirectory(buf);
  // Unix 0644 常规文件：0100644 << 16 = 0x81A40000，必须是正数无符号值
  assert.equal(entries[0].externalAttrs >>> 16, 0o100644);
  assert.ok(entries[0].externalAttrs >= 0, '不得为负数');
});

// ──────────────────────────────────────────────
//  4. DOS 时间
// ──────────────────────────────────────────────

test('DOS 时间字段由传入 date 决定', () => {
  const d = new Date(2026, 9, 7, 14, 30, 20); // 2026-10-07 14:30:20 本地时间
  const buf = buildZip([{ name: 'a.txt', data: 'x', date: d }]);
  const { entries } = readCentralDirectory(buf);
  const dosDate = entries[0];
  // 这里通过本地头重新读出 time/date（中央目录偏移：time@12 date@14）
  const eocd = findEocd(buf);
  const ptr = buf.readUInt32LE(eocd + 16);
  const t = buf.readUInt16LE(ptr + 12);
  const dt = buf.readUInt16LE(ptr + 14);
  assert.equal(dt, (((2026 - 1980) << 9) | (10 << 5) | 7), 'DOS 日期字段错误');
  assert.equal(t, ((14 << 11) | (30 << 5) | (20 / 2 | 0)) & 0xffff, 'DOS 时间字段错误');
  assert.ok(dosDate);
});

test('早于 1980 的日期被夹到 1980 而不是产生非法年份', () => {
  const buf = buildZip([{ name: 'a.txt', data: 'x', date: new Date(1970, 0, 1) }]);
  const eocd = findEocd(buf);
  const dt = buf.readUInt16LE(buf.readUInt32LE(eocd + 16) + 14);
  assert.equal((dt >> 9) + 1980, 1980);
});

// ──────────────────────────────────────────────
//  5. ZIP64 分支：靠"条目数 > 65535"触发，无需造 GB 级文件
// ──────────────────────────────────────────────

test('条目数超过 65535 时正确写出 ZIP64 记录（needZip64 分支）', () => {
  const zip = createZipStream();
  const N = 65536;
  for (let i = 0; i < N; i++) zip.add(`f${i}.txt`, 'a');
  const buf = zip.finalize();
  assert.equal(zip.count, N);

  const eocd = findEocd(buf);
  // 定位 ZIP64 EOCD 记录：它紧挨在 EOCD 之前（中间还有 20 字节 locator）
  const locator = eocd - 20;
  assert.equal(buf.readUInt32LE(locator), ZIP64_LOCATOR_SIG, '缺少 ZIP64 locator');
  const z64 = locator - 56;
  assert.equal(buf.readUInt32LE(z64), ZIP64_EOCD_SIG, '缺少 ZIP64 EOCD');
  // ZIP64 里必须是 64 位的真实条目数，不能被截成 16 位
  assert.equal(Number(buf.readBigUInt64LE(z64 + 32)), N);
  // 经典 EOCD 的 16 位计数应被钳到 0xffff 而不是溢出
  assert.equal(buf.readUInt16LE(eocd + 10), 0xffff);
});

/** 扫描完整 4 字节签名出现次数（单字节比对不可靠：EOCD 与 ZIP64 EOCD 末字节都是 0x50） */
function countSignature(buf, sig) {
  const pat = Buffer.alloc(4);
  pat.writeUInt32LE(sig >>> 0, 0);
  let n = 0, from = 0, at;
  while ((at = buf.indexOf(pat, from)) !== -1) { n++; from = at + 1; }
  return n;
}

test('条目数不足 65535 时不写 ZIP64（避免无谓体积）', () => {
  const buf = buildZip([{ name: 'a.txt', data: 'x' }]);
  assert.equal(countSignature(buf, ZIP64_EOCD_SIG), 0, '不应出现 ZIP64 EOCD');
  assert.equal(countSignature(buf, ZIP64_LOCATOR_SIG), 0, '不应出现 ZIP64 locator');
  // 反证：触发分支的用例里这两个签名都应存在
  const big = createZipStream();
  for (let i = 0; i < 65536; i++) big.add(`f${i}.txt`, 'a');
  const bigBuf = big.finalize();
  assert.equal(countSignature(bigBuf, ZIP64_EOCD_SIG), 1);
  assert.equal(countSignature(bigBuf, ZIP64_LOCATOR_SIG), 1);
});

test('顺序添加与增量 offset 保持一致（多条目本地头偏移须正确）', () => {
  const buf = buildZip([
    { name: 'big.bin', data: Buffer.alloc(70000, 7) },
    { name: 'small.txt', data: 'tail' },
  ]);
  const { entries } = readCentralDirectory(buf);
  assert.equal(entries[0].size, 70000);
  assert.deepEqual(readEntryData(buf, entries[1]).data, Buffer.from('tail'));
});