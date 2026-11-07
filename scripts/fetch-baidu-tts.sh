#!/bin/bash
# SAFG 推广视频 · 旁白抓取（百度翻译 TTS 端点，无需 key）
# 7 段文案卡 7 个场景起点；spd=5 正常语速
set -e
cd "$(dirname "$0")/.."
OUT="remotion-videos/out/vo"
mkdir -p "$OUT"
PY="C:/Users/nanre/.workbuddy/binaries/python/versions/3.13.12/python.exe"

TEXTS=(
"今晚，骗子就会打给你爸妈。"
"公检法、保健品、稳赚理财……骗子的剧本，早就写好了。"
"长者防诈守护——装在爸妈手机里的，二十四小时防线。"
"异常通话、敏感地点、异常扣款、跌倒检测。风险还没发生，它先知道。"
"一旦洗脑开始，强提醒穿透静音。红色覆屏加亲情语音，一键叫停。"
"录音、轨迹、扣款记录自动留存。一键生成，报案证据包。"
"微信扫码即用，或免费下载安装包。别等报警了，才装这道防线。"
)

i=1
for t in "${TEXTS[@]}"; do
  enc=$("$PY" -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$t")
  curl -s --max-time 25 "https://fanyi.baidu.com/gettts?lan=zh&text=${enc}&spd=5&source=web" \
    -o "$OUT/vo$i.mp3"
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/vo$i.mp3" 2>/dev/null)
  s=$(stat -c%s "$OUT/vo$i.mp3" 2>/dev/null || echo 0)
  echo "vo$i dur=${d}s size=${s}B :: $t"
  i=$((i+1))
done
echo DONE
