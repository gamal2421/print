@echo off
setlocal

set "APP_FOLDER=%LOCALAPPDATA%\ZAHA"
set "SOURCE_EXE=%~dp0zaha-print-service.exe"
set "EXE_PATH=%APP_FOLDER%\zaha-print-service.exe"
set "SHORTCUT_NAME=ZAHA Print Service.lnk"
set "STARTUP_FOLDER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"

if not exist "%SOURCE_EXE%" (
    echo "%SOURCE_EXE%" was not found.
    pause
    exit /b 1
)

if not exist "%APP_FOLDER%" mkdir "%APP_FOLDER%"

copy /Y "%SOURCE_EXE%" "%EXE_PATH%" >nul
if errorlevel 1 (
    echo Unable to copy the print service executable.
    pause
    exit /b 1
)

if exist "%~dp0poppler\" (
    if not exist "%APP_FOLDER%\poppler" mkdir "%APP_FOLDER%\poppler"
    xcopy /E /I /Y "%~dp0poppler\*" "%APP_FOLDER%\poppler\" >nul
    if errorlevel 1 (
        echo Unable to copy the PDF rendering files.
        pause
        exit /b 1
    )
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=Start-Process -FilePath 'sc.exe' -ArgumentList 'config stisvc start= auto' -Verb RunAs -Wait -PassThru; exit $p.ExitCode"
if errorlevel 1 (
    echo Unable to enable the Windows Image Acquisition service.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath 'sc.exe' -ArgumentList 'start stisvc' -Verb RunAs -Wait"

sc.exe query stisvc | findstr /I "RUNNING" >nul
if errorlevel 1 (
    echo Windows Image Acquisition is not running.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$manager=New-Object -ComObject WIA.DeviceManager; $scanner=@($manager.DeviceInfos | Where-Object { $_.Type -eq 1 }); if ($scanner.Count -eq 0) { exit 1 }"
if errorlevel 1 (
    echo No WIA scanner was found. Install the scanner manufacturer's WIA driver, then run this installer again.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass ^
"$s=(New-Object -COM WScript.Shell).CreateShortcut('%STARTUP_FOLDER%\%SHORTCUT_NAME%');$s.TargetPath='%EXE_PATH%';$s.WorkingDirectory='%APP_FOLDER%';$s.Save()"

echo Windows Image Acquisition is ready and the startup shortcut was created successfully.
pause
endlocal
