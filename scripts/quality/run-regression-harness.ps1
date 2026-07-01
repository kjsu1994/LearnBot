param(
    [switch]$IncludeFrontendBuild,
    [switch]$IncludeBackendFullTest,
    [switch]$IncludeLiveLocalAgentSmoke,
    [switch]$IncludeLiveRunnerReadOnlyPostgres,
    [ValidateSet("polling", "websocket")]
    [string]$LiveLocalAgentTransport = "polling",
    [string]$Server = "http://localhost:8083",
    [string]$WorkspacePath = (Get-Location).Path,
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$startedAt = Get-Date
$results = New-Object System.Collections.Generic.List[object]
$hadFailure = $false

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $reportDir = Join-Path $root ".tmp\quality"
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
    $ReportPath = Join-Path $reportDir ("regression-harness-{0:yyyyMMdd-HHmmss}.json" -f $startedAt)
}

function Invoke-HarnessStep {
    param(
        [string]$Name,
        [string]$Command,
        [string]$WorkingDirectory,
        [string[]]$Coverage = @()
    )

    Write-Host "== $Name =="
    $stepStartedAt = Get-Date
    Push-Location $WorkingDirectory
    try {
        Invoke-Expression $Command
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }
        if ($exitCode -ne 0) {
            throw "$Name failed with exit code $exitCode"
        }
        $results.Add([pscustomobject]@{
            name = $Name
            status = "passed"
            coverage = @($Coverage)
            command = $Command
            workingDirectory = $WorkingDirectory
            durationSeconds = [math]::Round(((Get-Date) - $stepStartedAt).TotalSeconds, 3)
        })
    } catch {
        $script:hadFailure = $true
        $results.Add([pscustomobject]@{
            name = $Name
            status = "failed"
            coverage = @($Coverage)
            command = $Command
            workingDirectory = $WorkingDirectory
            durationSeconds = [math]::Round(((Get-Date) - $stepStartedAt).TotalSeconds, 3)
            error = $_.Exception.Message
        })
    } finally {
        Pop-Location
    }
}

function New-CoverageSummary {
    param(
        [object[]]$AllResults
    )

    $coverage = [ordered]@{}
    foreach ($result in $AllResults) {
        foreach ($area in @($result.coverage)) {
            if ([string]::IsNullOrWhiteSpace($area)) {
                continue
            }
            if (-not $coverage.Contains($area)) {
                $coverage[$area] = [ordered]@{
                    totalSteps = 0
                    passedSteps = 0
                    failedSteps = 0
                    durationSeconds = 0.0
                }
            }
            $entry = $coverage[$area]
            $entry.totalSteps += 1
            if ($result.status -eq "passed") {
                $entry.passedSteps += 1
            } else {
                $entry.failedSteps += 1
            }
            $entry.durationSeconds = [math]::Round($entry.durationSeconds + [double]$result.durationSeconds, 3)
        }
    }

    return $coverage
}

function New-QualitySignal {
    param(
        [string]$Name,
        [string[]]$RequiredCoverage,
        [System.Collections.Specialized.OrderedDictionary]$CoverageSummary
    )

    $missing = @()
    $failed = @()
    foreach ($area in $RequiredCoverage) {
        if (-not $CoverageSummary.Contains($area)) {
            $missing += $area
            continue
        }
        if ([int]$CoverageSummary[$area].failedSteps -gt 0 -or [int]$CoverageSummary[$area].passedSteps -lt 1) {
            $failed += $area
        }
    }

    $status = "covered"
    if ($missing.Count -gt 0) {
        $status = "missing"
    } elseif ($failed.Count -gt 0) {
        $status = "failing"
    }

    return [pscustomobject]@{
        name = $Name
        status = $status
        requiredCoverage = @($RequiredCoverage)
        missingCoverage = @($missing)
        failingCoverage = @($failed)
    }
}

$backendFocusedTests = @(
    "RagPipelineServiceTest",
    "RagServiceTest",
    "RagConversationServiceTest",
    "CodeRagServiceTest",
    "CodeSearchServiceTest",
    "RagStreamLimiterTest",
    "OllamaClientTest",
    "WebCrawlerTest",
    "WebPageExtractorTest",
    "LocalAgentToolGatewayServiceTest",
    "LocalAgentControllerTest",
    "LocalAgentMutationResultClassifierTest",
    "LocalAgentPatchMutationInputBuilderTest",
    "LocalAgentToolExecutionRepositoryLivePostgresTest"
) -join ","

$frontendQualityTests = @(
    "..\scripts\quality\regression-harness\assert-quality-report.test.mjs",
    "..\scripts\quality\regression-harness\compare-quality-reports.test.mjs",
    "src/components/code/mutationDisabledFlagGuard.test.mjs",
    "src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs",
    "src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs",
    "src/components/code/approvedExecutionFlowInspectionSummary.test.mjs",
    "src/features/code/approvedExecutionFlowInspectionClient.test.mjs",
    "src/components/code/mutationFinalReportDraft.test.mjs",
    "src/components/code/mutationRagFreshnessGate.test.mjs",
    "src/components/code/mutationResultAggregationGate.test.mjs",
    "src/components/code/mutationPublicationGate.test.mjs"
)

try {
    Invoke-HarnessStep `
        -Name "rag-quality-fixture-score" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\rag-quality\evaluate-rag-quality-fixtures.mjs" `
        -Coverage @("rag-quality-scoring", "citation-correctness", "evidence-relevance", "follow-up-quality", "latency-budget", "hallucination-risk")

    Invoke-HarnessStep `
        -Name "rag-quality-live-capture-normalization" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\rag-quality\evaluate-rag-quality-fixtures.test.mjs" `
        -Coverage @("rag-quality-scoring", "live-rag-capture", "citation-correctness", "evidence-relevance", "follow-up-quality", "latency-budget")

    Invoke-HarnessStep `
        -Name "rag-quality-live-baseline-promotion" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\rag-quality\create-live-baseline-from-capture.test.mjs" `
        -Coverage @("rag-quality-scoring", "live-rag-capture", "citation-correctness", "evidence-relevance")

    Invoke-HarnessStep `
        -Name "rag-quality-report-comparison" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\rag-quality\compare-rag-quality-reports.test.mjs" `
        -Coverage @("rag-quality-scoring", "regression-comparison", "citation-correctness", "evidence-relevance", "follow-up-quality", "latency-budget", "hallucination-risk")

    Invoke-HarnessStep `
        -Name "patch-proposal-quality-score" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\patch-proposal\evaluate-patch-proposal-fixtures.test.mjs" `
        -Coverage @("patch-validity", "local-agent-safety", "rollbackability", "approval-safety", "test-command-allowlist")

    Invoke-HarnessStep `
        -Name "patch-proposal-report-comparison" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\patch-proposal\compare-patch-proposal-reports.test.mjs" `
        -Coverage @("patch-validity", "regression-comparison", "local-agent-safety", "rollbackability", "approval-safety", "test-command-allowlist")

    Invoke-HarnessStep `
        -Name "streaming-first-delta-latency" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\streaming\measure-sse-first-delta.test.mjs" `
        -Coverage @("streaming-fallback", "first-token-latency", "latency-budget")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-quality-score" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\evaluate-indexing-diagnostics-fixtures.test.mjs" `
        -Coverage @("indexing-diagnostics", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-capture-normalization" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\capture-indexing-audit-fixtures.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-template-generation" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\create-live-capture-template.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-seed-discovery" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\discover-live-seed-manifest.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-seed-action-plan" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\create-live-seed-action-plan.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-seed-action-execution" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\execute-live-seed-action-plan.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-document-retry-seed-contract" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\seed-document-post-processing-retry.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "document-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-live-audit-runner" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\run-live-indexing-audit.test.mjs" `
        -Coverage @("indexing-diagnostics", "live-indexing-capture", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback", "regression-comparison")

    Invoke-HarnessStep `
        -Name "indexing-diagnostics-report-comparison" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\indexing\compare-indexing-diagnostics-reports.test.mjs" `
        -Coverage @("indexing-diagnostics", "regression-comparison", "crawler-extraction", "document-rag", "code-rag", "evidence-fallback")

    Invoke-HarnessStep `
        -Name "backend-focused-quality-regressions" `
        -WorkingDirectory $root `
        -Command ".\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml `"-Dtest=$backendFocusedTests`" test" `
        -Coverage @(
            "document-rag",
            "code-rag",
            "streaming-fallback",
            "crawler-extraction",
            "local-agent-backend-safety",
            "approved-execution-flow",
            "rollbackability"
        )

    foreach ($frontendTest in $frontendQualityTests) {
        $frontendCoverage = @("frontend-quality")
        switch -Wildcard ($frontendTest) {
            "*assert-quality-report*" { $frontendCoverage += @("quality-report-gate") }
            "*compare-quality-reports*" { $frontendCoverage += @("quality-report-gate", "regression-comparison") }
            "*mutationDisabledFlagGuard*" { $frontendCoverage += @("mutation-disabled-regression", "local-agent-safety") }
            "*codeWorkspaceReadiness*" { $frontendCoverage += @("workspace-readiness", "local-agent-safety") }
            "*approvedExecutionFlowInspection*" { $frontendCoverage += @("approved-execution-flow", "local-agent-safety") }
            "*mutationFinalReportDraft*" { $frontendCoverage += @("final-report-quality", "rollbackability") }
            "*mutationRagFreshnessGate*" { $frontendCoverage += @("rag-freshness", "rollbackability") }
            "*mutationResultAggregationGate*" { $frontendCoverage += @("result-aggregation", "evidence-fallback") }
            "*mutationPublicationGate*" { $frontendCoverage += @("publication-readiness", "final-answer-readiness") }
        }

        Invoke-HarnessStep `
            -Name "frontend-$([IO.Path]::GetFileNameWithoutExtension($frontendTest))" `
            -WorkingDirectory (Join-Path $root "frontend") `
            -Command "node $frontendTest" `
            -Coverage $frontendCoverage
    }

    Invoke-HarnessStep `
        -Name "local-agent-build" `
        -WorkingDirectory $root `
        -Command "dotnet build local-agent\LearnBot.LocalAgent.csproj" `
        -Coverage @("local-agent-runtime", "local-agent-safety")

    Invoke-HarnessStep `
        -Name "local-agent-approved-execution-flow-contract" `
        -WorkingDirectory $root `
        -Command "dotnet run --project local-agent -- self-test approved-execution-flow-contract --report .tmp\quality\local-agent-approved-flow\approved-flow-report.json" `
        -Coverage @("local-agent-runtime", "approved-execution-flow", "rollbackability", "local-agent-safety")

    Invoke-HarnessStep `
        -Name "local-agent-approved-execution-flow-report" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\local-agent-flow\assert-approved-execution-flow-report.mjs --report .tmp\quality\local-agent-approved-flow\approved-flow-report.json" `
        -Coverage @("local-agent-runtime", "approved-execution-flow", "rollbackability", "local-agent-safety")

    Invoke-HarnessStep `
        -Name "local-agent-approved-server-queue-flow-contract" `
        -WorkingDirectory $root `
        -Command "dotnet run --project local-agent -- self-test approved-server-queue-flow-contract --report .tmp\quality\local-agent-approved-flow\approved-server-queue-flow-report.json" `
        -Coverage @("local-agent-runtime", "approved-execution-flow", "rollbackability", "local-agent-safety", "local-agent-smoke-contract")

    Invoke-HarnessStep `
        -Name "local-agent-approved-server-queue-flow-report" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\local-agent-flow\assert-approved-execution-flow-report.mjs --report .tmp\quality\local-agent-approved-flow\approved-server-queue-flow-report.json" `
        -Coverage @("local-agent-runtime", "approved-execution-flow", "rollbackability", "local-agent-safety", "local-agent-smoke-contract")

    Invoke-HarnessStep `
        -Name "local-agent-approved-flow-seed-contract" `
        -WorkingDirectory $root `
        -Command "node scripts\quality\local-agent-flow\seed-approved-tool-executions.test.mjs" `
        -Coverage @("local-agent-runtime", "approved-execution-flow", "rollbackability", "local-agent-safety", "local-agent-smoke-contract")

    Invoke-HarnessStep `
        -Name "local-agent-live-smoke-contract" `
        -WorkingDirectory $root `
        -Command ".\scripts\quality\local-agent-smoke\assert-local-agent-smoke-contract.ps1" `
        -Coverage @("local-agent-live-smoke", "local-agent-smoke-contract", "transport-fallback", "local-agent-safety")

    if ($IncludeFrontendBuild) {
        Invoke-HarnessStep `
            -Name "frontend-build" `
            -WorkingDirectory (Join-Path $root "frontend") `
            -Command "npm run build" `
            -Coverage @("frontend-build", "frontend-quality")
    }

    if ($IncludeBackendFullTest) {
        Invoke-HarnessStep `
            -Name "backend-full-test" `
            -WorkingDirectory $root `
            -Command ".\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml test" `
            -Coverage @("backend-full-regression")
    }

    if ($IncludeLiveLocalAgentSmoke) {
        Invoke-HarnessStep `
            -Name "local-agent-live-smoke-$LiveLocalAgentTransport" `
            -WorkingDirectory $root `
            -Command ".\scripts\local-agent-smoke.ps1 -Server $Server -WorkspacePath `"$WorkspacePath`" -ToolName git.status -Transport $LiveLocalAgentTransport" `
            -Coverage @("local-agent-live-smoke", "local-agent-runtime", "local-agent-safety")
    }

    if ($IncludeLiveRunnerReadOnlyPostgres) {
        Invoke-HarnessStep `
            -Name "local-agent-runner-read-only-live-postgres" `
            -WorkingDirectory $root `
            -Command "powershell.exe -ExecutionPolicy Bypass -File .\scripts\quality\local-agent-flow\run-runner-read-only-live-postgres.ps1" `
            -Coverage @("local-agent-runner-loop", "local-agent-live-postgres", "approved-execution-flow", "local-agent-safety")
    }
} finally {
    $finishedAt = Get-Date
    $allResults = @()
    foreach ($result in $results) {
        $allResults += $result
    }
    $failedResults = @($allResults | Where-Object { $_.status -ne "passed" })
    $passedResults = @($allResults | Where-Object { $_.status -eq "passed" })
    $coverageSummary = New-CoverageSummary -AllResults $allResults
    $qualitySignals = @(
        New-QualitySignal -Name "document-rag-grounding" -RequiredCoverage @("document-rag", "evidence-fallback", "rag-quality-scoring", "citation-correctness", "evidence-relevance", "follow-up-quality") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "code-rag-and-patch-safety" -RequiredCoverage @("code-rag", "workspace-readiness", "mutation-disabled-regression", "rag-quality-scoring", "patch-validity", "approval-safety", "test-command-allowlist") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "streaming-and-crawler-fallbacks" -RequiredCoverage @("streaming-fallback", "first-token-latency", "crawler-extraction") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "approved-local-agent-flow" -RequiredCoverage @("approved-execution-flow", "local-agent-runtime", "local-agent-safety", "local-agent-smoke-contract") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "rollbackability" -RequiredCoverage @("rollbackability") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "final-answer-readiness" -RequiredCoverage @("final-report-quality", "publication-readiness", "final-answer-readiness") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "latency-and-hallucination-risk" -RequiredCoverage @("latency-budget", "hallucination-risk") -CoverageSummary $coverageSummary
    )
    $blockedQualitySignals = @($qualitySignals | Where-Object { $_.status -ne "covered" })
    $summary = [pscustomobject]@{
        schema = "learnbot.quality.regression-harness.v1"
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 3)
        stepSummary = [pscustomobject]@{
            totalSteps = $allResults.Count
            passedSteps = $passedResults.Count
            failedSteps = $failedResults.Count
        }
        qualitySignalSummary = [pscustomobject]@{
            totalSignals = $qualitySignals.Count
            coveredSignals = @($qualitySignals | Where-Object { $_.status -eq "covered" }).Count
            blockedSignals = $blockedQualitySignals.Count
        }
        coverageSummary = $coverageSummary
        qualitySignals = $qualitySignals
        includeFrontendBuild = [bool]$IncludeFrontendBuild
        includeBackendFullTest = [bool]$IncludeBackendFullTest
        includeLiveLocalAgentSmoke = [bool]$IncludeLiveLocalAgentSmoke
        includeLiveRunnerReadOnlyPostgres = [bool]$IncludeLiveRunnerReadOnlyPostgres
        liveLocalAgentTransport = $LiveLocalAgentTransport
        results = $allResults
        passed = ($failedResults.Count -eq 0 -and $blockedQualitySignals.Count -eq 0)
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $ReportPath -Encoding UTF8
    Write-Host "quality regression report: $ReportPath"
    if ($blockedQualitySignals.Count -gt 0) {
        $script:hadFailure = $true
        $blockedNames = ($blockedQualitySignals | ForEach-Object { "$($_.name):$($_.status)" }) -join ", "
        Write-Error "quality signal gate failed: $blockedNames"
    }
    if ($hadFailure) {
        exit 1
    }
}
