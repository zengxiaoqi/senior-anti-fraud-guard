#!/usr/bin/env node
/**
 * 存量录音上传：本地 uploads/ → R2，并生成 file_name → R2 key 的回填 SQL。
 *
 * 用法（workers/ 目录）：
 *   node scripts/upload-to-r2.mjs --dry-run                    # 只打印映射，不碰任何存储
 *   node scripts/upload-to-r2.mjs --local                      # 上传到本地模拟 R2（验证用）
 *   node scripts/upload-to-r2.mjs --remote                     # 正式上传（需 wrangler login）
 *   完成后执行生成的 updates.sql：
 *   npx wrangler d1 execute safg-db --local|--remote --file scripts/r2-key-updates.sql
 *
 * key 方案：recordings/{elderId}/{sessionId}/{id}{ext}
 *  - elderId 隔离删除面（R2 无目录，但统一前缀便于按户清理与对账）；
 *  - id 是 recordings 主键，天然唯一；ext 从 mime/原名推断（audioMimeOf 的反向）。
 *
 * 依赖 node:sqlite（Node 22.5+，实验性但够用）；上传走 npx wrangler r2 object put，
 * 不引入 AWS SDK，省签名配置。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const args = process.argv.slice(2);
const mode = args.includes('--remote') ? 'remote' : args.includes('--local') ? 'local' : 'dry-run';
const ROOT = dirname(dirname(import.meta.url.replace('file:///', '').replaceAll('/', '\\'))); // workers/
const DB_PATH = args.find((a, i) => args[i - 1] === '--db') || join(ROOT, '..', 'data.sqlite');
const UPLOADS = join(ROOT, '..', 'uploads', 'recordings'); // recordingStore.js 的落盘子目录
const BUCKET = 'safg-recordings';

function extOf(mime, originalName) {
  if (originalName && /\.[a-z0-9]{2,5}$/i.test(originalName)) return originalName.match(/\.[a-z0-9]{2,5}$/i)[0].toLowerCase();
  if (mime) {
    const sub = mime.split('/')[1] || '';
    if (sub === 'mp4' || sub === 'm4a') return '.m4a';
    if (sub === 'aac') return '.aac';
    if (sub === 'amr') return '.amr';
    if (sub === 'mpeg') return '.mp3';
    if (sub === 'wav') return '.wav';
  }
  return '.m4a';
}

const db = new DatabaseSync(DB_PATH);
const rows = db
  .prepare('SELECT id, elder_id, session_id, file_name, original_name, mime_type FROM recordings ORDER BY id')
  .all();

const updates = [];
const failures = [];
let uploaded = 0;
let skipped = 0;

for (const r of rows) {
  const src = join(UPLOADS, r.file_name);
  if (!existsSync(src)) {
    console.warn(`MISS  #${r.id} file not found: ${r.file_name} (already cleaned up? skip)`);
    skipped++;
    continue;
  }
  const key = `recordings/${r.elder_id}/${r.session_id}/${r.id}${extOf(r.mime_type, r.original_name)}`;
  updates.push(`UPDATE recordings SET file_name = '${key}' WHERE id = ${r.id};`);

  if (mode === 'dry-run') {
    console.log(`PLAN  #${r.id} ${r.file_name} -> ${key}`);
    continue;
  }
  const flag = mode === 'remote' ? '--remote' : '--local';
  let ok = false;
  let lastErr = '';
  // 重试 3 次：单次 wrangler 调用偶发代理/网络抖动，幂等 put 可安全重传
  for (let attempt = 1; attempt <= 3 && !ok; attempt++) {
    const res = spawnSync('npx', ['wrangler', 'r2', 'object', 'put', `${BUCKET}/${key}`, `--file`, src, flag], {
      cwd: ROOT,
      stdio: 'pipe',
      encoding: 'utf-8',
      shell: process.platform === 'win32'
    });
    if (res.status === 0) {
      ok = true;
      break;
    }
    lastErr = [res.stderr, res.stdout].filter(Boolean).join('\n').trim() || '(empty stderr)';
    if (attempt < 3) console.warn(`RETRY #${r.id} (attempt ${attempt} failed)`);
  }
  if (!ok) {
    console.error(`FAIL  #${r.id} -> ${key}\n${lastErr.slice(0, 800)}`);
    failures.push({ id: r.id, key, err: lastErr.slice(0, 200) });
    continue; // 不中断：把剩余文件传完，最后汇总失败清单
  }
  uploaded++;
  console.log(`PUT   #${r.id} -> ${key}`);
}

const outFile = join(ROOT, 'scripts', 'r2-key-updates.sql');
writeFileSync(outFile, `-- file_name -> R2 key backfill (${mode})\n${updates.join('\n')}\n`, 'utf-8');
console.log(
  `\nSummary: ${rows.length} rows, ${mode === 'dry-run' ? 'planned' : 'uploaded'} ${mode === 'dry-run' ? rows.length - skipped : uploaded}, skipped(missing) ${skipped}` +
    `\nBackfill SQL written to ${outFile} — run it with wrangler d1 execute AFTER upload succeeds.`
);
if (failures.length > 0) {
  console.error(`\n${failures.length} FAILED uploads:\n${failures.map((f) => `  #${f.id} ${f.key}\n    ${f.err}`).join('\n')}`);
  // 让 stdout 缓冲先落盘再退出，避免丢失汇总输出
  process.exitCode = 1;
}
