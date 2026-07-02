param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\quality\local-agent-flow\run-approved-server-queue-flow-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Approved server queue flow smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Approved server queue flow smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )

    if ($source -notmatch $Pattern) {
        throw "Approved server queue flow smoke contract missing: $Name"
    }
}

Assert-Contains "approved server queue self-test" 'self-test approved-server-queue-flow-contract --report \$ReportPath'
Assert-Contains "report assertion" 'assert-approved-execution-flow-report\.mjs --report \$ReportPath'
Assert-Contains "default report path" 'approved-server-queue-flow-smoke-report\.json'
Assert-Contains "self-test failure check" 'approved server queue flow self-test failed'
Assert-Contains "report assertion failure check" 'approved server queue flow report assertion failed'
Assert-Contains "json success payload" '"passed"'

$selfTest = $source.IndexOf('self-test approved-server-queue-flow-contract --report $ReportPath')
$assertion = $source.IndexOf('assert-approved-execution-flow-report.mjs --report $ReportPath')
if ($selfTest -lt 0 -or $assertion -lt 0) {
    throw "Approved server queue flow smoke script must run the self-test and report assertion"
}
if ($selfTest -gt $assertion) {
    throw "Approved server queue flow smoke must create the report before asserting it"
}

Write-Output "approved-server-queue-flow-smoke-contract-ok"
