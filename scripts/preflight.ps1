<#
.SYNOPSIS
    本地开发环境前置自检。

.DESCRIPTION
    逐项检查 JDK / Maven / Node / Docker 以及端口占用情况，输出 PASS / WARN / FAIL。
    只做只读检查，不启动、不修改任何东西。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\preflight.ps1
#>

$ErrorActionPreference = 'SilentlyContinue'

$fail = 0
$warn = 0
$rows = New-Object System.Collections.ArrayList

function Add-Row {
    param([string]$Item, [string]$Status, [string]$Detail)
    [void]$rows.Add([pscustomobject]@{ Item = $Item; Status = $Status; Detail = $Detail })
    if ($Status -eq 'FAIL') { $script:fail++ }
    if ($Status -eq 'WARN') { $script:warn++ }
}

# 为什么不用 & $exe 2>&1：java -version 把版本打到 stderr，在 $ErrorActionPreference='SilentlyContinue'
# 下这些 stderr 行会变成错误记录并被丢弃，结果就是"命令成功但输出为空"。用 Process API 直读两个流更稳。
function Get-CommandVersion {
    param([string]$Exe, [string[]]$Arguments)
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        # mvn / npm 等是 .cmd 包装脚本，CreateProcess 不会自动补 .cmd，必须经 cmd.exe 执行
        if ($Exe -match '\.(cmd|bat)$') {
            $psi.FileName = $env:ComSpec
            $psi.Arguments = '/c ""' + $Exe + '" ' + ($Arguments -join ' ') + '"'
        } else {
            $psi.FileName = $Exe
            $psi.Arguments = ($Arguments -join ' ')
        }
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true

        $proc = [System.Diagnostics.Process]::Start($psi)
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $proc.StandardError.ReadToEnd()
        [void]$proc.WaitForExit(15000)
        return (($stdout + "`n" + $stderr)).Trim()
    } catch {
        return ''
    }
}

# ---------------------------------------------------------------- JDK
# 优先用 JAVA_HOME 下的 java：Windows 常见 C:\Program Files\Common Files\Oracle\Java\javapath\java.exe
# 是个空壳，java -version 返回空且退出码 0，会让"检测到 JDK 但版本解析失败"。
$javaExe = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidate) { $javaExe = $candidate }
}
if (-not $javaExe) { $javaExe = (Get-Command 'java' -ErrorAction SilentlyContinue).Source }

$javaOut = ''
if ($javaExe) { $javaOut = Get-CommandVersion $javaExe @('-version') }

if (-not $javaOut) {
    if ($javaExe) {
        Add-Row 'JDK' 'FAIL' "找到 $javaExe 但执行无输出（多为 Oracle javapath 空壳），请把真实 JDK 21 放到 PATH 前面并设好 JAVA_HOME"
    } else {
        Add-Row 'JDK' 'FAIL' '未找到 java，请安装 JDK 21 并加入 PATH'
    }
} else {
    $match = [regex]::Match($javaOut, 'version "?(\d+)')
    if ($match.Success) {
        $major = [int]$match.Groups[1].Value
        if ($major -ge 21) {
            Add-Row 'JDK' 'PASS' "Java $major"
        } else {
            Add-Row 'JDK' 'FAIL' "Java $major，本项目要求 21+"
        }
    } else {
        Add-Row 'JDK' 'WARN' '版本号无法解析'
    }
}

# ---------------------------------------------------------------- Maven
$mvnCmd = Get-Command 'mvn' -ErrorAction SilentlyContinue
$mvnOut = ''
if ($mvnCmd) { $mvnOut = Get-CommandVersion $mvnCmd.Source @('-v') }
if (-not $mvnOut) {
    Add-Row 'Maven' 'FAIL' '未找到 mvn，请安装 Maven 3.9+ 或改用 IDEA 内置 Maven'
} else {
    $match = [regex]::Match($mvnOut, 'Apache Maven (\d+\.\d+)')
    if ($match.Success) {
        Add-Row 'Maven' 'PASS' "Maven $($match.Groups[1].Value)"
    } else {
        Add-Row 'Maven' 'WARN' '版本号无法解析'
    }
}

# ---------------------------------------------------------------- Node
$nodeOut = Get-CommandVersion 'node' @('-v')
if (-not $nodeOut) {
    Add-Row 'Node.js' 'FAIL' '未找到 node，请安装 Node 20+'
} else {
    $major = 0
    $match = [regex]::Match($nodeOut, 'v(\d+)')
    if ($match.Success) { $major = [int]$match.Groups[1].Value }
    if ($major -ge 20) {
        Add-Row 'Node.js' 'PASS' $nodeOut
    } elseif ($major -ge 18) {
        Add-Row 'Node.js' 'WARN' "$nodeOut（建议 20/22）"
    } else {
        Add-Row 'Node.js' 'FAIL' "$nodeOut，要求 18+"
    }
}

# ---------------------------------------------------------------- Docker
$dockerReady = $false
$dockerOut = Get-CommandVersion 'docker' @('version', '--format', '{{.Client.Version}}')
if (-not $dockerOut) {
    Add-Row 'Docker CLI' 'FAIL' '未安装 Docker Desktop（本项目必须，pgvector 在 Windows 无法本地安装）'
} else {
    Add-Row 'Docker CLI' 'PASS' "client $dockerOut"
    $server = (& docker info --format '{{.ServerVersion}}' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -eq 0 -and $server -match '^\d+\.\d+') {
        $dockerReady = $true
        Add-Row 'Docker 引擎' 'PASS' "server $server"
    } else {
        Add-Row 'Docker 引擎' 'FAIL' '客户端在但引擎未运行，请启动 Docker Desktop 并等鲸鱼图标变绿'
    }
}

# 容器状态（引擎不可用时直接跳过，避免误报成"未启动"）
$compose = Join-Path $PSScriptRoot '..\deploy\docker-compose.dev.yml'
if (-not $dockerReady) {
    Add-Row '容器状态' 'WARN' 'Docker 引擎不可用，跳过检查'
} elseif (Test-Path $compose) {
    $running = & docker compose -f $compose ps --format '{{.Name}} {{.Status}}' 2>&1 | Out-String
    if ($running -match 'cr-postgres') {
        if ($running -match 'cr-postgres\s+Up.*healthy') {
            Add-Row 'postgres' 'PASS' '已运行且 healthy'
        } else {
            Add-Row 'postgres' 'WARN' '已运行但未 healthy（等待中或异常）'
        }
    } else {
        Add-Row 'postgres' 'WARN' '未启动：docker compose --env-file deploy\.env -f deploy\docker-compose.dev.yml up -d postgres redis'
    }
    if ($running -match 'cr-redis') {
        if ($running -match 'cr-redis\s+Up.*healthy') {
            Add-Row 'redis' 'PASS' '已运行且 healthy'
        } else {
            Add-Row 'redis' 'WARN' '已运行但未 healthy（等待中或异常）'
        }
    } else {
        Add-Row 'redis' 'WARN' '未启动（登录链路依赖它，缺了会 503）'
    }
} else {
    Add-Row 'compose 文件' 'FAIL' "未找到 $compose"
}

# ---------------------------------------------------------------- .env
$envFile = Join-Path $PSScriptRoot '..\deploy\.env'
if (-not (Test-Path $envFile)) {
    Add-Row 'deploy\.env' 'WARN' '不存在，将使用全部默认值（可跑通，但 JWT 会用源码里的 dev 密钥）'
} else {
    $content = Get-Content $envFile -Raw
    $jwtFilled = $content -match '(?m)^\s*APP_JWT_SECRET\s*=\s*\S'
    $repoFilled = $content -match '(?m)^\s*APP_REPO_SECRET_KEY\s*=\s*\S'
    if ($jwtFilled) { Add-Row 'APP_JWT_SECRET' 'PASS' '已配置' }
    else { Add-Row 'APP_JWT_SECRET' 'WARN' '未配置，将回退到源码 dev 密钥（任何人可伪造令牌）' }

    if ($repoFilled) { Add-Row 'APP_REPO_SECRET_KEY' 'PASS' '已配置，私有仓库凭据可用' }
    else { Add-Row 'APP_REPO_SECRET_KEY' 'WARN' '未配置，Git 凭据功能禁用（公开仓库不受影响）' }
}

# ---------------------------------------------------------------- 端口
$portChecks = @(
    @{ Port = 5432; Name = 'PostgreSQL';   Note = '本机自装 PostgreSQL 会占用，需改 DB_PORT 并同步给后端' },
    @{ Port = 6379; Name = 'Redis';        Note = '本机自装 Redis 会占用，需改 REDIS_PORT 并同步给后端' },
    @{ Port = 8080; Name = '后端';         Note = '后端已在运行则属正常' },
    @{ Port = 5173; Name = '前端 Vite';    Note = '前端已在运行则属正常' },
    @{ Port = 9000; Name = 'MinIO API';    Note = 'M6 才用' },
    @{ Port = 9001; Name = 'MinIO 控制台'; Note = 'M6 才用' },
    @{ Port = 8090; Name = '向量化服务';   Note = 'M3 才用' }
)

foreach ($check in $portChecks) {
    $port = [int]$check.Port
    $label = "端口 $port ($($check.Name))"
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $ownerPid = $listener.OwningProcess
        $proc = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
        $procName = if ($proc) { $proc.ProcessName } else { "PID $ownerPid" }
        Add-Row $label 'WARN' "被 $procName 占用 · $($check.Note)"
    } else {
        Add-Row $label 'PASS' '空闲'
    }
}

# ---------------------------------------------------------------- 输出
Write-Host ''
Write-Host '================ 环境自检 ================' -ForegroundColor Cyan
foreach ($row in $rows) {
    $color = switch ($row.Status) {
        'PASS' { 'Green' }
        'WARN' { 'Yellow' }
        default { 'Red' }
    }
    $mark = switch ($row.Status) {
        'PASS' { '[ OK ]' }
        'WARN' { '[WARN]' }
        default { '[FAIL]' }
    }
    Write-Host ("{0} {1,-28} {2}" -f $mark, $row.Item, $row.Detail) -ForegroundColor $color
}
Write-Host '==========================================' -ForegroundColor Cyan
Write-Host ("FAIL: {0}   WARN: {1}" -f $fail, $warn)

if ($fail -gt 0) {
    Write-Host '存在 FAIL 项，按 docs\00-本地启动与验收手册.md §10 处理后再继续。' -ForegroundColor Red
    exit 1
}

Write-Host '关键项全部通过，可以按手册 §4 启动中间件。' -ForegroundColor Green
if ($warn -gt 0) {
    Write-Host '（WARN 不阻塞启动，按需处理）' -ForegroundColor Yellow
}
exit 0
