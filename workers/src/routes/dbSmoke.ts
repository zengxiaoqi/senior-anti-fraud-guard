/**
 * 数据层自检端点（POST /api/_smoke/db）。
 *
 * 目的：在真实 D1 运行时里把 P2 的每个 DAO 都过一遍读写删，不用等到 P3 路由
 * 全部接好才发现 API 用法问题（例如 D1 的 meta 字段差异、FK 顺序）。
 * 原则：只创建带 _smoke 标记的临时行，跑完即删，不碰存量数据。
 */
import type { Env } from '../env';
import * as users from '../db/dao/users';
import * as events from '../db/dao/events';
import * as geofence from '../db/dao/geofence';
import * as recordings from '../db/dao/recordings';
import * as tokenAuth from '../services/tokenAuth';

const MARK = '_smoke_';

export async function runDbSmoke(env: Env): Promise<Response> {
  const steps: Record<string, unknown> = {};

  try {
    // ── users ────────────────────────────────
    const bindCode = await users.generateUniqueBindCode(env);
    const phone = `${MARK}${Date.now()}`;
    const userId = await users.createElder(env, { name: MARK + 'elder', phone, bindCode });
    const fetched = await users.findElderById(env, userId);
    steps.users = {
      created: userId,
      fetchedOk: !!fetched && fetched.phone === phone,
      bindCodeUnique: !(await users.bindCodeTaken(env, bindCode, userId))
    };

    // ── events ───────────────────────────────
    const eventId = await events.insertRiskEvent(env, {
      elderId: userId,
      eventType: 'TEST',
      severity: 'HIGH',
      details: '{"smoke":true}'
    });
    const evList = await events.listRiskEvents(env, userId, 20);
    const locId = await events.insertLocation(env, {
      elderId: userId,
      latitude: 28.27,
      longitude: 113.06,
      address: MARK,
      isSensitive: 0
    });
    steps.events = { eventId, listed: evList.length === 1, locationId: locId };

    // ── geofence ─────────────────────────────
    const fenceId = await geofence.insertFence(env, {
      elderId: userId,
      name: MARK,
      latitude: 28.27,
      longitude: 113.06,
      radius: 200,
      dwellMinutes: 5
    });
    const elderFences = await geofence.listEnabledForElder(env, userId);
    const upd = await geofence.getFence(env, fenceId, userId);
    await geofence.updateFence(env, fenceId, {
      name: MARK + '2',
      radius: 300,
      enabled: 0,
      dwellMinutes: 10
    });
    const afterDisable = await geofence.listEnabledForElder(env, userId);
    steps.geofence = {
      fenceId,
      visibleWhenEnabled: elderFences.length === 1,
      updated: !!upd && upd.enabled === 1,
      hiddenAfterDisable: afterDisable.length === 0
    };

    // ── recordings ───────────────────────────
    const sha = `smoke${Date.now()}${'0'.repeat(24)}`;
    const recId = await recordings.insertRecording(env, {
      elderId: userId,
      sessionId: MARK,
      segmentIndex: 1,
      reason: 'SOS',
      placeName: null,
      fileName: `recordings/${userId}/${MARK}/smoke.m4a`,
      originalName: null,
      durationMs: 1234,
      sizeBytes: 45678,
      sha256: sha,
      mimeType: 'audio/mp4',
      recordedAt: new Date().toISOString(),
      latitude: 28.27,
      longitude: 113.06,
      address: MARK
    });
    const dup = await recordings.findBySha(env, userId, sha);
    await recordings.markTranscriptFailed(env, recId, {
      status: 'FAILED',
      error: 'smoke',
      verdict: 'smoke verdict',
      retentionUntil: new Date(Date.now() + 86400_000).toISOString()
    });
    const recRow = await recordings.findById(env, recId);
    const expired = await recordings.listExpiredForCleanup(env, new Date(Date.now() + 9 * 86400_000).toISOString());
    steps.recordings = {
      recId,
      shaDedupOk: !!dup && dup.id === recId,
      failedMarkedAsSuspect: recRow?.fraud_status === 'SUSPECT',
      retentionSet: !!recRow?.retention_until,
      cleanupSweepWouldPick: expired.some((r) => r.id === recId)
    };

    // ── tokenAuth ────────────────────────────
    const token = await tokenAuth.issueToken(env, userId);
    const tokenUser = await tokenAuth.verifyToken(env, token);
    await tokenAuth.revokeToken(env, token);
    const tokenAfterRevoke = await tokenAuth.verifyToken(env, token);
    steps.tokenAuth = { issued: token.length === 48, verified: tokenUser === userId, revoked: tokenAfterRevoke === null };

    // ── 清理临时行（先子后父，FK 顺序）──────
    await env.DB.prepare('DELETE FROM risk_events WHERE elder_id = ?').bind(userId).run();
    await env.DB.prepare('DELETE FROM locations WHERE elder_id = ?').bind(userId).run();
    await env.DB.prepare('DELETE FROM geofences WHERE elder_id = ?').bind(userId).run();
    await env.DB.prepare('DELETE FROM recordings WHERE elder_id = ?').bind(userId).run();
    await env.DB.prepare('DELETE FROM users WHERE id = ?').bind(userId).run();
    const leftover = await users.findElderById(env, userId);

    const allOk = Object.values(steps).every(
      (s) => typeof s === 'object' && s !== null && Object.values(s).every((v) => v !== false && v !== null)
    );
    return Response.json({
      success: allOk && leftover === null,
      cleanedUp: leftover === null,
      steps
    });
  } catch (e) {
    return Response.json({ success: false, error: String(e), steps }, { status: 500 });
  }
}
