# ============================================================
# Windows 任务计划程序 — Cookie 监控定时任务
# 用法: 以管理员身份运行此脚本
#   powershell -ExecutionPolicy Bypass -File setup-scheduler.ps1
# ============================================================

$TaskName = "vibeMusic-CookieMonitor"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $PythonExe) { $PythonExe = (Get-Command python3 -ErrorAction SilentlyContinue).Source }
if (-not $PythonExe) {
    Write-Error "未找到 Python，请先安装并添加到 PATH"
    exit 1
}

$MonitorScript = Join-Path $ScriptDir "cookie-monitor.py"
$LogFile = Join-Path $ScriptDir "monitor.log"
$SCKEY = $env:SCKEY

if (-not $SCKEY) {
    Write-Warning "环境变量 SCKEY 未设置，微信通知不可用"
    Write-Warning "请先运行: `$env:SCKEY = '你的key'"
}

# 删除已有任务（如果存在）
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "已删除旧任务: $TaskName"
}

# 创建新任务：每 3 小时执行一次
$Action = New-ScheduledTaskAction -Execute $PythonExe `
    -Argument "`"$MonitorScript`"" `
    -WorkingDirectory $ScriptDir

$Trigger = New-ScheduledTaskTrigger -Daily -At "00:00" `
    -RepetitionInterval (New-TimeSpan -Hours 3) `
    -RepetitionDuration (New-TimeSpan -Days 365)

$Principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
    -LogonType Interactive -RunLevel Limited

$Settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -DontStopOnIdleEnd `
    -AllowStartIfOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 5)

Register-ScheduledTask -TaskName $TaskName `
    -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings `
    -Description "vibeMusic Cookie 存活检测，每 2 小时检查 QQ + 网易云 API" `
    -Force

Write-Host "============================================" -ForegroundColor Green
Write-Host "✅ 任务计划已创建: $TaskName" -ForegroundColor Green
Write-Host "⏱️  间隔: 每 2 小时" -ForegroundColor Green
Write-Host "📍 脚本: $MonitorScript" -ForegroundColor Green
Write-Host "📋 日志: $LogFile" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "管理命令:" -ForegroundColor Yellow
Write-Host "  立即运行: Start-ScheduledTask -TaskName '$TaskName'"
Write-Host "  查看状态: Get-ScheduledTask -TaskName '$TaskName' | Select State"
Write-Host "  查看日志: Get-Content '$LogFile' -Tail 20"
Write-Host "  删除任务: Unregister-ScheduledTask -TaskName '$TaskName' -Confirm:`$false"
