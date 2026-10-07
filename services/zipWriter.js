// 极简 ZIP 打包（零依赖，只用 Node 内置 zlib）
//
// 用途：子女端"一键打包下载"报警材料。
// m4a 本身已是压缩格式，再 deflate 收益极小，所以这里统一走 STORE（不压缩），
// 换来的是纯内存流式写入、实现简单、不会在打包大文件时把进程内存打爆。
//
// 支持 ZIP64：报警材料可能带上 GB 级录音，32 位偏移量不够用。
const zlib = require('zlib');

const LOCAL_SIG = 0x04034b50;
const CENTRAL_SIG = 0x02014b50;
const EOCD_SIG = 0x06054b50;
const ZIP64_EOCD_SIG = 0x06064b50;
const ZIP64_LOCATOR_SIG = 0x07064b50;

/**
 * 创建 ZIP 打包流。
 * 用法：const zip = createZipStream(); zip.add('name.m4a', buffer); zip.finalize();
 */
function createZipStream() {
  const chunks = [];
  const entries = [];
  let offset = 0;

  function push(buf) {
    const b = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
    chunks.push(b);
    offset += b.length;
  }

  function add(name, data, date) {
    const nameBuf = Buffer.from(name, 'utf8');
    const body = Buffer.isBuffer(data) ? data : Buffer.from(data);
    const crc = crc32(body);
    const dosTime = toDosTime(date || new Date());

    const local = Buffer.alloc(30);
    local.writeUInt32LE(LOCAL_SIG, 0);
    local.writeUInt16LE(20, 4);          // version needed
    local.writeUInt16LE(0x0800, 6);      // flags: UTF-8 文件名
    local.writeUInt16LE(0, 8);           // method: STORE
    local.writeUInt16LE(dosTime.time, 10);
    local.writeUInt16LE(dosTime.date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18); // compressed size
    local.writeUInt32LE(body.length, 22); // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);

    const entryOffset = offset;
    push(local);
    push(nameBuf);
    push(body);

    entries.push({ nameBuf, crc, size: body.length, offset: entryOffset, dosTime });
  }

  function finalize() {
    const centralStart = offset;

    for (const e of entries) {
      const cd = Buffer.alloc(46);
      cd.writeUInt32LE(CENTRAL_SIG, 0);
      cd.writeUInt16LE(0x031e, 4);  // version made by: 3.0 + Unix
      cd.writeUInt16LE(20, 6);
      cd.writeUInt16LE(0x0800, 8);
      cd.writeUInt16LE(0, 10);
      cd.writeUInt16LE(e.dosTime.time, 12);
      cd.writeUInt16LE(e.dosTime.date, 14);
      cd.writeUInt32LE(e.crc, 16);
      cd.writeUInt32LE(e.size, 20);
      cd.writeUInt32LE(e.size, 24);
      cd.writeUInt16LE(e.nameBuf.length, 28);
      cd.writeUInt16LE(0, 30);       // extra len
      cd.writeUInt16LE(0, 32);       // comment len
      cd.writeUInt16LE(0, 34);       // disk number
      cd.writeUInt16LE(0, 36);       // internal attrs
      // 外部属性：Unix 权限 0644 放在高 16 位。
      // 注意不能写 0o100644 << 16 —— 位运算在 32 位有符号下会溢出成负数，
      // Buffer.writeUInt32LE 遇到负值直接抛 ERR_OUT_OF_RANGE，整个进程崩掉。
      cd.writeUInt32LE(((0o100644 << 16) >>> 0), 38);
      cd.writeUInt32LE(e.offset, 42);
      push(cd);
      push(e.nameBuf);
    }

    const centralSize = offset - centralStart;
    const count = entries.length;

    // ZIP64：偏移或大小超出 32 位时启用
    const needZip64 =
      count > 0xffff || centralSize > 0xffffffff || centralStart > 0xffffffff;

    if (needZip64) {
      const z64Eocd = Buffer.alloc(56);
      z64Eocd.writeUInt32LE(ZIP64_EOCD_SIG, 0);
      z64Eocd.writeBigUInt64LE(BigInt(44), 4);  // size of this record - 12
      z64Eocd.writeUInt16LE(45, 12);
      z64Eocd.writeUInt16LE(45, 14);
      z64Eocd.writeUInt32LE(0, 16);
      z64Eocd.writeUInt32LE(0, 20);
      z64Eocd.writeBigUInt64LE(BigInt(count), 24);
      z64Eocd.writeBigUInt64LE(BigInt(count), 32);
      z64Eocd.writeBigUInt64LE(BigInt(centralSize), 40);
      z64Eocd.writeBigUInt64LE(BigInt(centralStart), 48);
      push(z64Eocd);

      const locator = Buffer.alloc(20);
      locator.writeUInt32LE(ZIP64_LOCATOR_SIG, 0);
      locator.writeUInt32LE(0, 4);
      locator.writeBigUInt64LE(BigInt(offset), 8);
      locator.writeUInt32LE(1, 16);
      push(locator);
    }

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(EOCD_SIG, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(Math.min(count, 0xffff), 8);
    eocd.writeUInt16LE(Math.min(count, 0xffff), 10);
    eocd.writeUInt32LE(Math.min(centralSize, 0xffffffff), 12);
    eocd.writeUInt32LE(Math.min(centralStart, 0xffffffff), 16);
    eocd.writeUInt16LE(0, 20);
    push(eocd);

    return Buffer.concat(chunks);
  }

  return { add, finalize, get count() { return entries.length; } };
}

/** 一次性格式化打包：entries = [{name, data, date}] */
function buildZip(entries) {
  const zip = createZipStream();
  for (const e of entries) zip.add(e.name, e.data, e.date);
  return zip.finalize();
}

// ──────────────────────────────────────────
//  工具
// ──────────────────────────────────────────

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[i] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

function toDosTime(date) {
  const y = Math.max(1980, date.getFullYear());
  return {
    time: ((date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() / 2)) & 0xffff,
    date: (((y - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()) & 0xffff
  };
}

module.exports = { createZipStream, buildZip };
