# 日志整理脚本（默认只打印计划；加 -Apply 才真正执行）
#
# 规则：
#   1. 工程根的 build-*.log / devcheck-*.log / server-*.log：每个前缀只留最新一份；
#      其余 1.7.x 的日志归档到 logs/archive/1.7.x/，1.6.x 及更早的直接删除。
#   2. 工程根的临时文本（devcheck_*.txt / build_*.txt / runserver_*.txt / compilejava*.log）直接删除。
#   3. 上层工作区目录（..）里 _devcheck_* / _runserver_* / summy-reliquary-*-自检日志.log 直接删除。
#   4. 开发实例 run/logs 只保留 latest.log；run/crash-reports 下的旧崩溃报告删除。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/clean-logs.ps1          # 只打印计划
#   powershell -ExecutionPolicy Bypass -File scripts/clean-logs.ps1 -Apply   # 真正执行

param(
    [switch]$Apply,
    [int]$KeepPerPrefix = 1,
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $ProjectRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$archiveRoot = Join-Path $ProjectRoot 'logs\archive\1.7.x'
$actions = New-Object System.Collections.ArrayList
$freedBytes = 0L

function Add-Action([string]$kind, [string]$path) {
    [void]$actions.Add([pscustomobject]@{ Kind = $kind; Path = $path })
}

# 只允许在工程目录或上层工作区目录内操作，避免误删
function Assert-InScope([string]$path) {
    $full = [System.IO.Path]::GetFullPath($path)
    $inProject = $full.StartsWith([System.IO.Path]::GetFullPath($ProjectRoot), [StringComparison]::OrdinalIgnoreCase)
    $inWorkspace = $full.StartsWith([System.IO.Path]::GetFullPath($WorkspaceRoot), [StringComparison]::OrdinalIgnoreCase)
    if (-not ($inProject -or $inWorkspace)) {
        throw ("拒绝操作范围外的路径：" + $full)
    }
    return $full
}

# ==================== ① 工程根的构建 / 自检 / 服务端日志 ====================
foreach ($prefix in @('build-*.log', 'devcheck-*.log', 'server-*.log')) {
    $files = Get-ChildItem -LiteralPath $ProjectRoot -File -Filter $prefix -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending
    $index = 0
    foreach ($file in $files) {
        $index++
        if ($index -le $KeepPerPrefix) { continue }   # 最新的若干份保留在工程根
        $target = Assert-InScope $file.FullName
        if ($file.Name -match '1\.7\.') {
            Add-Action '归档' (Join-Path $archiveRoot $file.Name)
        } else {
            Add-Action '删除' $target
        }
    }
}

# ==================== ② 工程根的临时文本 ====================
foreach ($pattern in @('devcheck_*.txt', 'build_*.txt', 'runserver_*.txt', 'compilejava*.log', 'build_log*.txt')) {
    Get-ChildItem -LiteralPath $ProjectRoot -File -Filter $pattern -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Action '删除' (Assert-InScope $_.FullName) }
}

# ==================== ③ 上层工作区目录里的历史日志 ====================
foreach ($pattern in @('_devcheck_*.log', '_runserver_*.log', 'summy-reliquary-*-自检日志.log')) {
    Get-ChildItem -LiteralPath $WorkspaceRoot -File -Filter $pattern -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Action '删除' (Assert-InScope $_.FullName) }
}

# ==================== ④ 开发实例的游戏日志 ====================
$runLogs = Join-Path $ProjectRoot 'run\logs'
if (Test-Path -LiteralPath $runLogs) {
    Get-ChildItem -LiteralPath $runLogs -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '*.log.gz' -or $_.Name -like 'debug*.log' } |
        ForEach-Object { Add-Action '删除' (Assert-InScope $_.FullName) }
}
$crashDir = Join-Path $ProjectRoot 'run\crash-reports'
if (Test-Path -LiteralPath $crashDir) {
    Get-ChildItem -LiteralPath $crashDir -File -Filter 'crash-*.txt' -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Action '删除' (Assert-InScope $_.FullName) }
}

# ==================== 执行或打印 ====================
$grouped = $actions | Group-Object Kind
foreach ($group in $grouped) {
    Write-Host ("[{0}] {1} 个文件" -f $group.Name, $group.Count) -ForegroundColor Cyan
    foreach ($item in $group.Group) {
        Write-Host ("    " + $item.Path)
    }
}

if ($actions.Count -eq 0) {
    Write-Host '日志已经很干净，没有需要整理的文件 ✓' -ForegroundColor Green
    exit 0
}

if (-not $Apply) {
    Write-Host ''
    Write-Host ("以上是计划（干跑）。确认无误后加 -Apply 真正执行：" + $actions.Count + " 个文件") -ForegroundColor Yellow
    exit 0
}

foreach ($item in $actions) {
    if ($item.Kind -eq '归档') {
        $dir = Split-Path -Parent $item.Path
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        # 归档的源文件＝工程根里同名的那个
        $source = Join-Path $ProjectRoot (Split-Path -Leaf $item.Path)
        if (Test-Path -LiteralPath $source) {
            $freedBytes += (Get-Item -LiteralPath $source).Length   # 归档不算回收，仅统计
            Move-Item -LiteralPath $source -Destination $item.Path -Force
        }
    } else {
        if (Test-Path -LiteralPath $item.Path) {
            $freedBytes += (Get-Item -LiteralPath $item.Path).Length
            [System.IO.File]::Delete($item.Path)
        }
    }
}

Write-Host ''
Write-Host ("已整理 {0} 个文件（其中删除占用约 {1:N1} MB）✓" -f $actions.Count, ($freedBytes / 1MB)) -ForegroundColor Green
