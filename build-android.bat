@echo off
REM Build and install Android app to connected device/emulator
REM Run from anywhere in the project: build-android.bat

setlocal enabledelayedexpansion

set "projectRoot=%~dp0"
set "frontendDir=%projectRoot%frontend"

if not exist "%frontendDir%" (
    echo Error: Frontend directory not found at: %frontendDir%
    exit /b 1
)

echo Building Android app...
cd /d "%frontendDir%"
call gradlew.bat installDebug

if %ERRORLEVEL% equ 0 (
    echo.
    echo Build successful!
) else (
    echo.
    echo Build failed with exit code %ERRORLEVEL%
    exit /b %ERRORLEVEL%
)
