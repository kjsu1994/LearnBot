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
$workspacePath = Join-Path $qualityDir ("local-agent-live-durable-workspace-" + [Guid]::NewGuid().ToString("N"))
$configPath = Join-Path $flowDir ("local-agent-live-durable-" + [Guid]::NewGuid().ToString("N") + ".json")
$seedReportPath = Join-Path $flowDir "live-server-approved-durable-flow-seed-report.json"
$diffPath = Join-Path $flowDir "live-server-approved-durable-flow.patch"

if ([string]::IsNullOrWhiteSpace($LoginId)) {
    $LoginId = "jinsu.kim"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = "admin1234"
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    New-Item -ItemType Directory -Force -Path $flowDir | Out-Null
    $ReportPath = Join-Path $flowDir "live-server-approved-durable-flow-smoke-report.json"
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
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
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

function Convert-ToSqlJsonLiteral {
    param([object]$Value)
    $json = $Value | ConvertTo-Json -Depth 30 -Compress
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
$dryRunRequestId = [Guid]::NewGuid()
$sourceRequestId = $dryRunRequestId
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

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-Json -Session $session -Method POST -Uri "$Server/api/auth/login" -Body @{
        loginId = $LoginId
        password = $Password
        rememberLogin = $false
    } | Out-Null
    $me = Invoke-Json -Session $session -Method GET -Uri "$Server/api/auth/me"
    $userId = [Guid]$me.user.id
    $pairing = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/pairing-token" -Body @{
        label = "local-agent-live-durable-flow-smoke"
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
    $dryRunInput = [ordered]@{
        workspaceId = $workspaceId
        sourceRequestId = $sourceRequestId
        releaseAttemptId = $releaseAttemptId
        dryRunOnly = $true
        mutationAllowed = $false
        diff = $diff
        targetFiles = @("src/App.cs")
        expectedFiles = @(@{
            path = "src/App.cs"
            sha256 = $originalHash
        })
    }
    $dryRunInputSql = Convert-ToSqlJsonLiteral -Value $dryRunInput
    Invoke-Psql -Sql @"
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
    '["quality smoke: create managed snapshot through live durable queue"]'::jsonb,
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
    '{"schema":"learnbot.quality.local-agent-live-server-approved-durable-flow.release-attempt.v1","source":"quality-smoke"}'::jsonb,
    '["quality smoke: release attempt exists only to satisfy live durable result FK"]'::jsonb,
    now(),
    now()
);
"@
    $cleanupSql += @"
DELETE FROM local_agent_mutation_observation_intake
WHERE user_id = '$userId'::uuid
  AND (request_id = '$dryRunRequestId'::uuid OR source_request_id = '$sourceRequestId'::uuid OR release_attempt_id = '$releaseAttemptId'::uuid);
DELETE FROM local_agent_patch_release_attempts WHERE user_id = '$userId'::uuid AND id = '$releaseAttemptId'::uuid;
DELETE FROM local_agent_tool_executions WHERE user_id = '$userId'::uuid AND id = '$dryRunRequestId'::uuid;
"@

    dotnet run --project local-agent -- agent start --once --transport polling | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Local Agent dry-run polling exited with $LASTEXITCODE; continuing to inspect terminal tool status."
    }
    $dryRunResult = Wait-ToolTerminal -Session $session -RequestId $dryRunRequestId
    if ($dryRunResult.status -ne "REJECTED" -or $dryRunResult.failureCode -ne "UNSAFE_TOOL") {
        throw "Expected dry-run snapshot request to finish as REJECTED/UNSAFE_TOOL, got status=$($dryRunResult.status) failure=$($dryRunResult.failureCode)"
    }
    if ($dryRunResult.output.preflightPassed -ne $true -or $dryRunResult.output.snapshotCreated -ne $true) {
        throw "Dry-run snapshot request did not pass preflight and create a managed snapshot"
    }
    $manifestId = [string]$dryRunResult.output.snapshotObservation.manifestPreview.id
    if ([string]::IsNullOrWhiteSpace($manifestId)) {
        throw "Dry-run result did not include a snapshot manifest id"
    }

    node scripts\quality\local-agent-flow\seed-approved-tool-executions.mjs `
        --execute `
        --output $seedReportPath `
        --container $Container `
        --database $Database `
        --username $Username `
        --user-id $userId `
        --agent-id $agentId `
        --workspace-id $workspaceId `
        --manifest-id $manifestId `
        --source-request-id $sourceRequestId `
        --release-attempt-id $releaseAttemptId `
        --session-id $sessionId `
        --target-file src/App.cs `
        --diff-file $diffPath
    if ($LASTEXITCODE -ne 0) {
        throw "approved durable flow seed failed"
    }
    $seedReport = Get-Content -Raw $seedReportPath | ConvertFrom-Json
    $cleanupSql += [string]$seedReport.cleanupSql

    for ($i = 0; $i -lt 4; $i++) {
        dotnet run --project local-agent -- agent start --once --transport polling | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Local Agent approved row polling failed at iteration $($i + 1)"
        }
    }

    $inspection = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/tools/approved-execution-flow/inspection/by-release-attempt" -Body @{
        releaseAttemptId = $releaseAttemptId
    }
    if ($inspection.requestIdSource -ne "durableCompletedRows") {
        throw "Approved flow inspection did not use durable completed rows"
    }
    if (@($inspection.requestIds).Count -ne 4) {
        throw "Approved flow inspection did not return four request ids"
    }
    foreach ($name in @("patch.apply", "command.runAllowed", "git.status", "rollback.restore")) {
        if (-not (($inspection.steps | ForEach-Object { $_.toolName }) -contains $name)) {
            throw "Approved flow inspection is missing step: $name"
        }
    }
    $failedSteps = @($inspection.steps | Where-Object { $_.status -ne "SUCCEEDED" })
    if ($failedSteps.Count -gt 0) {
        $details = ($failedSteps | ForEach-Object { "$($_.toolName):$($_.status):$($_.verificationStatus)" }) -join ", "
        throw "Approved durable flow expected every seeded step to succeed: $details"
    }

    $summary = [pscustomobject]@{
        schema = "learnbot.quality.local-agent-live-server-approved-durable-flow.v1"
        status = "passed"
        server = $Server
        userId = $userId
        agentId = $agentId
        workspaceId = $workspaceId
        sourceRequestId = $sourceRequestId
        releaseAttemptId = $releaseAttemptId
        dryRunRequestId = $dryRunRequestId
        manifestId = $manifestId
        durableInspection = $inspection
        seedReportPath = (Resolve-Path $seedReportPath).Path
        workspacePath = (Resolve-Path $workspacePath).Path
        rowsKept = [bool]$KeepRows
        limitations = @(
            "The smoke seeds already-approved durable rows directly for the live local stack.",
            "It proves Spring durable claim/complete plus Local Agent polling for approved patch, command, git status, and rollback rows.",
            "Production request creation, final-answer publication, and acknowledgement save remain disabled."
        )
    }
    $summary | ConvertTo-Json -Depth 20 | Set-Content -Path $ReportPath -Encoding UTF8
    $summary | ConvertTo-Json -Depth 20
} finally {
    if (-not $KeepRows -and $cleanupSql.Count -gt 0) {
        try {
            Invoke-Psql -Sql ($cleanupSql -join "`n")
        } catch {
            Write-Warning "Durable flow cleanup failed: $($_.Exception.Message)"
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
