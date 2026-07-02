param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\quality\local-agent-flow\run-live-server-release-ui-flow-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Live server release UI flow smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Live server release UI flow smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )

    if ($source -notmatch $Pattern) {
        throw "Live server release UI flow contract missing: $Name"
    }
}

Assert-Contains "schema" 'learnbot\.quality\.local-agent-live-server-release-ui-flow\.v1'
Assert-Contains "route flow smoke" 'codeWorkspaceReleaseForExecutionRouteFlow\.test\.mjs'
Assert-Contains "live release-created smoke" 'run-live-server-release-created-flow-smoke\.ps1'
Assert-Contains "release compose override" 'docker-compose\.local-agent-release\.yml'
Assert-Contains "flagged stack switch" '\[switch\]\$StartFlaggedStack'
Assert-Contains "default restore switch" '\[switch\]\$RestoreDefaultStack'
Assert-Contains "build switch" '\[switch\]\$Build'
Assert-Contains "release endpoint proof" '/api/local-agents/tools/\{requestId\}/release-for-execution'
Assert-Contains "release flags" 'LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED=true'
Assert-Contains "sequence flag" 'LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED=true'
Assert-Contains "route limitation" 'not a true interactive browser click'
Assert-Contains "final publication disabled limitation" 'Final-answer publication and acknowledgement save remain disabled\.'
Assert-Contains "report path" 'live-server-release-ui-flow-smoke-report\.json'
Assert-Contains "release created report path" 'live-server-release-ui-flow-release-created-report\.json'
Assert-Contains "completion handoff report" 'completionHandoffStatus'
Assert-Contains "runner final disabled state" 'READY_FINAL_RESULT_DISABLED'
Assert-Contains "repository id report" 'repositoryId'
Assert-Contains "loop id report" 'loopId'
Assert-Contains "runner preview report" 'runnerPreview'

$routeIndex = $source.IndexOf('codeWorkspaceReleaseForExecutionRouteFlow.test.mjs')
$liveIndex = $source.IndexOf('run-live-server-release-created-flow-smoke.ps1')
$reportIndex = $source.IndexOf('learnbot.quality.local-agent-live-server-release-ui-flow.v1')
if ($routeIndex -lt 0 -or $liveIndex -lt 0 -or $reportIndex -lt 0) {
    throw "Live server release UI flow must bind route UI, live smoke, and final report"
}
if ($routeIndex -gt $reportIndex -or $liveIndex -gt $reportIndex) {
    throw "Live server release UI flow must run route/live checks before writing the final report"
}

Write-Output "live-server-release-ui-flow-contract-ok"
