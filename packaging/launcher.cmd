@echo off
setlocal
set "APPROOT=%LOCALAPPDATA%\AIUsageWidget"
set "VERSION="
set "CURRENT="
set /p VERSION=<"%~dp0version.txt"
if exist "%APPROOT%\version.txt" set /p CURRENT=<"%APPROOT%\version.txt"
if not exist "%APPROOT%\AIUsageWidget.exe" set "CURRENT="

if /I not "%CURRENT%"=="%VERSION%" (
    if not exist "%APPROOT%" mkdir "%APPROOT%"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%~dp0payload.zip' -DestinationPath '%APPROOT%' -Force"
    if errorlevel 1 (
        echo AI Usage Widget extraction failed.
        pause
        exit /b 1
    )
    copy /y "%~dp0version.txt" "%APPROOT%\version.txt" >nul
    rem Create a desktop shortcut for the widget (continue even if this fails).
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { $ws = New-Object -ComObject WScript.Shell; $lnk = $ws.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'AI Usage Widget.lnk')); $lnk.TargetPath = '%APPROOT%\AIUsageWidget.exe'; $lnk.WorkingDirectory = '%APPROOT%'; $lnk.IconLocation = '%APPROOT%\AIUsageWidget.exe,0'; $lnk.Description = 'AI Account Usage Widget'; $lnk.Save() } catch { }"
)

start "" "%APPROOT%\AIUsageWidget.exe"
endlocal
