# Build and install Android app to connected device/emulator
# Run from anywhere in the project: .\build-android.ps1

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendDir = Join-Path $projectRoot "frontend"

if (-not (Test-Path $frontendDir)) {
    Write-Error "Frontend directory not found at: $frontendDir"
    exit 1
}

Write-Host "Building Android app..." -ForegroundColor Cyan
Set-Location $frontendDir
& .\gradlew installDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Build successful!" -ForegroundColor Green
} else {
    Write-Host "❌ Build failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
