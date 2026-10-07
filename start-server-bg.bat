@echo off
REM ------------------------------------------------------------
REM  后端常驻启动脚本（供计划任务 / 看门狗调用）
REM  与 start-server.bat 的区别：不 kill 已有进程、不 pause，
REM  因此在无交互环境（计划任务）中不会卡死。
REM  幂等：已在监听则直接退出，避免端口冲突与反复重启。
REM ------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0"

REM 已有服务在跑就退出（幂等）
netstat -ano | findstr ":3000 .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [start-server-bg] port 3000 already listening, nothing to do.
    exit /b 0
)

echo [start-server-bg] starting node server.js ...
node server.js >> server.log 2>> server.err.log
REM node 退出后才走到这里；记录退出码便于排查
echo [start-server-bg] node exited with code %errorlevel% at %date% %time% >> server.log