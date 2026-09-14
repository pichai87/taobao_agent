param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$workspaceDir = Split-Path -Parent $projectDir
$localMaven = Join-Path $workspaceDir '.tools\apache-maven-3.9.11\bin\mvn.cmd'
$localRepository = Join-Path $workspaceDir '.tools\m2'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue

if (Test-Path -LiteralPath $localMaven) {
    $mavenPath = $localMaven
    $mavenArgs = @('-B', ('-Dmaven.repo.local=' + $localRepository))
} elseif ($null -ne $mavenCommand) {
    $mavenPath = $mavenCommand.Source
    $mavenArgs = @('-B')
} else {
    throw 'Maven was not found. Install Maven 3.9.11 or use the local .tools Maven.'
}

if ($SkipTests) {
    $mavenArgs += '-DskipTests'
}

Push-Location $projectDir
try {
    & $mavenPath @mavenArgs verify
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven build failed. Read the error above.'
    }
} finally {
    Pop-Location
}
