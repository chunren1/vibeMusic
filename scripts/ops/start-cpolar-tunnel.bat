@echo off
chcp 65001 >nul
title cpolar tunnel - vibeMusic
echo ================================
echo   cpolar 自动重连隧道
echo   端口: 5173 (前端)
echo   Ctrl+C 停止
echo ================================

:loop
echo.
echo [%time%] 启动 cpolar...
cpolar http 5173
echo [%time%] cpolar 断开，3秒后重连...
timeout /t 3 >nul
goto loop
