# vibeMusic 一键部署脚本（本地运行）
# 用途：开发机 → 完整生产部署
# 用法：.\scripts\deploy.ps1

param(
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

Write-Host "`n=== vibeMusic 一键部署 ===" -ForegroundColor Cyan

# 1. 测试
if (-not $SkipTests) {
    Write-Host "`n[1/4] 运行测试..." -ForegroundColor Yellow
    npm run test
    if ($LASTEXITCODE -ne 0) {
        Write-Host "测试失败，部署中止" -ForegroundColor Red
        exit 1
    }
}

# 2. 构建后端
Write-Host "`n[2/4] 构建后端 JAR..." -ForegroundColor Yellow
Push-Location vibeMusic-backend
mvn package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "后端构建失败" -ForegroundColor Red
    Pop-Location
    exit 1
}
Pop-Location

# 3. 构建前端
Write-Host "`n[3/4] 构建前端..." -ForegroundColor Yellow
npm run build
if ($LASTEXITCODE -ne 0) {
    Write-Host "前端构建失败" -ForegroundColor Red
    exit 1
}

# 4. Docker 部署
Write-Host "`n[4/4] Docker 部署..." -ForegroundColor Yellow
docker-compose down
docker-compose up -d --build

Write-Host "`n等待服务健康检查..." -ForegroundColor Cyan
Start-Sleep 20
docker-compose ps

Write-Host "`n部署完成！http://localhost" -ForegroundColor Green
Write-Host "MinIO 控制台: http://localhost:9001 (rustfsadmin/rustfsadmin)" -ForegroundColor Green
