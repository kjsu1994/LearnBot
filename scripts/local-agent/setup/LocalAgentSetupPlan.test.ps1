$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentSetupPlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-setup-plan-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $plan = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083/" `
        -WorkspacePath $root `
        -LoginId "user@example.com" `
        -Transport "auto"

    if ($plan.schema -ne "learnbot.local-agent.setup-plan.v1") {
        throw "unexpected schema"
    }
    if ($plan.readyToRun -ne $true) {
        throw "expected ready setup plan"
    }
    if ($plan.server -ne "http://localhost:8083") {
        throw "expected normalized server"
    }
    if ($plan.steps.Count -ne 5) {
        throw "expected five setup steps"
    }
    if ($plan.steps[0].tokenPrinted -ne $false -or $plan.steps[2].tokenPrinted -ne $false) {
        throw "setup plan must not print token secrets"
    }
    if ($plan.safety.typedToolsOnly -ne $true -or $plan.safety.arbitraryShellExecution -ne $false -or $plan.safety.serverLocalMutationEnabled -ne $false) {
        throw "unexpected safety boundary"
    }

    $missing = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083" `
        -WorkspacePath (Join-Path $root "missing") `
        -LoginId "" `
        -Transport "polling"
    if ($missing.readyToRun -ne $false) {
        throw "expected missing setup plan to be blocked"
    }
    if ($missing.missingInputs -notcontains "loginId" -or $missing.missingInputs -notcontains "workspacePath") {
        throw "expected missing loginId and workspacePath"
    }

    "local-agent-setup-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
