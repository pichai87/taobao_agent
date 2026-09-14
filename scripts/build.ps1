param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$localMaven = Join-Path (Split-Path $projectDir -Parent) '.tools/apache-maven-3.9.11/bin/mvn.cmd'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $localMaven) {
    $mavenPath = $localMaven
    $mavenArgs = @('-B', "-Dmaven.repo.local=$(Join-Path (Split-Path $projectDir -Parent) '.tools/m2')")
} elseif ($mavenCommand) {
    $mavenPath = $mavenCommand.Source
    $mavenArgs = @('-B')
} else {
    throw '请先安装 Maven 3.9.11+，或使用当前工作区的 .tools Maven。'
}
if ($SkipTests) { $mavenArgs += '-DskipTests' }
Push-Location $projectDir
try {
    & $mavenPath @mavenArgs verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败，见上面的错误。' }
} finally { Pop-Location }

