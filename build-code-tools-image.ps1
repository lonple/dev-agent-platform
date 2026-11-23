$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
Set-Location (Join-Path $Root "sandbox\code-tools")
Write-Host "==> build code-tools jar"
.\gradlew.bat bootJar --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "==> docker build dap-sandbox-webdev:dev"
docker build -t dap-sandbox-webdev:dev .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "OK: dap-sandbox-webdev:dev (worktree mount + m2/npm cache volumes at attach)"
