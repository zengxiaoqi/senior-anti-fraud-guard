/**
 * 生成微信小程序码（真·圆形小程序码，非普通二维码）
 *
 * 背景：docs/screenshots/miniprogram-qr.png 历史上误用了 APK 下载码
 * （与 dist/qr-v1.8.9.png 同一文件），README 和推广视频里的"小程序码"
 * 全是安卓安装包下载链接。本脚本从微信官方 API 拉真码覆盖之。
 *
 * 用法：
 *   node scripts/gen-miniprogram-qr.js [输出路径]
 * 默认输出：docs/screenshots/miniprogram-qr.png
 *
 * 凭据来源：项目根 .env 的 WECHAT_APPID / WECHAT_APPSECRET（已 gitignore）
 * 接口文档：https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/qrcode-link/qrcode/getQRCode.html
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const OUT = process.argv[2] || path.join(ROOT, 'docs', 'screenshots', 'miniprogram-qr.png');

// 小程序入口页（wechat-miniprogram/app.json 第一个 page）
const PAGE = 'pages/dashboard/dashboard';
const SCENE = 'home';
const ENV_VERSION = 'release'; // 正式版；体验版用 'trial'

function loadEnv() {
  const envPath = path.join(ROOT, '.env');
  if (!fs.existsSync(envPath)) {
    throw new Error('.env 不存在，无法读取微信密钥');
  }
  const env = {};
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && !line.trim().startsWith('#')) env[m[1]] = m[2];
  }
  const appid = env.WECHAT_APPID;
  const secret = env.WECHAT_APPSECRET;
  if (!appid || !secret) {
    throw new Error('.env 缺少 WECHAT_APPID 或 WECHAT_APPSECRET');
  }
  return { appid, secret };
}

async function main() {
  const { appid, secret } = loadEnv();
  console.log('AppID:', appid);

  // 1. 获取 access_token
  const tokenUrl =
    'https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential' +
    `&appid=${encodeURIComponent(appid)}&secret=${encodeURIComponent(secret)}`;
  const tokenRes = await fetch(tokenUrl);
  const tokenJson = await tokenRes.json();
  if (!tokenJson.access_token) {
    throw new Error(
      `获取 access_token 失败: errcode=${tokenJson.errcode} errmsg=${tokenJson.errmsg}`
    );
  }
  console.log('access_token OK, expires_in =', tokenJson.expires_in);

  // 2. 生成小程序码（无限量，带 scene）
  const qrUrl =
    'https://api.weixin.qq.com/wxa/getwxacodeunlimit' +
    `?access_token=${encodeURIComponent(tokenJson.access_token)}`;
  const qrRes = await fetch(qrUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      scene: SCENE,
      page: PAGE,
      check_path: false,
      env_version: ENV_VERSION,
    }),
  });

  const contentType = qrRes.headers.get('content-type') || '';
  const buf = Buffer.from(await qrRes.arrayBuffer());

  if (!contentType.includes('image')) {
    // 出错时微信返回 JSON 错误体
    let msg = buf.toString('utf8').slice(0, 300);
    throw new Error(`生成小程序码失败(${contentType}): ${msg}`);
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, buf);
  console.log(`小程序码已生成: ${OUT} (${buf.length} bytes, ${contentType})`);

  // 3. 基本校验：图片魔数 + 宽高（小程序码是正方形位图，中间圆形 logo 镂空）
  //    注意：微信 getwxacodeunlimit 返回的是 JPEG 数据（image/jpeg），
  //    若输出路径要求 .png 且有 ffmpeg，就地转成真 PNG，保证扩展名与内容一致。
  const isPng = buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47;
  const isJpeg = buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
  if (!isPng && !isJpeg) {
    throw new Error('输出不是图片文件（既非 PNG 也非 JPEG）');
  }

  let finalPath = OUT;
  if (!isPng && OUT.toLowerCase().endsWith('.png')) {
    const tmpJpeg = OUT + '.jpg.tmp';
    fs.writeFileSync(tmpJpeg, buf);
    const r = spawnSync('ffmpeg', ['-v', 'error', '-i', tmpJpeg, '-y', OUT], {
      encoding: 'utf8',
    });
    fs.unlinkSync(tmpJpeg);
    if (r.status !== 0) {
      throw new Error(`ffmpeg 转 PNG 失败: ${r.stderr || r.error?.message}`);
    }
    console.log('已转换为真 PNG（ffmpeg）');
    finalPath = OUT;
  }

  // PNG 头读宽高（IHDR 偏移 16，大端 4 字节）
  const head = fs.readFileSync(finalPath).subarray(24, 28);
  const width = head.readUInt32BE(0);
  const height = head.readUInt32BE(4);
  console.log(`PNG 尺寸: ${width}x${height}`);
  if (width !== height || width < 200) {
    throw new Error(`尺寸异常: ${width}x${height}，疑似不是小程序码`);
  }
  console.log('OK - 圆形小程序码（getwxacodeunlimit）');
}

main().catch((e) => {
  console.error('FAILED:', e.message);
  process.exitCode = 1;
});
