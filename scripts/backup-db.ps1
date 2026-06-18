# vibeMusic 数据库自动备份
# 用途：mysqldump 导出 → 保留最近 7 天
# 定时：建议 Windows 任务计划 每天凌晨 4 点运行

param(
    [string]$BackupDir = "$PSScriptRoot\..\docker-data\backups"
)

$MYSQL_CONTAINER = "vibemusic-mysql"
$DB_USER = "root"
$DB_PASS = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "123456" }
$DB_NAME = "vibemusic"
$KEEP_DAYS = 7

# 创建备份目录
if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

# 生成备份文件名
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupFile = "$BackupDir\vibemusic_$timestamp.sql.gz"

Write-Host "Backup started: $DB_NAME -> $backupFile" -ForegroundColor Cyan

try {
    # 导出 + gzip 压缩
    docker exec $MYSQL_CONTAINER mysqldump `
        -u$DB_USER -p$DB_PASS `
        --single-transaction `
        --routines `
        --triggers `
        $DB_NAME | gzip > $backupFile

    if ($LASTEXITCODE -eq 0) {
        $size = (Get-Item $backupFile).Length / 1KB
        Write-Host "Backup OK: {0:N1} KB" -f $size -ForegroundColor Green
    }
} catch {
    Write-Host "Backup FAILED: $_" -ForegroundColor Red
    exit 1
}

# 清理过期备份
$cutoff = (Get-Date).AddDays(-$KEEP_DAYS)
Get-ChildItem $BackupDir -Filter "vibemusic_*.sql.gz" |
    Where-Object { $_.CreationTime -lt $cutoff } |
    ForEach-Object {
        Write-Host "Removing old backup: $($_.Name)" -ForegroundColor DarkGray
        Remove-Item $_.FullName
    }

# 统计
$count = @(Get-ChildItem $BackupDir -Filter "vibemusic_*.sql.gz").Count
Write-Host "Total backups: $count (keeping last $KEEP_DAYS days)" -ForegroundColor Cyan
