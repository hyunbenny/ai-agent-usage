function Get-Java17Toolchain {
    [CmdletBinding()]
    param(
        [switch]$RequirePackaging
    )

    $candidateBins = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $candidateBins.Add((Join-Path $env:JAVA_HOME "bin"))
    }

    foreach ($commandName in @("javac", "jpackage")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            $candidateBins.Add((Split-Path -Parent $command.Source))
        }
    }

    foreach ($root in @(
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:ProgramFiles "Microsoft"),
        (Join-Path $env:ProgramFiles "Eclipse Adoptium")
    )) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidateBins.Add((Join-Path $_.FullName "bin")) }
    }

    $seen = @{}
    foreach ($bin in $candidateBins) {
        if (-not $bin) { continue }
        $fullBin = [System.IO.Path]::GetFullPath($bin)
        if ($seen.ContainsKey($fullBin)) { continue }
        $seen[$fullBin] = $true

        $java = Join-Path $fullBin "java.exe"
        $javaw = Join-Path $fullBin "javaw.exe"
        $javac = Join-Path $fullBin "javac.exe"
        if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) { continue }

        $versionOutput = (& $javac -version 2>&1 | Out-String)
        $versionExitCode = $LASTEXITCODE
        if ($versionExitCode -ne 0) { continue }
        if ($versionOutput -notmatch 'javac\s+(\d+)') { continue }
        $major = [int]$Matches[1]
        if ($major -lt 17) { continue }

        $jar = Join-Path $fullBin "jar.exe"
        $jpackage = Join-Path $fullBin "jpackage.exe"
        if ($RequirePackaging -and
            (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $jpackage))) {
            continue
        }

        return [pscustomobject]@{
            Home = Split-Path -Parent $fullBin
            Bin = $fullBin
            Java = $java
            Javaw = if (Test-Path -LiteralPath $javaw) { $javaw } else { $java }
            Javac = $javac
            Jar = $jar
            Jpackage = $jpackage
            Major = $major
        }
    }

    throw "JDK 17 이상을 찾지 못했습니다. 빌드하려면 JDK 17 이상이 필요합니다. 완성된 EXE 실행에는 Java가 필요하지 않습니다."
}
