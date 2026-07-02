param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\quality\local-agent-flow\run-live-server-approved-durable-flow-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Live server approved durable flow smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Live server approved durable flow smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )

    if ($source -notmatch $Pattern) {
        throw "Live server approved durable flow contract missing: $Name"
    }
}

Assert-Contains "durable schema" 'learnbot\.quality\.local-agent-live-server-approved-durable-flow\.v1'
Assert-Contains "temporary workspace" 'local-agent-live-durable-workspace-'
Assert-Contains "auth me user id" '/api/auth/me'
Assert-Contains "pairing token" '/api/local-agents/pairing-token'
Assert-Contains "agent config isolation" 'LEARNBOT_AGENT_CONFIG'
Assert-Contains "bomless workspace file" 'UTF8Encoding\]::new\(\$false\)'
Assert-Contains "dry run durable insert" 'quality smoke: create managed snapshot through live durable queue'
Assert-Contains "release attempt durable insert" 'local_agent_patch_release_attempts'
Assert-Contains "release attempt fk reason" 'release attempt exists only to satisfy live durable result FK'
Assert-Contains "dry run safety expectation" 'REJECTED/UNSAFE_TOOL'
Assert-Contains "approved seed script" 'seed-approved-tool-executions\.mjs'
Assert-Contains "seed execute flag" '--execute'
Assert-Contains "seed diff file" '--diff-file'
Assert-Contains "approved inspection by release attempt" 'approved-execution-flow/inspection/by-release-attempt'
Assert-Contains "durable row source assertion" 'durableCompletedRows'
Assert-Contains "all seeded steps must succeed" 'Approved durable flow expected every seeded step to succeed'
Assert-Contains "expected patch step" 'patch\.apply'
Assert-Contains "expected command step" 'command\.runAllowed'
Assert-Contains "expected git status step" 'git\.status'
Assert-Contains "expected rollback step" 'rollback\.restore'
Assert-Contains "cleanup opt out" '\[switch\]\$KeepRows'
Assert-Contains "safe temp cleanup guard" 'Refusing to remove workspace outside quality temp directory'
Assert-Contains "final publication disabled limitation" 'Production request creation, final-answer publication, and acknowledgement save remain disabled\.'

$dryRunInsert = $source.IndexOf('quality smoke: create managed snapshot through live durable queue')
$seed = $source.IndexOf('seed-approved-tool-executions.mjs')
$inspection = $source.IndexOf('approved-execution-flow/inspection/by-release-attempt')
if ($dryRunInsert -lt 0 -or $seed -lt 0 -or $inspection -lt 0) {
    throw "Live server approved durable flow must create dry-run snapshot, seed approved rows, and inspect completed durable rows"
}
if ($dryRunInsert -gt $seed -or $seed -gt $inspection) {
    throw "Live server approved durable flow must run dry-run snapshot before seed, and seed before inspection"
}

Write-Output "live-server-approved-durable-flow-contract-ok"
