# DAP one-click start (PowerShell)
# backend :8200  frontend :8201  agent :8202
# sandbox-manager :9210  sandbox-client :9300
# Free ports if occupied; merge logs; Ctrl+C stops all
# Usage: .\start-dev.ps1
# Optional: .\start-dev.ps1 -BuildSandboxImage   # 缺失时构建 dap-sandbox-webdev:dev

param(
    [switch]$BuildSandboxImage
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$BackendPort = 8200
$FrontendPort = 8201
$AgentPort = 8202
$SandboxManagerPort = 9210
$SandboxClientPort = 9300
$SandboxImage = 'dap-sandbox-webdev:dev'

# 统一控制台与子进程 stdout 为 UTF-8，避免中文乱码
try { chcp 65001 | Out-Null } catch { }
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
# 统一 Java/Gradle/Logback 为 UTF-8，避免中文 Windows 上 CONSOLE_LOG_CHARSET=GBK 导致乱码
$javaUtf8Flags = @(
    '-Dfile.encoding=UTF-8',
    '-Dsun.jnu.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8',
    '-Dsun.stderr.encoding=UTF-8',
    '-Dconsole.encoding=UTF-8',
    '-DCONSOLE_LOG_CHARSET=UTF-8',
    '-DFILE_LOG_CHARSET=UTF-8'
) -join ' '
$env:JAVA_TOOL_OPTIONS = $javaUtf8Flags
$env:GRADLE_OPTS = $javaUtf8Flags
$env:JAVA_OPTS = $javaUtf8Flags

function Write-Sys([string]$Message, [string]$Color = 'Cyan') {
    $t = Get-Date -Format 'HH:mm:ss'
    Write-Host "[$t][sys] $Message" -ForegroundColor $Color
}

function Get-ListenPids([int]$Port) {
    $pids = [System.Collections.Generic.HashSet[int]]::new()
    try {
        $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($c in $conns) {
            if ($c.OwningProcess -gt 0) { [void]$pids.Add([int]$c.OwningProcess) }
        }
    } catch {
        $lines = netstat -ano -p tcp 2>$null
        foreach ($line in $lines) {
            if ($line -notmatch 'LISTENING') { continue }
            if ($line -notmatch ":$Port\s") { continue }
            $parts = ($line -split '\s+') | Where-Object { $_ -ne '' }
            $pidVal = 0
            if ([int]::TryParse($parts[-1], [ref]$pidVal) -and $pidVal -gt 0) {
                [void]$pids.Add($pidVal)
            }
        }
    }
    return @($pids)
}

function Stop-PortListeners([int[]]$Ports) {
    $all = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($port in $Ports) {
        $found = Get-ListenPids -Port $port
        if ($found.Count -gt 0) {
            Write-Sys "Port $port in use, PID: $($found -join ', '), stopping..." 'Yellow'
            foreach ($p in $found) { [void]$all.Add($p) }
        } else {
            Write-Sys "Port $port is free"
        }
    }

    if ($all.Count -eq 0) {
        Write-Sys 'No existing process found, starting fresh'
        return
    }

    foreach ($pidVal in $all) {
        try {
            & taskkill /PID $pidVal /T /F 2>$null | Out-Null
            Write-Sys "Stopped PID $pidVal" 'Yellow'
        } catch {
            Write-Sys "Failed to stop PID $pidVal (may already exit)" 'Yellow'
        }
    }

    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 300
        $still = @()
        foreach ($port in $Ports) { $still += Get-ListenPids -Port $port }
        if ($still.Count -eq 0) { break }
    }
    Write-Sys 'Old processes cleaned'
}

function Start-LoggedProcess {
    param(
        [string]$Name,
        [ConsoleColor]$Color,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = ($ArgumentList -join ' ')
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = $utf8
    $psi.StandardErrorEncoding = $utf8
    foreach ($key in @(
            'JAVA_TOOL_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS',
            'PYTHONIOENCODING', 'PYTHONUTF8',
            'LLM_API_KEY', 'LLM_BASE_URL', 'LLM_MODEL',
            'DAP_REVIEW_LLM_API_KEY', 'DAP_REVIEW_LLM_BASE_URL', 'DAP_REVIEW_LLM_CHAT_MODEL'
        )) {
        $val = [Environment]::GetEnvironmentVariable($key)
        if (-not [string]::IsNullOrEmpty($val)) {
            $psi.EnvironmentVariables[$key] = $val
        }
    }

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()

    $outHandler = {
        if (-not [string]::IsNullOrEmpty($EventArgs.Data)) {
            $t = Get-Date -Format 'HH:mm:ss'
            Write-Host "[$t][$($Event.MessageData.Name)] $($EventArgs.Data)" -ForegroundColor $Event.MessageData.Color
        }
    }
    $meta = @{ Name = $Name; Color = $Color }
    $null = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $outHandler -MessageData $meta
    $null = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action $outHandler -MessageData $meta
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()
    return $proc
}

function Ensure-SandboxImage {
    # 仅预检：不创建容器。缺镜像只告警，不阻断 start-dev。
    $docker = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $docker) { $docker = Get-Command docker -ErrorAction SilentlyContinue }
    if (-not $docker) {
        Write-Sys '[sandbox check] docker not found — OK to continue; attach later needs Docker Desktop' 'Yellow'
        return
    }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 吞掉 docker 的 "No such image" 原生 stderr，避免看起来像启动失败
        cmd /c "docker image inspect $SandboxImage >nul 2>&1"
        $imgOk = ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($imgOk) {
        Write-Sys "[sandbox check] image ok: $SandboxImage (containers still created only on lease attach)"
        return
    }
    if ($BuildSandboxImage) {
        Write-Sys "[sandbox check] building image $SandboxImage ..." 'Yellow'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'build-code-tools-image.ps1')
        if ($LASTEXITCODE -ne 0) {
            Write-Sys '[sandbox check] image build failed — start-dev continues; fix before attach' 'Yellow'
        } else {
            Write-Sys "[sandbox check] image built: $SandboxImage"
        }
        return
    }
    Write-Sys "[sandbox check] image not built yet: $SandboxImage — start-dev continues. Build once with .\build-code-tools-image.ps1 (or -BuildSandboxImage). Container create = lease attach, not now." 'Yellow'
}

Set-Location $Root
Write-Sys "Starting DAP: backend :$BackendPort , frontend :$FrontendPort , agent :$AgentPort , sandbox-manager :$SandboxManagerPort , sandbox-client :$SandboxClientPort"
Write-Sys 'Press Ctrl+C to stop all'
Write-Sys 'Note: code-tools Docker containers are created on lease attach, not at start-dev'

Stop-PortListeners -Ports @($BackendPort, $FrontendPort, $AgentPort, $SandboxManagerPort, $SandboxClientPort)
Ensure-SandboxImage

$gradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
if (-not $gradle) { $gradle = Get-Command gradle -ErrorAction SilentlyContinue }
if (-not $gradle) {
    Write-Sys 'gradle not found in PATH' 'Red'
    exit 1
}

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npm) {
    Write-Sys 'npm not found. Please install Node.js' 'Red'
    exit 1
}

$uv = Get-Command uv.exe -ErrorAction SilentlyContinue
if (-not $uv) { $uv = Get-Command uv -ErrorAction SilentlyContinue }
if (-not $uv) {
    Write-Sys 'uv not found. Please install uv for dap-agent' 'Red'
    exit 1
}

$agentEnv = Join-Path $Root 'dap-agent\.env'
if (-not (Test-Path $agentEnv)) {
    $example = Join-Path $Root 'dap-agent\.env.example'
    if (Test-Path $example) {
        Copy-Item $example $agentEnv
        Write-Sys 'Created dap-agent/.env from .env.example — fill LLM_* before chatting' 'Yellow'
    }
}

# 将 dap-agent/.env 的 LLM_* 注入进程环境，供 backend 代码评审分片与 agent 共用同一套 key/模型
function Import-AgentLlmEnv([string]$EnvFile) {
    if (-not (Test-Path $EnvFile)) { return }
    $keys = @('LLM_API_KEY', 'LLM_BASE_URL', 'LLM_MODEL')
    Get-Content -Path $EnvFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith('#')) { return }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { return }
        $k = $line.Substring(0, $eq).Trim()
        if ($keys -notcontains $k) { return }
        $v = $line.Substring($eq + 1).Trim().Trim('"').Trim("'")
        Set-Item -Path "Env:$k" -Value $v
    }
    if ($env:LLM_API_KEY) {
        Write-Sys 'Loaded LLM_* from dap-agent/.env for backend review shards'
    } else {
        Write-Sys 'LLM_API_KEY missing in dap-agent/.env — code review shards will fail' 'Yellow'
    }
}
Import-AgentLlmEnv $agentEnv

$sbMgrGradle = Join-Path $Root 'sandbox\sandbox-manager\gradlew.bat'
$sbCliGradle = Join-Path $Root 'sandbox\sandbox-client\gradlew.bat'
if (-not (Test-Path $sbMgrGradle) -or -not (Test-Path $sbCliGradle)) {
    Write-Sys 'sandbox gradlew.bat not found under sandbox/sandbox-manager|client' 'Red'
    exit 1
}

$sbManager = Start-LoggedProcess -Name 'sb-manager' -Color DarkYellow `
    -FilePath $sbMgrGradle `
    -ArgumentList @('bootRun', '--no-daemon') `
    -WorkingDirectory (Join-Path $Root 'sandbox\sandbox-manager')

$sbClient = Start-LoggedProcess -Name 'sb-client' -Color DarkCyan `
    -FilePath $sbCliGradle `
    -ArgumentList @('bootRun', '--no-daemon') `
    -WorkingDirectory (Join-Path $Root 'sandbox\sandbox-client')

$backend = Start-LoggedProcess -Name 'backend' -Color Green `
    -FilePath $gradle.Source `
    -ArgumentList @('bootRun', '--no-daemon') `
    -WorkingDirectory (Join-Path $Root 'dap-server')

$frontend = Start-LoggedProcess -Name 'frontend' -Color Blue `
    -FilePath $npm.Source `
    -ArgumentList @('run', 'dev', '--', '--host', '0.0.0.0', '--port', "$FrontendPort") `
    -WorkingDirectory (Join-Path $Root 'dap-front')

$agent = Start-LoggedProcess -Name 'agent' -Color Magenta `
    -FilePath $uv.Source `
    -ArgumentList @('run', 'dap-agent') `
    -WorkingDirectory (Join-Path $Root 'dap-agent')

$script:procs = @($sbManager, $sbClient, $backend, $frontend, $agent)

try {
    while ($true) {
        $alive = $script:procs | Where-Object { -not $_.HasExited }
        if ($alive.Count -eq 0) {
            Write-Sys 'All processes exited' 'Yellow'
            break
        }
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Sys 'Stopping child processes...' 'Yellow'
    foreach ($p in $script:procs) {
        if ($p -and -not $p.HasExited) {
            try { & taskkill /PID $p.Id /T /F 2>$null | Out-Null } catch { }
        }
    }
    Get-EventSubscriber -ErrorAction SilentlyContinue | Unregister-Event -Force -ErrorAction SilentlyContinue
    Write-Sys 'All stopped'
}
