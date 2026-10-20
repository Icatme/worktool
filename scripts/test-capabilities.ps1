param([string]$Gradle = ".\gradlew.bat")
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$gradleExecutable = (Resolve-Path -LiteralPath $Gradle).Path
$previousContract = $env:WORKTOOL_CONTRACT_DIR
$previousRequired = $env:WORKTOOL_REQUIRE_KOTLIN
Push-Location $repo
try {
    $env:WORKTOOL_CONTRACT_DIR = Join-Path $repo "build\contract"
    $env:WORKTOOL_REQUIRE_KOTLIN = "0"
    Push-Location "sdk\go"
    try {
        & go test -count=1 -run '^TestExportGoContract$' ./...
        if ($LASTEXITCODE -ne 0) { throw "Go contract export failed" }
    } finally { Pop-Location }
    & $gradleExecutable --no-daemon --console=plain -PunsignedValidation=true :capability-core:test --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Kotlin contract tests failed" }
    $env:WORKTOOL_REQUIRE_KOTLIN = "1"
    Push-Location "sdk\go"
    try {
        & go test -count=1 ./...
        if ($LASTEXITCODE -ne 0) { throw "Go contract tests failed" }
        & go test -race -count=1 ./...
        if ($LASTEXITCODE -ne 0) { throw "Go race tests failed (requires a supported C compiler)" }
        & go vet ./...
        if ($LASTEXITCODE -ne 0) { throw "Go vet failed" }
    } finally { Pop-Location }
} finally {
    $env:WORKTOOL_CONTRACT_DIR = $previousContract
    $env:WORKTOOL_REQUIRE_KOTLIN = $previousRequired
    Pop-Location
}
