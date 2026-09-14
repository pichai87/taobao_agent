$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$jarPath = Join-Path $projectDir 'ecom-agent-bootstrap/target/ecom-agent-bootstrap-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先执行 ./scripts/build.ps1' }
Push-Location $projectDir
try { & java -jar $jarPath } finally { Pop-Location }

