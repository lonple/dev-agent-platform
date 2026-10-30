# DAP one-click start (PowerShell)
# backend :8200  frontend :8201  agent :8202
# Free ports if occupied; merge logs; Ctrl+C stops all
# Usage: .\start-dev.ps1

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$BackendPort = 8200
$FrontendPort = 8201
$AgentPort = 8202

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
    foreach ($key in @('JAVA_TOOL_OPTIONS', 'PYTHONIOENCODING', 'PYTHONUTF8')) {
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

Set-Location $Root
Write-Sys "Starting DAP: backend :$BackendPort , frontend :$FrontendPort , agent :$AgentPort"
Write-Sys 'Press Ctrl+C to stop all'

Stop-PortListeners -Ports @($BackendPort, $FrontendPort, $AgentPort)

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

$script:procs = @($backend, $frontend, $agent)

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
