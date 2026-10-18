#!/bin/bash
# 围栏录音三段式（入场段/守听/续录）E2E 验证脚本
# 前置：MuMu 运行中、本地 server 已启动(3000)、debug 包已装、elderId=17 + 围栏(28.2,113.0,r200)已入库
ADB="D:/Android/platform-tools/adb.exe"
S="192.168.1.6:5555"
LOG=/tmp/geofence-test.log

ts() { date +"%H:%M:%S"; }
step() { echo "[$(ts)] $1" | tee -a $LOG; }

"$ADB" start-server >/dev/null 2>&1
"$ADB" connect $S >/dev/null 2>&1
step "adb 已连接"

# 1. 反向端口转发：模拟器 127.0.0.1:3000 -> 主机 127.0.0.1:3000
"$ADB" -s $S reverse tcp:3000 tcp:3000 || { step "FATAL: adb reverse 失败"; exit 1; }
step "adb reverse tcp:3000 已建立"

# 2. 指向 127.0.0.1 并重启 App（角色已记住，直接进老人端）
"$ADB" -s $S shell am force-stop com.antifraud.guard
"$ADB" -s $S shell "run-as com.antifraud.guard sh -c \"sed -i 's|192.168.1.6:3000|127.0.0.1:3000|' shared_prefs/guard_config.xml\""
"$ADB" -s $S logcat -c
"$ADB" -s $S shell "monkey -p com.antifraud.guard -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 5
"$ADB" -s $S shell input tap 450 778   # 老人端按钮（若停在角色选择页）
sleep 8
step "App 已启动"

# 3. 确认围栏拉取成功
sleep 3
n=$("$ADB" -s $S shell "logcat -d -s LocationGuard:*" | grep -c "已同步")
if [ "$n" -ge 1 ]; then step "PASS: 围栏已从本地服务同步"; else step "FAIL: 围栏未同步"; fi

# 4. 注入围栏外坐标（28.30,113.10，距围栏约 14km）→ 不应触发
"$ADB" -s $S shell am broadcast -a com.antifraud.guard.action.DEBUG_GEO --es lat 28.30 --es lng 113.10 >/dev/null 2>&1
sleep 4
if "$ADB" -s $S shell "logcat -d -s LocationGuard:*" | grep -q "进入敏感地点围栏"; then
  step "FAIL: 围栏外坐标误触发"
else
  step "PASS: 围栏外坐标未触发"
fi

# 5. 注入围栏内坐标（28.2000,113.0000）→ 应开录入场段
"$ADB" -s $S shell am broadcast -a com.antifraud.guard.action.DEBUG_GEO --es lat 28.2000 --es lng 113.0000 >/dev/null 2>&1
sleep 6
"$ADB" -s $S shell "logcat -d -s LocationGuard:* RecordingGuard:*" | tail -20 >> $LOG
if "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep -q "开始环境录音存证"; then
  step "PASS: 进入围栏，入场段录音已启动"
else
  step "FAIL: 入围栏未触发录音"
fi

# 6. 等 2 分钟入场段录满 → 应转入守听
step "等待入场段录满（125s）..."
sleep 125
if "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep -q "进入声音守听"; then
  step "PASS: 入场段结束，已转入声音守听"
else
  step "FAIL: 未转入守听"; "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | tail -15 >> $LOG
fi

# 7. 守听期 RMS 读数采样（验证 AudioRecord 能量检测活着）
sleep 12
rms=$("$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep "守听 RMS" | tail -3)
echo "$rms" >> $LOG
if [ -n "$rms" ]; then step "PASS: 守听 AudioRecord 采样运行中"; else step "FAIL: 守听无 RMS 读数"; fi

# 8. 模拟持续说话声 → 续录
"$ADB" -s $S shell am broadcast -a com.antifraud.guard.action.DEBUG_WATCH_RESUME >/dev/null 2>&1
sleep 6
if "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep -q "续录第2段"; then
  step "PASS: 续录触发（第 2 段开始）"
else
  step "FAIL: 续录未触发"; "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | tail -15 >> $LOG
fi

# 9. 续录段安静 95s → 应回守听（不结束会话）
step "等待续录段安静回退（95s）..."
sleep 95
if "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep -q "停段回守听"; then
  step "PASS: 续录段安静 90s 回守听，会话保持"
else
  step "FAIL: 未回守听"; "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | tail -15 >> $LOG
fi

# 10. 再次触发续录，然后离开围栏 → 整场停止
"$ADB" -s $S shell am broadcast -a com.antifraud.guard.action.DEBUG_WATCH_RESUME >/dev/null 2>&1
sleep 5
"$ADB" -s $S shell am broadcast -a com.antifraud.guard.action.DEBUG_GEO --es lat 28.30 --es lng 113.10 >/dev/null 2>&1
sleep 6
if "$ADB" -s $S shell "logcat -d -s RecordingGuard:*" | grep -q "录音已停止"; then
  step "PASS: 离开围栏，整场录音停止"
else
  step "FAIL: 离开围栏未停止"
fi

# 11. 汇总
step "===== 服务端收到的录音分段 ====="
curl -s --noproxy "*" "http://127.0.0.1:3000/api/recordings/list/17" 2>/dev/null | head -c 100 >> $LOG 2>&1
node D:/project/github/senior-anti-fraud-guard/scripts/check-test-recordings.js 2>>$LOG >> $LOG || {
  node -e "
  const db = require('D:/project/github/senior-anti-fraud-guard/database/db');
  db.all('SELECT id, session_id, segment_index, reason, duration_ms, size_bytes, place_name FROM recordings WHERE elder_id = 17 ORDER BY id', (e, rows) => {
    if (e) console.log('query err', e.message);
    else { console.log(JSON.stringify(rows, null, 1)); process.exit(0); }
  });" >> $LOG 2>&1
}
echo "[$(ts)] ===== 完整日志见 $LOG / 完整 logcat 尾部 =====" | tee -a $LOG
"$ADB" -s $S shell "logcat -d -s LocationGuard:* RecordingGuard:*" >> $LOG
step "E2E 验证脚本执行完毕"
