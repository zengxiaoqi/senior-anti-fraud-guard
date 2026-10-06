@echo off
chcp 65001 >nul
setlocal

REM ------------------------------------------------------------
REM  把微信公众平台的「服务器域名」同步成 config.js 里的 host
REM  需要：已登录公众平台的 Chrome 调试会话，wsUrl 存在 MP_WS_FILE
REM        （默认 D:\mp-ci\.ws）
REM  过程中弹出管理员扫码二维码，扫码后脚本自动校验结果
REM ------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"

where node >nul 2>&1
if errorlevel 1 (
    set "NODE=C:\Users\nanre\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
) else (
    set "NODE=node"
)

cd /d "%SCRIPT_DIR%"
"%NODE%" sync-wx-domain.js

echo.
pause
