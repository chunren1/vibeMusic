# vibeMusic Docker 管理脚本
param(
    [ValidateSet('up','down','restart','status','logs')]
    [string]$Action = 'up'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..

function Write-Header($text) {
    Write-Host "`n=== $text ===" -ForegroundColor Cyan
}

switch ($Action) {
    'up' {
        Write-Header '启动 Docker 服务'
        docker-compose up -d
        Write-Header '等待健康检查'
        Start-Sleep 2
        docker-compose ps
        Write-Host "`nMySQL:   localhost:3306" -ForegroundColor Green
        Write-Host "Redis:   localhost:6379" -ForegroundColor Green
        Write-Host "MinIO:   localhost:9000 (API) / localhost:9001 (Web)" -ForegroundColor Green
        Write-Host "`nMinIO 管理: http://localhost:9001 - rustfsadmin / rustfsadmin" -ForegroundColor Yellow
    }
    'down' {
        Write-Header '停止 Docker 服务'
        docker-compose down
        Write-Host "容器已停止，数据保留在 docker-data/ 下" -ForegroundColor Green
    }
    'restart' {
        Write-Header '重启 Docker 服务'
        docker-compose down
        docker-compose up -d
        docker-compose ps
    }
    'status' {
        Write-Header 'Docker 服务状态'
        docker-compose ps
    }
    'logs' {
        Write-Header 'Docker 服务日志 (Ctrl+C 退出)'
        docker-compose logs -f
    }
}
