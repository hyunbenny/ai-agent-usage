$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ProjectRoot "java-toolchain.ps1")
$JavaTools = Get-Java17Toolchain
$ClassesDir = Join-Path $ProjectRoot "build\classes"
$DiagnosticsClass = Join-Path $ClassesDir "dev\tokenwidget\Diagnostics.class"

if (-not (Test-Path $DiagnosticsClass)) {
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ProjectRoot "build.ps1")
}

& $JavaTools.Java --add-modules jdk.httpserver -cp $ClassesDir dev.tokenwidget.Diagnostics
