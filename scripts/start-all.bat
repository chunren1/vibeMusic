@echo off
chcp 65001 >nul
title vibeMusic - 启动中...

echo.
echo ╔══════════════════════════════╗
echo ║    vibeMusic 自动启动       ║
echo ╚══════════════════════════════╝
echo.

:: 1. 检查 Cookie
echo [1/3] 检测 Cookie...
python "%~dp0check-cookies.py"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ⚠️ Cookie 异常！请更新后重试。
    echo 按任意键继续启动（可能部分功能不可用）...
    pause >nul
)
echo.

:: 2. 启动 musicapi (3000)
echo [2/3] 启动 musicapi (端口 3000)...
start "musicapi-3000" cmd /c "cd /d %~dp0..\musicapi && title musicapi-3000 && node server.js"

:: 3. 启动前端 (5173)
echo [3/3] 启动前端 vite (端口 5173)...
start "vibemusic-web-5173" cmd /c "cd /d %~dp0..\vibemusic-web && title vibemusic-web-5173 && npm run dev"

echo.
echo ✅ 启动完成！
echo    musicapi  → http://localhost:3000
echo    前端      → http://localhost:5173
echo.
echo ⚠️ 请手动启动后端: cd vibeMusic-backend ^&^& mvnw spring-boot:run
echo.
pause
