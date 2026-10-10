@echo off
setlocal

REM Build script for the Antiviber JetBrains plugin on Windows.
REM Run this from the antiviber\ directory.

set "SCRIPT_DIR=%~dp0"
powershell.exe -ExecutionPolicy Bypass -File "%SCRIPT_DIR%build-plugin.ps1"
exit /b %ERRORLEVEL%
