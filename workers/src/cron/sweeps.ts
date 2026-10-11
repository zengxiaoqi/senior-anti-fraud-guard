/**
 * Cron 定时任务入口。
 *
 * 对应本地后端 services/recordingCleanup.js 的 setInterval 与
 * services/elderPresenceScanner.js 的 setInterval —— Workers 没有长驻进程，
 * 定时清扫只能走 Cron Triggers（wrangler.jsonc 的 triggers.crons）。
 */
import type { Env } from '../env';
import { runPresenceSweep } from './presenceSweep';

/** 扫描间隔的合法范围（分钟）：下界防误配成秒级狂扫，上界防失联告警彻底失灵 */
const MIN_INTERVAL_MIN = 5;
const MAX_INTERVAL_MIN = 240;
const DEFAULT_INTERVAL_MIN = 30;

/** KV key：运行时覆盖扫描间隔，改完下一轮心跳就生效，不用重新部署 */
const INTERVAL_OVERRIDE_KEY = 'config:presenceScanIntervalMin';
/** KV key：上一次真正执行扫描的时间戳（毫秒） */
const LAST_RUN_KEY = 'presence:lastRunAt';

/**
 * 扫描闸门：cron 每 5 分钟只是心跳，这一轮到底跑不跑由配置决定。
 *
 * 为什么不把 cron 直接写成目标频率：Cron Trigger 是静态的，改频率要重新部署；
 * 做成配置后，测试期调频率只需要改 KV（或改 wrangler.jsonc 的 vars 后部署一次）。
 * KV 读远比 D1 便宜（免费额度 10 万读/天 vs D1 500 万行/天，且心跳本身不碰 D1）。
 *
 * 优先级：KV `config:presenceScanIntervalMin` > 环境变量 SCAN_INTERVAL_MINUTES > 默认 30。
 */
async function gatePresenceScan(env: Env): Promise<{ run: boolean; intervalMin: number; reason: string }> {
  const now = Date.now();

  let intervalMin = Number(env.SCAN_INTERVAL_MINUTES);
  if (!Number.isFinite(intervalMin) || intervalMin <= 0) intervalMin = DEFAULT_INTERVAL_MIN;

  try {
    const override = parseInt(String(await env.CACHE.get(INTERVAL_OVERRIDE_KEY) ?? ''), 10);
    if (Number.isFinite(override) && override > 0) intervalMin = override;
  } catch {
    // KV 不可用就用环境变量/默认值，别让一次 KV 抖动把整条告警链路打断
  }
  intervalMin = Math.min(Math.max(Math.round(intervalMin), MIN_INTERVAL_MIN), MAX_INTERVAL_MIN);

  let lastRun = 0;
  try {
    lastRun = Number(await env.CACHE.get(LAST_RUN_KEY)) || 0;
  } catch {
    // 读不到就当作"从没跑过"，宁多跑一轮也别漏告警
  }

  if (now - lastRun < intervalMin * 60_000) {
    return { run: false, intervalMin, reason: `距上次扫描不足 ${intervalMin} 分钟` };
  }

  // 先记时间再跑：万一这轮抛异常，下一轮也要等间隔到了才会重试，
  // 避免异常风暴时每 5 分钟打一轮 D1（把配额炸掉的方向反过来同样成立）
  try {
    await env.CACHE.put(LAST_RUN_KEY, String(now));
  } catch {
    // 写失败顶多导致两轮靠得太近，可接受
  }
  return { run: true, intervalMin, reason: `按 ${intervalMin} 分钟间隔触发` };
}

export async function handleScheduled(cron: string, env: Env, ctx: ExecutionContext): Promise<void> {
  console.log(`[cron] ${cron} 触发 stage=${env.SERVICE_STAGE || 'migration'}`);
  void ctx;

  // "*/5 * * * *" → 失联扫描心跳：过闸门决定是否真跑（间隔配置见 gatePresenceScan）
  if (cron === '*/5 * * * *') {
    const gate = await gatePresenceScan(env);
    if (!gate.run) {
      console.log(`[cron] 失联扫描跳过：${gate.reason}`);
      return;
    }
    try {
      const res = await runPresenceSweep(env);
      console.log(`[cron] 失联扫描完成（${gate.reason}）：检查 ${res.scanned} 位老人，生成 ${res.alerted.length} 条告警`);
    } catch (e) {
      console.error('[cron] 失联扫描失败:', e instanceof Error ? e.message : e);
    }
    return;
  }

  // 其余 cron（录音分析扫描 / 保留策略清理）留待后续阶段填充
}
