/**
 * /api/auth 路由（对齐本地 routes/auth.js，逐分支移植）。
 *
 * 关键契约点（tests/contract/auth.contract.test.js）：
 *  - elder-register 三态回执：boundFamily 未绑定时必须是显式 null；
 *  - 换号找回（previousPhone）必须抢在"按新号认领"之前（防冒用他人账号）；
 *  - 找回路径姓名留空沿用服务端原值（COALESCE(NULLIF)）；
 *  - elder-settings 增量合并 + 逐项夹取，空配置 400；
 *  - Android optString 读 JSON null 会得到字符串 "null"，NULL 一律抹平。
 */
import { Hono } from 'hono';
import { scryptSync, randomBytes, randomInt } from 'node:crypto';
import { issueToken } from '../services/tokenAuth';
import {
  findUserById,
  findFamilyForLogin,
  usernameExists,
  mobileTaken,
  findElderByPhone,
  findElderByPhoneFull,
  findElderById,
  createFamily,
  createElder,
  updateElderProfile,
  applyPhoneChange,
  updateMobile,
  updateBindCode,
  updatePasswordHash,
  findFamilyForPasswordReset,
  bindCodeTaken,
  setGuardSettings,
  getGuardSettings,
  getElderWithFamily,
  getUserWithBound,
  generateUniqueBindCode
} from '../db/dao/users';
import * as bindingsDao from '../db/dao/bindings';
import { requireFamilyAuth, requireBoundElder, type AppEnv } from '../middleware/auth';
import { notifyElder } from '../lib/hub';
import type { Env } from '../env';

// 中国大陆手机号格式校验
const MOBILE_RE = /^1[3-9]\d{9}$/;

/** 清洗用户输入：去除首尾空白 + 粘贴夹带的零宽字符/软连字符等不可见字符 */
function cleanIdentifier(s: unknown): string {
  return String(s ?? '')
    .trim()
    .replace(/[\u200B-\u200D\uFEFF\u2060\u00AD]/g, '');
}

/** 解析 guard_settings：脏数据一律当"没有配置"，不能让一行坏 JSON 把整个接口变 500 */
function parseGuardSettings(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null;
  try {
    const obj = JSON.parse(raw);
    return obj && typeof obj === 'object' ? obj : null;
  } catch {
    return null;
  }
}

/** 归一化防护规则配置：逐项做类型与范围校验，非法值一律丢弃用默认值 */
const GUARD_SETTING_BOUNDS: Record<string, { min?: number; max?: number; bool?: boolean }> = {
  callThresholdMinutes: { min: 1, max: 240 },
  paymentThreshold: { min: 1, max: 1000000 },
  recordingMaxSegments: { min: 1, max: 6 },
  // 对齐本地 services/guardSettingsPolicy.js：此前漏了单段时长，提交被 sanitize 静默丢弃
  recordingSegmentMinutes: { min: 1, max: 10 },
  recordingAutoUpload: { bool: true },
  // 对齐本地 services/guardSettingsPolicy.js：开屏广告拦截开关，默认 false（主动开启制）
  adSkipEnabled: { bool: true },
  homeAwayRadiusMeters: { min: 100, max: 5000 },
  stayMoveMeters: { min: 20, max: 1000 },
  homeStayMinutes: { min: 5, max: 240 },
  homeLat: { min: -90, max: 90 },
  homeLng: { min: -180, max: 180 },
  // 对齐本地 services/guardSettingsPolicy.js：跌倒检测失重时长下限（灵敏度）
  fallFreefallMinMs: { min: 100, max: 400 }
};

function sanitizeGuardSettings(input: unknown): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!input || typeof input !== 'object') return out;
  const obj = input as Record<string, unknown>;

  // 字符串型配置（信任列表 / 高危 App 远程规则）：只做类型与长度校验，内容原样透传
  for (const key of ['trustedCallNumbersJson', 'highRiskPackages']) {
    const v = obj[key];
    if (typeof v === 'string' && v.length <= 20000 && v.trimStart().startsWith('[')) {
      out[key] = v;
    }
  }

  for (const [key, bound] of Object.entries(GUARD_SETTING_BOUNDS)) {
    if (obj[key] === undefined || obj[key] === null) continue;
    if (bound.bool) {
      if (typeof obj[key] === 'boolean') out[key] = obj[key];
      continue;
    }
    const num = Number(obj[key]);
    if (!Number.isFinite(num)) continue;
    out[key] = Math.min(bound.max!, Math.max(bound.min!, num));
  }
  return out;
}

/**
 * 读取"清除家基准"指令（对齐本地 services/guardSettingsPolicy.js 的 extractHomeCleared）。
 * 必须在 sanitize 之前调用：sanitize 只处理 bounds 里的键，homeCleared 会被静默丢弃。
 * 严格 `=== true`：清除不可撤销，宁可不清除也不能被脏数据误触发。
 */
function extractHomeCleared(rawSettings: unknown): boolean {
  return (
    !!rawSettings &&
    typeof rawSettings === 'object' &&
    (rawSettings as Record<string, unknown>).homeCleared === true
  );
}

/**
 * 子女端可写字段白名单（对齐本地 services/guardSettingsPolicy.js）。
 * 白名单而非黑名单：新字段默认不可写。
 * 刻意排除 trustedCallNumbersJson（信任链是诈骗话术利用的东西）与
 * highRiskPackages（改了会静默缩小告警面，属运维级动作）。
 */
const FAMILY_WRITABLE_SETTINGS = [
  'homeAwayRadiusMeters',
  'stayMoveMeters',
  'homeStayMinutes',
  'homeLat',
  'homeLng',
  'callThresholdMinutes',
  'paymentThreshold',
  'recordingAutoUpload',
  'recordingMaxSegments',
  'recordingSegmentMinutes',
  'fallFreefallMinMs',
  'adSkipEnabled'
];

/** 保留白名单内的键，其余全部丢弃（入参应已过 sanitize，这里不重复校验范围） */
function pickFamilyWritable(settings: unknown): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!settings || typeof settings !== 'object') return out;
  const obj = settings as Record<string, unknown>;
  for (const key of FAMILY_WRITABLE_SETTINGS) {
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      out[key] = obj[key];
    }
  }
  return out;
}

/**
 * 把子女端提交的字段增量合并到已存配置上。
 * homeCleared 在合并之后处理，保证"清除"永远赢（不依赖键序）。
 */
function mergeFamilySettings(
  stored: Record<string, unknown> | null,
  incoming: Record<string, unknown>,
  homeCleared: boolean
): Record<string, unknown> {
  const merged = { ...(stored ?? {}), ...incoming };
  if (homeCleared) {
    delete merged.homeLat;
    delete merged.homeLng;
  }
  return merged;
}

/**
 * 老人端账号状态统一回执：一次带全 bindCode + boundFamilies + guardSettings。
 * 换机/重装后客户端不用再赌一次网络往返补齐状态（铁律：配置事实源在服务端）。
 * 多人绑定：boundFamilies=全部守护人（主在前）；boundFamily 兼容字段=主守护人。
 */
async function replyElderState(
  c: any,
  env: Env,
  elderId: number,
  extra: Record<string, unknown> = {}
): Promise<Response> {
  const found = await getElderWithFamily(env, elderId);
  if (!found) return c.json({ error: '老人账号不存在' }, 404);

  const payload: Record<string, unknown> = {
    success: true,
    elderId: found.elder.id,
    bindCode: found.elder.bind_code || '',
    name: found.elder.name,
    phone: found.elder.phone,
    ...extra
  };

  // 未绑定时明确回 null / 空数组，客户端据此清掉本地残留缓存（与"字段缺失"处理路径不同）
  payload.boundFamilies = found.families;
  payload.boundFamily = found.family ?? null;
  payload.guardSettings = parseGuardSettings(found.elder.guard_settings ?? null);
  return c.json(payload);
}

/** 子女视角绑定列表项 */
interface BoundElderInfo {
  id: number;
  name: string;
  phone: string;
  bindCode: string;
  isPrimary: boolean;
  boundAt: string;
}

/** 子女视角绑定列表（最早绑定在前），boundUser 兼容字段与 boundUsers 数组共用同源 */
async function fetchBoundElders(env: Env, userId: number): Promise<BoundElderInfo[]> {
  const rows = await bindingsDao.listEldersOfFamily(env, userId);
  return rows.map((r) => ({
    id: r.id,
    name: r.name,
    phone: r.phone,
    bindCode: r.bind_code,
    isPrimary: !!r.is_primary,
    boundAt: r.created_at
  }));
}

/**
 * 子女端登录/微信关联成功后的会话回执（/wx-login、/login、/wx-link 共用）。
 * boundUser 兼容字段 = 最早绑定的老人全行（旧客户端唯一认的字段）；
 * boundUsers = 全部 ACTIVE 老人列表（多人绑定的新客户端列表来源）。
 */
async function replyFamilySession(
  c: any,
  env: Env,
  user: { id: number; bind_code: string | null; mobile: string | null } | Record<string, any>,
  extra: Record<string, unknown> = {}
): Promise<Response> {
  const userId = Number(user.id);
  let list: BoundElderInfo[] = [];
  try {
    list = await fetchBoundElders(env, userId);
  } catch {
    list = [];
  }
  const primary = list.length ? list[0] : null;
  const boundUser = primary
    ? (await env.DB.prepare('SELECT id, role, name, phone, mobile FROM users WHERE id = ?')
        .bind(primary.id)
        .first<Record<string, unknown>>()) ?? null
    : null;
  return c.json({
    success: true,
    data: {
      userId,
      bindCode: user.bind_code,
      boundUser,
      boundUsers: list,
      mobile: user.mobile || '',
      mobileMissing: !user.mobile,
      token: await issueToken(env, userId),
      ...extra
    }
  });
}

function hashPassword(password: string): string {
  const salt = randomBytes(16).toString('hex');
  const hash = scryptSync(String(password), salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(password: string, stored: string): boolean {
  const [salt, hash] = String(stored).split(':');
  try {
    return scryptSync(String(password), salt, 64).toString('hex') === hash;
  } catch {
    return false;
  }
}

export const authRoutes = new Hono<AppEnv>();

// ── 获取当前用户或老人/子女绑定状态（需登录态 + 归属校验） ──────────
authRoutes.get('/user/:id', requireFamilyAuth, requireBoundElder, async (c) => {
  const userId = c.req.param('id');
  const user = await getUserWithBound(c.env, Number(userId));
  if (!user) return c.json({ error: '用户不存在' }, 404);
  // SQLite NULL 序列化成 JSON null，Android optString() 读成字符串 "null" → 抹平
  if ((user as any).mobile === null) (user as any).mobile = '';
  // 子女视角附上全量绑定列表：客户端 onResume 刷新时同步多老人列表
  let boundElders: BoundElderInfo[] = [];
  try {
    boundElders = await fetchBoundElders(c.env, Number(userId));
  } catch { /* 列表降级为空，主字段照常返回 */ }
  return c.json({ success: true, data: { ...user, bound_elders: boundElders } });
});

// ── 子女视角：我绑定的全部老人（多人绑定列表页数据源，含失联状态摘要） ──
authRoutes.get('/my-elders', requireFamilyAuth, async (c) => {
  const userId = c.get('authUserId');
  const { results } = await c.env.DB.prepare(
    `SELECT e.id, e.name, e.phone, e.bind_code, b.is_primary, b.created_at AS bound_at,
            lv.state AS liveness_state, lv.last_report_at AS last_report_at
       FROM bindings b
       JOIN users e ON e.id = b.elder_id
       LEFT JOIN elder_liveness lv ON lv.elder_id = e.id
      WHERE b.family_id = ? AND b.status = 'ACTIVE'
      ORDER BY b.id ASC`
  )
    .bind(userId)
    .all<Record<string, unknown>>();
  return c.json({
    success: true,
    data: (results ?? []).map((r) => ({
      id: r.id,
      name: r.name,
      phone: r.phone,
      bindCode: r.bind_code,
      isPrimary: !!r.is_primary,
      boundAt: r.bound_at,
      livenessState: r.liveness_state || null,
      lastReportAt: r.last_report_at || null
    }))
  });
});

// ── 绑定亲情账号（userId 以登录态为准，防伪造） ─────────────────────
// 两种凭据任传其一：bindCode（老人端 6 位绑定码）或 phone（老人登记手机号）。
// 手机号匹配限定 role='elder'（phone/mobile 列），防止误命中子女账号的 username。
// 多人绑定语义（对齐本地 routes/auth.js）：追加式而非覆盖式，重复绑同一对幂等；
// 容量上限防滥用（bindings DAO 常量，与本地一致）。
authRoutes.post('/bind', requireFamilyAuth, async (c) => {
  const userId = c.get('authUserId');
  const body = await c.req.json().catch(() => ({}) as any);
  const { bindCode } = body || {};
  // 手机号归一化：全角数字→半角 + 去空白/连字符（中文输入法全角是高频坑，服务端兜一层）
  const phone = cleanIdentifier(body && body.phone)
    .replace(/[０-９]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0))
    .replace(/[\s\-－]/g, '');

  if (!bindCode && !phone) return c.json({ error: '参数缺失' }, 400);
  if (phone && !MOBILE_RE.test(phone)) {
    return c.json({ error: '手机号格式不正确（需 11 位大陆手机号）' }, 400);
  }

  // 绑定码路径也限定 role='elder'（对齐本地）：子女的绑定码不是 elder 绑定凭据
  const targetUser = bindCode
    ? await c.env.DB.prepare("SELECT * FROM users WHERE bind_code = ? AND role = 'elder'")
        .bind(bindCode)
        .first<Record<string, any>>()
    : await findElderByPhoneFull(c.env, phone);
  if (!targetUser) {
    // 错误文案区分两种方式，别让"手机号没登记"被误读成"绑定码输错了"
    return c.json({ error: bindCode ? '绑定码无效' : '未找到该手机号登记的老人账号' }, 404);
  }
  if (targetUser.id === userId) {
    return c.json({ error: '不能绑定自己的账号' }, 400);
  }

  // 容量上限：双侧都查
  const famCount = await bindingsDao.countFamiliesOfElder(c.env, targetUser.id);
  if (famCount >= bindingsDao.MAX_FAMILIES_PER_ELDER) {
    return c.json({ error: `该老人的守护人已达上限（${bindingsDao.MAX_FAMILIES_PER_ELDER} 位）` }, 409);
  }
  const elderCount = await bindingsDao.countEldersOfFamily(c.env, userId);
  if (elderCount >= bindingsDao.MAX_ELDERS_PER_FAMILY) {
    return c.json({ error: `你已绑定 ${bindingsDao.MAX_ELDERS_PER_FAMILY} 位老人，达到上限` }, 409);
  }

  const result = await bindingsDao.addBinding(c.env, targetUser.id, userId);
  if (!result) return c.json({ error: '绑定失败' }, 500);

  // boundUser 兼容字段=本次绑定的对象（只回安全字段，不吐 password_hash/bind_code）
  const safeTarget = {
    id: targetUser.id,
    role: targetUser.role,
    name: targetUser.name,
    phone: targetUser.phone,
    mobile: targetUser.mobile ?? null
  };
  let boundList: Awaited<ReturnType<typeof fetchBoundElders>> = [];
  try {
    boundList = await fetchBoundElders(c.env, userId);
  } catch { /* 列表降级为空 */ }
  return c.json({
    success: true,
    message: result.already ? '该老人已在你的守护列表中' : '亲情绑定成功',
    boundUser: safeTarget,
    boundList
  });
});

// ── 微信登录：code2Session（secrets 未配置时 500，与本地一致） ──────
authRoutes.post('/wx-login', async (c) => {
  const { code } = await c.req.json().catch(() => ({}) as any);
  if (!code) return c.json({ error: '参数缺失: code' }, 400);

  const appId = c.env.WECHAT_APPID;
  const appSecret = c.env.WECHAT_APPSECRET;
  if (!appId || !appSecret) return c.json({ error: '服务器未配置微信密钥' }, 500);

  let data: any;
  try {
    const url =
      'https://api.weixin.qq.com/sns/jscode2session' +
      `?appid=${encodeURIComponent(appId)}&secret=${encodeURIComponent(appSecret)}` +
      `&js_code=${encodeURIComponent(code)}&grant_type=authorization_code`;
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    data = await res.json();
  } catch {
    return c.json({ error: '微信登录请求失败，请稍后重试' }, 500);
  }

  const { openid, errcode, errmsg } = data;
  if (errcode) return c.json({ error: `微信登录失败: ${errmsg || errcode}` }, 400);

  const existing = await c.env.DB.prepare('SELECT * FROM users WHERE wx_openid = ?')
    .bind(openid)
    .first<Record<string, any>>();
  if (existing) {
    return replyFamilySession(c, c.env, existing, { hasPassword: !!existing.password_hash });
  }

  // 新用户：建号必然没有手机号，客户端据此提示补录
  const bindCode = String(randomInt(100000, 1000000));
  const phone = 'wx_' + String(openid).slice(-8);
  const res = await c.env.DB.prepare(
    "INSERT INTO users (role, name, phone, bind_code, wx_openid) VALUES ('family', '微信用户', ?, ?, ?)"
  )
    .bind(phone, bindCode, openid)
    .run();
  return c.json({
    success: true,
    data: {
      userId: res.meta.last_row_id,
      bindCode,
      boundUser: null,
      boundUsers: [],
      mobile: '',
      mobileMissing: true,
      hasPassword: false,
      token: await issueToken(c.env, res.meta.last_row_id)
    }
  });
});

// ── 账号密码注册（App 子女端）：username 存 phone 字段，真实手机号存 mobile ──
authRoutes.post('/register', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const { password, name, phone } = body;
  const username = cleanIdentifier(body.username);
  if (!username || !password || !phone) {
    return c.json({ error: '参数缺失: username / password / phone' }, 400);
  }
  if (!/^[A-Za-z0-9_]{4,20}$/.test(username)) {
    return c.json({ error: '用户名需为 4-20 位字母、数字或下划线' }, 400);
  }
  if (String(password).length < 6) {
    return c.json({ error: '密码至少 6 位' }, 400);
  }
  const mobile = String(phone).trim();
  if (!MOBILE_RE.test(mobile)) {
    return c.json({ error: '手机号格式不正确（需 11 位大陆手机号）' }, 400);
  }

  if (await usernameExists(c.env, username)) {
    return c.json({ error: '该用户名已被注册' }, 409);
  }
  // 真实手机号不能与其他账号重复（elder 的真实号存在 phone 列，也要比）
  if (await mobileTaken(c.env, mobile)) {
    return c.json({ error: '该手机号已被其他账号使用' }, 409);
  }

  const bindCode = String(randomInt(100000, 1000000));
  const userId = await createFamily(c.env, {
    username,
    mobile,
    name: (name && String(name).trim()) || '守护人',
    passwordHash: hashPassword(String(password)),
    bindCode
  });
  return c.json({
    success: true,
    data: { userId, bindCode, mobile, mobileMissing: false, boundUser: null, boundUsers: [], token: await issueToken(c.env, userId) }
  });
});

// ── 完善/修改本人手机号（补录入口：缺号会让老人端一键拨号失效） ────
authRoutes.post('/profile-mobile', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const mobile = cleanIdentifier(body && body.phone);
  if (!mobile) return c.json({ error: '参数缺失: phone' }, 400);
  if (!MOBILE_RE.test(mobile)) {
    return c.json({ error: '手机号格式不正确（需 11 位大陆手机号）' }, 400);
  }

  const userId = c.get('authUserId');
  // 手机号全局唯一：除 mobile 列外，elder 账号的真实号存 phone 列（mobile 恒 NULL），
  // 必须一并比对 —— 否则把老人 App 端登记的号填进"我的手机号"能静默通过，
  // 老人端紧急回拨会拨到老人自己的号。口径与注册接口一致。
  const dup = await c.env.DB.prepare(
    "SELECT id, role FROM users WHERE (mobile = ?1 OR (role = 'elder' AND phone = ?1)) AND id != ?2"
  )
    .bind(mobile, userId)
    .first<{ id: number; role: string }>();
  if (dup) {
    return c.json(
      {
        error:
          dup.role === 'elder'
            ? '这是老人端登记的手机号，请填写子女自己的号码（回拨号码不能与老人重复）'
            : '该手机号已被其他账号使用'
      },
      409
    );
  }

  const ok = await updateMobile(c.env, userId, mobile);
  if (!ok) return c.json({ error: '账号不存在' }, 404);
  return c.json({ success: true, mobile });
});

// ── 微信端关联已有 App 端账密账号 ─────────────────────────────────
// 微信登录自动建号是独立空账号，子女在 App 端注册过的话两边割裂。
// 这里用「用户名+密码」验证身份后把 wx_openid 挂到 App 账号上，微信端
// 从此复用同一身份（手机号/绑定关系/绑定码全同步）。与本地 routes/auth.js 双写。
// 凭据错误回 403：小程序请求封装把 401 当"登录态失效静默重登录"，401 会吞掉报错。
authRoutes.post('/wx-link', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const username = cleanIdentifier(body && body.username);
  const password = body && body.password;
  if (!username || !password) return c.json({ error: '参数缺失: username / password' }, 400);

  const userId = c.get('authUserId');
  const me = await findUserById(c.env, userId);
  if (!me) return c.json({ error: '账号不存在' }, 404);
  if (me.password_hash) return c.json({ error: '当前已经是 App 端账号，无需关联' }, 409);
  if (!me.wx_openid) return c.json({ error: '当前不是微信登录账号，无需关联' }, 409);

  const target = await findFamilyForLogin(c.env, username);
  if (!target) return c.json({ error: '用户名或密码错误' }, 403);
  if (!verifyPassword(String(password), target.password_hash as string)) {
    return c.json({ error: '用户名或密码错误' }, 403);
  }
  if (target.wx_openid) {
    return c.json({ error: '该 App 账号已关联过微信，直接用微信登录即可' }, 409);
  }
  // 多人绑定后不再限制"两端绑了不同老人"：绑定集合做并集合并（同 elder 去重）。

  // 1. 绑定集合迁移：占位号全部 ACTIVE 绑定并入 App 账号（UNIQUE 去重），
  //    随后占位号绑定行删除、主守护人与派生列全量重算（bindings DAO 内部 batch）。
  const migrated = await bindingsDao.migrateBindings(c.env, userId, target.id);
  if (!migrated) return c.json({ error: '关联失败，请重试' }, 500);

  // 2. 身份合并 + 派生列重算（一个 batch，全量重算语句幂等，对齐本地 routes/auth.js）
  const stmts = [
    c.env.DB.prepare('UPDATE users SET wx_openid = ? WHERE id = ?').bind(me.wx_openid, target.id),
    // 占位微信账号废弃：清 openid 防止再次被登录，绑定缓存一并清掉（行已迁移）
    c.env.DB.prepare('UPDATE users SET wx_openid = NULL, bound_user_id = NULL WHERE id = ?').bind(userId)
  ];
  const results = await c.env.DB.batch(stmts);
  if (!results.every((r) => r.success)) {
    return c.json({ error: '关联失败，请重试' }, 500);
  }
  console.log(`🔗 微信账号 [ID: ${userId}] 已关联 App 账号 [ID: ${target.id}]（绑定集合已合并）`);

  // 关联成功后按 App 账号身份重新下发完整登录态（与 /login 同形状）
  const merged = await findUserById(c.env, target.id);
  if (!merged) return c.json({ error: '关联成功但回读失败' }, 500);
  return replyFamilySession(c, c.env, merged, { hasPassword: true });
});

// ── 账号密码登录（支持用户名或手机号） ─────────────────────────────
authRoutes.post('/login', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const { password } = body;
  const identifier = cleanIdentifier(body.username); // 兼容用户名或手机号
  if (!identifier || !password) {
    return c.json({ error: '参数缺失: username / password' }, 400);
  }

  const user = await findFamilyForLogin(c.env, identifier);
  if (!user || !user.password_hash) {
    return c.json({ error: '用户名或密码错误' }, 401);
  }
  if (!verifyPassword(String(password), user.password_hash)) {
    return c.json({ error: '用户名或密码错误' }, 401);
  }

  return replyFamilySession(c, c.env, user);
});

// ── 修改密码（需登录态）：旧密码验证通过后换新密码 ──────────────────
// 只服务 App 子女端账密账号；微信登录建号的账号 password_hash 为空，
// 没有旧密码可验证，直接拒绝（前端也不给这类账号显示改密入口）。
authRoutes.post('/change-password', requireFamilyAuth, async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const oldPassword = body && body.oldPassword;
  const newPassword = body && body.newPassword;
  if (!oldPassword || !newPassword) {
    return c.json({ error: '参数缺失: oldPassword / newPassword' }, 400);
  }
  if (String(newPassword).length < 6) {
    return c.json({ error: '新密码至少 6 位' }, 400);
  }

  const userId = c.get('authUserId');
  const user = await findUserById(c.env, userId);
  if (!user) return c.json({ error: '账号不存在' }, 404);
  if (!user.password_hash) {
    return c.json({ error: '当前账号未设置密码（微信登录账号无需密码）' }, 400);
  }
  if (!verifyPassword(String(oldPassword), user.password_hash)) {
    return c.json({ error: '旧密码不正确' }, 401);
  }

  await updatePasswordHash(c.env, userId, hashPassword(String(newPassword)));
  return c.json({ success: true, message: '密码已修改' });
});

// ── 忘记密码重置：账号 + 绑定手机号双重匹配后直接设置新密码 ──────────
// 项目未接短信通道，用"账号 + 绑定手机号"作为身份凭据；
// 只匹配 password_hash 非空的账密账号。不区分"账号不存在"与"手机号不匹配"，
// 避免帮攻击者探测账号是否注册过。
authRoutes.post('/reset-password', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const username = cleanIdentifier(body && body.username);
  const mobile = String((body && body.phone) || '').trim();
  const newPassword = body && body.newPassword;
  if (!username || !mobile || !newPassword) {
    return c.json({ error: '参数缺失: username / phone / newPassword' }, 400);
  }
  if (String(newPassword).length < 6) {
    return c.json({ error: '新密码至少 6 位' }, 400);
  }
  if (!MOBILE_RE.test(mobile)) {
    return c.json({ error: '手机号格式不正确（需 11 位大陆手机号）' }, 400);
  }

  const user = await findFamilyForPasswordReset(c.env, username, mobile);
  if (!user) return c.json({ error: '账号与手机号不匹配，无法重置' }, 404);

  await updatePasswordHash(c.env, user.id, hashPassword(String(newPassword)));
  return c.json({ success: true, message: '密码已重置，请使用新密码登录' });
});

// ── 解除亲情绑定（多人绑定语义：只解除自己那一条） ───────────────
// elderId 缺省仅当只绑一位老人时允许（旧客户端兼容）；多绑定必须显式指定，
// 绝不静默替用户选择。对齐本地 routes/auth.js /unbind。
authRoutes.post('/unbind', requireFamilyAuth, async (c) => {
  const userId = c.get('authUserId');
  const body = await c.req.json().catch(() => ({}) as any);
  const elderIdParam = parseInt(body && body.elderId, 10) || 0;

  const mine = await bindingsDao.listEldersOfFamily(c.env, userId);
  if (mine.length === 0) {
    // 与旧版口径一致：未绑定也返回成功（幂等）
    return c.json({ success: true, message: '已解除绑定' });
  }

  let targetElderId = elderIdParam;
  if (!targetElderId) {
    if (mine.length > 1) {
      return c.json({ error: '你绑定了多位老人，请指定要解除绑定的老人' }, 400);
    }
    targetElderId = mine[0].id;
  }
  if (!mine.some((e) => e.id === targetElderId)) {
    return c.json({ error: '与该老人不存在绑定关系' }, 403);
  }

  const ok = await bindingsDao.removeBinding(c.env, targetElderId, userId);
  if (!ok) return c.json({ error: '解绑失败，请重试' }, 500);
  console.log(`💔 子女 [ID: ${userId}] 已解除与老人 [ID: ${targetElderId}] 的绑定`);

  let boundList: Awaited<ReturnType<typeof fetchBoundElders>> = [];
  try {
    boundList = await fetchBoundElders(c.env, userId);
  } catch { /* 列表降级为空 */ }
  return c.json({ success: true, message: '已解除绑定', boundList });
});

// ── 老人端账号激活/注册 ────────────────────────────────────────────
authRoutes.post('/elder-register', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const elderId = parseInt(body && body.elderId, 10) || 0;
  const name = (body && body.name ? String(body.name) : '').trim();
  const mobile = (body && body.phone ? String(body.phone) : '').trim();
  const previousPhone = cleanIdentifier(body && body.previousPhone);

  if (!MOBILE_RE.test(mobile)) {
    return c.json({ error: '手机号格式不正确（需 11 位大陆手机号）' }, 400);
  }

  // ── 已激活设备：更新资料 ──
  if (elderId > 0) {
    const elder = await findElderById(c.env, elderId);
    if (!elder) return c.json({ error: '老人账号不存在' }, 404);
    if (await mobileTaken(c.env, mobile, elderId)) {
      return c.json({ error: '该手机号已被其他账号使用' }, 409);
    }
    await updateElderProfile(c.env, elderId, name, mobile);
    return replyElderState(c, c.env, elderId);
  }

  // ── 首次激活 ──

  // ── 换号找回（必须优先于按新号认领，防冒用他人账号） ──
  if (previousPhone && previousPhone !== mobile && MOBILE_RE.test(previousPhone)) {
    const prev = await findElderByPhone(c.env, previousPhone);
    // 旧号查无此账号 → 没有可继承的身份，按全新号正常注册
    if (!prev) return activateByNewPhone(name, mobile);

    // 新号已被别的账号占用：不能抢占，如实报错
    if (await mobileTaken(c.env, mobile, prev.id)) {
      return c.json({ error: '该手机号已被其他账号使用' }, 409);
    }
    // 复用原账号：只改号，bound_user_id / bind_code / 历史记录一律不动
    await applyPhoneChange(c.env, prev.id, name, mobile);
    return replyElderState(c, c.env, prev.id, { recoveredByPhoneChange: true });
  }

  return activateByNewPhone(name, mobile);

  /** 按新号激活：号已存在则认领原账号（卸载重装/换机），否则新建 */
  async function activateByNewPhone(name: string, mobile: string): Promise<Response> {
    const existing = await findElderByPhone(c.env, mobile);
    if (existing) {
      // 找回路径只改姓名（留空沿用服务端原值），phone/bound_user_id/bind_code 一律不动 —— 与本地一致
      await c.env.DB.prepare("UPDATE users SET name = COALESCE(NULLIF(?, ''), name) WHERE id = ?")
        .bind(name, existing.id)
        .run();
      return replyElderState(c, c.env, existing.id, { recoveredExistingAccount: true });
    }
    return createFreshAccount(name, mobile);
  }

  /** 新建 elder 账号（手机号确认为全新）：先校验占用，再生成不冲突的绑定码 */
  async function createFreshAccount(name: string, mobile: string): Promise<Response> {
    // 全新账号必须留名；找回路径不受此限（COALESCE 会沿用原姓名）
    if (!name) return c.json({ error: '新账号需要填写老人姓名' }, 400);
    if (await mobileTaken(c.env, mobile)) {
      return c.json({ error: '该手机号已被其他账号使用' }, 409);
    }
    const bindCode = await generateUniqueBindCode(c.env);
    const newId = await createElder(c.env, { name, phone: mobile, bindCode });
    return replyElderState(c, c.env, newId);
  }
});

// ── 老人端绑定码同步：服务器是绑定码唯一事实源 ─────────────────────
authRoutes.post('/elder-bind-code', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const elderId = parseInt(body && body.elderId, 10);
  const newCode = body && body.bindCode ? String(body.bindCode) : null;

  if (!elderId || elderId <= 0) return c.json({ error: '参数缺失: elderId' }, 400);
  if (newCode && !/^\d{6}$/.test(newCode)) {
    return c.json({ error: '绑定码需为 6 位数字' }, 400);
  }

  const elder = await findElderById(c.env, elderId);
  if (!elder) return c.json({ error: '老人账号不存在' }, 404);

  // 与 /elder-register 共用 replyElderState，两条路径字段完全一致
  const replyWithFamily = (bindCode: string) => replyElderState(c, c.env, elderId, { bindCode });

  if (!newCode) return replyWithFamily(elder.bind_code || '');

  // 刷新：先确保新码不与其他用户冲突
  if (await bindCodeTaken(c.env, newCode, elderId)) {
    return c.json({ error: '该绑定码已被占用，请重新生成' }, 409);
  }
  await updateBindCode(c.env, elderId, newCode);
  return replyWithFamily(newCode);
});

// ── 老人端防护规则配置云端同步（GET 拉取 / POST 保存） ──────────────
authRoutes.get('/elder-settings', async (c) => {
  const elderId = parseInt(c.req.query('elderId') ?? '', 10);
  if (!elderId || elderId <= 0) return c.json({ error: '参数缺失: elderId' }, 400);

  const raw = await getGuardSettings(c.env, elderId);
  if (raw === null && !(await findElderById(c.env, elderId))) {
    return c.json({ error: '老人账号不存在' }, 404);
  }
  return c.json({ success: true, settings: parseGuardSettings(raw) });
});

authRoutes.post('/elder-settings', async (c) => {
  const body = await c.req.json().catch(() => ({}) as any);
  const elderId = parseInt(body && body.elderId, 10);
  if (!elderId || elderId <= 0) return c.json({ error: '参数缺失: elderId' }, 400);

  // 逐项校验范围，非法的丢弃而不是整包拒绝：不能因为一个字段脏了让整次保存失败
  const clean = sanitizeGuardSettings(body && body.settings);
  if (Object.keys(clean).length === 0) {
    return c.json({ error: '没有可保存的配置项' }, 400);
  }

  const raw = await getGuardSettings(c.env, elderId);
  if (raw === null && !(await findElderById(c.env, elderId))) {
    return c.json({ error: '老人账号不存在' }, 404);
  }

  // 增量合并而不是整体覆盖：多端各改各的字段，整体覆盖会无声冲掉对方刚改的项
  const merged = { ...(parseGuardSettings(raw) ?? {}), ...clean };
  await setGuardSettings(c.env, elderId, merged);
  return c.json({ success: true, settings: merged });
});

// ── 子女端守护设置（带鉴权，elderId 只从路径参数取） ──────────────────
// 与上面 POST /elder-settings 的区别：那条零鉴权（老人端免登录通道，
// 路线图 G15）；这条给子女端用，必须通过绑定关系校验。改家基准会直接
// 让「离家 / 停留」告警失效，属于可被恶意利用的静默降级，必须拦住。

authRoutes.get('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = parseInt(c.req.param('elderId'), 10);
  if (!elderId || elderId <= 0) return c.json({ error: '参数缺失: elderId' }, 400);

  const raw = await getGuardSettings(c.env, elderId);
  if (raw === null && !(await findElderById(c.env, elderId))) {
    return c.json({ error: '老人账号不存在' }, 404);
  }
  return c.json({ success: true, settings: parseGuardSettings(raw) });
});

authRoutes.post('/elder-settings/family/:elderId', requireFamilyAuth, requireBoundElder, async (c) => {
  const elderId = parseInt(c.req.param('elderId'), 10);
  if (!elderId || elderId <= 0) return c.json({ error: '参数缺失: elderId' }, 400);

  const body = await c.req.json().catch(() => ({}) as any);
  const rawSettings = body && body.settings;
  // 顺序不可调换：homeCleared 必须在 sanitize 之前取出
  const homeCleared = extractHomeCleared(rawSettings);
  const clean = pickFamilyWritable(sanitizeGuardSettings(rawSettings));

  if (Object.keys(clean).length === 0 && !homeCleared) {
    return c.json({ error: '没有可保存的配置项' }, 400);
  }

  const stored = parseGuardSettings(await getGuardSettings(c.env, elderId));
  if (stored === null && !(await findElderById(c.env, elderId))) {
    return c.json({ error: '老人账号不存在' }, 404);
  }

  const merged = mergeFamilySettings(stored, clean, homeCleared);
  await setGuardSettings(c.env, elderId, merged);

  // 通知老人端立刻重算。离线不缓存：配置没有时效性，
  // 缓存陈旧配置会在上线时覆盖更新的值，靠 30 分钟拉取兜底。
  c.executionCtx.waitUntil(
    notifyElder(c.env, elderId, {
      type: 'ELDER_SETTINGS_UPDATED',
      data: { settings: merged, changedBy: 'family' }
    })
  );
  return c.json({ success: true, settings: merged });
});
