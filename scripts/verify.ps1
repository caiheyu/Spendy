param([switch]$Device)
$ErrorActionPreference = 'Stop'
# Sanitize this child process only: stray quotes in a Windows PATH entry otherwise
# leak into AGP's java.library.path JVM argument. No system environment is changed.
$originalPath = $env:Path
try {
    $env:Path = $env:Path.Replace('"', '')
    Push-Location (Split-Path $PSScriptRoot -Parent)
    $tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
    if ($Device) { $tasks += ':app:connectedDebugAndroidTest' }
    # Serialize AGP work on Windows: concurrent lint/compile jar tasks can retain
    # open handles to the same intermediate jar.
    & .\gradlew.bat --no-daemon --max-workers=1 @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
    $env:Path = $originalPath
}
