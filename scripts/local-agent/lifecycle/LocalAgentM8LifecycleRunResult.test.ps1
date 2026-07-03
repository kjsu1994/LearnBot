$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentM8LifecycleRunResult.ps1")

$pairedStopped = [pscustomobject]@{
    configured = $true
    approvedWorkspaceCount = 1
    running = $false
}
$script:called = @()
$started = Invoke-LearnBotLocalAgentM8LifecycleRunResult `
    -InitialStatus $pairedStopped `
    -InvokeLifecycleCommand {
        param([string]$LifecycleAction)
        $script:called += $LifecycleAction
        [pscustomobject]@{
            schema = "learnbot.local-agent.lifecycle-command-result.v1"
            action = $LifecycleAction
            status = "SUCCEEDED"
            exitCode = 0
            output = "ok $LifecycleAction"
            tokenSecretPrinted = $false
            serviceCommandExecuted = $false
        }
    }

if ($started.schema -ne "learnbot.local-agent.m8-lifecycle-run-result.v1") {
    throw "unexpected schema"
}
if ($started.status -ne "SUCCEEDED" -or $started.startAttempted -ne $true -or $started.duplicateStartPrevented -ne $false) {
    throw "expected paired stopped lifecycle run to start"
}
if (($script:called -join ",") -ne "background-start,status,logs") {
    throw "expected background-start, status, logs sequence"
}

$pairedRunning = [pscustomobject]@{
    configured = $true
    approvedWorkspaceCount = 1
    running = $true
}
$script:runningCalls = @()
$running = Invoke-LearnBotLocalAgentM8LifecycleRunResult `
    -InitialStatus $pairedRunning `
    -InvokeLifecycleCommand {
        param([string]$LifecycleAction)
        $script:runningCalls += $LifecycleAction
        [pscustomobject]@{
            schema = "learnbot.local-agent.lifecycle-command-result.v1"
            action = $LifecycleAction
            status = "SUCCEEDED"
            exitCode = 0
            output = "ok $LifecycleAction"
            tokenSecretPrinted = $false
            serviceCommandExecuted = $false
        }
    }

if ($running.status -ne "SUCCEEDED" -or $running.startAttempted -ne $false -or $running.duplicateStartPrevented -ne $true) {
    throw "expected duplicate start prevention"
}
if (($script:runningCalls -join ",") -ne "status,logs") {
    throw "expected running lifecycle run to skip background-start"
}

$unpaired = [pscustomobject]@{
    configured = $false
    approvedWorkspaceCount = 0
    running = $false
}
$script:blockedCalls = @()
$blocked = Invoke-LearnBotLocalAgentM8LifecycleRunResult `
    -InitialStatus $unpaired `
    -InvokeLifecycleCommand {
        param([string]$LifecycleAction)
        $script:blockedCalls += $LifecycleAction
        [pscustomobject]@{
            status = "FAILED"
            exitCode = 1
            output = "should not run"
        }
    }

if ($blocked.status -ne "BLOCKED" -or $blocked.startAttempted -ne $false -or $blocked.blockedReasons.Count -ne 2) {
    throw "expected blocked unpaired lifecycle run"
}
if ($script:blockedCalls.Count -ne 0) {
    throw "blocked lifecycle run must not invoke commands"
}

$json = $started | ConvertTo-Json -Depth 10
if ($json -match "secret-token") {
    throw "m8 lifecycle result must not print token secrets"
}
if ($started.tokenSecretPrinted -ne $false -or $started.serviceCommandExecuted -ne $false -or $started.signedInstallerUsed -ne $false -or $started.autoUpdateExecuted -ne $false) {
    throw "m8 lifecycle result must keep productization safety flags disabled"
}

"local-agent-m8-lifecycle-run-result-contract-ok"
