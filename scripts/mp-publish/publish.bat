@echo off
chcp 65001 >nul
setlocal

REM ------------------------------------------------------------
REM  一键换域名 + 上传小程序
REM  用法（在任意目录运行均可，路径自动推导）:
REM    scripts\mp-publish\publish.bat
REM    scripts\mp-publish\publish.bat xxx.r25.cpolar.top 1.0.3
REM    scripts\mp-publish\publish.bat api.example.com 1.0.0 "正式域名"
REM ------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"

REM 优先用系统 node，找不到再退到 WorkBuddy 自带的
where node >nul 2>&1
if errorlevel 1 (
    set "NODE=C:\Users\nanre\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
) else (
    set "NODE=node"
)

REM 清代理：miniprogram-ci 读到代理会走境外出口，微信报 invalid ip
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "http_proxy="
set "https_proxy="
set "ALL_PROXY="
set "all_proxy="

REM miniprogram-ci 未装进项目时，用它兜底（也可在本项目 npm i -D miniprogram-ci）
if not defined MP_CI_MODULES set "MP_CI_MODULES=D:\mp-ci\node_modules"

if "%~2"=="" ( set "VER=1.0.1" ) else ( set "VER=%~2" )
if "%~3"=="" ( set "DESC=长者防诈亲情守护系统" ) else ( set "DESC=%~3" )

cd /d "%SCRIPT_DIR%"

echo.
echo [1/2] 写入域名到 config.js ...
"%NODE%" set-domain.js %1
if errorlevel 1 goto fail

echo.
echo [2/2] 上传版本 %VER% ...
"%NODE%" --dns-result-order=ipv4first --require "%SCRIPT_DIR%force_ipv4.js" upload.js %VER% "%DESC%"
if errorlevel 1 goto fail

echo.
echo ============================================================
echo  代码已上传。若域名有变化，还需同步微信后台服务器域名：
echo     scripts\mp-publish\sync-wx-domain.bat   （需管理员扫码一次）
echo ============================================================
echo.
pause
exit /b 0

:fail
echo.
echo 执行失败，请查看上面的错误信息。
pause
exit /b 1
