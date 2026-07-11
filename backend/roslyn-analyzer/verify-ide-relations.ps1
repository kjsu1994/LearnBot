param(
    [string]$AnalyzerDll = "$PSScriptRoot/bin/Debug/net8.0/LearnBot.RoslynAnalyzer.dll"
)

$ErrorActionPreference = "Stop"
$fixture = Join-Path $PSScriptRoot "fixtures/IdeRelations"

if (-not (Test-Path -LiteralPath $AnalyzerDll -PathType Leaf)) {
    dotnet build (Join-Path $PSScriptRoot "LearnBot.RoslynAnalyzer.csproj")
    if ($LASTEXITCODE -ne 0) {
        throw "Roslyn analyzer build failed."
    }
}

$json = dotnet $AnalyzerDll $fixture SAFE_PROJECT false
if ($LASTEXITCODE -ne 0) {
    throw "Roslyn analyzer execution failed."
}

$graph = $json | ConvertFrom-Json
$required = @("DEFINES", "IMPLEMENTS", "CALLS", "OVERRIDES", "READS_FIELD", "WRITES_FIELD")
$actual = @($graph.edges | ForEach-Object { $_.type } | Sort-Object -Unique)
$missing = @($required | Where-Object { $_ -notin $actual })

if ($missing.Count -gt 0) {
    throw "Missing Roslyn relations: $($missing -join ', '). Actual: $($actual -join ', ')"
}
if (-not $graph.workspaceLoaded) {
    throw "SAFE_PROJECT did not load the project through MSBuildWorkspace."
}

Write-Output "Roslyn IDE relation fixture passed with MSBuildWorkspace: $($required -join ', ')"
