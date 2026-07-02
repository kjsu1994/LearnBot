$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentSetupPlan.ps1")
. (Join-Path $PSScriptRoot "LocalAgentSetupRunPlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-setup-run-plan-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null

    $setupPlan = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083/" `
        -WorkspacePath $root `
        -LoginId "user@example.com" `
        -Transport "auto"

    $runPlan = Get-LearnBotLocalAgentSetupRunPlan -SetupPlan $setupPlan
    if ($runPlan.schema -ne "learnbot.local-agent.setup-run-plan.v1") {
        throw "unexpected schema"
    }
    if ($runPlan.readyToRun -ne $true -or $runPlan.blocked -ne $false) {
        throw "expected ready unblocked preview"
    }
    if ($runPlan.executionEnabled -ne $false -or $runPlan.networkCallsEnabled -ne $false -or $runPlan.localCommandsEnabled -ne $false) {
        throw "setup run preview must not enable execution"
    }
    if ($runPlan.steps.Count -ne 5 -or $runPlan.steps[0].executionEnabled -ne $false) {
        throw "expected disabled setup steps"
    }
    if ($runPlan.safety.tokenSecretPrinted -ne $false -or $runPlan.safety.localConfigWriteEnabled -ne $false) {
        throw "unexpected setup run safety boundary"
    }
    Assert-LearnBotLocalAgentSetupRunReady -SetupRunPlan $runPlan

    $json = $runPlan | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "setup run plan must not print token secrets"
    }

    $missingPlan = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083" `
        -WorkspacePath (Join-Path $root "missing") `
        -LoginId "" `
        -Transport "polling"
    $blocked = Get-LearnBotLocalAgentSetupRunPlan -SetupPlan $missingPlan
    if ($blocked.blocked -ne $true) {
        throw "expected blocked setup run plan"
    }
    if ($blocked.blockedReasons -notcontains "loginId" -or $blocked.blockedReasons -notcontains "workspacePath") {
        throw "expected missing setup inputs as blocked reasons"
    }
    $blockedThrown = $false
    try {
        Assert-LearnBotLocalAgentSetupRunReady -SetupRunPlan $blocked
    } catch {
        $blockedThrown = $true
    }
    if ($blockedThrown -ne $true) {
        throw "expected blocked setup run readiness assertion to fail"
    }

    "local-agent-setup-run-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
