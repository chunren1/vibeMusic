@echo off
chcp 65001 >nul
echo ========================================
echo   Cloudflare Tunnel 启动中...
echo   域名: https://www.vibemusic.abrdns.com
echo ========================================

set "CLOUDFLARED=%~dp0..\.cloudflared\cloudflared.exe"
set "CONFIG=%~dp0..\.cloudflared\config.yml"

if not exist "%CLOUDFLARED%" (
    echo [错误] 找不到 cloudflared.exe
    pause
    exit /b 1
)

if not exist "%CONFIG%" (
    echo [错误] 找不到 config.yml
    pause
    exit /b 1
)

"%CLOUDFLARED%" tunnel --config "%CONFIG%" --protocol http2 --edge-ip-version 4 run vibeMusic
pause
