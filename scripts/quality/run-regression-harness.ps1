param(
    [switch]$IncludeFrontendBuild,
    [switch]$IncludeBackendFullTest,
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
    "CodeControllerRagOnlyTest",
    "AuthControllerTest",
    "AuthInterceptorTest",
    "RagPipelineServiceTest",
    "RagServiceTest",
    "RagConversationServiceTest",
    "CodeRagServiceTest",
    "CodeSearchServiceTest",
    "RagStreamLimiterTest",
    "OllamaClientTest",
    "WebCrawlerTest",
    "WebPageExtractorTest"
) -join ","

$frontendQualityTests = @(
    "src/components/code/ragOnlyWorkspace.test.mjs",
    "src/lib/routing.test.mjs",
    "..\scripts\quality\regression-harness\assert-quality-report.test.mjs",
    "..\scripts\quality\regression-harness\compare-quality-reports.test.mjs",
    "src/components/documents/documentWorkspaceCrawlAuditSmoke.test.mjs",
    "src/components/documents/documentWorkspaceRetryContextSmoke.test.mjs"
)

try {
    Invoke-HarnessStep -Name "lan-deployment-contract" -WorkingDirectory $root -Command "powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy/Initialize-LanHttp.test.ps1" -Coverage @("deployment")
    Invoke-HarnessStep -Name "rag-non-regression-comparison" -WorkingDirectory $root -Command "node scripts/quality/rag-quality/non-regression.test.mjs" -Coverage @("regression-comparison")
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
            "crawler-extraction"
        )

    foreach ($frontendTest in $frontendQualityTests) {
        $frontendCoverage = @("frontend-quality")
        switch -Wildcard ($frontendTest) {
            "*ragOnlyWorkspace*" { $frontendCoverage += @("code-rag", "legacy-conversation-rendering") }
            "*routing.test*" { $frontendCoverage += @("rag-navigation") }
            "*assert-quality-report*" { $frontendCoverage += @("quality-report-gate") }
            "*compare-quality-reports*" { $frontendCoverage += @("quality-report-gate", "regression-comparison") }
            "*documentWorkspaceCrawlAuditSmoke*" { $frontendCoverage += @("document-rag", "crawler-extraction", "indexing-diagnostics", "evidence-fallback") }
            "*documentWorkspaceRetryContextSmoke*" { $frontendCoverage += @("document-rag", "indexing-diagnostics", "evidence-fallback", "workspace-readiness") }
        }

        Invoke-HarnessStep `
            -Name "frontend-$([IO.Path]::GetFileNameWithoutExtension($frontendTest))" `
            -WorkingDirectory (Join-Path $root "frontend") `
            -Command "node $frontendTest" `
            -Coverage $frontendCoverage
    }

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
        New-QualitySignal -Name "code-rag-grounding" -RequiredCoverage @("code-rag", "rag-quality-scoring", "citation-correctness", "evidence-relevance") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "document-rag-grounding" -RequiredCoverage @("document-rag", "evidence-fallback", "rag-quality-scoring", "citation-correctness", "evidence-relevance", "follow-up-quality") -CoverageSummary $coverageSummary
        New-QualitySignal -Name "streaming-and-crawler-fallbacks" -RequiredCoverage @("streaming-fallback", "first-token-latency", "crawler-extraction") -CoverageSummary $coverageSummary
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
