# =============================================================
# 停止项目内自带的 nginx（前缀目录 deploy\nginx）
# 用法：powershell -ExecutionPolicy Bypass -File deploy\stop-nginx.ps1
# =============================================================
$ErrorActionPreference = 'Stop'

$prefix = Join-Path $PSScriptRoot 'nginx'
$exe = Join-Path $prefix 'nginx.exe'
$argPrefix = $prefix.Replace('\', '/')
$pidFile = Join-Path $prefix 'logs\nginx.pid'

if (-not (Test-Path $exe)) {
    throw "未找到 $exe。"
}

$nginxPid = $null
if (Test-Path $pidFile) {
    $nginxPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
}
if (-not $nginxPid -or -not (Get-Process -Id $nginxPid -ErrorAction SilentlyContinue)) {
    Write-Host 'nginx 当前未运行。' -ForegroundColor Yellow
    return
}

$stopProc = Start-Process -FilePath $exe `
    -ArgumentList @('-p', "$argPrefix/", '-c', 'conf/nginx.conf', '-s', 'stop') `
    -Wait -PassThru -NoNewWindow
if ($stopProc.ExitCode -ne 0) {
    throw '发送停止指令失败，请查看 deploy\nginx\logs\error.log。'
}
Start-Sleep -Seconds 1
if (Get-Process -Id $nginxPid -ErrorAction SilentlyContinue) {
    Write-Host "nginx（PID $nginxPid）仍在退出中，稍后再试或查看 deploy\nginx\logs\error.log。" -ForegroundColor Yellow
} else {
    Write-Host 'nginx 已停止。' -ForegroundColor Green
}
