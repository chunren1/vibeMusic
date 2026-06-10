@echo off
chcp 65001 >nul
title cpolar Monitor — vibeMusic

:: Server酱 SendKey（微信推送通知用，从 https://sct.ftqq.com/ 获取）
set SCKEY=SCT360080TS4RhHIAils91EzFHwpqlaaEa

echo ==================================================
echo   cpolar 内网穿透存活监控
echo   端口 5173  检测间隔 3~5 分钟
echo   连续2次不可达自动重启 + 微信通知
echo ==================================================
echo.
echo   Server酱已配置 (SendKey: %SCKEY:~0,10%...)
echo.
echo   Ctrl+C 停止监控
echo ==================================================
echo.
python "%~dp0cpolar-monitor.py"
pause
