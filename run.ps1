$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ProjectRoot "java-toolchain.ps1")
$JavaTools = Get-Java17Toolchain
$ClassesDir = Join-Path $ProjectRoot "build\classes"
$MainClass = Join-Path $ClassesDir "dev\tokenwidget\App.class"

if (-not (Test-Path $MainClass)) {
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ProjectRoot "build.ps1")
}

& $JavaTools.Javaw --add-modules jdk.httpserver -cp $ClassesDir dev.tokenwidget.App
