param(
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_SMOKE_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_SMOKE_PASSWORD,
    [string]$Container = "learnbot-postgres",
    [string]$Database = "learnbot",
    [string]$Username = "learnbot",
    [switch]$KeepRows,
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$qualityDir = Join-Path $root ".tmp\quality"
$flowDir = Join-Path $qualityDir "local-agent-approved-flow"
$workspacePath = Join-Path $qualityDir ("local-agent-live-release-created-workspace-" + [Guid]::NewGuid().ToString("N"))
$configPath = Join-Path $flowDir ("local-agent-live-release-created-" + [Guid]::NewGuid().ToString("N") + ".json")
$diffPath = Join-Path $flowDir "live-server-release-created-flow.patch"

if ([string]::IsNullOrWhiteSpace($LoginId)) {
    $LoginId = "jinsu.kim"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = "admin1234"
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    New-Item -ItemType Directory -Force -Path $flowDir | Out-Null
    $ReportPath = Join-Path $flowDir "live-server-release-created-flow-smoke-report.json"
}

function Invoke-Json {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        WebSession = $Session
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 30)
    }
    Invoke-RestMethod @params
}

function Invoke-Psql {
    param([string]$Sql)

    $Sql | docker exec -i $Container psql -U $Username -d $Database -v ON_ERROR_STOP=1 | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "psql execution failed with exit code $LASTEXITCODE"
    }
}

function Invoke-PsqlText {
    param([string]$Sql)

    $output = $Sql | docker exec -i $Container psql -U $Username -d $Database -v ON_ERROR_STOP=1 -t -A
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar execution failed with exit code $LASTEXITCODE"
    }
    ($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1).Trim()
}

function Convert-ToSqlJsonLiteral {
    param([object]$Value)
    $json = $Value | ConvertTo-Json -Depth 40 -Compress
    "'" + $json.Replace("'", "''") + "'::jsonb"
}

function Wait-ToolTerminal {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$RequestId
    )

    $terminal = @("SUCCEEDED", "FAILED", "REJECTED", "TIMED_OUT", "CANCELLED", "DISCONNECTED")
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $result = Invoke-Json -Session $Session -Method GET -Uri "$Server/api/local-agents/tools/$RequestId"
        if ($terminal -contains $result.status) {
            return $result
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Tool request did not reach a terminal status: $RequestId"
}

function Remove-TemporaryWorkspace {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }
    $resolvedQualityDir = (Resolve-Path $qualityDir).Path
    $resolvedWorkspace = (Resolve-Path $Path).Path
    if (-not $resolvedWorkspace.StartsWith($resolvedQualityDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove workspace outside quality temp directory: $resolvedWorkspace"
    }
    Remove-Item -LiteralPath $resolvedWorkspace -Recurse -Force
}

$previousConfig = $env:LEARNBOT_AGENT_CONFIG
$releaseAttemptId = [Guid]::NewGuid()
$sessionId = [Guid]::NewGuid()
$spaceId = [Guid]::NewGuid()
$repositoryId = [Guid]::NewGuid()
$loopId = [Guid]::NewGuid()
$sourceRequestId = [Guid]::NewGuid()
$approvalRequestId = [Guid]::NewGuid()
$gitStatusRequestId = [Guid]::NewGuid()
$dryRunRequestId = [Guid]::NewGuid()
$cleanupSql = @()

Push-Location $root
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $workspacePath "src") | Out-Null
    $targetFile = Join-Path $workspacePath "src\App.cs"
    $original = "class App {`n    string Name = ""old"";`n    string Stable = ""same"";`n}`n"
    [System.IO.File]::WriteAllText($targetFile, $original, [System.Text.UTF8Encoding]::new($false))
    git -C $workspacePath init | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "git init failed for temporary workspace"
    }
    git -C $workspacePath config user.email learnbot-quality@example.invalid | Out-Host
    git -C $workspacePath config user.name "LearnBot Quality" | Out-Host
    git -C $workspacePath add src/App.cs | Out-Host
    git -C $workspacePath commit -m "quality baseline" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "git baseline commit failed for temporary workspace"
    }
    git -C $workspacePath remote add origin https://example.invalid/learnbot-quality.git | Out-Host

    $gitRoot = (git -C $workspacePath rev-parse --show-toplevel).Trim()
    $headCommit = (git -C $workspacePath rev-parse HEAD).Trim()
    $branch = (git -C $workspacePath branch --show-current).Trim()
    $remoteUrl = (git -C $workspacePath config --get remote.origin.url).Trim()

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-Json -Session $session -Method POST -Uri "$Server/api/auth/login" -Body @{
        loginId = $LoginId
        password = $Password
        rememberLogin = $false
    } | Out-Null
    $me = Invoke-Json -Session $session -Method GET -Uri "$Server/api/auth/me"
    $userId = [Guid]$me.user.id
    $pairing = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/pairing-token" -Body @{
        label = "local-agent-live-release-created-flow-smoke"
    }
    $agentId = [Guid]$pairing.agentId

    $env:LEARNBOT_AGENT_CONFIG = $configPath
    dotnet run --project local-agent -- pair --server $Server --agent-id $agentId --token $pairing.token --transport polling | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Local Agent pair failed"
    }
    dotnet run --project local-agent -- workspace add $workspacePath | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Local Agent workspace add failed"
    }
    $config = Get-Content -Raw $configPath | ConvertFrom-Json
    $workspaceId = [Guid]$config.workspaces[0].workspaceId

    $originalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetFile).Hash.ToLowerInvariant()
    $diff = @"
--- a/src/App.cs
+++ b/src/App.cs
@@ -1,4 +1,5 @@
 class App {
-    string Name = "old";
+    string Name = "new";
+    string Mode = "safe";
     string Stable = "same";
 }
"@
    [System.IO.File]::WriteAllText($diffPath, $diff, [System.Text.UTF8Encoding]::new($false))

    $sourceRepository = [ordered]@{
        id = $repositoryId
        name = "quality-live-release-created-flow"
        branch = $branch
        lastIndexedCommit = $headCommit
        gitUrl = $remoteUrl
        gitRoot = $gitRoot.Replace("\", "/")
    }
    $commonPatchInput = [ordered]@{
        schemaVersion = 1
        workspaceId = $workspaceId
        diff = $diff
        targetFiles = @("src/App.cs")
        expectedFiles = @(@{
            path = "src/App.cs"
            sha256 = $originalHash
        })
        requiresSnapshot = $true
        staleIndexPolicy = "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH"
        sourceRepository = $sourceRepository
        snapshotPolicy = @{
            required = $true
            scope = "TARGET_FILES"
            location = "LOCAL_AGENT_MANAGED"
            createBeforeMutation = $true
            includeExpectedHashes = $true
        }
        rollbackPolicy = @{
            required = $true
            tool = "rollback.restore"
            restoreScope = "SNAPSHOT_TARGET_FILES"
            requiresUserApproval = $true
        }
        commandId = "dotnet.version"
        timeoutSeconds = 30
        maxOutputBytes = 4096
    }
    $sourceInput = [ordered]@{}
    foreach ($key in $commonPatchInput.Keys) {
        $sourceInput[$key] = $commonPatchInput[$key]
    }
    $sourceInput["repositoryId"] = $repositoryId
    $sourceInput["loopId"] = $loopId
    $sourceInput["approvalRequestId"] = $approvalRequestId.ToString()
    $sourceInput["approvalPersistenceRequired"] = $true
    $sourceInput["approvalPersisted"] = $true
    $gitStatusInput = [ordered]@{
        workspaceId = $workspaceId
        sourceRequestId = $sourceRequestId
        releaseAttemptId = $releaseAttemptId
        freshObservationOnly = $true
        sourceRepository = $sourceRepository
    }
    $dryRunInput = [ordered]@{}
    foreach ($key in $commonPatchInput.Keys) {
        $dryRunInput[$key] = $commonPatchInput[$key]
    }
    $dryRunInput["sourceRequestId"] = $sourceRequestId
    $dryRunInput["releaseAttemptId"] = $releaseAttemptId
    $dryRunInput["dryRunOnly"] = $true
    $dryRunInput["mutationAllowed"] = $false
    $dryRunInput["freshObservationOnly"] = $true

    $sourceInputSql = Convert-ToSqlJsonLiteral -Value $sourceInput
    $gitStatusInputSql = Convert-ToSqlJsonLiteral -Value $gitStatusInput
    $dryRunInputSql = Convert-ToSqlJsonLiteral -Value $dryRunInput
    Invoke-Psql -Sql @"
INSERT INTO spaces (id, name, description, created_by)
VALUES (
    '$spaceId'::uuid,
    'quality live release-created flow',
    'Temporary space for Local Agent release-created flow API smoke.',
    '$userId'::uuid
);

INSERT INTO space_members (space_id, user_id, role)
VALUES (
    '$spaceId'::uuid,
    '$userId'::uuid,
    'OWNER'
);

INSERT INTO code_repositories (
    id, name, source_type, source_label, git_url, branch, auth_type, local_path,
    status, last_indexed_commit, space_id, created_by
)
VALUES (
    '$repositoryId'::uuid,
    'quality-live-release-created-flow',
    'GIT',
    '$remoteUrl',
    '$remoteUrl',
    '$branch',
    'NONE',
    '$gitRoot',
    'INDEXED',
    '$headCommit',
    '$spaceId'::uuid,
    '$userId'::uuid
);

INSERT INTO code_agent_loop_timelines (
    id, user_id, repository_id, space_id, instruction, status, max_steps, timeout_seconds,
    cancellation_enabled, timeline_persistence_enabled, mutation_enabled,
    steps, stop_conditions, warnings, created_at
)
VALUES (
    '$loopId'::uuid,
    '$userId'::uuid,
    '$repositoryId'::uuid,
    '$spaceId'::uuid,
    'quality smoke: release-created flow completion handoff',
    'PREVIEW_ONLY',
    6,
    120,
    false,
    true,
    false,
    '[]'::jsonb,
    '[]'::jsonb,
    '["quality smoke: temporary timeline for approved execution completion handoff"]'::jsonb,
    now()
);

INSERT INTO local_agent_tool_executions (
    id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
    approval_state, status, input, request_warnings, created_at
)
VALUES (
    '$sourceRequestId'::uuid,
    '$sessionId'::uuid,
    '$userId'::uuid,
    '$agentId'::uuid,
    '$workspaceId'::uuid,
    'USER_LOCAL_AGENT',
    'patch.apply',
    'APPROVED',
    'APPROVED_HELD',
    $sourceInputSql,
    '["quality smoke: held source patch for backend-created approved execution sequence"]'::jsonb,
    now()
);

INSERT INTO local_agent_patch_release_attempts (
    id, source_request_id, session_id, user_id, agent_id, workspace_id,
    status, claimable, stale_window_seconds, evidence, failure_reasons,
    created_at, updated_at
)
VALUES (
    '$releaseAttemptId'::uuid,
    '$sourceRequestId'::uuid,
    '$sessionId'::uuid,
    '$userId'::uuid,
    '$agentId'::uuid,
    '$workspaceId'::uuid,
    'CREATED_DISABLED',
    false,
    120,
    '{"schema":"learnbot.quality.local-agent-live-server-release-created-flow.release-attempt.v1","source":"quality-smoke"}'::jsonb,
    '["quality smoke: release attempt waits for live git.status and patch dry-run evidence"]'::jsonb,
    now(),
    now()
);

INSERT INTO local_agent_tool_executions (
    id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
    approval_state, status, input, request_warnings, created_at
)
VALUES (
    '$gitStatusRequestId'::uuid,
    '$sessionId'::uuid,
    '$userId'::uuid,
    '$agentId'::uuid,
    '$workspaceId'::uuid,
    'USER_LOCAL_AGENT',
    'git.status',
    'NOT_REQUIRED',
    'PENDING',
    $gitStatusInputSql,
    '["quality smoke: fresh repository verification before backend release"]'::jsonb,
    now() + interval '1 millisecond'
);

INSERT INTO local_agent_tool_executions (
    id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
    approval_state, status, input, request_warnings, created_at
)
VALUES (
    '$dryRunRequestId'::uuid,
    '$sessionId'::uuid,
    '$userId'::uuid,
    '$agentId'::uuid,
    '$workspaceId'::uuid,
    'USER_LOCAL_AGENT',
    'patch.apply',
    'APPROVED',
    'APPROVED',
    $dryRunInputSql,
    '["quality smoke: fresh patch dry-run evidence before backend release"]'::jsonb,
    now() + interval '2 milliseconds'
);
"@
    $cleanupSql += @"
DELETE FROM local_agent_mutation_observation_intake
WHERE user_id = '$userId'::uuid
  AND (request_id IN ('$sourceRequestId'::uuid, '$gitStatusRequestId'::uuid, '$dryRunRequestId'::uuid)
       OR source_request_id = '$sourceRequestId'::uuid
       OR release_attempt_id = '$releaseAttemptId'::uuid);
DELETE FROM local_agent_patch_release_attempts WHERE user_id = '$userId'::uuid AND id = '$releaseAttemptId'::uuid;
DELETE FROM local_agent_tool_executions
WHERE user_id = '$userId'::uuid
  AND (id IN ('$sourceRequestId'::uuid, '$gitStatusRequestId'::uuid, '$dryRunRequestId'::uuid)
       OR input->>'releaseAttemptId' = '$releaseAttemptId');
DELETE FROM code_agent_loop_timelines WHERE user_id = '$userId'::uuid AND id = '$loopId'::uuid;
DELETE FROM code_repositories WHERE id = '$repositoryId'::uuid;
DELETE FROM space_members WHERE space_id = '$spaceId'::uuid AND user_id = '$userId'::uuid;
DELETE FROM spaces WHERE id = '$spaceId'::uuid;
"@

    for ($i = 0; $i -lt 2; $i++) {
        dotnet run --project local-agent -- agent start --once --transport polling | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Local Agent evidence polling exited with $LASTEXITCODE at iteration $($i + 1); continuing to inspect terminal tool status."
        }
    }
    $gitStatusResult = Wait-ToolTerminal -Session $session -RequestId $gitStatusRequestId
    if ($gitStatusResult.status -ne "SUCCEEDED" -or $gitStatusResult.output.repositoryVerification.status -ne "MATCH") {
        throw "Expected git.status evidence to succeed with MATCH repository verification"
    }
    $dryRunResult = Wait-ToolTerminal -Session $session -RequestId $dryRunRequestId
    if ($dryRunResult.status -ne "REJECTED" -or $dryRunResult.failureCode -ne "UNSAFE_TOOL") {
        throw "Expected dry-run snapshot request to finish as REJECTED/UNSAFE_TOOL, got status=$($dryRunResult.status) failure=$($dryRunResult.failureCode)"
    }
    if ($dryRunResult.output.preflightPassed -ne $true -or $dryRunResult.output.snapshotCreated -ne $true) {
        throw "Dry-run snapshot request did not pass preflight and create a managed snapshot"
    }

    $released = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/tools/$sourceRequestId/release-for-execution"
    if ($released.status -ne "APPROVED") {
        throw "Backend release did not make held patch claimable; status=$($released.status)"
    }
    if ($released.input.mutationAllowed -ne $true -or $released.input.dryRunOnly -ne $false) {
        throw "Backend release did not return mutation-enabled patch input"
    }

    for ($i = 0; $i -lt 4; $i++) {
        dotnet run --project local-agent -- agent start --once --transport polling | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Local Agent backend-created approved row polling failed at iteration $($i + 1)"
        }
    }

    $inspection = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/tools/approved-execution-flow/inspection/by-release-attempt" -Body @{
        releaseAttemptId = $releaseAttemptId
    }
    if ($inspection.requestIdSource -ne "durableCompletedRows") {
        throw "Approved flow inspection did not use durable completed rows"
    }
    if (@($inspection.requestIds).Count -ne 4) {
        throw "Backend-created approved flow inspection did not return four request ids"
    }
    foreach ($name in @("patch.apply", "command.runAllowed", "git.status", "rollback.restore")) {
        if (-not (($inspection.steps | ForEach-Object { $_.toolName }) -contains $name)) {
            throw "Backend-created approved flow inspection is missing step: $name"
        }
    }
    $failedSteps = @($inspection.steps | Where-Object { $_.status -ne "SUCCEEDED" })
    if ($failedSteps.Count -gt 0) {
        $details = ($failedSteps | ForEach-Object { "$($_.toolName):$($_.status):$($_.verificationStatus)" }) -join ", "
        throw "Backend-created approved flow expected every released step to succeed: $details"
    }

    $completionEventText = Invoke-PsqlText -Sql @"
SELECT jsonb_build_object(
    'eventType', event_type,
    'sequenceNumber', sequence_number,
    'phase', phase,
    'executionTarget', execution_target,
    'toolName', tool_name,
    'requiresApproval', requires_approval,
    'mayMutate', may_mutate,
    'enabled', enabled,
    'details', details
)::text
FROM code_agent_loop_timeline_events
WHERE user_id = '$userId'::uuid
  AND timeline_id = '$loopId'::uuid
  AND event_type = 'LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED'
ORDER BY sequence_number DESC
LIMIT 1;
"@
    if ([string]::IsNullOrWhiteSpace($completionEventText)) {
        throw "Approved execution flow completion timeline event was not recorded"
    }
    $completionEvent = $completionEventText | ConvertFrom-Json
    if ($completionEvent.details.status -ne "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED") {
        throw "Unexpected completion handoff status: $($completionEvent.details.status)"
    }
    if ($completionEvent.details.requestIdSource -ne "durableCompletedRows") {
        throw "Completion handoff did not use durable completed rows"
    }
    if ([int]$completionEvent.details.stepCount -ne 4 -or $completionEvent.details.allSucceeded -ne $true) {
        throw "Completion handoff did not prove all four approved execution steps succeeded"
    }
    if ($completionEvent.details.finalResultEnabled -ne $false -or
        $completionEvent.details.publicationEnabled -ne $false -or
        $completionEvent.details.acknowledgementEnabled -ne $false -or
        $completionEvent.details.ragFreshnessUpdateEnabled -ne $false -or
        $completionEvent.details.mutationEnabled -ne $false) {
        throw "Completion handoff unexpectedly enabled final result, publication, acknowledgement, RAG freshness, or mutation"
    }

    $runnerPreview = Invoke-Json -Session $session -Method POST -Uri "$Server/api/code-agent/loop/runner/preview" -Body @{
        repositoryId = $repositoryId
        loopId = $loopId
        agentId = $agentId
        workspaceId = $workspaceId
    }
    if ($runnerPreview.runnerDecision -ne "READY_FINAL_RESULT_DISABLED") {
        throw "Runner preview did not expose READY_FINAL_RESULT_DISABLED after completion handoff: $($runnerPreview.runnerDecision)"
    }
    if ($runnerPreview.handoffSummary.schema -ne "learnbot.code-agent.approved-execution-flow-completed-handoff.v1") {
        throw "Runner preview did not expose approved execution flow completed handoff schema"
    }
    if ($runnerPreview.handoffSummary.requestIdSource -ne "durableCompletedRows" -or
        [int]$runnerPreview.handoffSummary.stepCount -ne 4 -or
        $runnerPreview.handoffSummary.allSucceeded -ne $true) {
        throw "Runner preview completed handoff did not preserve durable completed row proof"
    }
    if ($runnerPreview.handoffSummary.finalMutationReportSummaryStatus -ne "READY_SUMMARY_AUDIT_ONLY" -or
        $runnerPreview.handoffSummary.ragFreshnessMarkerStatus -ne "STALE_INDEX_WARNING_REQUIRED" -or
        $runnerPreview.handoffSummary.finalAnswerPublicationHandoffStatus -ne "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED" -or
        $runnerPreview.handoffSummary.acknowledgementSaveHandoffStatus -ne "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED") {
        throw "Runner preview completed handoff did not expose final-result/report publication handoff readiness statuses"
    }
    if ($runnerPreview.handoffSummary.finalResultHandoff.publicationEnabled -ne $false -or
        $runnerPreview.handoffSummary.finalResultHandoff.finalAnswerGenerationEnabled -ne $false -or
        $runnerPreview.handoffSummary.finalResultHandoff.acknowledgementSaveEnabled -ne $false -or
        $runnerPreview.handoffSummary.finalResultHandoff.ragFreshnessUpdateEnabled -ne $false -or
        $runnerPreview.handoffSummary.finalResultHandoff.mutationEnabled -ne $false) {
        throw "Runner preview final-result handoff unexpectedly enabled publication, final answer, acknowledgement, RAG freshness, or mutation"
    }
    if ($runnerPreview.finalResultEnabled -ne $false -or
        $runnerPreview.publicationEnabled -ne $false -or
        $runnerPreview.acknowledgementEnabled -ne $false -or
        $runnerPreview.mutationEnabled -ne $false) {
        throw "Runner preview unexpectedly enabled final result, publication, acknowledgement, or mutation"
    }

    $summary = [pscustomobject]@{
        schema = "learnbot.quality.local-agent-live-server-release-created-flow.v1"
        status = "passed"
        server = $Server
        requiredBackendFlags = @(
            "LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED=true",
            "LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED=true"
        )
        userId = $userId
        agentId = $agentId
        workspaceId = $workspaceId
        spaceId = $spaceId
        repositoryId = $repositoryId
        loopId = $loopId
        sourceRequestId = $sourceRequestId
        releaseAttemptId = $releaseAttemptId
        gitStatusRequestId = $gitStatusRequestId
        dryRunRequestId = $dryRunRequestId
        released = $released
        durableInspection = $inspection
        completionHandoff = $completionEvent
        runnerPreview = $runnerPreview
        workspacePath = (Resolve-Path $workspacePath).Path
        rowsKept = [bool]$KeepRows
        limitations = @(
            "The smoke creates only the held source patch and fresh evidence rows directly.",
            "The approved execution sequence rows must be created by /api/local-agents/tools/{requestId}/release-for-execution.",
            "Final-answer publication and acknowledgement save remain disabled."
        )
    }
    $summary | ConvertTo-Json -Depth 30 | Set-Content -Path $ReportPath -Encoding UTF8
    $summary | ConvertTo-Json -Depth 30
} finally {
    if (-not $KeepRows -and $cleanupSql.Count -gt 0) {
        try {
            Invoke-Psql -Sql ($cleanupSql -join "`n")
        } catch {
            Write-Warning "Release-created flow cleanup failed: $($_.Exception.Message)"
        }
    }
    if ($null -eq $previousConfig) {
        Remove-Item Env:\LEARNBOT_AGENT_CONFIG -ErrorAction SilentlyContinue
    } else {
        $env:LEARNBOT_AGENT_CONFIG = $previousConfig
    }
    if (Test-Path $configPath) {
        Remove-Item -LiteralPath $configPath -Force
    }
    if (Test-Path $diffPath) {
        Remove-Item -LiteralPath $diffPath -Force
    }
    Remove-TemporaryWorkspace -Path $workspacePath
    Pop-Location
}
