@echo off
chcp 65001 >nul
title cpolar Monitor — vibeMusic
echo ==================================================
echo   cpolar 内网穿透存活监控
echo   端口: 5173
echo   检测间隔: 3~5 分钟
echo   连续2次不可达自动重启 + 微信通知
echo ==================================================
echo.
echo   环境要求: Python 3 + requests 库
echo   Server酱: set SCKEY=你的SendKey
echo.
echo   Ctrl+C 停止监控
echo ==================================================
echo.
python "%~dp0cpolar-monitor.py"
pause
