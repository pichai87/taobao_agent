$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectDir 'ecom-agent-bootstrap\target\ecom-agent-bootstrap-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Jar not found. Run .\scripts\build.ps1 first.'
}

Push-Location $projectDir
try {
    & java -jar $jarPath
} finally {
    Pop-Location
}
