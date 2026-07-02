param(
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_SMOKE_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_SMOKE_PASSWORD,
    [string]$Container = "learnbot-postgres",
    [string]$Database = "learnbot",
    [string]$Username = "learnbot",
    [switch]$StartFlaggedStack,
    [switch]$RestoreDefaultStack,
    [switch]$Build,
    [switch]$KeepRows,
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$qualityDir = Join-Path $root ".tmp\quality\local-agent-approved-flow"
$routeFlowTest = Join-Path $root "frontend\src\components\code\codeWorkspaceReleaseForExecutionRouteFlow.test.mjs"
$releaseCreatedSmoke = Join-Path $root "scripts\quality\local-agent-flow\run-live-server-release-created-flow-smoke.ps1"
$composeRelease = Join-Path $root "docker-compose.local-agent-release.yml"
$composeDefault = Join-Path $root "docker-compose.yml"
$startedAt = Get-Date

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    New-Item -ItemType Directory -Force -Path $qualityDir | Out-Null
    $ReportPath = Join-Path $qualityDir "live-server-release-ui-flow-smoke-report.json"
}

function Invoke-Checked {
    param(
        [string]$Name,
        [scriptblock]$Block
    )

    Write-Host "== $Name =="
    & $Block
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Invoke-ComposeUp {
    param(
        [string[]]$Files
    )

    $args = @("compose")
    foreach ($file in $Files) {
        $args += @("-f", $file)
    }
    $args += @("up", "-d")
    if ($Build) {
        $args += "--build"
    }
    $args += @("backend", "nginx")
    docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up failed with exit code $LASTEXITCODE"
    }
}

function Wait-ServerGateway {
    param([string]$Name)

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    do {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$Server/api/auth/me" -TimeoutSec 5 | Out-Null
            Write-Host "$Name is reachable"
            return
        } catch {
            if ($_.Exception.Response) {
                $status = [int]$_.Exception.Response.StatusCode
                if ($status -ne 502 -and $status -ne 503 -and $status -ne 504) {
                    Write-Host "$Name is reachable with status $status"
                    return
                }
            }
            Start-Sleep -Seconds 2
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "$Name did not become reachable before timeout: $Server"
}

Push-Location $root
try {
    if (-not (Test-Path $routeFlowTest)) {
        throw "Release route-flow smoke is missing: $routeFlowTest"
    }
    if (-not (Test-Path $releaseCreatedSmoke)) {
        throw "Live release-created smoke is missing: $releaseCreatedSmoke"
    }
    if (-not (Test-Path $composeRelease)) {
        throw "Release compose override is missing: $composeRelease"
    }

    if ($StartFlaggedStack) {
        Invoke-Checked -Name "start flagged local-agent release stack" -Block {
            Invoke-ComposeUp -Files @($composeDefault, $composeRelease)
        }
        Wait-ServerGateway -Name "flagged local-agent release stack"
    }

    Invoke-Checked -Name "Code workspace release route-flow UI smoke" -Block {
        Push-Location (Join-Path $root "frontend")
        try {
            node "src\components\code\codeWorkspaceReleaseForExecutionRouteFlow.test.mjs"
        } finally {
            Pop-Location
        }
    }

    $releaseReportPath = Join-Path $qualityDir "live-server-release-ui-flow-release-created-report.json"
    $releaseArgs = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $releaseCreatedSmoke,
        "-Server", $Server,
        "-Container", $Container,
        "-Database", $Database,
        "-Username", $Username,
        "-ReportPath", $releaseReportPath
    )
    if (-not [string]::IsNullOrWhiteSpace($LoginId)) {
        $releaseArgs += @("-LoginId", $LoginId)
    }
    if (-not [string]::IsNullOrWhiteSpace($Password)) {
        $releaseArgs += @("-Password", $Password)
    }
    if ($KeepRows) {
        $releaseArgs += "-KeepRows"
    }

    Invoke-Checked -Name "live server release-created Local Agent polling smoke" -Block {
        Wait-ServerGateway -Name "local-agent release smoke target"
        powershell.exe @releaseArgs
    }

    $releaseReport = $null
    if (Test-Path $releaseReportPath) {
        $releaseReport = Get-Content -Raw $releaseReportPath | ConvertFrom-Json
    }

    $summary = [pscustomobject]@{
        schema = "learnbot.quality.local-agent-live-server-release-ui-flow.v1"
        status = "passed"
        server = $Server
        startedAt = $startedAt.ToUniversalTime().ToString("o")
        finishedAt = (Get-Date).ToUniversalTime().ToString("o")
        flaggedStackStarted = [bool]$StartFlaggedStack
        defaultStackRestoreRequested = [bool]$RestoreDefaultStack
        buildRequested = [bool]$Build
        routeFlow = [pscustomobject]@{
            status = "passed"
            test = "frontend/src/components/code/codeWorkspaceReleaseForExecutionRouteFlow.test.mjs"
            proves = @(
                "Release Local Agent patch button is enabled for a flagged-ready fixture.",
                "The visible route handler calls POST /api/local-agents/tools/{requestId}/release-for-execution.",
                "The released patch keeps mutationAllowed=true, dryRunOnly=false, and releaseAttemptId linkage."
            )
        }
        liveReleaseCreatedFlow = [pscustomobject]@{
            status = "passed"
            script = "scripts/quality/local-agent-flow/run-live-server-release-created-flow-smoke.ps1"
            reportPath = $releaseReportPath
            sourceRequestId = $releaseReport.sourceRequestId
            releaseAttemptId = $releaseReport.releaseAttemptId
            repositoryId = $releaseReport.repositoryId
            loopId = $releaseReport.loopId
            requestIdSource = $releaseReport.durableInspection.requestIdSource
            completionHandoffStatus = $releaseReport.completionHandoff.details.status
            completionHandoffRunnerState = "READY_FINAL_RESULT_DISABLED"
            runnerPreview = $releaseReport.runnerPreview
        }
        requiredBackendFlags = @(
            "LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED=true",
            "LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED=true"
        )
        limitations = @(
            "This harness combines the route-level UI release flow and live Local Agent polling release flow in one repeatable command.",
            "The route UI check is Vite SSR/direct-handler coverage, not a true interactive browser click.",
            "Final-answer publication and acknowledgement save remain disabled."
        )
    }
    $summary | ConvertTo-Json -Depth 30 | Set-Content -Path $ReportPath -Encoding UTF8
    $summary | ConvertTo-Json -Depth 30
} finally {
    if ($RestoreDefaultStack) {
        try {
            Invoke-Checked -Name "restore default release-disabled stack" -Block {
                Invoke-ComposeUp -Files @($composeDefault)
            }
            Wait-ServerGateway -Name "default release-disabled stack"
        } catch {
            Write-Warning "Default stack restore failed: $($_.Exception.Message)"
        }
    }
    Pop-Location
}
