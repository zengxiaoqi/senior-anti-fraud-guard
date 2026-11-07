#!/usr/bin/env node
/* SAFG 推广视频 · 旁白配音生成（msedge-tts / 微软晓晓）
 * 7 段文案对应 7 个场景，起点与时长按画面节奏设计
 * 输出：remotion-videos/out/vo/vo1.mp3 ... vo7.mp3
 */
const path = require("path");
const fs = require("fs");
const { MsEdgeTTS, OUTPUT_FORMAT } = require("msedge-tts");

const OUT_DIR = path.resolve(__dirname, "../remotion-videos/out/vo");

// [起点秒, 文案] —— 起点略晚于场景切换，给画面留反应时间
const LINES = [
  [0.3, "今晚，骗子可能正在给你爸打电话。"],
  [4.5, "公检法、保健品、稳赚理财……骗子的剧本，早就写好了。"],
  [13.4, "长者防诈守护——装在爸妈手机里的，二十四小时防线。"],
  [19.4, "异常通话、敏感地点、异常扣款、跌倒检测。风险还没发生，它先知道。"],
  [27.4, "一旦洗脑开始，强提醒穿透静音。红色覆屏加亲情语音，一键叫停。"],
  [35.4, "录音、轨迹、扣款记录自动留存。一键生成，报案证据包。"],
  [42.5, "微信扫码即用，或免费下载安装包。别让骗子，抢先见到你爸妈。"],
];

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const tts = new MsEdgeTTS();
  await tts.setMetadata("zh-CN-XiaoxiaoNeural", OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3, {
    rate: "+8%",
    pitch: "+0Hz",
    volume: "+0%",
  });

  const timings = [];
  for (let i = 0; i < LINES.length; i++) {
    const [start, text] = LINES[i];
    const file = path.join(OUT_DIR, `vo${i + 1}.mp3`);
    await tts.toFile(file, text);
    const size = fs.statSync(file).size;
    timings.push({ file: `vo${i + 1}.mp3`, start, size });
    console.log(`OK vo${i + 1}.mp3 start=${start}s size=${(size / 1024).toFixed(0)}KB :: ${text}`);
  }
  fs.writeFileSync(path.join(OUT_DIR, "timings.json"), JSON.stringify(timings, null, 2));
  tts.close();
}

main().catch((e) => {
  console.error("FAIL", e);
  process.exitCode = 1;
});
