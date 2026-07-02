$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentSetupPlan.ps1")
. (Join-Path $PSScriptRoot "LocalAgentBrowserPairingPlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-browser-pairing-plan-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null

    $setupPlan = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083/" `
        -WorkspacePath $root `
        -LoginId "browser-login" `
        -Transport "auto"
    $plan = Get-LearnBotLocalAgentBrowserPairingPlan -SetupPlan $setupPlan

    if ($plan.schema -ne "learnbot.local-agent.browser-pairing-plan.v1") {
        throw "unexpected schema"
    }
    if ($plan.readyToPair -ne $true -or $plan.blocked -ne $false) {
        throw "expected ready browser pairing plan"
    }
    if ($plan.cliPasswordAccepted -ne $false -or $plan.safety.cliPasswordCollection -ne $false -or $plan.safety.browserOwnsLogin -ne $true) {
        throw "browser pairing plan must avoid CLI password collection"
    }
    if ($plan.networkCallsEnabled -ne $false -or $plan.localCommandsEnabled -ne $false) {
        throw "browser pairing plan must stay preview-only"
    }
    if ($plan.steps.Count -ne 6 -or $plan.steps[0].name -ne "openWebUi" -or $plan.steps[3].name -ne "pairLocalAgentWithPastedToken") {
        throw "unexpected browser pairing steps"
    }
    if ($plan.steps[2].tokenPrintedByPlan -ne $false -or $plan.steps[3].tokenPrintedByPlan -ne $false) {
        throw "browser pairing plan must not print token secrets"
    }

    $json = $plan | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "browser pairing plan must not print token secrets"
    }

    $missingSetupPlan = Get-LearnBotLocalAgentSetupPlan `
        -Server "http://localhost:8083" `
        -WorkspacePath (Join-Path $root "missing") `
        -LoginId "browser-login" `
        -Transport "polling"
    $blocked = Get-LearnBotLocalAgentBrowserPairingPlan -SetupPlan $missingSetupPlan
    if ($blocked.readyToPair -ne $false -or $blocked.blockedReasons -notcontains "workspacePath") {
        throw "expected missing workspace to block browser pairing plan"
    }

    "local-agent-browser-pairing-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
