$ErrorActionPreference = "Stop"

$ProjectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
. (Join-Path $ProjectRoot "java-toolchain.ps1")
$JavaTools = Get-Java17Toolchain -RequirePackaging

if (-not (Test-Path -LiteralPath $JavaTools.Jar)) { throw "jar.exe가 포함된 전체 JDK가 필요합니다." }
if (-not (Test-Path -LiteralPath $JavaTools.Jpackage)) { throw "jpackage.exe가 포함된 전체 JDK가 필요합니다." }

$BuildScript = Join-Path $ProjectRoot "build.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $BuildScript
if ($LASTEXITCODE -ne 0) { throw "Java build failed" }

$BuildDir = Join-Path $ProjectRoot "build"
$ClassesDir = Join-Path $BuildDir "classes"
$PackageInput = Join-Path $BuildDir "package-input"
$PackageOutput = Join-Path $BuildDir "package-output"
$SfxStage = Join-Path $BuildDir "sfx"
# 배포용 포터블 EXE는 내부 런처(build\package-output\...\AIUsageWidget.exe)와
# 헷갈리지 않도록 dist 폴더에 별도 이름으로 생성한다. 이 파일 하나만 전달하면 된다.
$OutputsDir = Join-Path $ProjectRoot "dist"
if (-not (Test-Path -LiteralPath $OutputsDir)) {
    New-Item -ItemType Directory -Path $OutputsDir | Out-Null
}
$OutputExe = Join-Path $OutputsDir "AIUsageWidget-portable.exe"
$AppVersion = "1.4.0"

foreach ($path in @($PackageInput, $PackageOutput, $SfxStage)) {
    if (Test-Path -LiteralPath $path) {
        $resolved = [System.IO.Path]::GetFullPath($path)
        if (-not $resolved.StartsWith([System.IO.Path]::GetFullPath($BuildDir) + [System.IO.Path]::DirectorySeparatorChar)) {
            throw "Unsafe package path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $path | Out-Null
}

$JarPath = Join-Path $PackageInput "token-usage-widget.jar"
& $JavaTools.Jar --create --file $JarPath -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "JAR packaging failed" }

$JpackageArgs = @(
    "--type", "app-image",
    "--name", "AIUsageWidget",
    "--app-version", $AppVersion,
    "--input", $PackageInput,
    "--main-jar", "token-usage-widget.jar",
    "--main-class", "dev.tokenwidget.App",
    "--add-modules", "java.desktop,java.prefs,java.net.http,jdk.httpserver",
    "--dest", $PackageOutput
)
# EXE 아이콘: assets\app-icon.ico가 있으면 적용한다.
$AppIcon = Join-Path $ProjectRoot "assets\app-icon.ico"
if (Test-Path -LiteralPath $AppIcon) {
    $JpackageArgs += @("--icon", $AppIcon)
}
& $JavaTools.Jpackage @JpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$AppImage = Join-Path $PackageOutput "AIUsageWidget"
$PackagedLauncher = Join-Path $AppImage "AIUsageWidget.exe"
$SmokeTest = Start-Process -FilePath $PackagedLauncher -ArgumentList "--smoke-test" -Wait -PassThru -WindowStyle Hidden
if ($SmokeTest.ExitCode -ne 0) { throw "Packaged runtime smoke test failed" }

$ExtensionSource = Join-Path $ProjectRoot "extension"
if (Test-Path -LiteralPath $ExtensionSource) {
    Copy-Item -LiteralPath $ExtensionSource -Destination $AppImage -Recurse
}
$PayloadZip = Join-Path $SfxStage "payload.zip"
Compress-Archive -Path (Join-Path $AppImage "*") -DestinationPath $PayloadZip -CompressionLevel Optimal

# IExpress는 공백·한글이 포함된 경로에서 실패하는 고질적인 버그가 있으므로,
# 모든 PC에 존재하는 ASCII 경로(C:\Users\Public)에서 패키징한 뒤 결과만 dist로 옮긴다.
$AsciiStage = Join-Path $env:PUBLIC "AIUsageWidget-sfx"
if (Test-Path -LiteralPath $AsciiStage) {
    Remove-Item -LiteralPath $AsciiStage -Recurse -Force
}
New-Item -ItemType Directory -Path $AsciiStage | Out-Null
Copy-Item -LiteralPath $PayloadZip -Destination $AsciiStage
# launcher.cmd는 저장 방식(맥/에디터에 따라 LF가 될 수 있음)과 무관하게
# 항상 CRLF + ASCII로 정규화해서 넣는다. Windows cmd.exe가 LF 배치를 오파싱하기 때문.
$LauncherText = Get-Content -LiteralPath (Join-Path $ProjectRoot "packaging\launcher.cmd") -Raw
$LauncherText = ($LauncherText -replace "`r`n", "`n") -replace "`n", "`r`n"
[System.IO.File]::WriteAllText((Join-Path $AsciiStage "launcher.cmd"), $LauncherText, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $AsciiStage "version.txt"), $AppVersion, [System.Text.Encoding]::ASCII)

$SfxStage = $AsciiStage
$AsciiExe = Join-Path $AsciiStage "AIUsageWidget-portable.exe"

$SedPath = Join-Path $AsciiStage "AIUsageWidget.sed"
$Sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[SourceFiles]
SourceFiles0=$SfxStage\
[SourceFiles0]
%FILE0%=
%FILE1%=
%FILE2%=
[Strings]
TargetName="$AsciiExe"
FriendlyName="AI Account Usage Widget"
AppLaunched="launcher.cmd"
PostInstallCmd="<None>"
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="payload.zip"
FILE1="launcher.cmd"
FILE2="version.txt"
"@
[System.IO.File]::WriteAllText($SedPath, $Sed, [System.Text.Encoding]::Default)

$IExpress = Join-Path $env:WINDIR "System32\iexpress.exe"
$IExpressProcess = Start-Process -FilePath $IExpress -ArgumentList @("/N", "/Q", $SedPath) -Wait -PassThru -WindowStyle Hidden
if ($IExpressProcess.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $AsciiExe)) {
    Write-Host "IExpress exit code: $($IExpressProcess.ExitCode)"
    Write-Host "SED file: $SedPath"
    Write-Host "오류 원인을 보려면 다음을 직접 실행해 보세요: iexpress /N `"$SedPath`""
    throw "IExpress single-EXE packaging failed"
}

# 결과물을 프로젝트의 dist 폴더로 옮기고 임시 작업 폴더를 정리한다.
Move-Item -LiteralPath $AsciiExe -Destination $OutputExe -Force
Remove-Item -LiteralPath $AsciiStage -Recurse -Force

Write-Host "Portable single EXE: $OutputExe"
