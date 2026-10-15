# Dot-source this file from PowerShell: . ./tools/dev-env.ps1
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path $PSScriptRoot -Parent
$taskSdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME/platform-tools/adb.exe")) {
    $taskSdkRoot = $env:ANDROID_HOME
}
if (-not (Test-Path "$taskSdkRoot/platform-tools/adb.exe")) {
    throw 'Android SDK is missing. See docs/development-environment.md'
}

$taskJavaCandidates = @()
if ($env:JAVA_HOME) { $taskJavaCandidates += $env:JAVA_HOME }
$taskJavaCandidates += 'C:/Program Files/Android/Android Studio/jbr'
$taskJavaCandidates += Join-Path $env:LOCALAPPDATA 'Programs/AndroidStudio/android-studio/jbr'
$taskJavaCandidates += Get-ChildItem "$env:USERPROFILE/.gradle/jdks" -Directory -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName
$taskJava25 = $null
foreach ($taskCandidate in $taskJavaCandidates) {
    $taskRelease = Join-Path $taskCandidate 'release'
    if ((Test-Path "$taskCandidate/bin/java.exe") -and (Test-Path $taskRelease) -and
        (Select-String -LiteralPath $taskRelease -Pattern '^JAVA_VERSION="25(?:[.\"+\-])' -Quiet)) {
        $taskJava25 = $taskCandidate
        break
    }
}
if (-not $taskJava25) { throw 'JDK 25 is missing. See docs/development-environment.md' }
$env:JAVA_HOME = $taskJava25
$env:ANDROID_HOME = $taskSdkRoot
$env:ANDROID_SDK_ROOT = $taskSdkRoot
$taskPathEntries = @(
    "$taskJava25/bin",
    "$taskSdkRoot/platform-tools",
    "$taskSdkRoot/emulator",
    "$taskSdkRoot/cmdline-tools/latest/bin"
)
$taskExistingEntries = $env:Path -split ';' | Where-Object { $_ -and $_ -notin $taskPathEntries }
$env:Path = ($taskPathEntries + $taskExistingEntries) -join ';'
Write-Host "Izuna: JDK 25 / Android SDK ready ($taskProjectRoot)"
