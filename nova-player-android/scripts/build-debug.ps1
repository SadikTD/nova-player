$ErrorActionPreference = 'Stop'
$novaProject = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $novaJavaRoot = Join-Path $env:LOCALAPPDATA 'NovaPlayerBuild'
    $novaJava = Get-ChildItem -LiteralPath $novaJavaRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $novaJava) { throw 'Install JDK 17 and set JAVA_HOME before building.' }
    $env:JAVA_HOME = $novaJava.FullName
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'Install Android SDK 36 and set ANDROID_HOME before building.'
}
Push-Location -LiteralPath $novaProject
try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed. See the Gradle error above.' }
} finally {
    Pop-Location
}
