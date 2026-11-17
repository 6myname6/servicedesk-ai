# =============================================================
# 启动项目内自带的 nginx（前缀目录 deploy\nginx，监听 80）
# 用法：powershell -ExecutionPolicy Bypass -File deploy\start-nginx.ps1
# 说明：本脚本只负责 nginx 进程；/api 需要后端运行在 127.0.0.1:8080。
# =============================================================
$ErrorActionPreference = 'Stop'

$prefix = Join-Path $PSScriptRoot 'nginx'
$exe = Join-Path $prefix 'nginx.exe'
if (-not (Test-Path $exe)) {
    throw "未找到 $exe。请确认 deploy\nginx 目录存在（二进制不纳入 Git，需从任意 nginx 发行版复制 nginx.exe 与 conf 目录）。"
}

foreach ($dir in @('logs', 'temp', 'html')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $prefix $dir) | Out-Null
}
$siteDir = Join-Path $prefix 'html\servicedesk-ai'
if (-not (Test-Path $siteDir)) {
    New-Item -ItemType Directory -Force -Path $siteDir | Out-Null
    Write-Host "提示：$siteDir 为空，请先执行 deploy\deploy-frontend.ps1 构建并拷贝产物。" -ForegroundColor Yellow
}

# 仓库里的配置是唯一事实源，每次启动都覆盖运行时副本
Copy-Item (Join-Path $PSScriptRoot 'nginx.conf') (Join-Path $prefix 'conf\nginx.conf') -Force

$argPrefix = $prefix.Replace('\', '/')
# nginx 会把 "syntax is ok" 写到 stderr；在 $ErrorActionPreference=Stop 下直接调用会被当成
# 终止性错误，因此统一用 Start-Process 拿退出码，失败时再看 logs\error.log
$testProc = Start-Process -FilePath $exe `
    -ArgumentList @('-p', "$argPrefix/", '-c', 'conf/nginx.conf', '-t') `
    -Wait -PassThru -NoNewWindow
if ($testProc.ExitCode -ne 0) {
    throw 'nginx 配置校验失败，请查看上方输出与 deploy\nginx\logs\error.log。'
}

$pidFile = Join-Path $prefix 'logs\nginx.pid'
$running = $false
if (Test-Path $pidFile) {
    $nginxPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($nginxPid -and (Get-Process -Id $nginxPid -ErrorAction SilentlyContinue)) {
        $running = $true
    }
}

if ($running) {
    $reloadProc = Start-Process -FilePath $exe `
        -ArgumentList @('-p', "$argPrefix/", '-c', 'conf/nginx.conf', '-s', 'reload') `
        -Wait -PassThru -NoNewWindow
    if ($reloadProc.ExitCode -ne 0) {
        throw 'nginx 重载失败，请查看 deploy\nginx\logs\error.log。'
    }
    Write-Host 'nginx 已重载（reload）：http://localhost/' -ForegroundColor Green
} else {
    Start-Process -FilePath $exe `
        -ArgumentList @('-p', "$argPrefix/", '-c', 'conf/nginx.conf') `
        -WorkingDirectory $prefix -WindowStyle Hidden
    Start-Sleep -Seconds 1
    if (-not (Test-Path $pidFile)) {
        throw 'nginx 未能启动，请查看 deploy\nginx\logs\error.log。'
    }
    Write-Host 'nginx 已启动：http://localhost/' -ForegroundColor Green
}

Write-Host '停止请执行：powershell -ExecutionPolicy Bypass -File deploy\stop-nginx.ps1'
