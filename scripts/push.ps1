<#
.SYNOPSIS
    带重试的 git 推送，代理抖动时不必手忙脚乱。

.DESCRIPTION
    本机走的是本地 HTTP 代理，代理上游节点偶尔返回 502，表现为：
        error: RPC failed; HTTP 502 curl 22 The requested URL returned error: 502
    提交本身没问题，等几十秒重试通常就能过。这个脚本把"等待 + 重试 + 失败诊断"
    固定下来，避免每次手动试错。

    失败时不会动工作区，也不会改历史——只重复执行 push。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\push.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\push.ps1 -Branch dev -Retries 5
#>

param(
    [string]$Remote = 'origin',
    [string]$Branch = 'main',
    [int]$Retries = 4
)

$ErrorActionPreference = 'Continue'

# 脚本放在 scripts/ 下，仓库根在它上一层。切换过去再操作，
# 否则从任意目录调用都会读不到当前分支。
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $repoRoot -or -not (Test-Path (Join-Path $repoRoot '.git'))) {
    Write-Host "找不到仓库根目录（脚本需放在仓库的 scripts/ 下）" -ForegroundColor Red
    exit 2
}
Set-Location $repoRoot

# 统一输出为 UTF-8，避免中文提示在 PS 5.1 控制台变问号
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step {
    param([string]$Message, [string]$Color = 'Gray')
    Write-Host $Message -ForegroundColor $Color
}

function Get-PendingCount {
    # 统计本地领先远端的提交数；远端引用不存在时返回 -1（首次推送）
    $ref = "refs/remotes/$Remote/$Branch"
    $exists = git rev-parse --verify --quiet $ref 2>$null
    if (-not $exists) { return -1 }
    $count = git rev-list --count "$ref..HEAD" 2>$null
    if (-not $count) { return 0 }
    return [int]$count
}

Write-Step ""
Write-Step "== 推送前检查 ==" 'Cyan'

$branchNow = git rev-parse --abbrev-ref HEAD 2>$null
if ($branchNow -ne $Branch) {
    Write-Step "当前分支是 $branchNow，与目标分支 $Branch 不一致。" 'Yellow'
    Write-Step "如果确实要推 $branchNow，请加参数 -Branch $branchNow" 'Yellow'
    exit 2
}

$dirty = git status --porcelain 2>$null
if ($dirty) {
    Write-Step "工作区有未提交改动（push 只推已提交内容，不影响本次操作）：" 'Yellow'
    $dirty | Select-Object -First 10 | ForEach-Object { Write-Step "  $_" 'DarkGray' }
}

$pending = Get-PendingCount
if ($pending -eq 0) {
    Write-Step "本地与 $Remote/$Branch 已一致，无需推送。" 'Green'
    exit 0
}
if ($pending -gt 0) {
    Write-Step "待推送提交 $pending 个：" 'Gray'
    git log --oneline "$Remote/$Branch..HEAD" | ForEach-Object { Write-Step "  $_" 'DarkGray' }
} else {
    Write-Step "远端尚无 $Branch 分支，将新建。" 'Gray'
}

Write-Step ""
Write-Step "== 开始推送（最多 $Retries 次）==" 'Cyan'

$delay = 5
for ($i = 1; $i -le $Retries; $i++) {
    Write-Step ""
    Write-Step "[$i/$Retries] git push $Remote $Branch ..." 'Gray'

    # 2>&1 在 PS 5.1 里会让 $? 失真，统一用 Process 分别接住 stdout / stderr
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'git'
    $psi.Arguments = "push $Remote $Branch"
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = (Get-Location).Path
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $code = $proc.ExitCode

    $output = ($stdout + $stderr).Trim()
    if ($output) { $output -split "`r?`n" | ForEach-Object { Write-Step "  $_" 'DarkGray' } }

    if ($code -eq 0) {
        Write-Step ""
        Write-Step "推送成功。" 'Green'
        Write-Step "远端最新：" 'Gray'
        git log --oneline "$Remote/$Branch" -3 | ForEach-Object { Write-Step "  $_" 'DarkGray' }
        exit 0
    }

    # 失败分类：只有网络/代理类错误才值得重试
    $retryable = $output -match '502|503|504|timed out|Connection reset|Could not resolve|Failed to connect|RPC failed|early EOF'
    if (-not $retryable) {
        Write-Step ""
        Write-Step "这次失败看起来不是网络问题（非 5xx / 非超时），重试大概率也没用，先停下。" 'Red'
        Write-Step "常见原因：远端有本地没有的提交（需要先 pull --rebase）、无推送权限、凭据失效。" 'Yellow'
        exit 1
    }

    if ($i -lt $Retries) {
        Write-Step "第 $i 次失败，$delay 秒后重试（代理抖动通常十几秒内恢复）..." 'Yellow'
        Start-Sleep -Seconds $delay
        $delay = $delay * 2
    }
}

Write-Step ""
Write-Step "== $Retries 次都失败，做一次诊断 ==" 'Red'

$proxy = $env:https_proxy
if (-not $proxy) { $proxy = $env:HTTPS_PROXY }
Write-Step "https_proxy = $(if ($proxy) { $proxy } else { '<未设置>' })"

Write-Step ""
Write-Step "1) 代理本身通不通（访问一个国内站点）" 'Cyan'
try {
    $r = Invoke-WebRequest -Uri 'https://www.baidu.com' -TimeoutSec 10 -UseBasicParsing
    Write-Step "   baidu  $($r.StatusCode)" 'Green'
} catch {
    Write-Step "   baidu  失败：$($_.Exception.Message)" 'Red'
    Write-Step "   先确认代理客户端在运行（托盘图标/服务状态）" 'Yellow'
}

Write-Step ""
Write-Step "2) GitHub 通不通（走代理）" 'Cyan'
try {
    $r = Invoke-WebRequest -Uri 'https://github.com' -TimeoutSec 20 -UseBasicParsing
    Write-Step "   github  $($r.StatusCode)" 'Green'
} catch {
    Write-Step "   github  失败：$($_.Exception.Message)" 'Red'
    Write-Step "   502 一般是代理上游节点故障，换个节点或等几分钟" 'Yellow'
}

Write-Step ""
Write-Step "3) git 能不能读到远端" 'Cyan'
$probe = git ls-remote --heads $Remote 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Step "   ls-remote 正常，说明只是 push 阶段被中断，直接重跑本脚本即可" 'Green'
} else {
    Write-Step "   ls-remote 也失败：$($probe -join ' ')" 'Red'
}

Write-Step ""
Write-Step "4) 已写入的兼容配置（HTTP/2 与大包分块是 502 的常见诱因）" 'Cyan'
foreach ($key in @('http.version', 'http.postBuffer', 'http.lowSpeedLimit', 'http.lowSpeedTime')) {
    $value = git config --local --get $key 2>$null
    Write-Step ("   {0,-18} {1}" -f $key, $(if ($value) { $value } else { '<未设置>' }))
}

Write-Step ""
Write-Step "排查顺序建议：换代理节点 -> 重跑本脚本；仍失败时用 git ls-remote 判断是'连不上'还是'推不动'。" 'Yellow'
exit 1
