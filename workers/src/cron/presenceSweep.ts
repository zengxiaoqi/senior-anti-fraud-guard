/**
 * 老人失联扫描（D1 版，对齐本地 services/elderPresenceScanner.js）。
 *
 * Workers 没有长驻进程，定时清扫走 Cron Triggers（wrangler.jsonc 的 triggers.crons）。
 * 判定口径完全走 ../lib/elderLiveness.ts，与本地同一把尺子；
 * 数据访问走 eventsDao（与 /status 接口共用同一批 SQL，避免两边口径漂移）。
 */
import type { Env } from '../env';
import * as eventsDao from '../db/dao/events';
import { notifyFamily } from '../lib/hub';
import {
  LIVENESS_ONLINE,
  evaluateLiveness,
  nextAlertState,
  describeThresholds,
  extractLivenessThresholds,
  parseSqliteTime,
} from '../lib/elderLiveness';

interface ElderRow {
  id: number;
  name: string | null;
  guard_settings: string | null;
}

function isoSqlite(ms: number): string {
  return new Date(ms).toISOString().replace('T', ' ').slice(0, 19);
}

function describeEvent(
  state: string,
  minutes: number | null,
  thresholds: { warnMinutes: number; offlineMinutes: number }
): string {
  if (state === LIVENESS_ONLINE) {
    return `老人已重新上报，守护恢复正常（此前中断约 ${minutes ?? 0} 分钟）`;
  }
  const human = (m: number | null) => {
    const v = Number(m) || 0;
    if (v < 60) return `${v} 分钟`;
    const h = Math.floor(v / 60);
    const rest = v % 60;
    return rest ? `${h} 小时 ${rest} 分钟` : `${h} 小时`;
  };
  if (state === 'SILENT') {
    return `老人已 ${human(minutes)} 没有任何上报，超过 ${thresholds.warnMinutes} 分钟静默阈值，请留意`;
  }
  return `老人已 ${human(minutes)} 没有任何上报，超过 ${thresholds.offlineMinutes} 分钟失联阈值，请立即电话或上门核实`;
}

export async function runPresenceSweep(env: Env): Promise<{ scanned: number; alerted: string[] }> {
  const nowMs = Date.now();
  const alerted: string[] = [];

  // 只扫「有绑定子女」的 elder：没有绑定就没有告警对象，
  // 给这样的老人记状态只会污染他自己的数据。
  // （守护开关 guard_enabled 是**本机**状态，服务端无从得知，这是唯一可判的筛子。）
  const { results } = await env.DB.prepare(
    "SELECT id, name, guard_settings FROM users WHERE role = 'elder' AND bound_user_id IS NOT NULL"
  ).all<ElderRow>();

  const elders = results ?? [];
  for (const elder of elders) {
    try {
      const lastTs = parseSqliteTime(await eventsDao.getLastReportAt(env, elder.id));
      const minutesSince = lastTs === null ? null : Math.floor((nowMs - lastTs) / 60000);

      const { state, thresholds } = evaluateLiveness(
        minutesSince,
        extractLivenessThresholds(elder.guard_settings)
      );

      const lv = await eventsDao.getLivenessRow(env, elder.id);
      const previousAlerted = lv?.alerted_state ?? null;
      const decision = nextAlertState(state, previousAlerted);

      // 状态与告警级别都没变且无需新告警 → 不写库。
      // 无条件 UPDATE 会把 updated_at 刷成"刚刚"，这个字段就再也说明不了任何事。
      const stateUnchanged = (lv?.state ?? null) === state;
      const alertedUnchanged = (lv?.alerted_state ?? null) === previousAlerted;
      if (stateUnchanged && alertedUnchanged && !decision.shouldAlert) continue;

      const nowIso = isoSqlite(nowMs);
      const nextAlerted =
        decision.shouldAlert && decision.eventType !== 'ELDER_RECONNECTED'
          ? state
          : decision.eventType === 'ELDER_RECONNECTED'
            ? null
            : previousAlerted;

      await eventsDao.upsertLiveness(env, {
        elderId: elder.id,
        state,
        alertedState: nextAlerted,
        lastReportAt: lastTs === null ? null : isoSqlite(lastTs),
        nowIso,
      });

      if (decision.shouldAlert && decision.eventType && decision.severity) {
        const details = {
          state,
          minutes_since_report: minutesSince,
          warn_minutes: thresholds.warnMinutes,
          offline_minutes: thresholds.offlineMinutes,
          threshold_desc: describeThresholds(thresholds),
          event_desc: describeEvent(state, minutesSince, thresholds),
        };
        await eventsDao.insertRiskEvent(env, {
          elderId: elder.id,
          eventType: decision.eventType,
          severity: decision.severity,
          details: JSON.stringify(details),
        });
        await notifyFamily(env, elder.id, {
          type: 'RISK_ALERT',
          data: {
            id: 0,
            elder_id: elder.id,
            event_type: decision.eventType,
            severity: decision.severity,
            details,
            created_at: nowIso,
            // 是否打断子女必须由服务端算好（复用同一把尺子 shouldInterrupt 的口径），
            // 否则会出现"服务端广播说该打断、客户端不弹"的静默不一致。
            // 这里不能直接 import shouldInterrupt：lib 不依赖 hub 层，保持单向依赖。
            interruptible: decision.eventType === 'ELDER_OFFLINE',
          },
        });
        alerted.push(`${elder.name ?? elder.id}:${decision.eventType}`);
      }
    } catch (e) {
      // 单个老人失败不中断整轮扫描
      console.error(`[失联扫描] 老人 ${elder.id} 判定异常:`, e instanceof Error ? e.message : e);
    }
  }

  if (alerted.length) console.log(`[失联扫描] 本轮生成告警：${alerted.join('、')}`);
  return { scanned: elders.length, alerted };
}
