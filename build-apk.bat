@echo off
setlocal
cd /d "%~dp0android"

echo ==============================================
echo   AntiFraudGuard - One-Click APK Build
echo   Usage: build-apk.bat          (Release, signed)
echo          build-apk.bat debug    (Debug test build)
echo ==============================================
echo.

rem --nopause may appear in any position: "build-apk.bat --nopause" or "build-apk.bat debug --nopause"
set NOPAUSE=0
if /i "%1"=="--nopause" set NOPAUSE=1
if /i "%2"=="--nopause" set NOPAUSE=1

set GRADLE=D:\Android\gradle\gradle-8.7\bin\gradle.bat
if not exist "%GRADLE%" (
    echo [ERROR] Gradle not found: %GRADLE%
    goto :end_fail
)
if not exist keystore\guard-release.jks (
    echo [ERROR] keystore not found: keystore\guard-release.jks
    goto :end_fail
)

if /i "%1"=="debug" goto :build_debug

:build_release
echo [1/3] Building RELEASE apk (signed, first run ~3 min)...
call "%GRADLE%" assembleRelease --no-daemon
if errorlevel 1 goto :build_fail
set APK_SRC=app\build\outputs\apk\release\app-release.apk
set APK_TAG=release
goto :copy_out

:build_debug
echo [1/3] Building DEBUG apk...
call "%GRADLE%" assembleDebug --no-daemon
if errorlevel 1 goto :build_fail
set APK_SRC=app\build\outputs\apk\debug\app-debug.apk
set APK_TAG=debug
goto :copy_out

:copy_out
echo [2/3] Reading version name...
for /f "tokens=2" %%a in ('findstr /c:"versionName" app\build.gradle') do set VER=%%a
set VER=%VER:"=%

echo [3/3] Copying apk to dist folder...
if not exist ..\dist mkdir ..\dist
copy /y "%APK_SRC%" "..\dist\AntiFraudGuard-v%VER%-%APK_TAG%.apk" >nul
if errorlevel 1 goto :build_fail

echo.
echo ==============================================
echo   BUILD SUCCESS
echo   Version : v%VER% (%APK_TAG%)
echo   Output  : dist\AntiFraudGuard-v%VER%-%APK_TAG%.apk
echo   Tip     : bump versionCode in app/build.gradle before releasing
echo ==============================================
goto :end_ok

:build_fail
echo.
echo [FAILED] build error, see android\build.log for details
goto :end_fail

:end_ok
if "%NOPAUSE%"=="0" pause
exit /b 0

:end_fail
if "%NOPAUSE%"=="0" pause
exit /b 1
