# Cloudflare Tunnel 一键启动脚本
# 访问地址: https://vibemusic.abrdns.com

$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Cloudflare Tunnel 启动中..." -ForegroundColor Cyan
Write-Host "  域名: https://vibemusic.abrdns.com" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan

# 启动 tunnel
cloudflared tunnel run vibeMusic
