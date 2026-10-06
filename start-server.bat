@echo off
chcp 65001 >nul
title 长者防诈守护 - 后端服务 (port 3000)
cd /d "%~dp0"

REM 防重复：如已有服务在 3000 端口则先结束
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":3000 .*LISTENING"') do taskkill /F /PID %%p >nul 2>&1

echo ============================================
echo   长者防诈守护 API 和 WebSocket 服务启动中...
echo   地址: http://localhost:3000
echo   请保持本窗口开启，关闭窗口即停止服务
echo ============================================
node server.js
pause
