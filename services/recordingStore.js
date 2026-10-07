// 录音文件落盘 + 完整性校验
//
// 为什么单独抽一层：
//  1. 报警取证的第一个门槛是"文件没被掉包/篡改"，所以每段录音落盘时立刻算 SHA-256 存库；
//  2. 老人端重传（断网队列重试）时用 client_sha256 做幂等去重，避免同一段录音重复入库；
//  3. 所有文件名由服务端生成，绝不使用老人端上传的原始文件名拼路径（防目录穿越）。
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const UPLOAD_ROOT = path.join(__dirname, '..', 'uploads');
const RECORDING_DIR = path.join(UPLOAD_ROOT, 'recordings');

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/**
 * 计算文件的 SHA-256（流式读取，避免大文件占内存）。
 * 报警材料里这就是"录音原始件摘要"，民警可现场复算比对。
 */
function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('error', reject);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

/** 对 Buffer 直接算摘要（用于重传时的小文件快速校验） */
function sha256Buffer(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/**
 * 生成安全的落盘文件名：<UTC时间戳>_<随机8位>.<ext>
 * 完全由服务端决定，扩展名走白名单，杜绝老人端传入 "../../x" 这类路径。
 *
 * 老人 ID 不拼进文件名：multer 的 filename 回调早于请求体解析完成，
 * 那时读不到 elderId。老人维度由 recordings 表的 elder_id 关联。
 */
function buildStoredName(originalName) {
  const ext = pickExtension(originalName);
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '_');
  const rand = crypto.randomBytes(4).toString('hex');
  return `${stamp}_${rand}${ext}`;
}

const EXT_WHITELIST = ['.m4a', '.mp4', '.aac', '.mp3', '.amr', '.3gp', '.wav', '.ogg'];

/** 只保留白名单扩展名，兜底给 .m4a */
function pickExtension(originalName) {
  const ext = path.extname(String(originalName || '')).toLowerCase();
  return EXT_WHITELIST.includes(ext) ? ext : '.m4a';
}

/** 确保目录存在并返回绝对路径 */
function ensureRecordingDir() {
  return ensureDir(RECORDING_DIR);
}

/** 由已存储的文件名解析出绝对路径，并二次确认它没跑出 uploads 目录 */
function resolveStoredPath(fileName) {
  const safe = path.basename(String(fileName || ''));
  const abs = path.join(RECORDING_DIR, safe);
  const normalizedRoot = path.resolve(RECORDING_DIR) + path.sep;
  if (!path.resolve(abs).startsWith(normalizedRoot)) return null;
  return abs;
}

function fileExists(fileName) {
  const abs = resolveStoredPath(fileName);
  return !!(abs && fs.existsSync(abs));
}

function fileSize(fileName) {
  const abs = resolveStoredPath(fileName);
  if (!abs || !fs.existsSync(abs)) return 0;
  try {
    return fs.statSync(abs).size;
  } catch (e) {
    return 0;
  }
}

/** 删除录音文件（AI 判定非诈骗后清理，或子女端人工删除） */
function deleteStoredFile(fileName) {
  const abs = resolveStoredPath(fileName);
  if (!abs) return false;
  try {
    if (fs.existsSync(abs)) {
      fs.unlinkSync(abs);
      return true;
    }
  } catch (e) {
    console.error('删除录音文件失败:', fileName, e.message);
  }
  return false;
}

module.exports = {
  UPLOAD_ROOT,
  RECORDING_DIR,
  ensureDir,
  ensureRecordingDir,
  sha256File,
  sha256Buffer,
  buildStoredName,
  pickExtension,
  resolveStoredPath,
  fileExists,
  fileSize,
  deleteStoredFile,
  EXT_WHITELIST
};
