# =============================================================
# 构建前端并发布到本机 nginx 站点目录，然后重载 nginx
# 用法：powershell -ExecutionPolicy Bypass -File deploy\deploy-frontend.ps1
# 产物目录：deploy\nginx\html\servicedesk-ai（与 frontend\dist 同步）
# =============================================================
$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path $repo 'frontend'
$prefix = Join-Path $PSScriptRoot 'nginx'
$target = Join-Path $prefix 'html\servicedesk-ai'

if (-not (Test-Path (Join-Path $frontend 'package.json'))) {
    throw "未找到 $frontend\package.json。"
}

# 部分机器的系统临时目录 ACL 异常会让 esbuild 写临时文件失败，这里指到项目内
$tmp = Join-Path $PSScriptRoot 'tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TMP = $tmp
$env:TEMP = $tmp

Write-Host '构建前端 …' -ForegroundColor Cyan
Push-Location $frontend
try {
    npm run build
    if ($LASTEXITCODE -ne 0) {
        throw '前端构建失败，请查看上方输出。'
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $target | Out-Null
Write-Host "发布到 $target …" -ForegroundColor Cyan
robocopy (Join-Path $frontend 'dist') $target /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
$copyCode = $LASTEXITCODE
if ($copyCode -ge 8) {
    throw "复制产物失败（robocopy 退出码 $copyCode）。"
}

# 运行中的 nginx 需要重载；未运行则提示启动
$pidFile = Join-Path $prefix 'logs\nginx.pid'
$running = $false
if (Test-Path $pidFile) {
    $nginxPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($nginxPid -and (Get-Process -Id $nginxPid -ErrorAction SilentlyContinue)) {
        $running = $true
    }
}

if ($running) {
    $exe = Join-Path $prefix 'nginx.exe'
    $argPrefix = $prefix.Replace('\', '/')
    $reloadProc = Start-Process -FilePath $exe `
        -ArgumentList @('-p', "$argPrefix/", '-c', 'conf/nginx.conf', '-s', 'reload') `
        -Wait -PassThru -NoNewWindow
    if ($reloadProc.ExitCode -ne 0) {
        throw '产物已更新，但 nginx 重载失败，请查看 deploy\nginx\logs\error.log。'
    }
    Write-Host '已发布并重载 nginx：http://localhost/' -ForegroundColor Green
} else {
    Write-Host '已发布。nginx 未运行，执行 deploy\start-nginx.ps1 后访问 http://localhost/' -ForegroundColor Yellow
}
