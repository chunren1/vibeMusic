@echo off
chcp 65001 >nul
title vibeMusic 控制台
color 0A

:menu
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║       vibeMusic 控制台            ║
echo   ╚════════════════════════════════════╝
echo.
echo    [1] 启动全部服务 (musicapi + 前端)
echo    [2] 启动内网穿透监控 (cpolar)
echo    [3] 启动全部 + 监控 (推荐)
echo   ──────────────────────────────────
echo    [4] 查看日志
echo    [5] 检查服务状态
echo    [6] 停止所有服务
echo    [0] 退出
echo.
set /p choice="   请选择: "

if "%choice%"=="1" goto start_services
if "%choice%"=="2" goto start_monitor
if "%choice%"=="3" goto start_all
if "%choice%"=="4" goto view_logs
if "%choice%"=="5" goto check_status
if "%choice%"=="6" goto stop_all
if "%choice%"=="0" exit
goto menu

:start_services
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║      启动 vibeMusic 服务           ║
echo   ╚════════════════════════════════════╝
echo.
echo   [1/2] 启动 musicapi (端口 3000) ...
start "musicapi-3000" /min cmd /c "cd /d %~dp0musicapi && node server.js"
timeout /t 2 >nul
echo          musicapi 已启动 (窗口最小化)
echo.
echo   [2/2] 启动前端 (端口 5173) ...
start "vibemusic-web-5173" /min cmd /c "cd /d %~dp0vibemusic-web && npm run dev"
timeout /t 2 >nul
echo          前端已启动 (窗口最小化)
echo.
echo   ──────────────────────────────────
echo   ⚠️  请手动启动后端: cd vibeMusic-backend ^&^& mvnw spring-boot:run
echo   ──────────────────────────────────
echo.
echo   访问地址:
echo     前端:     http://localhost:5173
echo     后端:     http://localhost:8080
echo     musicapi: http://localhost:3000
echo     health:   http://localhost:3000/health
echo.
pause
goto menu

:start_monitor
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║   启动 cpolar 内网穿透监控        ║
echo   ╚════════════════════════════════════╝
echo.
echo   检测间隔: 3~5 分钟 | 连续2次失败自动重启
echo   微信通知: 已配置 Server酱
echo.
start "cpolar-monitor" cmd /c "%~dp0scripts\ops\start-cpolar-monitor.bat"
echo   cpolar 监控已在新窗口启动
echo.
pause
goto menu

:start_all
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║  启动全部服务 + 内网穿透监控      ║
echo   ╚════════════════════════════════════╝
echo.
echo   [1/3] 启动 musicapi ...
start "musicapi-3000" /min cmd /c "cd /d %~dp0musicapi && node server.js"
timeout /t 3 >nul
echo         OK
echo.
echo   [2/3] 启动前端 ...
start "vibemusic-web-5173" /min cmd /c "cd /d %~dp0vibemusic-web && npm run dev"
timeout /t 3 >nul
echo         OK
echo.
echo   [3/3] 启动 cpolar 监控 ...
start "cpolar-monitor" cmd /c "%~dp0scripts\ops\start-cpolar-monitor.bat"
timeout /t 2 >nul
echo         OK
echo.
echo   ──────────────────────────────────
echo   ⚠️  请手动启动后端: cd vibeMusic-backend ^&^& mvnw spring-boot:run
echo   ──────────────────────────────────
echo.
echo   所有窗口:
echo     - musicapi-3000      (最小化)
echo     - vibemusic-web-5173 (最小化)
echo     - cpolar-monitor     (监控窗口)
echo.
echo   日志: musicapi\logs\
echo.
pause
goto menu

:view_logs
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║      查看日志                      ║
echo   ╚════════════════════════════════════╝
echo.
if not exist "%~dp0musicapi\logs\*.log" (
    echo   暂无日志文件（服务还未运行过）
    echo.
    echo   日志目录: musicapi\logs\
    echo   包含: api-errors / cookie-monitor / cpolar-monitor / access / degradation
    echo.
    pause
    goto menu
)
echo   日志目录: musicapi\logs\
echo.
dir /b /o-d "%~dp0musicapi\logs\*.log" 2>nul
echo.
set /p logfile="   输入文件名查看 (回车返回): "
if "%logfile%"=="" goto menu
if exist "%~dp0musicapi\logs\%logfile%" (
    start notepad "%~dp0musicapi\logs\%logfile%"
) else (
    echo   文件不存在
    timeout /t 2 >nul
)
goto menu

:check_status
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║      服务状态检查                  ║
echo   ╚════════════════════════════════════╝
echo.
echo   [musicapi] 端口 3000...
curl -s -o nul -w "%%{http_code}" http://localhost:3000/health >nul 2>&1
if %errorlevel%==0 (echo            ✅ 运行中) else (echo            ❌ 未启动)
echo.
echo   [前端] 端口 5173...
curl -s -o nul -w "%%{http_code}" http://localhost:5173 >nul 2>&1
if %errorlevel%==0 (echo            ✅ 运行中) else (echo            ❌ 未启动)
echo.
echo   [后端] 端口 8080...
curl -s -o nul -w "%%{http_code}" http://localhost:8080 >nul 2>&1
if %errorlevel%==0 (echo            ✅ 运行中) else (echo            ❌ 未启动)
echo.
echo   [Cookie] 状态...
curl -s http://localhost:3000/cookie-status 2>nul | findstr "netease\|qq" >nul 2>&1
if %errorlevel%==0 (
    echo            ✅ 可用 (详情见 http://localhost:3000/cookie-status)
) else (
    echo            ❌ 无法获取 (musicapi 未启动)
)
echo.
pause
goto menu

:stop_all
cls
echo.
echo   ╔════════════════════════════════════╗
echo   ║      停止所有服务                  ║
echo   ╚════════════════════════════════════╝
echo.
echo   正在停止...
taskkill /FI "WINDOWTITLE eq musicapi-3000*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq vibemusic-web-5173*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq cpolar-monitor*" /F >nul 2>&1
timeout /t 1 >nul
echo   ✅ 已停止
echo.
pause
goto menu
