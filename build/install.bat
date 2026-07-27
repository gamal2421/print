@echo off

set "EXE_PATH=C:\zaha\zaha-service.exe"
set "SHORTCUT_NAME=ZAHA Print Service.lnk"
set "STARTUP_FOLDER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"

powershell -NoProfile -ExecutionPolicy Bypass ^
"$s=(New-Object -COM WScript.Shell).CreateShortcut('%STARTUP_FOLDER%\%SHORTCUT_NAME%');$s.TargetPath='%EXE_PATH%';$s.WorkingDirectory='C:\zaha';$s.Save()"

echo Startup shortcut created successfully.
pause