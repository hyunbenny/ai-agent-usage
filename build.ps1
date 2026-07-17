$ErrorActionPreference = "Stop"

$ProjectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
. (Join-Path $ProjectRoot "java-toolchain.ps1")
$JavaTools = Get-Java17Toolchain
$BuildDir = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "build"))
$ClassesDir = Join-Path $BuildDir "classes"
$TestClassesDir = Join-Path $BuildDir "test-classes"

if (Test-Path $BuildDir) {
    $ExpectedBuildDir = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "build"))
    if ($BuildDir -ne $ExpectedBuildDir -or -not $BuildDir.StartsWith($ProjectRoot + [System.IO.Path]::DirectorySeparatorChar)) {
        throw "Unsafe build directory: $BuildDir"
    }
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Path $TestClassesDir | Out-Null

$MainSources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "src\main\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
$TestSources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "src\test\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

& $JavaTools.Javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d $ClassesDir $MainSources
if ($LASTEXITCODE -ne 0) { throw "Main source compilation failed" }

& $JavaTools.Javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d $TestClassesDir $MainSources $TestSources
if ($LASTEXITCODE -ne 0) { throw "Test source compilation failed" }

# 애플리케이션·트레이 아이콘 리소스를 클래스패스에 포함시킨다.
$AssetsSource = Join-Path $ProjectRoot "assets"
if (Test-Path -LiteralPath $AssetsSource) {
    foreach ($destination in @($ClassesDir, $TestClassesDir)) {
        $assetsDestination = Join-Path $destination "assets"
        New-Item -ItemType Directory -Path $assetsDestination -Force | Out-Null
        Copy-Item -Path (Join-Path $AssetsSource "*.png") -Destination $assetsDestination -Force
    }
}

& $JavaTools.Java --add-modules jdk.httpserver -ea -cp $TestClassesDir dev.tokenwidget.UsageParserTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }

Write-Host "Built classes: $ClassesDir"
