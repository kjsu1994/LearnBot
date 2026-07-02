$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentPairFromWebTokenPlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-pair-from-web-token-plan-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $agentId = "11111111-1111-1111-1111-111111111111"

    $plan = Get-LearnBotLocalAgentPairFromWebTokenPlan `
        -Server "http://localhost:8083/" `
        -WorkspacePath $root `
        -AgentId $agentId `
        -PairingToken "secret-token" `
        -Transport "auto"

    if ($plan.schema -ne "learnbot.local-agent.pair-from-web-token-plan.v1") {
        throw "unexpected schema"
    }
    if ($plan.readyToRun -ne $true -or $plan.blocked -ne $false) {
        throw "expected ready pair-from-web-token plan"
    }
    if ($plan.agentIdProvided -ne $true -or $plan.agentIdValid -ne $true -or $plan.pairingTokenProvided -ne $true) {
        throw "expected valid agent id and provided pairing token"
    }
    if ($plan.cliPasswordAccepted -ne $false -or $plan.safety.cliPasswordCollection -ne $false -or $plan.safety.browserOwnsLogin -ne $true) {
        throw "pair-from-web-token plan must avoid CLI password collection"
    }
    if ($plan.networkCallsEnabled -ne $false -or $plan.localCommandsEnabled -ne $false) {
        throw "pair-from-web-token plan must stay preview-only"
    }
    if ($plan.localConfigWriteEnabled -ne $false -or $plan.workspaceRegistrationEnabled -ne $false) {
        throw "pair-from-web-token plan must not write config or register workspace"
    }
    if ($plan.steps.Count -ne 3 -or $plan.steps[0].name -ne "pairLocalAgentWithPastedToken") {
        throw "unexpected pair-from-web-token steps"
    }
    if ($plan.steps[0].tokenPrintedByPlan -ne $false -or $plan.tokenSecretVisible -ne $false -or $plan.safety.tokenSecretPrinted -ne $false) {
        throw "pair-from-web-token plan must not print token secrets"
    }

    $json = $plan | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "pair-from-web-token plan must not print token secrets"
    }
    if ($plan.steps[0].command -notmatch "<pairing-token-from-web>") {
        throw "pair-from-web-token plan should show a token placeholder"
    }

    Assert-LearnBotLocalAgentPairFromWebTokenReady -PairFromWebTokenPlan $plan

    $blocked = Get-LearnBotLocalAgentPairFromWebTokenPlan `
        -Server "http://localhost:8083" `
        -WorkspacePath (Join-Path $root "missing") `
        -AgentId "not-a-guid" `
        -PairingToken "" `
        -Transport "polling"

    if ($blocked.readyToRun -ne $false) {
        throw "expected blocked pair-from-web-token plan"
    }
    if ($blocked.blockedReasons -notcontains "workspacePath" -or $blocked.blockedReasons -notcontains "agentId" -or $blocked.blockedReasons -notcontains "pairingToken") {
        throw "expected workspace, agent id, and token blockers"
    }

    $threw = $false
    try {
        Assert-LearnBotLocalAgentPairFromWebTokenReady -PairFromWebTokenPlan $blocked
    } catch {
        $threw = $true
    }
    if (-not $threw) {
        throw "expected blocked pair-from-web-token plan assertion to throw"
    }

    "local-agent-pair-from-web-token-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
