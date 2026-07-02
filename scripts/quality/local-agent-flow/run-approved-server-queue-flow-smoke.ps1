param(
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $reportDir = Join-Path $root ".tmp\quality\local-agent-approved-flow"
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
    $ReportPath = Join-Path $reportDir "approved-server-queue-flow-smoke-report.json"
}

Push-Location $root
try {
    dotnet run --project local-agent -- self-test approved-server-queue-flow-contract --report $ReportPath
    if ($LASTEXITCODE -ne 0) {
        throw "approved server queue flow self-test failed with exit code $LASTEXITCODE"
    }

    node scripts\quality\local-agent-flow\assert-approved-execution-flow-report.mjs --report $ReportPath
    if ($LASTEXITCODE -ne 0) {
        throw "approved server queue flow report assertion failed with exit code $LASTEXITCODE"
    }

    [pscustomobject]@{
        status = "passed"
        reportPath = (Resolve-Path $ReportPath).Path
        contract = "approved-server-queue-flow-contract"
    } | ConvertTo-Json -Depth 5
} finally {
    Pop-Location
}
