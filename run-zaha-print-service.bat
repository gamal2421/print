@echo off
setlocal
set "EXE_PATH=%~dp0build\zaha-print-service.exe"
if not exist "%EXE_PATH%" (
    echo Error: "%EXE_PATH%" not found.
    pause
    exit /b 1
)
start "Zaha Print Service" "%EXE_PATH%"
endlocal
