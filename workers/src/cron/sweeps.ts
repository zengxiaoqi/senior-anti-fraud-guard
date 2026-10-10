/**
 * Cron 定时任务入口（骨架）。
 *
 * 对应本地后端 services/recordingCleanup.js 里的 setInterval —— Workers 没有长驻进程，
 * 定时清扫只能走 Cron Triggers（wrangler.jsonc 的 triggers.crons）。
 */
import type { Env } from '../env';

export async function handleScheduled(cron: string, env: Env, ctx: ExecutionContext): Promise<void> {
  // P4/P5 阶段填充：
  //   "*/1 * * * *" → analyzeSweep：捞 transcript_status='PENDING' 的录音做转写 + 研判
  //   "0 4 * * *"   → cleanupSweep：按保留策略清理到期录音（FRAUD 永久 / 其余 14 天）
  console.log(`[cron] ${cron} 触发（迁移期暂不执行具体逻辑） stage=${env.SERVICE_STAGE || 'migration'}`);
}
