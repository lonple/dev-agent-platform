$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $Root "sandbox\code-tools")
$gradleCommand = Get-Command gradle -ErrorAction Stop
$gradleHomeForImage = Split-Path -Parent (Split-Path -Parent $gradleCommand.Source)
if (-not (Test-Path (Join-Path $gradleHomeForImage 'bin\gradle'))) {
    throw 'Please install a Gradle distribution containing bin/gradle before building the sandbox image.'
}
$gradleStage = Join-Path (Get-Location) '.build-tools\gradle'
New-Item -ItemType Directory -Path $gradleStage -Force | Out-Null
foreach ($part in @('bin', 'lib')) {
    Copy-Item -LiteralPath (Join-Path $gradleHomeForImage $part) -Destination $gradleStage -Recurse -Force
}
$runtimeScriptsStage = Join-Path (Get-Location) '.build-tools\dap-scripts'
New-Item -ItemType Directory -Path $runtimeScriptsStage -Force | Out-Null
foreach ($scriptName in @('sandbox-start.sh', 'sandbox-test.sh')) {
    Copy-Item -LiteralPath (Join-Path $Root "scripts\$scriptName") -Destination $runtimeScriptsStage -Force
}
Write-Host "==> build code-tools jar"
.\gradlew.bat bootJar --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "==> docker build dap-sandbox-webdev:dev"
docker build -t dap-sandbox-webdev:dev .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "OK: dap-sandbox-webdev:dev (worktree mount + m2/npm cache volumes at attach)"
