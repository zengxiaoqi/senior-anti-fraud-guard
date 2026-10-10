/**
 * 极简 ZIP 打包（STORE 不压缩，零依赖）——对齐本地 services/zipWriter.js。
 *
 * m4a 已是压缩格式，deflate 收益极小；统一 STORE 换实现简单与内存可控。
 * 支持 ZIP64（报警材料可能上 GB，32 位偏移不够用）。
 *
 * 移植注意（项目铁律）：Node 位运算是 32 位有符号，写 Buffer 前必须 `>>> 0`，
 * 否则 writeUInt32LE 遇到负值直接抛 ERR_OUT_OF_RANGE。
 */

const LOCAL_SIG = 0x04034b50;
const CENTRAL_SIG = 0x02014b50;
const EOCD_SIG = 0x06054b50;
const ZIP64_EOCD_SIG = 0x06064b50;
const ZIP64_LOCATOR_SIG = 0x07064b50;

export interface ZipEntry {
  name: string;
  data: Uint8Array | string;
  date?: Date;
}

let CRC_TABLE: Int32Array | null = null;
function crc32(buf: Uint8Array): number {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? (0xedb88320 ^ (c >>> 1)) >>> 0 : c >>> 1;
      CRC_TABLE[i] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) {
    crc = ((crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xff]) >>> 0;
  }
  return (crc ^ -1) >>> 0;
}

function toDosTime(date: Date): { time: number; date: number } {
  const y = Math.max(1980, date.getFullYear());
  return {
    time: ((date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() / 2)) & 0xffff,
    date: (((y - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()) & 0xffff
  };
}

interface CentralRec {
  nameBuf: Buffer;
  crc: number;
  size: number;
  offset: number;
  dosTime: { time: number; date: number };
}

/** 一次性打包：entries = [{name, data, date?}]，返回完整 zip 字节 */
export function buildZip(entries: ZipEntry[]): Uint8Array {
  const chunks: Uint8Array[] = [];
  const central: CentralRec[] = [];
  let offset = 0;

  const push = (b: Uint8Array) => {
    chunks.push(b);
    offset += b.length;
  };
  const toBuf = (d: Uint8Array | string): Buffer =>
    typeof d === 'string' ? Buffer.from(d, 'utf8') : Buffer.from(d);

  for (const e of entries) {
    const nameBuf = Buffer.from(e.name, 'utf8');
    const body = toBuf(e.data);
    const crc = crc32(body);
    const dosTime = toDosTime(e.date || new Date());

    const local = Buffer.alloc(30);
    local.writeUInt32LE(LOCAL_SIG, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // flags: UTF-8 文件名
    local.writeUInt16LE(0, 8); // method: STORE
    local.writeUInt16LE(dosTime.time, 10);
    local.writeUInt16LE(dosTime.date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(body.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);

    const entryOffset = offset;
    push(local);
    push(nameBuf);
    push(body);
    central.push({ nameBuf, crc, size: body.length, offset: entryOffset, dosTime });
  }

  const centralStart = offset;
  for (const e of central) {
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(CENTRAL_SIG, 0);
    cd.writeUInt16LE(0x031e, 4); // version made by: 3.0 + Unix
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(0, 10);
    cd.writeUInt16LE(e.dosTime.time, 12);
    cd.writeUInt16LE(e.dosTime.date, 14);
    cd.writeUInt32LE(e.crc, 16);
    cd.writeUInt32LE(e.size, 20);
    cd.writeUInt32LE(e.size, 24);
    cd.writeUInt16LE(e.nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);
    cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34);
    cd.writeUInt16LE(0, 36);
    // Unix 权限 0644 放高 16 位；必须 >>> 0（见文件头铁律注释）
    cd.writeUInt32LE(((0o100644 << 16) >>> 0), 38);
    cd.writeUInt32LE(e.offset, 42);
    push(cd);
    push(e.nameBuf);
  }

  const centralSize = offset - centralStart;
  const count = central.length;
  const needZip64 = count > 0xffff || centralSize > 0xffffffff || centralStart > 0xffffffff;

  if (needZip64) {
    const z64 = Buffer.alloc(56);
    z64.writeUInt32LE(ZIP64_EOCD_SIG, 0);
    z64.writeBigUInt64LE(BigInt(44), 4);
    z64.writeUInt16LE(45, 12);
    z64.writeUInt16LE(45, 14);
    z64.writeUInt32LE(0, 16);
    z64.writeUInt32LE(0, 20);
    z64.writeBigUInt64LE(BigInt(count), 24);
    z64.writeBigUInt64LE(BigInt(count), 32);
    z64.writeBigUInt64LE(BigInt(centralSize), 40);
    z64.writeBigUInt64LE(BigInt(centralStart), 48);
    push(z64);

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

  const total = chunks.reduce((n, c) => n + c.length, 0);
  const out = new Uint8Array(total);
  let pos = 0;
  for (const c of chunks) {
    out.set(c, pos);
    pos += c.length;
  }
  return out;
}
