param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\quality\local-agent-flow\run-live-server-release-created-flow-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Live server release-created flow smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Live server release-created flow smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath
$applicationConfigPath = Join-Path $root "backend\src\main\resources\application.yml"
$composeOverridePath = Join-Path $root "docker-compose.local-agent-release.yml"
$applicationConfig = Get-Content -Raw $applicationConfigPath
$composeOverride = Get-Content -Raw $composeOverridePath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern,
        [string]$Text = $source
    )

    if ($Text -notmatch $Pattern) {
        throw "Live server release-created flow contract missing: $Name"
    }
}

Assert-Contains "release-created schema" 'learnbot\.quality\.local-agent-live-server-release-created-flow\.v1'
Assert-Contains "temporary workspace" 'local-agent-live-release-created-workspace-'
Assert-Contains "auth me user id" '/api/auth/me'
Assert-Contains "pairing token" '/api/local-agents/pairing-token'
Assert-Contains "agent config isolation" 'LEARNBOT_AGENT_CONFIG'
Assert-Contains "bomless workspace file" 'UTF8Encoding\]::new\(\$false\)'
Assert-Contains "temporary loop timeline" 'code_agent_loop_timelines'
Assert-Contains "temporary quality space" 'INSERT INTO spaces'
Assert-Contains "temporary space membership" 'INSERT INTO space_members'
Assert-Contains "temporary code repository" 'INSERT INTO code_repositories'
Assert-Contains "repository id source input" '\$sourceInput\["repositoryId"\]'
Assert-Contains "loop id source input" '\$sourceInput\["loopId"\]'
Assert-Contains "held source row" 'held source patch for backend-created approved execution sequence'
Assert-Contains "release attempt row" 'local_agent_patch_release_attempts'
Assert-Contains "git status evidence row" 'fresh repository verification before backend release'
Assert-Contains "source repository input" 'sourceRepository'
Assert-Contains "repository verification match" 'repositoryVerification\.status -ne "MATCH"'
Assert-Contains "dry run evidence row" 'fresh patch dry-run evidence before backend release'
Assert-Contains "dry run safety expectation" 'REJECTED/UNSAFE_TOOL'
Assert-Contains "release endpoint" '/api/local-agents/tools/\$sourceRequestId/release-for-execution'
Assert-Contains "release flag documentation" 'LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED=true'
Assert-Contains "sequence flag documentation" 'LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED=true'
Assert-Contains "backend mutation input assertion" 'mutationAllowed -ne \$true'
Assert-Contains "approved inspection by release attempt" 'approved-execution-flow/inspection/by-release-attempt'
Assert-Contains "durable row source assertion" 'durableCompletedRows'
Assert-Contains "all released steps must succeed" 'Backend-created approved flow expected every released step to succeed'
Assert-Contains "completion event assertion" 'LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED'
Assert-Contains "completion handoff status assertion" 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED'
Assert-Contains "completion final result disabled assertion" 'finalResultEnabled'
Assert-Contains "completion freshness disabled assertion" 'ragFreshnessUpdateEnabled'
Assert-Contains "runner preview endpoint" '/api/code-agent/loop/runner/preview'
Assert-Contains "runner final result disabled assertion" 'READY_FINAL_RESULT_DISABLED'
Assert-Contains "runner completed handoff schema assertion" 'learnbot\.code-agent\.approved-execution-flow-completed-handoff\.v1'
Assert-Contains "runner final report summary status assertion" 'READY_SUMMARY_AUDIT_ONLY'
Assert-Contains "runner rag freshness marker status assertion" 'STALE_INDEX_WARNING_REQUIRED'
Assert-Contains "runner publication handoff status assertion" 'READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED'
Assert-Contains "runner acknowledgement handoff status assertion" 'READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED'
Assert-Contains "runner final result handoff disabled assertion" 'finalResultHandoff\.publicationEnabled'
Assert-Contains "temporary repository cleanup" 'DELETE FROM code_repositories'
Assert-Contains "temporary space cleanup" 'DELETE FROM spaces'
Assert-Contains "expected patch step" 'patch\.apply'
Assert-Contains "expected command step" 'command\.runAllowed'
Assert-Contains "expected git status step" 'git\.status'
Assert-Contains "expected rollback step" 'rollback\.restore'
Assert-Contains "cleanup opt out" '\[switch\]\$KeepRows'
Assert-Contains "safe temp cleanup guard" 'Refusing to remove workspace outside quality temp directory'
Assert-Contains "final publication disabled limitation" 'Final-answer publication and acknowledgement save remain disabled\.'
Assert-Contains "application yaml sequence flag binding" 'approved-execution-sequence-creation-enabled:\s*\$\{LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED:false\}' $applicationConfig
Assert-Contains "compose override release flag" 'LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED:\s*"true"' $composeOverride
Assert-Contains "compose override sequence flag" 'LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED:\s*"true"' $composeOverride

if ($source -match 'seed-approved-tool-executions\.mjs') {
    throw "Live server release-created flow must not seed approved follow-up rows directly"
}

$held = $source.IndexOf('held source patch for backend-created approved execution sequence')
$git = $source.IndexOf('fresh repository verification before backend release')
$dryRun = $source.IndexOf('fresh patch dry-run evidence before backend release')
$release = $source.IndexOf('/api/local-agents/tools/$sourceRequestId/release-for-execution')
$inspection = $source.IndexOf('approved-execution-flow/inspection/by-release-attempt')
$completion = $source.IndexOf('LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED')
if ($held -lt 0 -or $git -lt 0 -or $dryRun -lt 0 -or $release -lt 0 -or $inspection -lt 0) {
    throw "Live server release-created flow must create source/evidence rows, release through backend, and inspect completed durable rows"
}
if ($held -gt $git -or $git -gt $dryRun -or $dryRun -gt $release -or $release -gt $inspection -or $inspection -gt $completion) {
    throw "Live server release-created flow must gather fresh evidence before backend release, inspect durable rows after polling, then verify completion handoff"
}

Write-Output "live-server-release-created-flow-contract-ok"
