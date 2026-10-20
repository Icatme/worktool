param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo 'build/protocol-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = Get-ChildItem -LiteralPath (Join-Path $repo 'app/src/main/java/org/yameida/worktool/protocol') -Filter '*.java' | ForEach-Object FullName
$sources += Join-Path $repo 'tests/jvm/ProtocolSafetyTest.java'
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Protocol compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $output ProtocolSafetyTest $output |
    Tee-Object -FilePath (Join-Path $output 'results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed' }
